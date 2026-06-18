package io.github.logan.gsonsafeparser

import com.google.gson.TypeAdapter
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.annotations.JsonAdapter
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonIOException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.Expose
import com.google.gson.annotations.Since
import com.google.gson.annotations.SerializedName
import com.google.gson.TypeAdapterFactory
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.lang.reflect.Type
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验证 SafeParser 的反射字段绑定能力。
 *
 * 重点覆盖字段命名、`@SerializedName`、Excluder、字段级和类级 `@JsonAdapter`、
 * 泛型字段、重复字段名以及运行时类型序列化。
 */
class SafeParserReflectiveTest {
    /** 测试模型：字段级 JsonAdapter 应优先于 Safe Reflective Adapter。 */
    data class FieldAdapterResponse(
        @JsonAdapter(FieldValueAdapter::class)
        val value: FieldValue = FieldValue(1)
    )

    /** 测试模型：字段级 JsonDeserializer 应和 SafeParser 字段级兜底同时生效。 */
    data class FieldDeserializerResponse(
        @JsonAdapter(FieldValueDeserializer::class)
        val value: FieldValue = FieldValue(1),
        val count: Int = 6
    )

    /** 测试模型：字段级 JsonSerializer 应保持写出路径可用。 */
    data class FieldSerializerResponse(
        @JsonAdapter(FieldValueSerializer::class)
        val value: FieldValue = FieldValue(3)
    )

    /** 测试模型：字段级 TypeAdapterFactory 应保持读取路径可用。 */
    data class FieldAdapterFactoryResponse(
        @JsonAdapter(FieldValueAdapterFactory::class)
        val value: FieldValue = FieldValue(1)
    )

    /** 测试模型：字段 Adapter 读取失败时遵循 Gson 原生语义，不进入字段兜底。 */
    data class ThrowingFieldAdapterResponse(
        @JsonAdapter(ThrowingValueAdapter::class)
        val value: ThrowingValue = ThrowingValue("local"),
        val next: String = "local"
    )
    /** 测试模型：字段 Adapter 抛出 fatal 时必须外抛，不能被字段级兜底吞掉。 */
    data class FatalFieldAdapterResponse(
        @JsonAdapter(FatalFieldValueAdapter::class)
        val value: FatalFieldValue = FatalFieldValue("local"),
        val next: String = "local"
    )

    /** 测试模型：JSON:API 风格地址簿响应，用来覆盖 nullable Boolean 字段。 */
    data class AddressEnvelope(val data: AddressData = AddressData())
    /** 测试模型：地址簿 data 节点。 */
    data class AddressData(
        val type: String? = null,
        val attributes: AddressAttributes = AddressAttributes(),
        val id: String? = null
    )
    /** 测试模型：地址簿 attributes 节点。 */
    data class AddressAttributes(
        val shipping: List<AddressShipping> = emptyList(),
        val billing: List<AddressShipping> = emptyList()
    )
    /** 测试模型：地址项里的 nullable Boolean 字段应该交给 Gson 正常读取。 */
    data class AddressShipping(
        val id: Int? = null,
        val isDefault: Boolean? = null,
        val countryCode: String? = null,
        var state: CheckoutState? = null
    )
    /** 测试模型：模拟业务 Bean 里不属于 JSON 契约的界面态字段。 */
    sealed class CheckoutState(open val type: String?)

    /** 测试模型：FieldValueAdapter 会根据字符串长度生成 count。 */
    data class FieldValue(val count: Int = 0)
    /** 测试模型：读取时故意失败的字段值。 */
    data class ThrowingValue(val text: String = "")
    /** 测试模型：读取时抛出 fatal 的字段值。 */
    data class FatalFieldValue(val text: String = "")
    /** 测试模型：用于验证 GsonBuilder 的 FieldNamingPolicy 是否透传。 */
    data class NamingPolicyResponse(val userName: String = "local")

