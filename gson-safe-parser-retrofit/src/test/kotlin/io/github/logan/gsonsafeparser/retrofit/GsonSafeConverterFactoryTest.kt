package io.github.logan.gsonsafeparser.retrofit

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.EmptyResponsePolicy
import io.github.logan.gsonsafeparser.ObserverFailureEvent
import io.github.logan.gsonsafeparser.RawJsonCaptureSkipReason
import io.github.logan.gsonsafeparser.SafeParserEvent
import io.github.logan.gsonsafeparser.TypeMismatchEvent
import io.github.logan.gsonsafeparser.enableSafeParser
import io.github.logan.gsonsafeparser.ShapeCoercionAction
import io.github.logan.gsonsafeparser.ShapeCoercionPolicy
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
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
import java.net.ProtocolException

/**
 * 验证 Retrofit converter 的接入行为。
 *
 * 这里重点看空响应策略、rawJson 捕获、观察者异常隔离，以及超限时是否能回到原 Gson converter。
 * Retrofit 层不能重新造解析规则，只能把 core 模块的能力安全接到响应转换流程里。
 */
class GsonSafeConverterFactoryTest {
    private val jsonMediaType: MediaType? = "application/json".toMediaTypeOrNull()

    private fun jsonResponseBody(json: String): ResponseBody {
        return json.toResponseBody(jsonMediaType)
    }

    /** 测试模型：空响应时应该构造出的默认响应对象。 */
    data class EmptyApiResponse(
        val code: Int = 200,
        val data: EmptyPayload = EmptyPayload()
    )

    /** 测试模型：空响应里的默认 payload。 */
    data class EmptyPayload(val name: String = "local")
    /** 测试模型：响应体非空但字段错形时，用来验证 Retrofit rawJson 观测。 */
    data class MismatchApiResponse(val data: EmptyPayload = EmptyPayload())
    /** 测试模型：响应体里对象字段偶发返回数组，用来验证 shape coercion。 */
    data class ShapeApiResponse(val data: ShapePayload? = null)
    /** 测试模型：shape coercion 的字段值。 */
    data class ShapePayload(val id: Long = 0L)

    data class AdapterIOExceptionApiResponse(
        val data: AdapterIOExceptionPayload = AdapterIOExceptionPayload("default"),
        val next: String = "local"
    )

    @JsonAdapter(AdapterIOExceptionPayloadAdapter::class)
    data class AdapterIOExceptionPayload(val value: String)

    class AdapterIOExceptionPayloadAdapter : TypeAdapter<AdapterIOExceptionPayload>() {
        override fun write(out: JsonWriter, value: AdapterIOExceptionPayload?) {
            out.value(value?.value)
        }

        override fun read(reader: JsonReader): AdapterIOExceptionPayload {
            val value = reader.nextString()
            if (value == "bad") {
                throw IOException("connection reset by business adapter")
            }
            return AdapterIOExceptionPayload(value)
        }
    }

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

        val result = converter?.convert(jsonResponseBody(""))

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
            converter?.convert(jsonResponseBody("""{"data":[]}"""))
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

        val result = converter?.convert(jsonResponseBody("""{"data":[]}"""))

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

        val result = converter?.convert(jsonResponseBody(rawJson))

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

        val result = converter?.convert(jsonResponseBody("""{"data":[]}"""))

