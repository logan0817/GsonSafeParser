package io.github.logan.gsonsafeparser

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * 验证 SafeParser 自定义注解和 Kotlin 便捷 API。
 *
 * 类级注解用于把类型交回 Gson，字段级注解用于跳过高风险字段；
 * Kotlin API 则负责保留泛型信息和事件快照。
 */
class SafeParserAnnotationKotlinApiTest {
    /** 测试模型：外层响应，data 字段用于验证 reified API 和字段跳过行为。 */
    data class ApiResponse(val data: User = User("local"))
    /** 测试模型：用户对象，name 默认值用于判断错形时是否保留本地数据。 */
    data class User(val name: String = "local")

    /**
     * 测试模型：类级注解要求完全交回 Gson。
     *
     * 这里保留可变字段，模拟 Java Bean 风格对象。
     */
    @SafeParseDelegateToGson
    class NativeOnly {
        /** 测试字段：Gson 原生反射会直接读写它。 */
        var name: String = "local"
    }

    /** 测试模型：把类级 delegate 类型放进字段，验证父对象读取不会绕过 delegate 语义。 */
    data class NativeOnlyContainer(val native: NativeOnly = NativeOnly())

    /** 测试模型：类级 JsonAdapter 也属于严格交给 Gson 的类型。 */
    @JsonAdapter(NativeJsonAdapterOnlyAdapter::class)
    class NativeJsonAdapterOnly {
        var name: String = "local"
    }

    /** 测试模型：把类级 JsonAdapter 类型放进字段，验证全局 shape coercion 不会改写旧行为。 */
    data class NativeJsonAdapterOnlyContainer(
        val native: NativeJsonAdapterOnly = NativeJsonAdapterOnly()
    )

    /** 测试 Adapter：只读取对象形态，数组形态应按原 Gson 行为失败。 */
    class NativeJsonAdapterOnlyAdapter : TypeAdapter<NativeJsonAdapterOnly>() {
        override fun write(out: JsonWriter, value: NativeJsonAdapterOnly?) {
            out.beginObject()
            out.name("name").value(value?.name)
            out.endObject()
        }

        override fun read(reader: JsonReader): NativeJsonAdapterOnly {
            reader.beginObject()
            val result = NativeJsonAdapterOnly()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> result.name = reader.nextString()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return result
        }
    }

    /**
     * 测试模型：字段级注解跳过 Safe Reflective 绑定。
     *
     * `user` 被跳过后，后端错形不能覆盖它的默认值。
     */
    data class SkipFieldResponse(
        @field:SafeParseSkip
        val user: User = User("local"),
        val title: String = "local"
    )

    /**
     * 测试方法说明：验证“class annotation delegates parsing back to Gson native adapter”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `class annotation delegates parsing back to Gson native adapter`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        assertThrows(JsonSyntaxException::class.java) {
            gson.fromJson("[]", NativeOnly::class.java)
        }
    }

    @Test
    fun `global shape coercion does not override class delegate annotation`() {
        val events = mutableListOf<SafeParserEvent>()
        val gson = GsonSafeParser.create(
            SafeParserConfig(onEvent = events::add).withShapeCoercionPolicy(
                ShapeCoercionPolicy.ObjectAndCollection
            )
        )

        val result = gson.fromJson(
            """{"native":[{"name":"remote"}]}""",
            NativeOnlyContainer::class.java
        )

        assertEquals("local", result.native.name)
        assertFalse(events.any { event -> event is SafeParserEvent.ShapeCoercion })
    }

    @Test
    fun `global shape coercion does not override class json adapter`() {
        val events = mutableListOf<SafeParserEvent>()
        val gson = GsonSafeParser.create(
            SafeParserConfig(onEvent = events::add).withShapeCoercionPolicy(
                ShapeCoercionPolicy.ObjectAndCollection
            )
        )

        val result = gson.fromJson(
            """{"native":[{"name":"remote"}]}""",
            NativeJsonAdapterOnlyContainer::class.java
        )

        assertEquals("local", result.native.name)
        assertFalse(events.any { event -> event is SafeParserEvent.ShapeCoercion })
    }

    /**
     * 测试方法说明：验证“field annotation skips safe reflective field binding”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `field annotation skips safe reflective field binding`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"user":{"name":"remote"},"title":"remote"}""",
            SkipFieldResponse::class.java
        )

        assertEquals(User("local"), result.user)
        assertEquals("remote", result.title)
    }

    /**
     * 测试方法说明：验证“reified kotlin api parses without explicit class token”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `reified kotlin api parses without explicit class token`() {
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = GsonSafeParser.fromJsonSafe<ApiResponse>("""{"data":{"name":"remote"}}""")

        assertEquals(ApiResponse(User("remote")), result)
    }

    /**
     * 测试方法说明：验证“safe parse result captures unified events”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `safe parse result captures unified events`() {
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = GsonSafeParser.parseSafe<ApiResponse>("""{"data":[]}""")

        assertNotNull(result.value)
        val event = result.events.single() as SafeParserEvent.TypeMismatch
        assertEquals(JsonToken.BEGIN_ARRAY, event.detail.actualToken)
        assertEquals("data", event.detail.fieldName)
    }
}
