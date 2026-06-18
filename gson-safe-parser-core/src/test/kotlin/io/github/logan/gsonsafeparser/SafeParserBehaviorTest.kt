package io.github.logan.gsonsafeparser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * 验证 SafeParser 主行为。
 *
 * 这个类放的是跨基础类型、对象、集合、Map、字段注解和序列化优先级的综合回归。
 * 后续如果调整 Safe Adapter 分发顺序，优先看这里有没有破坏 SafeParser 已经覆盖的能力。
 */
class SafeParserBehaviorTest {
    /**
     * 综合默认值模型。
     *
     * 每个字段都有默认值，用来验证 null、错形和集合兜底时不会把本地默认语义打散。
     */
    data class Defaults(
        val title: String = "local",
        val count: Int = 6,
        val enabled: Boolean = true,
        val child: Child = Child("local-child"),
        val users: List<String> = listOf("local-user"),
        val profile: Map<String, String> = mapOf("local" to "profile")
    )

    /** 测试模型：nullable 字段用于区分后端显式 null 和字段缺失。 */
    data class NullableDefaults(
        val title: String? = "local",
        val child: Child? = Child("local-child"),
        val users: List<String>? = listOf("local-user"),
        val profile: Map<String, String>? = mapOf("local" to "profile")
    )

    /** 测试模型：`Object` 字段里的子节点，name 默认值用于判断对象兜底是否保留默认构造结果。 */
    data class Child(val name: String = "child")
    /** 测试模型：数字字段默认值，用来覆盖空字符串数字的兜底行为。 */
    data class NumberDefaults(val intValue: Int = 9, val decimal: BigDecimal = BigDecimal("8.8"))
    /** 测试模型：nullable Boolean 会在 JVM 上表现为 boxed java.lang.Boolean。 */
    data class NullableBooleanDefaults(val enabled: Boolean? = null)
    /** 测试模型：nullable 数字包装类型用来对照 Boolean 包装类型。 */
    data class NullableNumberDefaults(val count: Int? = null, val total: Long? = null)
    /** 测试模型：List item 使用 boxed Boolean。 */
    data class BooleanListDefaults(val values: List<Boolean?> = emptyList())
    /** 测试模型：Map value 使用 boxed Boolean。 */
    data class BooleanMapValueDefaults(val values: Map<String, Boolean?> = emptyMap())
    /** 测试模型：Map key 使用 boxed Boolean。 */
    data class BooleanKeyMapDefaults(val values: Map<Boolean, String> = emptyMap())
    /** 测试模型：Int key 的 Map，用来验证对象形式 Map key 能被正确解析。 */
    data class IntKeyMap(val values: Map<Int, String> = emptyMap())
    /** 测试模型：嵌套 Map，确保内层 Map 也能读取数组 entry 形式。 */
    data class NestedMapContainer(
        val values: Map<String, Map<Int, String>> = emptyMap()
    )
    /** 测试枚举：验证 Map key 也能尊重 @SerializedName。 */
    enum class AliasKey {
        @SerializedName("admin_user")
        ADMIN
    }
    /** 测试模型：复杂 Map key，开启复杂 key 写出时会变成数组 entry。 */
    data class ComplexKey(val name: String)
    /** 测试模型：toString 正常的 key，用来验证 keyAdapter 失败时还能退回字符串表示。 */
    data class ThrowingKey(val name: String) {
        override fun toString(): String = name
    }

    /** 测试模型：带类级 JsonAdapter 的 key，验证序列化优先级。 */
    @JsonAdapter(ObjectKeyAdapter::class)
    data class ObjectKey(val name: String) {
        override fun toString(): String = name
    }

    /** 测试模型：Adapter 写出会失败的 key，用来验证 SafeParser 不会因为 key 写出失败崩溃。 */
    @JsonAdapter(ThrowingKeyAdapter::class)
    data class AdapterThrowingKey(val name: String) {
        override fun toString(): String = name
    }
    /** 测试模型：Adapter 写出会抛出 fatal 的 key，不能退回 toString。 */
    @JsonAdapter(FatalWritingKeyAdapter::class)
    data class FatalWritingKey(val name: String) {
        override fun toString(): String = name
    }

    /**
     * ObjectKey 的测试 Adapter。
     *
     * 它把 key 写成对象结构，用来触发复杂 Map key 的写出路径。
     */
    class ObjectKeyAdapter : TypeAdapter<ObjectKey>() {
        /**
         * 把 key 写成 `{"name": ...}`。
         *
         * @param out JSON 输出流。
         * @param value 当前 key。
         */
        override fun write(out: JsonWriter, value: ObjectKey?) {
            out.beginObject()
            out.name("name").value(value?.name)
            out.endObject()
        }

