package io.github.logan.gsonsafeparser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * 验证嵌套对象的局部兜底。
 *
 * 内层字段错形时只应该替换当前字段，外层已经解析成功的字段不能被回滚或清空。
 */
class SafeParserNestedObjectTest {
    /** 测试模型：外层响应，data 用默认 User 验证嵌套对象兜底。 */
    data class ApiResponse(val data: User = User())
    /** 测试模型：中间层用户，profile 默认值用于验证只兜底当前坏字段。 */
    data class User(val id: Long = 0L, val profile: Profile = Profile())
    /** 测试模型：最内层资料，city 默认值用于判断嵌套字段是否被保留。 */
    data class Profile(val city: String = "")

    /**
     * 测试方法说明：验证“nested mismatch only falls back current field”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `nested mismatch only falls back current field`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"data":{"id":8,"profile":[]}}""",
            ApiResponse::class.java
        )

        assertEquals(8L, result.data.id)
        assertEquals(Profile(), result.data.profile)
    }
}
