package io.github.logan.gsonsafeparser

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.MalformedJsonException
import io.github.logan.gsonsafeparser.internal.runRecovering
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.EOFException
import java.util.concurrent.CancellationException

/** 数据类型错误可以恢复，损坏的 JSON 文档必须失败。 */
@Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SafeParserSyntaxBoundaryTest {
    class Payload {
        var title: String = "local"
        var count: Int = 7
        var values: List<String> = emptyList()
        var map: Map<String, String> = emptyMap()
        var next: String = "local"
    }

    private fun assertRejected(json: String) {
        assertThrows(JsonSyntaxException::class.java) {
            GsonBuilder().create().fromJson(json, Payload::class.java)
        }
        for (policy in PrimitiveParsingPolicy.entries) {
            val gson = GsonSafeParser.create(SafeParserConfig(primitiveParsingPolicy = policy))
            assertThrows(JsonSyntaxException::class.java, {
                gson.fromJson(json, Payload::class.java)
            }, policy.name)
        }
    }

    @Test
    fun `invalid unicode escape in a string is not field fallback`() {
        assertRejected("""{"title":"\uZZZZ","next":"remote"}""")
    }

    @Test
    fun `invalid escape in a numeric string is not field fallback`() {
        assertRejected("""{"count":"\q","next":"remote"}""")
    }

    @Test
    fun `invalid escape in a list is not item fallback`() {
        assertRejected("""{"values":["before","\uZZZZ","after"],"next":"remote"}""")
    }

    @Test
    fun `invalid escape in a map is not entry fallback`() {
        assertRejected("""{"map":{"bad":"\uZZZZ","ok":"after"},"next":"remote"}""")
    }

    @Test
    fun `truncated documents are rejected across fields and containers`() {
        for (json in listOf("""{"title":"open""", """{"values":["open""", """{"map":{"key":"open""")) {
            assertRejected(json)
        }
    }

    @Test
    fun `syntax wrappers do not hide nested cancellation or fatal signals`() {
        for (syntax in listOf(EOFException("truncated"), MalformedJsonException("invalid"))) {
            val cancelled = CancellationException("cancelled")
            syntax.initCause(cancelled)
            assertSame(cancelled, assertThrows(CancellationException::class.java) {
                runRecovering<Unit> { throw JsonSyntaxException(syntax) }
            })
        }
        val fatal = AssertionError("fatal")
        assertSame(fatal, assertThrows(AssertionError::class.java) {
            runRecovering<Unit> { throw JsonSyntaxException(EOFException().apply { initCause(fatal) }) }
        })
    }

    @Test
    fun `observer json failures are reported without changing successful parsing`() {
        val failures = mutableListOf<ObserverFailureEvent>()
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
                onEvent = { throw MalformedJsonException("observer payload failed") },
                onTypeMismatch = { throw EOFException("observer stream ended") },
                onObserverFailure = {
                    failures += it
                    throw EOFException("observer reporter failed")
                }
            )
        )
        assertEquals("remote", gson.fromJson("""{"title":{},"next":"remote"}""", Payload::class.java).next)
        assertEquals(listOf("onEvent", "onTypeMismatch"), failures.map { it.callbackName })
    }
}
