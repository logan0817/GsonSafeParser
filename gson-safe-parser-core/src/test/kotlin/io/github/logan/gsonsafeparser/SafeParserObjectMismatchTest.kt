package io.github.logan.gsonsafeparser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull

/**
 * 验证 `Object` 字段错形兜底。
 *
 * 这是当前库最核心的使用场景：后端把定义为 Object 的字段返回成 `[]` 时，
 * 外层 Bean 仍然能解析出来，字段按配置保留默认对象或返回 null。
 */
class SafeParserObjectMismatchTest {
    /** 测试模型：普通接口响应，data 是最典型的“`Object` 字段被后端返回成数组”的字段。 */
    data class ApiResponse(val code: Int, val data: User = User())
    /** 测试模型：data 允许为 null，用来验证 NullOnly 策略不会强行构造对象。 */
    data class NullableApiResponse(val code: Int, val data: User?)
    /** 测试模型：字段名使用 result，避免只覆盖 data 这一种命名。 */
    data class ResultResponse(val message: String = "ok", val result: User? = User())
    /** 测试模型：业务用户对象，默认值用于判断错形时是否保留本地默认数据。 */
    data class User(val id: Long = 0L, val name: String = "anonymous")

    /**
     * 测试方法说明：验证“object field uses default object when backend sends empty array”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `object field uses default object when backend sends empty array`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"code":200,"data":[]}""",
            ApiResponse::class.java
        )

        assertEquals(200, result.code)
        assertEquals(User(), result.data)
    }

    /**
     * 测试方法说明：验证“null only policy returns null when object field receives array”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `null only policy returns null when object field receives array`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(fallbackPolicy = FallbackPolicy.NullOnly)
        )
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"code":200,"data":[]}""",
            NullableApiResponse::class.java
        )

        assertEquals(200, result.code)
        assertNull(result.data)
    }

    /**
     * 测试方法说明：验证“object field keeps normal object payload”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `object field keeps normal object payload`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"code":200,"data":{"id":7,"name":"Tom"}}""",
            ApiResponse::class.java
        )

        assertNotNull(result.data)
        assertEquals(7L, result.data.id)
        assertEquals("Tom", result.data.name)
    }

    /**
     * 测试方法说明：验证“nullable object field accepts backend null without class cast failure”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `nullable object field accepts backend null without class cast failure`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"message":"操作成功","result":null}""",
            ResultResponse::class.java
        )

        assertEquals("操作成功", result.message)
        assertNull(result.result)
    }
}