        assertEquals(MismatchApiResponse(), result)
        val event = events.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.data", event.detail.path)
    }

    /**
     * 测试方法说明：验证 builder-first Retrofit 入口能让 shape coercion 配置进入字段级 Safe Adapter。
     */
    @Test
    fun `factory builder entry supports shape coercion for response field`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(
            onEvent = events::add
        ).withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        val factory = GsonSafeConverterFactory.create(
            builder = GsonBuilder().serializeNulls(),
            config = config
        )
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            ShapeApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        val result = converter?.convert(
            jsonResponseBody("""{"data":[{"id":9}]}""")
        )

        assertEquals(ShapeApiResponse(ShapePayload(9L)), result)
        val event = events.single() as SafeParserEvent.ShapeCoercion
        assertEquals("$.data", event.detail.path)
        assertEquals(ShapeCoercionAction.ObjectFromFirstArrayItem, event.detail.action)
    }

    /**
     * 测试方法说明：验证 plain Gson + Retrofit config 不会偷偷补注册字段级 shape coercion。
     */
    @Test
    fun `factory plain gson entry does not auto enable shape coercion`() {
        val factory = GsonSafeConverterFactory.create(
            gson = GsonBuilder().create(),
            config = SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        )
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            ShapeApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        assertThrows(JsonSyntaxException::class.java) {
            converter?.convert(jsonResponseBody("""{"data":[{"id":9}]}"""))
        }
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
     * 测试方法说明：验证正式读取响应体时遇到连接重置，不能被字段级兜底误报为 TypeMismatch。
     */
    @Test
    fun `response body connection reset read failure is rethrown without event`() {
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

        assertThrows(IOException::class.java) {
            converter?.convert(failingResponseBody(IOException("connection reset by peer")))
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证 Retrofit 里的业务 Adapter IOException 仍按调用方自定义 Adapter 语义外抛。
     */
    @Test
    fun `retrofit business adapter io wording is rethrown without event`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(SafeParserConfig(onEvent = events::add))
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            AdapterIOExceptionApiResponse::class.java,
            emptyArray(),
            retrofit
        )

        assertThrows(IOException::class.java) {
            converter?.convert(
                jsonResponseBody("""{"data":"bad","next":"remote"}""")
            )
        }

        assertTrue(events.isEmpty())
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
        val result = converter?.convert(jsonResponseBody(""))

        assertNull(result)
    }

    private fun failingResponseBody(failure: Throwable): ResponseBody {
        return object : ResponseBody() {
            override fun contentType(): MediaType? = jsonMediaType

            override fun contentLength(): Long = -1

            override fun source(): BufferedSource {
                return object : ForwardingSource(Buffer()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        throw failure
                    }
                }.buffer()
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

        val result = converter?.convert(jsonResponseBody(""))

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
        val result = converter?.convert(jsonResponseBody(""))

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
        val result = converter?.convert(jsonResponseBody(""))

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

        val result = converter?.convert(jsonResponseBody(""))

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

        val result = converter?.convert(jsonResponseBody("""{"ignored":true}"""))

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

        val result = converter?.convert(jsonResponseBody("""{"ignored":true}"""))

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
        val result = converter?.convert(jsonResponseBody(""))

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

        converter?.convert(jsonResponseBody(""))

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
            converter?.convert(jsonResponseBody(""))
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
            converter?.convert(jsonResponseBody(""))
        }
    }

    /**
     * 测试方法说明：验证空响应探测遇到 ResponseBody 读流失败时直接外抛，不降级成普通响应解析。
     */
    @Test
    fun `empty response marked probe failure is rethrown without event`() {
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

        assertThrows(IOException::class.java) {
            converter?.convert(firstRequestFailureBody("""{"data":{"name":"remote"}}""", IOException("probe failed")))
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证网络流重置不是空响应，也不能降级成 Gson 转换。
     */
    @Test
    fun `empty response stream reset probe failure is rethrown without event`() {
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

        assertThrows(IOException::class.java) {
            converter?.convert(
                firstRequestFailureBody(
                    """{"data":{"name":"remote"}}""",
                    IOException("stream was reset: CANCEL")
                )
            )
        }

        assertTrue(events.isEmpty())
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

        converter?.convert(jsonResponseBody(rawJson))

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

        converter?.convert(jsonResponseBody("""{"data":[]}"""))

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

        converter?.convert(jsonResponseBody("""{"data":[]}"""))

        val event = events.single { it is SafeParserEvent.RawJsonCaptureSkipped } as SafeParserEvent.RawJsonCaptureSkipped
        assertEquals(MismatchApiResponse::class.java.name, event.detail.typeName)
        assertEquals(4, event.detail.maxBytes)
        assertEquals(RawJsonCaptureSkipReason.ContentLengthExceedsLimit, event.detail.skipReason)
    }

    @Test
    fun `retrofit raw json capture verifies actual body size even when content length is smaller`() {
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

        val result = converter?.convert(lyingContentLengthBody("""{"data":[]}""", declaredLength = 1L))

        assertEquals(MismatchApiResponse(), result)
        val skipped = events.single { it is SafeParserEvent.RawJsonCaptureSkipped } as SafeParserEvent.RawJsonCaptureSkipped
        assertEquals(1L, skipped.detail.contentLength)
        assertEquals(RawJsonCaptureSkipReason.ContentLengthExceedsLimit, skipped.detail.skipReason)
        val mismatch = events.single { it is SafeParserEvent.TypeMismatch } as SafeParserEvent.TypeMismatch
        assertNull(mismatch.detail.rawJson)
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
     * 测试方法说明：验证未知长度 rawJson 探测遇到 ResponseBody 读流失败时直接外抛。
     */
    @Test
    fun `retrofit raw json unknown length marked probe failure is rethrown`() {
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

        assertThrows(IOException::class.java) {
            converter?.convert(
                failingRawJsonProbeBody("""{"data":{"name":"remote"}}""", IOException("rawJson probe failed"))
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证 rawJson 探测阶段遇到网络流重置时直接外抛，不记录 RawJsonCaptureSkipped。
     */
    @Test
    fun `retrofit raw json stream reset probe failure is rethrown without skipped event`() {
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

        assertThrows(IOException::class.java) {
            converter?.convert(
                failingRawJsonProbeBody(
                    """{"data":{"name":"remote"}}""",
                    IOException("stream was reset: CANCEL")
                )
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证 rawJson 探测阶段遇到 EOF 读流失败时直接外抛，不记录 RawJsonCaptureSkipped。
     */
    @Test
    fun `retrofit raw json eof probe failure is rethrown without skipped event`() {
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

        assertThrows(EOFException::class.java) {
            converter?.convert(
                failingRawJsonProbeBody(
                    """{"data":{"name":"remote"}}""",
                    EOFException("unexpected end of stream")
                )
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证空响应探测遇到协议读流失败时直接外抛，不记录 EmptyResponse。
     */
    @Test
    fun `empty response protocol probe failure is rethrown without event`() {
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

        assertThrows(ProtocolException::class.java) {
            converter?.convert(
                firstRequestFailureBody(
                    """{"data":{"name":"remote"}}""",
                    ProtocolException("unexpected end of stream")
                )
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证 Unit 响应的空响应探测遇到读流失败时不能被吞掉。
     */
    @Test
    fun `unit response body read failure is rethrown`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(SafeParserConfig(onEvent = events::add))
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            Unit::class.java,
            emptyArray(),
            retrofit
        )

        assertThrows(IOException::class.java) {
            converter?.convert(firstRequestFailureBody("""{}""", IOException("read failed")))
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证 Void 响应的空响应探测遇到读流失败时不能被吞掉。
     */
    @Test
    fun `void response body read failure is rethrown`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(SafeParserConfig(onEvent = events::add))
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        val converter = factory.responseBodyConverter(
            Void::class.java,
            emptyArray(),
            retrofit
        )

        assertThrows(IOException::class.java) {
            converter?.convert(firstRequestFailureBody("""{}""", IOException("read failed")))
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证 rawJson 探测阶段遇到已打标但普通文案的读流失败时直接外抛。
     */
    @Test
    fun `retrofit raw json marked probe failure is rethrown without skipped event`() {
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

        assertThrows(IOException::class.java) {
            converter?.convert(
                failingRawJsonProbeBody(
                    """{"data":{"name":"remote"}}""",
                    IOException("read failed")
                )
            )
        }

        assertTrue(events.isEmpty())
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
            converter?.convert(jsonResponseBody("""{"data":[]}"""))
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
            converter?.convert(jsonResponseBody("""{"data":[]}"""))
        }

        assertEquals(MismatchApiResponse(), result)
        assertTrue(observerFailures.any { it.sourceEvent is SafeParserEvent.RawJsonCaptureSkipped })
    }

    private fun unknownLengthBody(json: String): ResponseBody {
        return object : ResponseBody() {
            override fun contentLength(): Long = -1

            override fun contentType(): MediaType? = jsonMediaType

            override fun source(): BufferedSource = Buffer().writeUtf8(json)
        }
    }

    private fun lyingContentLengthBody(json: String, declaredLength: Long): ResponseBody {
        val source = Buffer().writeUtf8(json)
        return object : ResponseBody() {
            override fun contentLength(): Long = declaredLength

            override fun contentType(): MediaType? = jsonMediaType

            override fun source(): BufferedSource = source
        }
    }

    private fun firstRequestFailureBody(json: String, failure: Throwable): ResponseBody {
        val source = FirstReadFailureSource(Buffer().writeUtf8(json), failure).buffer()
        return object : ResponseBody() {
            override fun contentLength(): Long = -1

            override fun contentType(): MediaType? = jsonMediaType

            override fun source(): BufferedSource = source
        }
    }

    private fun failingRawJsonProbeBody(json: String, failure: Throwable): ResponseBody {
        val source = ProbeFailureSource(Buffer().writeUtf8(json), failure).buffer()
        return object : ResponseBody() {
            override fun contentLength(): Long = -1

            override fun contentType(): MediaType? = jsonMediaType

            override fun source(): BufferedSource = source
        }
    }

    private fun stringReadFailureBody(json: String): ResponseBody {
        return object : ResponseBody() {
            override fun contentLength(): Long = json.toByteArray(Charsets.UTF_8).size.toLong()

            override fun contentType(): MediaType? = jsonMediaType

            override fun source(): BufferedSource {
                return object : ForwardingSource(Buffer().writeUtf8(json)) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        throw IOException("rawJson string read failed")
                    }
                }.buffer()
            }
        }
    }

    private fun fatalRawJsonProbeBody(json: String): ResponseBody {
        val source = ProbeFailureSource(Buffer().writeUtf8(json), AssertionError("fatal raw json probe failure")).buffer()
        return object : ResponseBody() {
            override fun contentLength(): Long = -1

            override fun contentType(): MediaType? = jsonMediaType

            override fun source(): BufferedSource = source
        }
    }

    private class FirstReadFailureSource(
        private val delegateBuffer: Buffer,
        private val failure: Throwable
    ) : ForwardingSource(delegateBuffer) {
        private var shouldFailRead = true

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (shouldFailRead) {
                shouldFailRead = false
                throw failure
            }
            return delegateBuffer.read(sink, byteCount)
        }
    }

    private class ProbeFailureSource(
        private val delegateBuffer: Buffer,
        private val failure: Throwable
    ) : ForwardingSource(delegateBuffer) {
        private var readCount = 0

        override fun read(sink: Buffer, byteCount: Long): Long {
            readCount += 1
            if (readCount > 1) {
                throw failure
            }
            return delegateBuffer.read(sink, minOf(byteCount, 1L))
        }
    }
}
