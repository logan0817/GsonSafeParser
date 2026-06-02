package io.github.logan.gsonsafeparser

import com.google.gson.JsonSyntaxException
import com.google.gson.stream.JsonToken
import org.junit.jupiter.api.Assertions.assertEquals
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
