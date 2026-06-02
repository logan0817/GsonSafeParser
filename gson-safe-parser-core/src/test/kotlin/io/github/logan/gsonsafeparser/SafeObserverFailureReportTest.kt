package io.github.logan.gsonsafeparser

import com.google.gson.stream.JsonToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验证观察者失败报告。
 *
 * 观察者失败是日志、埋点或回调代码自己的问题，不应该混进解析契约报告。
 * 这里同时检查报告可读性和脱敏边界，避免 rawJson 或异常栈被直接写出。
 */
class SafeObserverFailureReportTest {
    /**
     * 测试方法说明：验证“observer failure report summarizes failures without leaking raw json or throwable message”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `observer failure report summarizes failures without leaking raw json or throwable message`() {
        val failures = listOf(
            ObserverFailureEvent(
                callbackName = "onEvent",
                eventName = "TypeMismatch",
                sourceEvent = SafeParserEvent.TypeMismatch(
                    TypeMismatchEvent(
                        expectedType = "com.example.User",
                        actualToken = JsonToken.BEGIN_ARRAY,
                        path = "$.data",
                        reason = "Unexpected JSON token",
                        fieldName = "data",
                        rawJson = """{"password":"secret"}"""
                    )
                ),
                reason = "upload failed with token=secret",
                error = IllegalStateException("upload failed with token=secret")
            )
        )

        // report 是根据事件生成的可读报告，用来验证观测结果是否完整。
        val report = failures.observerFailureReport()

        assertTrue(report.hasFailures)
        assertEquals(1, report.failures.size)
        assertEquals(1, report.failuresByCallback.getValue("onEvent").size)

        val failure = report.failures.single()
        assertEquals("onEvent", failure.callbackName)
        assertEquals("TypeMismatch", failure.eventName)
        assertEquals(SafeParserEventCategory.TypeMismatch, failure.sourceCategory)
        assertEquals("$.data", failure.sourcePath)
        assertEquals("data", failure.sourceFieldName)
        assertEquals("com.example.User", failure.sourceTypeName)
        assertEquals(IllegalStateException::class.java.name, failure.errorType)

        val markdown = report.toMarkdown()
        assertTrue(markdown.contains("onEvent"))
        assertTrue(markdown.contains("$.data"))
        assertFalse(markdown.contains("password"))
        assertFalse(markdown.contains("secret"))
        assertFalse(markdown.contains("upload failed"))
        assertFalse(markdown.contains("TypeMismatchEvent"))
    }

    /**
     * 测试方法说明：验证“observer failure report maps adapter creation and retrofit events”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `observer failure report maps adapter creation and retrofit events`() {
        val failures = listOf(
            ObserverFailureEvent(
                callbackName = "onAdapterCreationFailure",
                eventName = "AdapterCreationFailure",
                sourceEvent = SafeParserEvent.AdapterCreationFailure(
                    AdapterCreationFailureEvent(
                        typeName = "com.example.Broken",
                        reason = "Duplicate field",
                        error = IllegalArgumentException("internal detail")
                    )
                ),
                reason = "logger failed",
                error = IllegalStateException("logger failed")
            ),
            ObserverFailureEvent(
                callbackName = "onEvent",
                eventName = "EmptyResponse",
                sourceEvent = SafeParserEvent.EmptyResponse(
                    EmptyResponseEvent(
                        typeName = "com.example.ApiResponse",
                        policy = EmptyResponsePolicy.DefaultValue
                    )
                ),
                reason = "logger failed",
                error = IllegalStateException("logger failed")
            )
        )

        // report 是根据事件生成的可读报告，用来验证观测结果是否完整。
        val report = failures.observerFailureReport()

        assertEquals(2, report.failures.size)
        assertEquals(SafeParserEventCategory.AdapterCreationFailure, report.failures[0].sourceCategory)
        assertEquals("com.example.Broken", report.failures[0].sourceTypeName)
        assertEquals(SafeParserEventCategory.EmptyResponse, report.failures[1].sourceCategory)
        assertEquals("com.example.ApiResponse", report.failures[1].sourceTypeName)
    }

    /**
     * 测试方法说明：验证“observer failure report is empty for empty failures”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `observer failure report is empty for empty failures`() {
        // report 是根据事件生成的可读报告，用来验证观测结果是否完整。
        val report = emptyList<ObserverFailureEvent>().observerFailureReport()

        assertFalse(report.hasFailures)
        assertTrue(report.failures.isEmpty())
        assertTrue(report.failuresByCallback.isEmpty())
        assertEquals("No safe parser observer failures.", report.toMarkdown())
    }
}
