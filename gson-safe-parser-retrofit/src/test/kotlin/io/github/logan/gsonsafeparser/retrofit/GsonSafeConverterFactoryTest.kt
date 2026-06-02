package io.github.logan.gsonsafeparser.retrofit

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.EmptyResponsePolicy
import io.github.logan.gsonsafeparser.ObserverFailureEvent
import io.github.logan.gsonsafeparser.RawJsonCaptureSkipReason
import io.github.logan.gsonsafeparser.SafeParserEvent
import io.github.logan.gsonsafeparser.TypeMismatchEvent
import io.github.logan.gsonsafeparser.enableSafeParser
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.InvocationTargetException
import java.io.EOFException
import java.io.IOException
import java.nio.charset.Charset

/**
 * 验证 Retrofit converter 的接入行为。
 *
 * 这里重点看空响应策略、rawJson 捕获、观察者异常隔离，以及超限时是否能回到原 Gson converter。
 * Retrofit 层不能重新造解析规则，只能把 core 模块的能力安全接到响应转换流程里。
 */
class GsonSafeConverterFactoryTest {
    /** 测试模型：空响应时应该构造出的默认响应对象。 */
    data class EmptyApiResponse(
        val code: Int = 200,
        val data: EmptyPayload = EmptyPayload()
    )

    /** 测试模型：空响应里的默认 payload。 */
    data class EmptyPayload(val name: String = "local")
    /** 测试模型：响应体非空但字段错形时，用来验证 Retrofit rawJson 观测。 */
    data class MismatchApiResponse(val data: EmptyPayload = EmptyPayload())

    /**
     * 测试方法说明：验证“factory creates retrofit converter with default safe gson”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `factory creates retrofit converter with default safe gson`() {
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create()
        assertNotNull(factory)
    }

    /**
     * 测试方法说明：验证“factory creates retrofit converter with custom config”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `factory creates retrofit converter with custom config`() {
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create(SafeParserConfig())
        assertNotNull(factory)
    }

    /**
     * 测试方法说明：验证“factory can combine caller gson and safe parser config”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `factory can combine caller gson and safe parser config`() {
        val gson = GsonBuilder()
            .serializeNulls()
            .enableSafeParser()
            .create()
        val factory = GsonSafeConverterFactory.create(
            gson = gson,
            config = SafeParserConfig(
                emptyResponsePolicy = EmptyResponsePolicy.Null
            )
        )
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        val result = converter?.convert(ResponseBody.create(MediaType.parse("application/json"), ""))

        assertNull(result)
    }

    /**
     * 测试方法说明：验证外部 Gson 未注册 Safe Adapter 时，Retrofit 工厂不会偷偷补注册字段级保护。
     */
    @Test
    fun `factory with plain caller gson does not auto enable safe adapters`() {
        val gson = GsonBuilder().create()
        val factory = GsonSafeConverterFactory.create(gson, SafeParserConfig())
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        assertThrows(JsonSyntaxException::class.java) {
            converter?.convert(ResponseBody.create(MediaType.parse("application/json"), """{"data":[]}"""))
        }
    }

    /**
     * 测试方法说明：验证外部 Gson 已注册 Safe Adapter 时，Retrofit 工厂能保留字段级保护。
     */
    @Test
    fun `factory with safe caller gson keeps field level safe parsing`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(onEvent = events::add)
        val gson = GsonBuilder()
            .enableSafeParser(config)
            .create()
        val factory = GsonSafeConverterFactory.create(gson, config)
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        val result = converter?.convert(ResponseBody.create(MediaType.parse("application/json"), """{"data":[]}"""))