        /**
         * 从对象结构读回 key。
         *
         * @param reader Gson Reader。
         * @return 解析得到的 key。
         */
        override fun read(reader: JsonReader): ObjectKey {
            reader.beginObject()
            assertEquals("name", reader.nextName())
            val name = reader.nextString()
            reader.endObject()
            return ObjectKey(name)
        }
    }

    /**
     * 故意写出失败的 key Adapter。
     *
     * 用它模拟用户自定义 Adapter 里出现异常时，Map key 写出应该尽量降级。
     */
    class ThrowingKeyAdapter : TypeAdapter<AdapterThrowingKey>() {
        /**
         * 故意抛异常，验证 safeKeyToString 的兜底路径。
         */
        override fun write(out: JsonWriter, value: AdapterThrowingKey?) {
            throw IllegalStateException("key adapter failed")
        }

        /**
         * 读取字符串形式的 key。
         *
         * @param reader Gson Reader。
         * @return 解析得到的 key。
         */
        override fun read(reader: JsonReader): AdapterThrowingKey {
            return AdapterThrowingKey(reader.nextString())
        }
    }

    /**
     * 抛出 fatal 的 key Adapter。
     *
     * 用它模拟 key 写出期间出现不可安全隔离的失败。
     */
    class FatalWritingKeyAdapter : TypeAdapter<FatalWritingKey>() {
        /** 写出时抛出 fatal，验证 safeKeyToString 不能吞掉 Error。 */
        override fun write(out: JsonWriter, value: FatalWritingKey?) {
            throw AssertionError("fatal key write failure")
        }

        /** 读取字符串形式的 key。 */
        override fun read(reader: JsonReader): FatalWritingKey {
            return FatalWritingKey(reader.nextString())
        }
    }

    /**
     * 测试方法说明：验证“json null keeps non nullable field defaults”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `json null keeps non nullable field defaults`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"title":null,"child":null,"users":null,"profile":null}""",
            Defaults::class.java
        )

        assertEquals("local", result.title)
        assertEquals(Child("local-child"), result.child)
        assertEquals(listOf("local-user"), result.users)
        assertEquals(mapOf("local" to "profile"), result.profile)
    }

    /**
     * 测试方法说明：验证“json explicit null overwrites nullable field defaults”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `json explicit null overwrites nullable field defaults`() {
        val gson = GsonSafeParser.create()

        val explicitNull = gson.fromJson(
            """{"title":null,"child":null,"users":null,"profile":null}""",
            NullableDefaults::class.java
        )
        val missingFields = gson.fromJson("{}", NullableDefaults::class.java)

        assertNull(explicitNull.title)
        assertNull(explicitNull.child)
        assertNull(explicitNull.users)
        assertNull(explicitNull.profile)
        assertEquals(NullableDefaults(), missingFields)
    }

    /**
     * 测试方法说明：验证无法确认 Kotlin nullable 的 Java 字段不会被显式 null 覆盖。
     */
    @Test
    fun `json explicit null keeps java field defaults when nullability is unknown`() {
        val gson = GsonSafeParser.create()

        val result = gson.fromJson("""{"title":null}""", JavaNullDefaults::class.java)

        assertEquals("local", result.readTitle())
    }