    /** 测试模型：用于验证 @Expose 规则是否仍由 Gson Excluder 控制。 */
    data class ExposeResponse(
        @Expose(deserialize = true, serialize = true)
        val visible: String = "local-visible",
        val hidden: String = "local-hidden"
    )

    /** 测试模型：用于验证 @Since 版本过滤规则是否透传。 */
    data class VersionedResponse(
        @Since(2.0)
        val supported: String = "local-supported",
        @Since(3.0)
        val future: String = "local-future"
    )

    /** 测试模型：声明类型带 JsonAdapter，但运行时值是子类，用于验证写出优先级。 */
    data class RuntimeAdapterHolder(
        @JsonAdapter(DeclaredBaseAdapter::class)
        val value: DeclaredBase = DeclaredChild("remote")
    )

    /** 测试基类：字段声明类型，带自定义 Adapter。 */
    open class DeclaredBase(val name: String = "base")
    /** 测试子类：运行时真实类型，不能错误抢走声明类型 Adapter 的写出逻辑。 */
    class DeclaredChild(name: String = "child") : DeclaredBase(name) {
        /** 子类独有字段，用来确认反射写出没有误用运行时反射 Adapter。 */
        val childOnly: String = "child-only"
    }

    /** 字段级 JsonAdapter：把字符串长度转成 FieldValue。 */
    class FieldValueAdapter : TypeAdapter<FieldValue>() {
        /** 写出 count，方便序列化回归。 */
        override fun write(out: JsonWriter, value: FieldValue?) {
            out.value(value?.count)
        }

        /** 读取字符串，并用字符串长度生成 count。 */
        override fun read(reader: JsonReader): FieldValue {
            return FieldValue(reader.nextString().length)
        }
    }

