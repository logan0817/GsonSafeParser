package io.github.logan.gsonsafeparser.retrofit

import com.google.gson.JsonParseException
import io.github.logan.gsonsafeparser.EmptyResponsePolicy
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.GsonSafeParserLowLevelApi
import io.github.logan.gsonsafeparser.PrimitiveParsingPolicy
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.SafeParserEvent
import io.github.logan.gsonsafeparser.parseSafe
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import retrofit2.Call
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.http.GET
import java.io.IOException

@OptIn(GsonSafeParserLowLevelApi::class)
class GsonSafeConverterIntegrationTest {
    data class Model(val data: Payload = Payload())

    data class Payload(val name: String = "default")

    data class ScalarContainers(
        val label: String = "",
        val values: List<Int> = emptyList(),
        val mapping: Map<String, Int> = emptyMap()
    )

    interface Service {
        @GET("model")
        fun model(): Call<Model>

        @GET("unit")
        fun unit(): Call<Unit>

        @GET("void")
        fun noValue(): Call<Void>

        @GET("scalars")
        fun scalars(): Call<ScalarContainers>
    }

    @Test
    fun `nested service calls isolate disabled skipped and captured raw json`() {
        val json = """{"data":[]}"""
        val captureModes = listOf(
            SafeParserConfig(captureRawJsonInCallbacks = false) to null,
            SafeParserConfig(captureRawJsonInCallbacks = true, maxRawJsonCaptureBytes = 1) to null,
            SafeParserConfig(captureRawJsonInCallbacks = true) to json
        )
        captureModes.forEach { (baseConfig, expectedRawJson) ->
            val events = mutableListOf<SafeParserEvent>()
            val config = baseConfig.copy(onEvent = events::add)
            val service = service(GsonSafeConverterFactory.create(config), json)
            val outerEvents = mutableListOf<SafeParserEvent>()
            val outerConfig = SafeParserConfig(captureRawJsonInCallbacks = true, onEvent = outerEvents::add)
            val outerGson = GsonSafeParser.create(outerConfig)
            val outerRawJson = """{"data":[],"private":"outer response"}"""

            GsonSafeParser.withRawJsonContext(outerRawJson, outerConfig) {
                assertEquals(Model(), service.model().execute().body())
                assertEquals(Model(), outerGson.fromJson(json, Model::class.java))
            }

            val mismatch = events.filterIsInstance<SafeParserEvent.TypeMismatch>().single()
            assertEquals(expectedRawJson, mismatch.detail.rawJson)
            assertEquals(outerRawJson, (outerEvents.single() as SafeParserEvent.TypeMismatch).detail.rawJson)
            assertEquals(
                baseConfig.captureRawJsonInCallbacks && baseConfig.maxRawJsonCaptureBytes == 1,
                events.any { it is SafeParserEvent.RawJsonCaptureSkipped }
            )
            outerEvents.clear()
            assertEquals(Model(), outerGson.fromJson(json, Model::class.java))
            assertNull((outerEvents.single() as SafeParserEvent.TypeMismatch).detail.rawJson)
        }
    }

    @Test
    fun `nested service failures restore the outer raw json context`() {
        val service = service(GsonSafeConverterFactory.create(), """{"data":""")
        val outerEvents = mutableListOf<SafeParserEvent>()
        val outerConfig = SafeParserConfig(captureRawJsonInCallbacks = true, onEvent = outerEvents::add)
        val outerGson = GsonSafeParser.create(outerConfig)
        val outerRawJson = """{"private":"outer response"}"""

        GsonSafeParser.withRawJsonContext(outerRawJson, outerConfig) {
            assertThrows(IOException::class.java) {
                service.model().execute()
            }
            assertEquals(Model(), outerGson.fromJson("""{"data":[]}""", Model::class.java))
        }

        assertEquals(outerRawJson, (outerEvents.single() as SafeParserEvent.TypeMismatch).detail.rawJson)
    }