    /**
     * 测试方法说明：验证“invalid scalar values do not overwrite constructed field defaults”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `invalid scalar values do not overwrite constructed field defaults`() {
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                fallbackPolicy = FallbackPolicy.Default,
                primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
            )
        )
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"count":"abc","enabled":"not_boolean"}""",
            Defaults::class.java
        )

        assertEquals(6, result.count)
        assertEquals(true, result.enabled)
    }

    /**
     * 测试方法说明：验证“empty numeric string keeps constructed defaults under contract first strategy”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `empty numeric string uses SafeParser zero strategy`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(primitiveParsingPolicy = PrimitiveParsingPolicy.Safe)
        )
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"intValue":"","decimal":""}""",
            NumberDefaults::class.java
        )

        assertEquals(9, result.intValue)
        assertEquals(BigDecimal("8.8"), result.decimal)
    }

    /**
     * 测试方法说明：验证 boxed Boolean 根值在默认策略下直接交给 Gson，不产生 SafeParser 事件。
     */
    @Test
    fun `boxed Boolean root value delegates to Gson without events`() {
        val events = mutableListOf<SafeParserEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onEvent = events::add))

        val result = gson.fromJson("false", Boolean::class.javaObjectType)

        assertEquals(false, result)
        assertEquals(emptyList<SafeParserEvent>(), events)
    }

    /**
     * 测试方法说明：验证 boxed Boolean 字段在默认策略下直接交给 Gson，不产生 SafeParser 事件。
     */
    @Test
    fun `boxed Boolean field delegates to Gson without events`() {
        val events = mutableListOf<SafeParserEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onEvent = events::add))

        val result = gson.fromJson("""{"enabled":false}""", NullableBooleanDefaults::class.java)

        assertEquals(false, result.enabled)
        assertEquals(emptyList<SafeParserEvent>(), events)
    }

    /**
     * 测试方法说明：验证 boxed number 字段是安全对照组，不应产生 SafeParser 事件。
     */
    @Test
    fun `boxed number fields delegate to Gson without events`() {
        val events = mutableListOf<SafeParserEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onEvent = events::add))

        val result = gson.fromJson("""{"count":1,"total":2}""", NullableNumberDefaults::class.java)

        assertEquals(1, result.count)
        assertEquals(2L, result.total)
        assertEquals(emptyList<SafeParserEvent>(), events)
    }

    /**
     * 测试方法说明：验证 List 里的 boxed Boolean item 不应被当成普通 Object。
     */
    @Test
    fun `boxed Boolean list items delegate to Gson without events`() {
        val events = mutableListOf<SafeParserEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onEvent = events::add))

        val result = gson.fromJson("""{"values":[false,true]}""", BooleanListDefaults::class.java)

        assertEquals(listOf(false, true), result.values)
        assertEquals(emptyList<SafeParserEvent>(), events)
    }

    /**
     * 测试方法说明：验证 Map value 里的 boxed Boolean 不应被当成普通 Object。
     */
    @Test
    fun `boxed Boolean map values delegate to Gson without events`() {
        val events = mutableListOf<SafeParserEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onEvent = events::add))

        val result = gson.fromJson("""{"values":{"a":false,"b":true}}""", BooleanMapValueDefaults::class.java)

        assertEquals(mapOf("a" to false, "b" to true), result.values)
        assertEquals(emptyList<SafeParserEvent>(), events)
    }

    /**
     * 测试方法说明：验证 Map key 里的 boxed Boolean 使用 Gson 的 boolean-as-string 规则。
     */
    @Test
    fun `boxed Boolean map keys delegate to Gson without events`() {
        val events = mutableListOf<SafeParserEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onEvent = events::add))

        val result = gson.fromJson("""{"values":{"true":"yes","false":"no"}}""", BooleanKeyMapDefaults::class.java)

        assertEquals(mapOf(true to "yes", false to "no"), result.values)
        assertEquals(emptyList<SafeParserEvent>(), events)
    }

    /**
     * 测试方法说明：验证“root object with wrong json shape returns null like SafeParser”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `root object with wrong json shape returns null like SafeParser`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("[]", Child::class.java)

        assertNull(result)
    }

    /**
     * 测试方法说明：验证“object field with wrong json shape keeps constructed default”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `object field with wrong json shape keeps constructed default`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("""{"child":[]}""", Defaults::class.java)

        assertEquals(Child("local-child"), result.child)
    }

    /**
     * 测试方法说明：验证“map supports array entry form”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `map supports array entry form`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"values":[[1,"one"],[2,"two"]]}""",
            IntKeyMap::class.java
        )

        assertEquals("one", result.values[1])
        assertEquals("two", result.values[2])
    }

    /**
     * 测试方法说明：验证“map array entry form preserves null key instead of coercing to empty string”这个具体行为。
     * null key 是复杂 Map entry 的合法输入，不能被内部兜底写成空字符串污染强类型 Map。
     */
    @Test
    fun `map array entry form preserves null key instead of coercing to empty string`() {
        val gson = GsonSafeParser.create()
        val type = object : TypeToken<Map<Int, String>>() {}.type

        val result = gson.fromJson<Map<Int, String>>(
            """[[null,"null-key"],[1,"one"]]""",
            type
        ) as Map<*, *>

        assertEquals("null-key", result[null])
        assertEquals("one", result[1])
        assertFalse(result.containsKey(""))
    }

    /**
     * 测试方法说明：验证复杂 Map entry 缺少 value 时只跳过当前 entry，后续 entry 仍继续解析。
     */
    @Test
    fun `map array entry with missing value skips entry and keeps following entries`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        val result = gson.fromJson(
            """{"values":[[1],[2,"two"]]}""",
            IntKeyMap::class.java
        )

        assertEquals(mapOf(2 to "two"), result.values)
        val event = events.single()
        assertEquals(ParseExceptionKind.MAP_ITEM, event.kind)
        assertEquals(JsonToken.END_ARRAY, event.actualToken)
        assertEquals("values", event.fieldName)
        assertEquals("$.values[0][1]", event.path)
        assertEquals("Map entry value is missing", event.reason)
        assertNull(event.mapItemKey)
    }

    /**
     * 测试方法说明：验证数组 entry 形式的 Map key 解析失败时只跳过当前 entry，不能被 value 的原生委托边界误判成外抛。
     */
    @Test
    fun `map array entry with invalid key skips entry even when value delegates own shape`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        val result = gson.fromJson(
            """{"values":[["bad","bad-key"],[2,"two"]]}""",
            IntKeyMap::class.java
        )

        assertEquals(mapOf(2 to "two"), result.values)
        val event = events.single()
        assertEquals(ParseExceptionKind.MAP_ITEM, event.kind)
        assertEquals("$.values[0][0]", event.path)
        assertNull(event.mapItemKey)
    }

    /**
     * 测试方法说明：验证复杂 Map entry 带额外元素时按兼容策略静默跳过多余值，不能污染下一条 entry。
     */
    @Test
    fun `map array entry with extra values silently skips extras and keeps following entries`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        val result = gson.fromJson(
            """{"values":[[1,"one","extra",{"ignored":true}],[2,"two"]]}""",
            IntKeyMap::class.java
        )

        assertEquals(mapOf(1 to "one", 2 to "two"), result.values)
        assertEquals(emptyList<TypeMismatchEvent>(), events)
    }

    /**
     * 测试方法说明：验证“map object form skips entries whose key cannot be parsed as declared key type”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `map object form skips entries whose key cannot be parsed as declared key type`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"values":{"abc":"bad","1":"ok"}}""",
            IntKeyMap::class.java
        )

        assertEquals(mapOf(1 to "ok"), result.values)
        assertEquals(ParseExceptionKind.MAP_ITEM, events.single().kind)
        assertNull(events.single().mapItemKey)
    }

    /**
     * 测试方法说明：验证“complex map key serialization can be enabled”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `complex map key serialization can be enabled`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(complexMapKeySerialization = true)
        )
        val type = object : TypeToken<Map<Child, String>>() {}.type

        val json = gson.toJson(mapOf(Child("remote") to "value"), type)

        assertEquals("""[[{"name":"remote"},"value"]]""", json)
    }

    /**
     * 测试方法说明：验证 builder-first 入口会继承 GsonBuilder 上开启的复杂 Map key 序列化配置。
     */
    @Test
    fun `builder first entry inherits complex map key serialization from gson builder`() {
        val gson = GsonBuilder()
            .enableComplexMapKeySerialization()
            .enableSafeParser()
            .create()
        val type = object : TypeToken<Map<Child, String>>() {}.type

        val json = gson.toJson(mapOf(Child("remote") to "value"), type)

        assertEquals("""[[{"name":"remote"},"value"]]""", json)
    }

    /**
     * 测试方法说明：验证“default map key serialization uses key adapter”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `default map key serialization uses key adapter`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        val type = object : TypeToken<Map<AliasKey, String>>() {}.type

        val json = gson.toJson(mapOf(AliasKey.ADMIN to "owner"), type)

        assertEquals("""{"admin_user":"owner"}""", json)
    }

    /**
     * 测试方法说明：验证“default map key serialization falls back to key toString when key adapter is unsafe”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `default map key serialization falls back to key toString when key adapter is unsafe`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        val objectKeyType = object : TypeToken<Map<ObjectKey, String>>() {}.type
        val throwingKeyType = object : TypeToken<Map<AdapterThrowingKey, String>>() {}.type

        val objectKeyJson = gson.toJson(mapOf(ObjectKey("object-key") to "owner"), objectKeyType)
        val throwingKeyJson = gson.toJson(mapOf(AdapterThrowingKey("throwing-key") to "owner"), throwingKeyType)

        assertEquals("""{"object-key":"owner"}""", objectKeyJson)
        assertEquals("""{"throwing-key":"owner"}""", throwingKeyJson)
    }

    /**
     * 测试方法说明：验证默认 Map key 序列化遇到 fatal 时直接外抛，不退回 key.toString()。
     */
    @Test
    fun `default map key serialization fatal failure is rethrown`() {
        val gson = GsonSafeParser.create()
        val type = object : TypeToken<Map<FatalWritingKey, String>>() {}.type

        assertThrows(AssertionError::class.java) {
            gson.toJson(mapOf(FatalWritingKey("fatal-key") to "owner"), type)
        }
    }

    /**
     * 测试方法说明：验证“nested map values accept array entry form”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `nested map values accept array entry form`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"values":{"inner":[[1,"one"]]}}""",
            NestedMapContainer::class.java
        )

        assertEquals("one", result.values["inner"]?.get(1))
    }
}
