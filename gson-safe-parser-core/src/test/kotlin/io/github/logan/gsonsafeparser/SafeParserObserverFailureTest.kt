package io.github.logan.gsonsafeparser

import com.google.gson.annotations.SerializedName
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验证观察者异常隔离。
 *
 * 观察回调通常会接日志或埋点，这些外部依赖失败时不能打断 JSON 解析，
 * 也不能阻止 Adapter 创建失败回到 Gson 默认链路。
 */
class SafeParserObserverFailureTest {
    /** 测试模型：外层响应，data 错形会触发观察回调。 */
    data class ApiResponse(val data: User = User())
    /** 测试模型：用户对象，默认 id 用来确认解析未被观察者异常打断。 */
    data class User(val id: Long = 0L)
    /** 测试模型：重复 JSON 字段名，用来故意触发 Adapter 创建失败。 */
    data class DuplicateNamesForFallback(
        @SerializedName("same")
        val first: String = "",
        @SerializedName("same")
        val second: String = ""
    )

    /**
     * 测试方法说明：验证“type mismatch observer failures do not interrupt parsing”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `type mismatch observer failures do not interrupt parsing`() {
        val typeMismatches = mutableListOf<TypeMismatchEvent>()
        // observerFailures 用来收集观察者自身抛出的异常，验证它不会影响主解析。
        val observerFailures = mutableListOf<ObserverFailureEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                onEvent = {
                    throw IllegalStateException("event logger failed")
                },
                onTypeMismatch = { event ->
                    typeMismatches += event
                    throw IllegalStateException("compatibility logger failed")
                },
                onObserverFailure = observerFailures::add
            )
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = assertDoesNotThrow<ApiResponse> {
            gson.fromJson("""{"data":[]}""", ApiResponse::class.java)
        }

        assertEquals(ApiResponse(), result)
        assertEquals(1, typeMismatches.size)
        assertEquals(listOf("onEvent", "onTypeMismatch"), observerFailures.map { it.callbackName })
        assertTrue(observerFailures.all { it.sourceEvent is SafeParserEvent.TypeMismatch })
    }

    /**
     * 测试方法说明：验证“observer failure callback exceptions are swallowed”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `observer failure callback exceptions are swallowed`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                onTypeMismatch = {
                    throw IllegalStateException("compatibility logger failed")
                },
                onObserverFailure = {
                    throw IllegalStateException("observer failure logger failed")
                }
            )
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = assertDoesNotThrow<ApiResponse> {
            gson.fromJson("""{"data":[]}""", ApiResponse::class.java)
        }

        assertEquals(ApiResponse(), result)
    }

    /**
     * 测试方法说明：验证观察者回调抛出 fatal 时必须外抛，不能进入普通观察者失败隔离。
     */
    @Test
    fun `type mismatch observer fatal failure is rethrown`() {
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                onEvent = {
                    throw AssertionError("fatal event logger failure")
                }
            )
        )

        assertThrows(AssertionError::class.java) {
            gson.fromJson("""{"data":[]}""", ApiResponse::class.java)
        }
    }

    /**
     * 测试方法说明：验证 observerFailure 回调自身抛出 fatal 时必须外抛。
     */
    @Test
    fun `observer failure fatal failure is rethrown`() {
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                onTypeMismatch = {
                    throw IllegalStateException("compatibility logger failed")
                },
                onObserverFailure = {
                    throw AssertionError("fatal observer failure logger failure")
                }
            )
        )

        assertThrows(AssertionError::class.java) {
            gson.fromJson("""{"data":[]}""", ApiResponse::class.java)
        }
    }

    /**
     * 测试方法说明：验证“adapter creation observer failures do not block Gson fallback”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `adapter creation observer failures do not block Gson fallback`() {
        // observerFailures 用来收集观察者自身抛出的异常，验证它不会影响主解析。
        val observerFailures = mutableListOf<ObserverFailureEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                onAdapterCreationFailure = {
                    throw IllegalStateException("adapter logger failed")
                },
                onObserverFailure = observerFailures::add
            )
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            gson.getAdapter(DuplicateNamesForFallback::class.java)
        }

        assertFalse(error.message.orEmpty().contains("adapter logger failed"))
        assertEquals("onAdapterCreationFailure", observerFailures.single().callbackName)
    }

    /**
     * 测试方法说明：验证“parse safe captures event even when user event callback fails”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `parse safe captures event even when user event callback fails`() {
        // observerFailures 用来收集观察者自身抛出的异常，验证它不会影响主解析。
        val observerFailures = mutableListOf<ObserverFailureEvent>()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = GsonSafeParser.parseSafe<ApiResponse>(
            json = """{"data":[]}""",
            config = SafeParserConfig(
                onEvent = {
                    throw IllegalStateException("event logger failed")
                },
                onObserverFailure = observerFailures::add
            )
        )

        assertEquals(ApiResponse(), result.value)
        assertEquals(1, result.events.size)
        assertEquals("onEvent", observerFailures.single().callbackName)
    }
}