    @Test
    fun `real Unit and Void service calls use Retrofit built in converters for every policy`() {
        EmptyResponsePolicy.values().forEach { policy ->
            val events = mutableListOf<SafeParserEvent>()
            val factory = GsonSafeConverterFactory.create(
                SafeParserConfig(emptyResponsePolicy = policy, onEvent = events::add)
            )
            val service = service(factory, "")

            assertEquals(Unit, service.unit().execute().body(), "policy=$policy")
            assertNull(service.noValue().execute().body(), "policy=$policy")
            assertTrue(events.isEmpty(), "Retrofit handles Unit/Void before the configured factory: policy=$policy")
        }
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `real service rejects malformed and truncated scalar containers with capture enabled or disabled`() {
        val inputs = listOf(
            """{"label":"\x"}""",
            """{"values":[1,"\x"]}""",
            """{"values":[1,"bad"""",
            """{"mapping":{"ok":1,"bad":"""
        )
        listOf(false, true).forEach { capture ->
            inputs.forEach { json ->
                val factory = GsonSafeConverterFactory.create(
                    SafeParserConfig(
                        primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
                        captureRawJsonInCallbacks = capture
                    )
                )

                val failure = assertThrows(Exception::class.java, {
                    service(factory, json).scalars().execute()
                }, "capture=$capture, json=$json")

                assertTrue(
                    failure is IOException || failure is JsonParseException,
                    "Expected a JSON read error, got ${failure.javaClass.name}: capture=$capture, json=$json"
                )
            }
        }
    }

    @Test
    fun `real service reads actual json when the response declares zero length`() {
        val json = """{"data":{"name":"remote"}}"""
        for (capture in listOf(false, true)) {
            for (maxBytes in listOf(1, 64)) {
                val events = mutableListOf<SafeParserEvent>()
                val body = ZeroLengthBody(json)
                val factory = GsonSafeConverterFactory.create(
                    SafeParserConfig(
                        captureRawJsonInCallbacks = capture,
                        maxRawJsonCaptureBytes = maxBytes,
                        onEvent = events::add
                    )
                )

                val value = service(factory) { body }.model().execute().body()

                assertEquals(Model(Payload("remote")), value)
                assertTrue(body.closed)
                assertTrue(events.none { it is SafeParserEvent.EmptyResponse })
                assertEquals(
                    capture && maxBytes == 1,
                    events.any { it is SafeParserEvent.RawJsonCaptureSkipped }
                )
            }
        }
    }

    @Test
    fun `real service still applies empty policy when a zero length response is actually empty`() {
        for (capture in listOf(false, true)) {
            for (policy in listOf(EmptyResponsePolicy.Null, EmptyResponsePolicy.DefaultValue)) {
                val events = mutableListOf<SafeParserEvent>()
                val body = ZeroLengthBody("")
                val factory = GsonSafeConverterFactory.create(
                    SafeParserConfig(
                        captureRawJsonInCallbacks = capture,
                        emptyResponsePolicy = policy,
                        onEvent = events::add
                    )
                )

                val value = service(factory) { body }.model().execute().body()

                assertEquals(if (policy == EmptyResponsePolicy.DefaultValue) Model() else null, value)
                assertTrue(body.closed)
                assertEquals(policy, (events.single() as SafeParserEvent.EmptyResponse).detail.policy)
            }
        }
    }

    @Test
    fun `real service propagates read failures and closes responses declared as zero length`() {
        for (capture in listOf(false, true)) {
            val failure = IOException("zero length probe failed")
            val body = ZeroLengthBody("{}", failure)
            val events = mutableListOf<SafeParserEvent>()
            val factory = GsonSafeConverterFactory.create(
                SafeParserConfig(captureRawJsonInCallbacks = capture, onEvent = events::add)
            )

            val actual = assertThrows(IOException::class.java) {
                service(factory) { body }.model().execute()
            }

            assertSame(failure, actual)
            assertTrue(body.closed)
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `independent service calls inside observers do not enter the outer parse snapshot`() {
        for (callback in listOf("onEvent", "onTypeMismatch", "onObserverFailure")) {
            val innerEvents = mutableListOf<SafeParserEvent>()
            val innerService = service(
                GsonSafeConverterFactory.create(SafeParserConfig(onEvent = innerEvents::add)),
                """{"values":{}}"""
            )
            var innerCalls = 0
            val callInner = {
                assertEquals(ScalarContainers(), innerService.scalars().execute().body())
                innerCalls += 1
            }
            val config = when (callback) {
                "onEvent" -> SafeParserConfig(onEvent = { callInner() })
                "onTypeMismatch" -> SafeParserConfig(onTypeMismatch = { callInner() })
                else -> SafeParserConfig(
                    onEvent = { throw IllegalStateException("observer failure") },
                    onObserverFailure = { callInner() }
                )
            }
            val parser = GsonSafeParser.parser(config)

            val first = parser.parseSafe<Model>("""{"data":[]}""")
            val second = parser.parseSafe<Model>("""{"data":[]}""")

            assertEquals(Model(), first.value, callback)
            assertEquals(Model(), second.value, callback)
            assertEquals("$.data", (first.events.single() as SafeParserEvent.TypeMismatch).detail.path, callback)
            assertEquals("$.data", (second.events.single() as SafeParserEvent.TypeMismatch).detail.path, callback)
            assertEquals(2, innerCalls, callback)
            assertEquals(listOf("$.values", "$.values"), innerEvents.map {
                (it as SafeParserEvent.TypeMismatch).detail.path
            }, callback)
        }
    }

    private fun service(factory: Converter.Factory, json: String): Service {
        return service(factory) { json.toResponseBody("application/json".toMediaType()) }
    }

    private fun service(factory: Converter.Factory, body: () -> ResponseBody): Service {
        // 使用真实 ServiceMethod / OkHttpCall / OkHttp 链，只在应用拦截器提供响应，测试不依赖网络。
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body())
                    .build()
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://example.com/")
            .client(client)
            .addConverterFactory(factory)
            .build()
            .create(Service::class.java)
    }

    private class ZeroLengthBody(json: String, failure: IOException? = null) : ResponseBody() {
        var closed = false
            private set

        private val bufferedSource = object : ForwardingSource(Buffer().writeUtf8(json)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (failure != null) throw failure
                return super.read(sink, byteCount)
            }

            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()

        override fun contentLength(): Long = 0

        override fun contentType(): MediaType = "application/json".toMediaType()

        override fun source(): BufferedSource = bufferedSource
    }
}