    /** 字段级 JsonDeserializer：把字符串长度转成 FieldValue。 */
    class FieldValueDeserializer : JsonDeserializer<FieldValue> {
        /** 读取字符串 JsonElement，并用字符串长度生成 count。 */
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): FieldValue {
            return FieldValue(json.asString.length)
        }
    }

    /** 字段级 JsonSerializer：把 FieldValue 写成带前缀的字符串。 */
    class FieldValueSerializer : JsonSerializer<FieldValue> {
        /** 写出字符串 JsonElement，验证 JsonSerializer 分支没有退回反射写出。 */
        override fun serialize(src: FieldValue, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            return JsonPrimitive("serialized:${src.count}")
        }
    }

    /** 字段级 TypeAdapterFactory：返回和 FieldValueAdapter 等价的字段 Adapter。 */
    class FieldValueAdapterFactory : TypeAdapterFactory {
        /** 只处理 FieldValue 字段，其他类型交回 Gson。 */
        @Suppress("UNCHECKED_CAST")
        override fun <T> create(gson: com.google.gson.Gson, type: com.google.gson.reflect.TypeToken<T>): TypeAdapter<T>? {
            return if (type.rawType == FieldValue::class.java) {
                FieldValueAdapter() as TypeAdapter<T>
            } else {
                null
            }
        }
    }

    /** 故意读取失败的字段 Adapter，用来测试调用方自定义 Adapter 的原生失败边界。 */
    class ThrowingValueAdapter : TypeAdapter<ThrowingValue>() {
        /** 写出 text，写路径不失败。 */
        override fun write(out: JsonWriter, value: ThrowingValue?) {
            out.value(value?.text)
        }

        /** 读取时直接抛异常，模拟用户 Adapter 在字段内失败。 */
        override fun read(reader: JsonReader): ThrowingValue {
            throw JsonParseException("adapter failed before consuming token")
        }
    }

    /** 抛出 fatal 的字段 Adapter，用来证明不可安全隔离异常不能进入字段兜底。 */
    class FatalFieldValueAdapter : TypeAdapter<FatalFieldValue>() {
        /** 写出 text，写路径不失败。 */
        override fun write(out: JsonWriter, value: FatalFieldValue?) {
            out.value(value?.text)
        }

        /** 读取时抛出 AssertionError，模拟 VM/链接类错误之外的 fatal 失败。 */
        override fun read(reader: JsonReader): FatalFieldValue {
            throw AssertionError("fatal field adapter failure")
        }
    }

    /** 声明类型 Adapter：用于验证声明类型 Adapter 优先级。 */
    class DeclaredBaseAdapter : TypeAdapter<DeclaredBase>() {
        /** 写出带 declared 前缀的字符串，断言时可以直接看出是否用了这个 Adapter。 */
        override fun write(out: JsonWriter, value: DeclaredBase?) {
            out.value("declared:${value?.name}")
        }

        /** 从字符串读回基类对象。 */
        override fun read(reader: JsonReader): DeclaredBase {
            return DeclaredBase(reader.nextString())
        }
    }

    /** 测试模型：字段类型本身带类级 JsonAdapter。 */
    data class ClassAdapterResponse(
        val value: ClassAdaptedValue = ClassAdaptedValue("local")
    )

    /** 测试模型：类级 JsonAdapter 表示整个类型都应交给 Gson 的注解 Adapter。 */
    @JsonAdapter(ClassValueAdapter::class)
    data class ClassAdaptedValue(val text: String = "")

    /** 类级 JsonAdapter 的实际实现。 */
    class ClassValueAdapter : TypeAdapter<ClassAdaptedValue>() {
        /** 写出 text。 */
        override fun write(out: JsonWriter, value: ClassAdaptedValue?) {
            out.value(value?.text)
        }

        /** 读取字符串并转成大写，方便断言 Adapter 是否被使用。 */
        override fun read(reader: JsonReader): ClassAdaptedValue {
            return ClassAdaptedValue(reader.nextString().uppercase())
        }
    }

    /** 测试模型：两个字段映射到同一个 JSON 名称，用来验证重复字段名必须失败。 */
    data class DuplicateNames(
        @SerializedName("same")
        val first: String = "",
        @SerializedName("same")
        val second: String = ""
    )

    /** 测试模型：同一个字段主名称和 alternate 重复时应该去重，而不是误报字段冲突。 */
    data class DuplicateAlternateOnSameField(
        @SerializedName(value = "data", alternate = ["data"])
        val data: String = "local"
    )

    /** 测试模型：泛型字段里的对象类型。 */
    data class GenericResponse(val box: GenericBox<GenericChild> = GenericBox())
    /** 测试模型：泛型字段里的数字类型。 */
    data class GenericNumberResponse(val box: GenericBox<Int> = GenericBox())
    /** 测试模型：泛型容器，data 保留 T 的真实类型。 */
    data class GenericBox<T>(val data: T? = null)
    /** 测试模型：泛型容器里的对象。 */
    data class GenericChild(val id: Int = 0)
    /** 测试模型：接口字段默认不由 Safe Reflective 强行构造。 */
    data class InterfaceResponse(val value: Named? = null)
    /** 测试接口：用于验证接口类型交给 Gson 默认策略。 */
    interface Named {
        val name: String
    }
    /** 测试基类：运行时类型序列化时会被 Dog 继承。 */
    open class Animal {
        /** 基类字段：用于序列化时确认字段正常写出。 */
        var name: String = "animal"
    }
    /** 测试子类：用于验证运行时类型写出。 */
    class Dog : Animal() {
        /** 子类字段：用于确认运行时类型 Adapter 能看到子类字段。 */
        var bark: String = "woof"
    }
    /** 测试模型：动物字段声明为基类，实际值可能是子类。 */
    data class Zoo(val animal: Animal)
    /** 测试模型：自引用对象，序列化时不能无限递归。 */
    class SelfNode {
        /** 普通字段：确认对象正常写出。 */
        var name: String = "node"
        /** 自引用字段：Safe Reflective 写出时应该跳过它。 */
        var self: SelfNode? = this
    }

    /**
     * 测试方法说明：验证“default parser delegates nullable Boolean fields to Gson”这个具体行为。
     * 真实业务中 `Boolean?` 会以 boxed Boolean 出现，默认策略不能把它误判成普通对象。
     */
    @Test
    fun `default parser delegates nullable Boolean fields to Gson`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        val result = gson.fromJson(
            """
            {
              "data": {
                "type": "addressbook",
                "attributes": {
                  "shipping": [
                    {
                      "id": 41822156,
                      "isDefault": false,
                      "countryCode": "CN"
                    }
                  ],
                  "billing": []
                },
                "id": "0786577f-ca97-4092-becc-a993117825f4"
              }
            }
            """.trimIndent(),
            AddressEnvelope::class.java
        )

        assertEquals(false, result.data.attributes.shipping.first().isDefault)
        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证“field json adapter is used before reflective adapter”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `field json adapter is used before reflective adapter`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("""{"value":"remote"}""", FieldAdapterResponse::class.java)

        assertEquals(FieldValue(6), result.value)
    }

    /**
     * 测试方法说明：验证“field json deserializer keeps safe parsing for sibling fields”这个具体行为。
     * 字段级 JsonDeserializer 是 Gson 常见 API，不能让整个对象退回原生反射后丢掉其他字段的 Safe 兜底。
     */
    @Test
    fun `field json deserializer keeps safe parsing for sibling fields`() {
        val gson = GsonSafeParser.create(
            SafeParserConfig(primitiveParsingPolicy = PrimitiveParsingPolicy.Safe)
        )

        val result = gson.fromJson("""{"value":"remote","count":{}}""", FieldDeserializerResponse::class.java)

        assertEquals(FieldValue(6), result.value)
        assertEquals(6, result.count)
    }

    /**
     * 测试方法说明：验证“field json serializer is used during reflective serialization”这个具体行为。
     */
    @Test
    fun `field json serializer is used during reflective serialization`() {
        val gson = GsonSafeParser.create()

        val json = gson.toJson(FieldSerializerResponse())

        assertEquals("""{"value":"serialized:3"}""", json)
    }

    /**
     * 测试方法说明：验证“field json adapter factory still works after serializer deserializer support”这个具体行为。
     */
    @Test
    fun `field json adapter factory still works after serializer deserializer support`() {
        val gson = GsonSafeParser.create()

        val result = gson.fromJson("""{"value":"remote"}""", FieldAdapterFactoryResponse::class.java)

        assertEquals(FieldValue(6), result.value)
    }

    /**
     * 测试方法说明：验证“gson builder field naming policy is used by safe reflective adapter”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `gson builder field naming policy is used by safe reflective adapter`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .enableSafeParser()
            .create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("""{"user_name":"remote"}""", NamingPolicyResponse::class.java)

        assertEquals("remote", result.userName)
    }

    /**
     * 测试方法说明：验证“gson builder expose exclusion is used by safe reflective adapter”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `gson builder expose exclusion is used by safe reflective adapter`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonBuilder()
            .excludeFieldsWithoutExposeAnnotation()
            .enableSafeParser()
            .create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"visible":"remote-visible","hidden":"remote-hidden"}""",
            ExposeResponse::class.java
        )
        val json = gson.toJson(result)

        assertEquals("remote-visible", result.visible)
        assertEquals("local-hidden", result.hidden)
        assertEquals("""{"visible":"remote-visible"}""", json)
    }

    /**
     * 测试方法说明：验证“gson builder version exclusion is used by safe reflective adapter”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `gson builder version exclusion is used by safe reflective adapter`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonBuilder()
            .setVersion(2.0)
            .enableSafeParser()
            .create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"supported":"remote-supported","future":"remote-future"}""",
            VersionedResponse::class.java
        )
        val json = gson.toJson(result)

        assertEquals("remote-supported", result.supported)
        assertEquals("local-future", result.future)
        assertEquals("""{"supported":"remote-supported"}""", json)
    }

    /**
     * 测试方法说明：验证“field json adapter keeps declared adapter when serializing runtime subtype”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `field json adapter keeps declared adapter when serializing runtime subtype`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        val json = gson.toJson(RuntimeAdapterHolder(DeclaredChild("remote")))

        assertEquals("""{"value":"declared:remote"}""", json)
    }

    /**
     * 测试方法说明：验证字段 Adapter 抛错时保留 Gson 原生失败语义，不被字段级兜底吞掉。
     */
    @Test
    fun `field adapter failure is rethrown without mismatch event`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson("""{"value":{},"next":"remote"}""", ThrowingFieldAdapterResponse::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证字段 Adapter 抛出 fatal 时直接外抛，不产生字段默认值兜底。
     */
    @Test
    fun `field adapter fatal failure is rethrown`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(AssertionError::class.java) {
            gson.fromJson("""{"value":{},"next":"remote"}""", FatalFieldAdapterResponse::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证“class json adapter is delegated to Gson native adapter”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `class json adapter is delegated to Gson native adapter`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("""{"value":"remote"}""", ClassAdapterResponse::class.java)

        assertEquals(ClassAdaptedValue("REMOTE"), result.value)
    }

    /**
     * 测试方法说明：验证“duplicate json field names fail fast like SafeParser”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `duplicate json field names fail fast like SafeParser`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        assertThrows(IllegalArgumentException::class.java) {
            gson.fromJson("""{"same":"value"}""", DuplicateNames::class.java)
        }
    }

    /**
     * 测试方法说明：验证“same field duplicate alternate name is ignored”这个具体行为。
     * 主名和 alternate 写成同一个值时，Gson 自身能接受，本库也不应该把同一字段误判成多字段冲突。
     */
    @Test
    fun `same field duplicate alternate name is ignored`() {
        val gson = GsonSafeParser.create()

        val result = gson.fromJson("""{"data":"remote"}""", DuplicateAlternateOnSameField::class.java)

        assertEquals("remote", result.data)
    }

    /**
     * 测试方法说明：验证“generic field type is resolved before choosing field adapter”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `generic field type is resolved before choosing field adapter`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("""{"box":{"data":[]}}""", GenericResponse::class.java)

        assertNull(result.box.data)
    }

    /**
     * 测试方法说明：验证“generic field mismatch callback uses resolved field type”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `generic field mismatch callback uses resolved field type`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
                onTypeMismatch = events::add
            )
        )

        val result = gson.fromJson("""{"box":{"data":"abc"}}""", GenericNumberResponse::class.java)

        assertNull(result.box.data)
        val event = events.single()
        assertEquals("data", event.fieldName)
        assertEquals(Int::class.javaObjectType.name, event.expectedType)
    }

    /**
     * 测试方法说明：验证“construction failure delegates to Gson and returns null fallback”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `construction failure delegates to Gson and returns null fallback`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("""{"value":{"name":"Tom"}}""", InterfaceResponse::class.java)

        assertNull(result.value)
        assertEquals("value", events.single().fieldName)
    }

    /**
     * 测试方法说明：验证“root interface type is handled by Gson default strategy”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `root interface type is handled by Gson default strategy`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{"name":"Tom"}""", Named::class.java)
        }
    }

    /**
     * 测试方法说明：验证“field serialization uses runtime subtype adapter”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `field serialization uses runtime subtype adapter`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        val json = gson.toJson(Zoo(Dog()))

        assertTrue(json.contains("bark"))
    }

    /**
     * 测试方法说明：验证“self reference field is skipped during reflective serialization”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `self reference field is skipped during reflective serialization`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        val json = assertDoesNotThrow<String> { gson.toJson(SelfNode()) }

        assertFalse(json.contains("self"))
    }
}
