package io.github.logan.gsonsafeparser

import com.google.gson.stream.JsonToken
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 验证早期回调能力。
 *
 * 这些用例保留基础的错配通知行为，确保后续扩展统一事件流时不会破坏已接入方的 `onTypeMismatch`。
 */
class SafeParserCallbackTest {
    /** 测试模型：外层响应，data 错形时会触发类型错配回调。 */
    data class ApiResponse(val data: User = User())
    /** 测试模型：用户对象，默认 id 用于确认解析结果没有被坏数据覆盖。 */
    data class User(val id: Long = 0L)

    /**
     * 测试方法说明：验证“type mismatch handler receives mismatch event”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `type mismatch handler receives mismatch event`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                onTypeMismatch = { event -> events += event }
            )
        )

        assertEquals(ApiResponse(), gson.fromJson("""{"data":[]}""", ApiResponse::class.java))

        assertEquals(1, events.size)
        assertEquals(User::class.java.name, events.first().expectedType)
        assertEquals(JsonToken.BEGIN_ARRAY, events.first().actualToken)
        assertTrue(events.first().path.endsWith(".data"))
    }
}