        assertEquals(MismatchApiResponse(), result)
        val event = events.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.data", event.detail.path)
    }

    /**
     * 测试方法说明：验证外部 Safe Gson 的字段事件仍归属创建 Gson 时的配置，
     * Retrofit config 只提供 rawJson 捕获、空响应和 Retrofit 层事件策略。
     */
    @Test
    fun `factory with safe caller gson keeps adapter events on gson config while retrofit config captures raw json`() {
        val adapterEvents = mutableListOf<SafeParserEvent>()
        val retrofitEvents = mutableListOf<SafeParserEvent>()
        val gsonConfig = SafeParserConfig(onEvent = adapterEvents::add)
        val retrofitConfig = SafeParserConfig(
            captureRawJsonInCallbacks = true,
            onEvent = retrofitEvents::add
        )
        val gson = GsonBuilder()
            .enableSafeParser(gsonConfig)
            .create()
        val factory = GsonSafeConverterFactory.create(gson, retrofitConfig)
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )
        val rawJson = """{"data":[]}"""

        val result = converter?.convert(ResponseBody.create(MediaType.parse("application/json"), rawJson))

        assertEquals(MismatchApiResponse(), result)
        val event = adapterEvents.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.data", event.detail.path)
        assertEquals(rawJson, event.detail.rawJson)
        assertTrue(retrofitEvents.none { it is SafeParserEvent.TypeMismatch })
    }

    /**
     * 测试方法说明：验证 builder-first Retrofit 入口会在创建 Gson 前注册 Safe Adapter，减少外部 plain Gson 误传。
     */
    @Test
    fun `factory can create safe gson from caller builder`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(onEvent = events::add)
        val factory = GsonSafeConverterFactory.create(
            builder = GsonBuilder().serializeNulls(),
            config = config
        )
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        val result = converter?.convert(ResponseBody.create(MediaType.parse("application/json"), """{"data":[]}"""))

        assertEquals(MismatchApiResponse(), result)
        val event = events.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.data", event.detail.path)
    }

    /**
     * 测试方法说明：验证 Retrofit 层探测响应体时遇到 fatal，不能被 runRecovering 当成普通空响应。
     */
    @Test
    fun `response body fatal source failure is rethrown`() {
        val factory = GsonSafeConverterFactory.create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        assertThrows(AssertionError::class.java) {
            converter?.convert(failingResponseBody(AssertionError("fatal source failure")))
        }
    }

    /**
     * 测试方法说明：验证 Retrofit 层 fatal 即使被 InvocationTargetException 包住，也必须外抛原始 fatal。
     */
    @Test
    fun `response body invocation target fatal source failure is rethrown`() {
        val factory = GsonSafeConverterFactory.create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        assertThrows(AssertionError::class.java) {
            converter?.convert(
                failingResponseBody(
                    InvocationTargetException(AssertionError("wrapped fatal source failure"))
                )
            )
        }
    }

    /**
     * 测试方法说明：验证“response converter returns null for model when response body is empty by default”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `response converter returns null for model when response body is empty by default`() {
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create()
        // retrofit 是测试用的 Retrofit 实例，只用来触发 converter 创建流程。
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        // converter 是 Retrofit 最终使用的响应转换器，测试会直接调用它验证结果。
        val converter = factory.responseBodyConverter(
            EmptyApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = converter?.convert(ResponseBody.create(MediaType.parse("application/json"), ""))

        assertNull(result)
    }

    private fun failingResponseBody(failure: Throwable): ResponseBody {
        return object : ResponseBody() {
            override fun contentType(): MediaType? = MediaType.parse("application/json")

            override fun contentLength(): Long = -1

            override fun source(): BufferedSource {
                return okio.Okio.buffer(
                    object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            throw failure
                        }

                        override fun timeout(): Timeout = Timeout.NONE

                        override fun close() = Unit
                    }
                )
            }
        }
    }

    /**
     * 测试方法说明：验证 DefaultValue 空响应策略会为业务模型构造默认对象。
     */
    @Test
    fun `empty response can return default model when configured`() {
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DefaultValue)
        )
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            EmptyApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        val result = converter?.convert(ResponseBody.create(MediaType.parse("application/json"), ""))

        assertEquals(EmptyApiResponse(), result)
    }

    /**
     * 测试方法说明：验证“response converter returns Unit when response body is empty”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `response converter returns Unit when response body is empty`() {
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create()
        // retrofit 是测试用的 Retrofit 实例，只用来触发 converter 创建流程。
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        // converter 是 Retrofit 最终使用的响应转换器，测试会直接调用它验证结果。
        val converter = factory.responseBodyConverter(
            Unit::class.java,
            emptyArray(),
            retrofit
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = converter?.convert(ResponseBody.create(MediaType.parse("application/json"), ""))

        assertEquals(Unit, result)
    }

    /**
     * 测试方法说明：验证“response converter returns null for Void when response body is empty”这个具体行为。
     * 阅读时可以按准备数据、执行转换、断言结果的顺序跟下来。
     */
    @Test
    fun `response converter returns null for Void when response body is empty`() {
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create()
        // retrofit 是测试用的 Retrofit 实例，只用来触发 converter 创建流程。
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        // converter 是 Retrofit 最终使用的响应转换器，Void.TYPE 不能提前向 Gson 要 delegate。
        val converter = factory.responseBodyConverter(
            Void.TYPE,
            emptyArray(),
            retrofit
        )

        // result 是本次转换得到的实际结果；空响应下 Void 应直接返回 null。
        val result = converter?.convert(ResponseBody.create(MediaType.parse("application/json"), ""))

        assertNull(result)
    }

    /**
     * 测试方法说明：验证 boxed Void 空响应也直接返回 null，不提前触发 Gson void Adapter 创建失败。
     */
    @Test
    fun `response converter returns null for boxed Void when response body is empty`() {
        val factory = GsonSafeConverterFactory.create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            Void::class.java,
            emptyArray(),
            retrofit
        )

        val result = converter?.convert(ResponseBody.create(MediaType.parse("application/json"), ""))

        assertNull(result)
    }

    /**
     * 测试方法说明：验证 Unit 非空响应也按无返回值语义关闭 body 并返回 Unit。
     */
    @Test
    fun `unit response returns Unit when response body is not empty`() {
        val factory = GsonSafeConverterFactory.create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            Unit::class.java,
            emptyArray(),
            retrofit
        )

        val result = converter?.convert(ResponseBody.create(MediaType.parse("application/json"), """{"ignored":true}"""))

        assertEquals(Unit, result)
    }

    /**
     * 测试方法说明：验证 boxed Void 非空响应也按无返回值语义关闭 body 并返回 null。
     */
    @Test
    fun `boxed Void response returns null when response body is not empty`() {
        val factory = GsonSafeConverterFactory.create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            Void::class.java,
            emptyArray(),
            retrofit
        )

        val result = converter?.convert(ResponseBody.create(MediaType.parse("application/json"), """{"ignored":true}"""))

        assertNull(result)
    }

    /**
     * 测试方法说明：验证“empty response can return null when configured”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `empty response can return null when configured`() {
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.Null)
        )
        // retrofit 是测试用的 Retrofit 实例，只用来触发 converter 创建流程。
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        // converter 是 Retrofit 最终使用的响应转换器，测试会直接调用它验证结果。
        val converter = factory.responseBodyConverter(
            EmptyApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = converter?.convert(ResponseBody.create(MediaType.parse("application/json"), ""))

        assertNull(result)
    }

    /**
     * 测试方法说明：验证“empty response emits unified retrofit event”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `empty response emits unified retrofit event`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<SafeParserEvent>()
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(onEvent = events::add)
        )
        // retrofit 是测试用的 Retrofit 实例，只用来触发 converter 创建流程。
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        // converter 是 Retrofit 最终使用的响应转换器，测试会直接调用它验证结果。
        val converter = factory.responseBodyConverter(
            EmptyApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        converter?.convert(ResponseBody.create(MediaType.parse("application/json"), ""))

        val event = events.single() as SafeParserEvent.EmptyResponse
        assertEquals(EmptyApiResponse::class.java.name, event.detail.typeName)
        assertEquals(EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly, event.detail.policy)
    }

    /**
     * 测试方法说明：验证“retrofit event observer failure does not interrupt empty response conversion”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `retrofit event observer failure does not interrupt empty response conversion`() {
        // observerFailures 用来收集观察者自身抛出的异常，验证它不会影响主解析。
        val observerFailures = mutableListOf<ObserverFailureEvent>()
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                onEvent = {
                    throw IllegalStateException("event logger failed")
                },
                onObserverFailure = observerFailures::add
            )
        )
        // retrofit 是测试用的 Retrofit 实例，只用来触发 converter 创建流程。
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        // converter 是 Retrofit 最终使用的响应转换器，测试会直接调用它验证结果。
        val converter = factory.responseBodyConverter(
            EmptyApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = assertDoesNotThrow<Any?> {
            converter?.convert(ResponseBody.create(MediaType.parse("application/json"), ""))
        }

        assertNull(result)
        val failure = observerFailures.single()
        assertEquals("onEvent", failure.callbackName)
        assertEquals(SafeParserEvent.EmptyResponse::class.java, failure.sourceEvent::class.java)
    }

    /**
     * 测试方法说明：验证“empty response can delegate to Gson default failure when configured”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `empty response can delegate to Gson default failure when configured`() {
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DelegateToGson)
        )
        // retrofit 是测试用的 Retrofit 实例，只用来触发 converter 创建流程。
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        // converter 是 Retrofit 最终使用的响应转换器，测试会直接调用它验证结果。
        val converter = factory.responseBodyConverter(
            EmptyApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        assertThrows(EOFException::class.java) {
            converter?.convert(ResponseBody.create(MediaType.parse("application/json"), ""))
        }
    }

    /**
     * 测试方法说明：验证空响应探测的普通 I/O 失败不会由扩展层放大，应退回 Gson converter 继续解析。
     */
    @Test
    fun `empty response ordinary probe failure delegates to Gson conversion`() {
        val factory = GsonSafeConverterFactory.create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        val result = assertDoesNotThrow<Any?> {
            converter?.convert(firstRequestFailureBody("""{"data":{"name":"remote"}}""", IOException("probe failed")))
        }

        assertEquals(MismatchApiResponse(EmptyPayload("remote")), result)
    }

    /**
     * 测试方法说明：验证空响应探测遇到 fatal 时必须外抛，不能回退成普通响应解析。
     */
    @Test
    fun `empty response fatal probe failure is rethrown`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(SafeParserConfig(onEvent = events::add))
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        assertThrows(LinkageError::class.java) {
            converter?.convert(firstRequestFailureBody("""{"data":{"name":"remote"}}""", LinkageError("probe fatal")))
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证“retrofit response can attach raw json to mismatch callback when configured”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `retrofit response can attach raw json to mismatch callback when configured`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                onTypeMismatch = events::add
            )
        )
        // retrofit 是测试用的 Retrofit 实例，只用来触发 converter 创建流程。
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        // converter 是 Retrofit 最终使用的响应转换器，测试会直接调用它验证结果。
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )
        val rawJson = """{"data":[]}"""

        converter?.convert(ResponseBody.create(MediaType.parse("application/json"), rawJson))

        assertEquals(rawJson, events.single().rawJson)
    }

    /**
     * 测试方法说明：验证“retrofit response skips raw json capture when content length exceeds configured limit”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `retrofit response skips raw json capture when content length exceeds configured limit`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 4,
                onTypeMismatch = events::add
            )
        )
        // retrofit 是测试用的 Retrofit 实例，只用来触发 converter 创建流程。
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        // converter 是 Retrofit 最终使用的响应转换器，测试会直接调用它验证结果。
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        converter?.convert(ResponseBody.create(MediaType.parse("application/json"), """{"data":[]}"""))

        assertNull(events.single().rawJson)
    }

    /**
     * 测试方法说明：验证“retrofit response emits event when raw json capture is skipped by size limit”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `retrofit response emits event when raw json capture is skipped by size limit`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<SafeParserEvent>()
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 4,
                onEvent = events::add
            )
        )
        // retrofit 是测试用的 Retrofit 实例，只用来触发 converter 创建流程。
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        // converter 是 Retrofit 最终使用的响应转换器，测试会直接调用它验证结果。
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        converter?.convert(ResponseBody.create(MediaType.parse("application/json"), """{"data":[]}"""))

        val event = events.single { it is SafeParserEvent.RawJsonCaptureSkipped } as SafeParserEvent.RawJsonCaptureSkipped
        assertEquals(MismatchApiResponse::class.java.name, event.detail.typeName)
        assertEquals(4, event.detail.maxBytes)
        assertEquals(RawJsonCaptureSkipReason.ContentLengthExceedsLimit, event.detail.skipReason)
    }

    /**
     * 测试方法说明：验证“retrofit response captures raw json when length is unknown but body stays within limit”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `retrofit response captures raw json when length is unknown but body stays within limit`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 64,
                onEvent = events::add
            )
        )
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        converter?.convert(unknownLengthBody("""{"data":[]}"""))

        val event = events.single { it is SafeParserEvent.TypeMismatch } as SafeParserEvent.TypeMismatch
        assertEquals("""{"data":[]}""", event.detail.rawJson)
        assertTrue(events.none { it is SafeParserEvent.RawJsonCaptureSkipped })
    }

    /**
     * 测试方法说明：验证“retrofit response emits clear event when unknown raw json exceeds limit”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `retrofit response emits clear event when unknown raw json exceeds limit`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 4,
                onEvent = events::add
            )
        )
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        converter?.convert(unknownLengthBody("""{"data":[]}"""))

        val event = events.single { it is SafeParserEvent.RawJsonCaptureSkipped } as SafeParserEvent.RawJsonCaptureSkipped
        assertEquals(-1, event.detail.contentLength)
        assertEquals(RawJsonCaptureSkipReason.UnknownLengthExceedsLimit, event.detail.skipReason)
        assertTrue(event.detail.reason.contains("length is unknown"))
    }

    /**
     * 测试方法说明：验证未知长度 rawJson 探测普通失败时跳过捕获并回到原 Gson converter。
     */
    @Test
    fun `retrofit raw json unknown length ordinary probe failure skips capture and delegates`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 64,
                onEvent = events::add
            )
        )
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        val result = converter?.convert(
            failingRawJsonProbeBody("""{"data":{"name":"remote"}}""", IOException("rawJson probe failed"))
        )

        assertEquals(MismatchApiResponse(EmptyPayload("remote")), result)
        val event = events.single { it is SafeParserEvent.RawJsonCaptureSkipped } as SafeParserEvent.RawJsonCaptureSkipped
        assertEquals(RawJsonCaptureSkipReason.UnknownLengthExceedsLimit, event.detail.skipReason)
    }

    /**
     * 测试方法说明：验证未知长度 rawJson 探测抛出 fatal 时直接外抛，不降级成跳过捕获。
     */
    @Test
    fun `retrofit raw json unknown length fatal probe failure is rethrown`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 64,
                onEvent = events::add
            )
        )
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        assertThrows(AssertionError::class.java) {
            converter?.convert(fatalRawJsonProbeBody("""{"data":[]}"""))
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证 rawJson 超限跳过后，delegate converter 的异常按原样外抛。
     */
    @Test
    fun `retrofit raw json skipped delegate failure is rethrown`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(
            GsonBuilder().create(),
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 4,
                onEvent = events::add
            )
        )
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        assertThrows(JsonSyntaxException::class.java) {
            converter?.convert(ResponseBody.create(MediaType.parse("application/json"), """{"data":[]}"""))
        }

        assertTrue(events.any { it is SafeParserEvent.RawJsonCaptureSkipped })
    }

    /**
     * 测试方法说明：验证 rawJson 捕获阶段读取 body 失败时不伪造兜底结果。
     */
    @Test
    fun `retrofit raw json string read failure is rethrown`() {
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(captureRawJsonInCallbacks = true)
        )
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        assertThrows(IOException::class.java) {
            converter?.convert(stringReadFailureBody("""{"data":{"name":"remote"}}"""))
        }
    }

    /**
     * 测试方法说明：验证 requestBodyConverter 保持 Gson delegate 行为，不做响应侧安全解析介入。
     */
    @Test
    fun `request body converter delegates to Gson without response safety intervention`() {
        val factory = GsonSafeConverterFactory.create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        @Suppress("UNCHECKED_CAST")
        val converter = factory.requestBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            emptyArray(),
            retrofit
        ) as Converter<MismatchApiResponse, RequestBody>

        val body = converter.convert(MismatchApiResponse(EmptyPayload("remote")))!!
        val buffer = Buffer()
        body.writeTo(buffer)

        assertEquals("""{"data":{"name":"remote"}}""", buffer.readUtf8())
    }

    /**
     * 测试方法说明：验证 stringConverter 保持 GsonConverterFactory 原行为。
     */
    @Test
    fun `string converter delegates without adding safe parser behavior`() {
        val factory = GsonSafeConverterFactory.create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()

        val converter = factory.stringConverter(String::class.java, emptyArray(), retrofit)

        assertNull(converter)
    }

    /**
     * 测试方法说明：验证“raw json capture skipped observer failure does not interrupt conversion”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `raw json capture skipped observer failure does not interrupt conversion`() {
        // observerFailures 用来收集观察者自身抛出的异常，验证它不会影响主解析。
        val observerFailures = mutableListOf<ObserverFailureEvent>()
        // factory 是 Retrofit 的转换器工厂，后面会用它创建具体响应 converter。
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 4,
                onEvent = {
                    throw IllegalStateException("event logger failed")
                },
                onObserverFailure = observerFailures::add
            )
        )
        // retrofit 是测试用的 Retrofit 实例，只用来触发 converter 创建流程。
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        // converter 是 Retrofit 最终使用的响应转换器，测试会直接调用它验证结果。
        val converter = factory.responseBodyConverter(
            MismatchApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = assertDoesNotThrow<Any?> {
            converter?.convert(ResponseBody.create(MediaType.parse("application/json"), """{"data":[]}"""))
        }

        assertEquals(MismatchApiResponse(), result)
        assertTrue(observerFailures.any { it.sourceEvent is SafeParserEvent.RawJsonCaptureSkipped })
    }

    private fun unknownLengthBody(json: String): ResponseBody {
        return object : ResponseBody() {
            override fun contentLength(): Long = -1

            override fun contentType(): MediaType? = MediaType.parse("application/json")

            override fun source(): BufferedSource = Buffer().writeUtf8(json)
        }
    }

    private fun firstRequestFailureBody(json: String, failure: Throwable): ResponseBody {
        val source = FirstRequestFailureBufferedSource(Buffer().writeUtf8(json), failure)
        return object : ResponseBody() {
            override fun contentLength(): Long = -1

            override fun contentType(): MediaType? = MediaType.parse("application/json")

            override fun source(): BufferedSource = source
        }
    }

    private fun failingRawJsonProbeBody(json: String, failure: Throwable): ResponseBody {
        val source = FailingPeekBufferedSource(Buffer().writeUtf8(json), failure)
        return object : ResponseBody() {
            override fun contentLength(): Long = -1

            override fun contentType(): MediaType? = MediaType.parse("application/json")

            override fun source(): BufferedSource = source
        }
    }

    private fun stringReadFailureBody(json: String): ResponseBody {
        return object : ResponseBody() {
            override fun contentLength(): Long = json.toByteArray(Charsets.UTF_8).size.toLong()

            override fun contentType(): MediaType? = MediaType.parse("application/json")

            override fun source(): BufferedSource {
                return object : BufferedSource by Buffer().writeUtf8(json) {
                    override fun readString(charset: Charset): String {
                        throw IOException("rawJson string read failed")
                    }
                }
            }
        }
    }

    private fun fatalRawJsonProbeBody(json: String): ResponseBody {
        val source = FatalPeekBufferedSource(Buffer().writeUtf8(json))
        return object : ResponseBody() {
            override fun contentLength(): Long = -1

            override fun contentType(): MediaType? = MediaType.parse("application/json")

            override fun source(): BufferedSource = source
        }
    }

    private class FirstRequestFailureBufferedSource(
        private val delegate: BufferedSource,
        private val failure: Throwable
    ) : BufferedSource by delegate {
        private var shouldFailRequest = true

        override fun request(byteCount: Long): Boolean {
            if (shouldFailRequest) {
                shouldFailRequest = false
                throw failure
            }
            return delegate.request(byteCount)
        }
    }

    private class FailingPeekBufferedSource(
        private val delegate: BufferedSource,
        private val failure: Throwable
    ) : BufferedSource by delegate {
        override fun peek(): BufferedSource {
            val peeked = delegate.peek()
            return object : BufferedSource by peeked {
                override fun request(byteCount: Long): Boolean {
                    throw failure
                }
            }
        }
    }

    private class FatalPeekBufferedSource(
        private val delegate: BufferedSource
    ) : BufferedSource by delegate {
        override fun peek(): BufferedSource {
            val peeked = delegate.peek()
            return object : BufferedSource by peeked {
                override fun request(byteCount: Long): Boolean {
                    throw AssertionError("fatal raw json probe failure")
                }
            }
        }
    }
}
