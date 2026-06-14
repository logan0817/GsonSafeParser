package io.github.logan.gsonsafeparser.retrofit

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import io.github.logan.gsonsafeparser.EmptyResponsePolicy
import io.github.logan.gsonsafeparser.RawJsonCaptureSkipReason
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.SafeParserEvent
import io.github.logan.gsonsafeparser.ShapeCoercionPolicy
import io.github.logan.gsonsafeparser.TypeMismatchEvent
import io.github.logan.gsonsafeparser.enableSafeParser
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Call
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.http.GET
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.Type
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

class GsonSafeConverterJsonConversionExampleTest {
    data class ApiResponse(
        val code: Int = 200,
        val data: User = User()
    )

    data class NullableApiResponse(
        val data: User? = null
    )

    data class GenericEnvelope<T>(
        val code: Int = 200,
        val data: T
    )

    data class User(
        val id: Long = 0L,
        val name: String = "local"
    )

    data class RequestPayload(
        @SerializedName("user_name")
        val userName: String,
        val role: Role,
        val metadata: Map<String, Role>
    )

    enum class Role {
        @SerializedName("admin_user")
        ADMIN,
        MEMBER
    }

    interface ExampleService {
        @GET("valid")
        fun valid(): Call<ApiResponse>

        @GET("wrong-shape")
        fun wrongShape(): Call<ApiResponse>

        @GET("empty")
        fun empty(): Call<ApiResponse>

        @GET("oversized")
        fun oversized(): Call<ApiResponse>

        @GET("order")
        fun order(): Call<ApiResponse>

        @GET("chunked-small")
        fun chunkedSmall(): Call<ApiResponse>

        @GET("chunked-large")
        fun chunkedLarge(): Call<ApiResponse>

        @GET("gzip")
        fun gzip(): Call<ApiResponse>

        @GET("gzip-bad")
        fun gzipBad(): Call<ApiResponse>

        @GET("truncated")
        fun truncated(): Call<ApiResponse>

        @GET("chunked-truncated")
        fun chunkedTruncated(): Call<ApiResponse>

        @GET("gzip-truncated")
        fun gzipTruncated(): Call<ApiResponse>

        @GET("users")
        fun users(): Call<List<User>>

        @GET("user-map")
        fun userMap(): Call<Map<String, User>>

        @GET("envelope-list")
        fun envelopeList(): Call<GenericEnvelope<List<User>>>

        @GET("no-content")
        fun noContent(): Call<ApiResponse>

        @GET("reset-content")
        fun resetContent(): Call<ApiResponse>
    }

    data class HttpRoute(
        val body: String,
        val status: Int = 200,
        val contentType: String = "application/json; charset=utf-8",
        val transferMode: TransferMode = TransferMode.FixedLength,
        val bodyEncoding: BodyEncoding = BodyEncoding.Identity
    )

    enum class TransferMode {
        FixedLength,
        Chunked,
        TruncatedFixedLength,
        TruncatedChunked
    }

    enum class BodyEncoding {
        Identity,
        Gzip
    }

    private val jsonMediaType: MediaType? = "application/json".toMediaTypeOrNull()
    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://example.com/")
        .build()

    @Test
    fun `example converts valid retrofit json response and isolates wrong shaped fields`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(SafeParserConfig(onEvent = events::add))
        val converter = factory.responseConverter<ApiResponse>()

        val valid = converter.convert(jsonBody("""{"code":201,"data":{"id":1,"name":"Ada"}}"""))
        val wrongShape = converter.convert(jsonBody("""{"code":202,"data":[]}"""))

        assertEquals(ApiResponse(201, User(1L, "Ada")), valid)
        assertEquals(ApiResponse(202, User()), wrongShape)
        assertEquals("$.data", (events.single() as SafeParserEvent.TypeMismatch).detail.path)
    }

    @Test
    fun `example covers retrofit empty body policies for models unit void and delegate`() {
        val defaultFactory = GsonSafeConverterFactory.create()
        val defaultValueFactory = GsonSafeConverterFactory.create(
            SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DefaultValue)
        )
        val nullFactory = GsonSafeConverterFactory.create(
            SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.Null)
        )
        val delegateFactory = GsonSafeConverterFactory.create(
            SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DelegateToGson)
        )

        assertNull(defaultFactory.responseConverter<ApiResponse>().convert(jsonBody("")))
        assertEquals(ApiResponse(), defaultValueFactory.responseConverter<ApiResponse>().convert(jsonBody("")))
        assertNull(nullFactory.responseConverter<ApiResponse>().convert(jsonBody("")))
        assertEquals(Unit, defaultFactory.responseConverter<Unit>(Unit::class.java).convert(jsonBody("")))
        assertNull(defaultFactory.responseConverter<Void>(Void::class.java).convert(jsonBody("")))
        assertThrows(EOFException::class.java) {
            delegateFactory.responseConverter<ApiResponse>().convert(jsonBody(""))
        }
    }

    @Test
    fun `example attaches raw json to mismatch callbacks when capture is enabled`() {
        val mismatches = mutableListOf<TypeMismatchEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                onTypeMismatch = mismatches::add
            )
        )
        val converter = factory.responseConverter<ApiResponse>()
        val rawJson = """{"data":[]}"""

        val result = converter.convert(jsonBody(rawJson))

        assertEquals(ApiResponse(), result)
        assertEquals(rawJson, mismatches.single().rawJson)
    }

    @Test
    fun `example emits raw json skipped event and still converts oversized response`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 4,
                onEvent = events::add
            )
        )
        val converter = factory.responseConverter<ApiResponse>()

        val result = converter.convert(jsonBody("""{"data":[]}"""))

        assertEquals(ApiResponse(), result)
        val skipped = events.single { it is SafeParserEvent.RawJsonCaptureSkipped } as SafeParserEvent.RawJsonCaptureSkipped
        assertEquals(4, skipped.detail.maxBytes)
        assertEquals(RawJsonCaptureSkipReason.ContentLengthExceedsLimit, skipped.detail.skipReason)
        val mismatch = events.single { it is SafeParserEvent.TypeMismatch } as SafeParserEvent.TypeMismatch
        assertNull(mismatch.detail.rawJson)
    }

    @Test
    fun `example shows builder entry enables safe parsing while plain gson keeps native behavior`() {
        val builderFactory = GsonSafeConverterFactory.create(
            builder = GsonBuilder().serializeNulls(),
            config = SafeParserConfig()
        )
        val plainFactory = GsonSafeConverterFactory.create(GsonBuilder().create(), SafeParserConfig())

        val safeResult = builderFactory.responseConverter<ApiResponse>().convert(jsonBody("""{"data":[]}"""))

        assertEquals(ApiResponse(), safeResult)
        assertThrows(JsonSyntaxException::class.java) {
            plainFactory.responseConverter<ApiResponse>().convert(jsonBody("""{"data":[]}"""))
        }
    }

    @Test
    fun `example supports retrofit shape coercion through builder first entry`() {
        val factory = GsonSafeConverterFactory.create(
            builder = GsonBuilder(),
            config = SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        )
        val converter = factory.responseConverter<NullableApiResponse>()

        val result = converter.convert(jsonBody("""{"data":[{"id":3,"name":"first"}]}"""))

        assertEquals(NullableApiResponse(User(3L, "first")), result)
    }

    @Test
    fun `example keeps already configured external safe gson behavior`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(onEvent = events::add)
        val gson = GsonBuilder()
            .enableSafeParser(config)
            .create()
        val factory = GsonSafeConverterFactory.create(gson, config)
        val converter = factory.responseConverter<ApiResponse>()

        val result = converter.convert(jsonBody("""{"data":[]}"""))

        assertEquals(ApiResponse(), result)
        assertEquals("$.data", (events.single() as SafeParserEvent.TypeMismatch).detail.path)
    }

    @Test
    fun `example converts through a real retrofit service call chain`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                emptyResponsePolicy = EmptyResponsePolicy.DefaultValue,
                onEvent = events::add
            )
        )

        withLocalServer(
            "valid" to HttpRoute("""{"code":201,"data":{"id":1,"name":"Ada"}}"""),
            "wrong-shape" to HttpRoute("""{"code":202,"data":[]}"""),
            "empty" to HttpRoute("")
        ) { baseUrl ->
            val service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(factory)
                .build()
                .create(ExampleService::class.java)

            assertEquals(ApiResponse(201, User(1L, "Ada")), service.valid().execute().body())
            assertEquals(ApiResponse(202, User()), service.wrongShape().execute().body())
            assertEquals(ApiResponse(), service.empty().execute().body())
        }

        assertTrue(events.any { it is SafeParserEvent.TypeMismatch && it.detail.path == "$.data" })
        assertTrue(events.any { it is SafeParserEvent.EmptyResponse })
    }

    @Test
    fun `example converts retrofit generic list map and nested envelope responses`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                onEvent = events::add
            ).withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        )

        withLocalServer(
            "users" to HttpRoute("""[{"id":1,"name":"Ada"},[],{"id":2,"name":"Ben"}]"""),
            "user-map" to HttpRoute("""{"lead":{"id":3,"name":"Cat"},"bad":[],"next":{"id":4,"name":"Dee"}}"""),
            "envelope-list" to HttpRoute("""{"code":212,"data":{"id":5,"name":"Solo"}}""")
        ) { baseUrl ->
            val service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(factory)
                .build()
                .create(ExampleService::class.java)

            assertEquals(listOf(User(1L, "Ada"), User(2L, "Ben")), service.users().execute().body())
            assertEquals(
                mapOf("lead" to User(3L, "Cat"), "next" to User(4L, "Dee")),
                service.userMap().execute().body()
            )
            assertEquals(
                GenericEnvelope(212, listOf(User(5L, "Solo"))),
                service.envelopeList().execute().body()
            )
        }

        assertTrue(events.any { it is SafeParserEvent.TypeMismatch && it.detail.path == "$[1]" })
        assertTrue(events.any { it is SafeParserEvent.TypeMismatch && it.detail.path == "$.bad" })
        assertTrue(events.any {
            it is SafeParserEvent.ShapeCoercion &&
                it.detail.path == "$.data" &&
                it.detail.action.name == "CollectionFromSingleObject"
        })
    }

    @Test
    fun `example documents real retrofit 204 and 205 no content behavior`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                emptyResponsePolicy = EmptyResponsePolicy.DefaultValue,
                onEvent = events::add
            )
        )

        withLocalServer(
            "no-content" to HttpRoute("", status = 204),
            "reset-content" to HttpRoute("", status = 205)
        ) { baseUrl ->
            val service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(factory)
                .build()
                .create(ExampleService::class.java)
            val noContent = service.noContent().execute()
            val resetContent = service.resetContent().execute()

            assertEquals(204, noContent.code())
            assertEquals(205, resetContent.code())
            assertNull(noContent.body())
            assertNull(resetContent.body())
        }

        assertTrue(events.none { it is SafeParserEvent.EmptyResponse })
    }

    @Test
    fun `example emits raw json skip event through a real oversized retrofit response`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 8,
                onEvent = events::add
            )
        )

        withLocalServer(
            "oversized" to HttpRoute("""{"code":203,"data":[]}""")
        ) { baseUrl ->
            val service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(factory)
                .build()
                .create(ExampleService::class.java)

            assertEquals(ApiResponse(203, User()), service.oversized().execute().body())
        }

        val skipped = events.single { it is SafeParserEvent.RawJsonCaptureSkipped } as SafeParserEvent.RawJsonCaptureSkipped
        assertEquals(8, skipped.detail.maxBytes)
        assertEquals(RawJsonCaptureSkipReason.ContentLengthExceedsLimit, skipped.detail.skipReason)
    }

    @Test
    fun `example documents retrofit converter registration order`() {
        val safeEvents = mutableListOf<SafeParserEvent>()
        val safeFactory = GsonSafeConverterFactory.create(SafeParserConfig(onEvent = safeEvents::add))
        val fixedFactory = fixedApiResponseFactory(ApiResponse(599, User(99L, "fixed")))

        withLocalServer(
            "order" to HttpRoute("""{"code":204,"data":[]}""")
        ) { baseUrl ->
            val fixedFirst = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(fixedFactory)
                .addConverterFactory(safeFactory)
                .build()
                .create(ExampleService::class.java)
            val safeFirst = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(safeFactory)
                .addConverterFactory(fixedFactory)
                .build()
                .create(ExampleService::class.java)

            assertEquals(ApiResponse(599, User(99L, "fixed")), fixedFirst.order().execute().body())
            assertEquals(ApiResponse(204, User()), safeFirst.order().execute().body())
        }

        assertEquals("$.data", (safeEvents.single() as SafeParserEvent.TypeMismatch).detail.path)
    }

    @Test
    fun `example serializes retrofit request bodies with gson field enum and map rules`() {
        val factory = GsonSafeConverterFactory.create()
        val converter = factory.requestConverter<RequestPayload>()

        val body = converter.convert(
            RequestPayload(
                userName = "Ada",
                role = Role.ADMIN,
                metadata = linkedMapOf(
                    "primary" to Role.ADMIN,
                    "fallback" to Role.MEMBER
                )
            )
        )
        val buffer = Buffer()
        body?.writeTo(buffer)

        assertEquals(
            """{"user_name":"Ada","role":"admin_user","metadata":{"primary":"admin_user","fallback":"MEMBER"}}""",
            buffer.readUtf8()
        )
    }

    @Test
    fun `example converts chunked unknown length and gzip retrofit json bodies`() {
        val mismatches = mutableListOf<TypeMismatchEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 512,
                onTypeMismatch = mismatches::add
            )
        )
        val chunkedRaw = """{"code":205,"data":[]}"""

        withLocalServer(
            "chunked-small" to HttpRoute(chunkedRaw, transferMode = TransferMode.Chunked),
            "gzip" to HttpRoute(
                """{"code":206,"data":{"id":6,"name":"Zip"}}""",
                bodyEncoding = BodyEncoding.Gzip
            )
        ) { baseUrl ->
            val service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(factory)
                .build()
                .create(ExampleService::class.java)

            assertEquals(ApiResponse(205, User()), service.chunkedSmall().execute().body())
            assertEquals(ApiResponse(206, User(6L, "Zip")), service.gzip().execute().body())
        }

        assertEquals(chunkedRaw, mismatches.single().rawJson)
    }

    @Test
    fun `example captures gzip mismatch raw json and rethrows damaged gzip response`() {
        val mismatches = mutableListOf<TypeMismatchEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 512,
                onTypeMismatch = mismatches::add
            )
        )
        val gzipMismatchRaw = """{"code":209,"data":[]}"""

        withLocalServer(
            "gzip-bad" to HttpRoute(gzipMismatchRaw, bodyEncoding = BodyEncoding.Gzip),
            "gzip-truncated" to HttpRoute(
                """{"code":210,"data":{"id":10,"name":"broken-gzip"}}""",
                transferMode = TransferMode.TruncatedFixedLength,
                bodyEncoding = BodyEncoding.Gzip
            )
        ) { baseUrl ->
            val service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(factory)
                .build()
                .create(ExampleService::class.java)

            assertEquals(ApiResponse(209, User()), service.gzipBad().execute().body())
            assertThrows(IOException::class.java) {
                service.gzipTruncated().execute()
            }
        }

        assertEquals(gzipMismatchRaw, mismatches.single().rawJson)
    }

    @Test
    fun `example skips oversized unknown length raw json and rethrows truncated transport failure`() {
        val events = mutableListOf<SafeParserEvent>()
        val factory = GsonSafeConverterFactory.create(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 8,
                onEvent = events::add
            )
        )
        val transportFactory = GsonSafeConverterFactory.create()

        withLocalServer(
            "chunked-large" to HttpRoute(
                """{"code":207,"data":[],"padding":"this body is intentionally larger than eight bytes"}""",
                transferMode = TransferMode.Chunked
            ),
            "truncated" to HttpRoute(
                """{"code":208,"data":{"id":8,"name":"cut"}}""",
                transferMode = TransferMode.TruncatedFixedLength
            ),
            "chunked-truncated" to HttpRoute(
                """{"code":211,"data":{"id":11,"name":"cut-chunk"}}""",
                transferMode = TransferMode.TruncatedChunked
            )
        ) { baseUrl ->
            val oversizedService = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(factory)
                .build()
                .create(ExampleService::class.java)
            val transportService = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(transportFactory)
                .build()
                .create(ExampleService::class.java)

            assertEquals(ApiResponse(207, User()), oversizedService.chunkedLarge().execute().body())
            assertThrows(IOException::class.java) {
                transportService.truncated().execute()
            }
            assertThrows(IOException::class.java) {
                transportService.chunkedTruncated().execute()
            }
        }

        val skipped = events.single { it is SafeParserEvent.RawJsonCaptureSkipped } as SafeParserEvent.RawJsonCaptureSkipped
        assertEquals(RawJsonCaptureSkipReason.UnknownLengthExceedsLimit, skipped.detail.skipReason)
        assertTrue(events.any { it is SafeParserEvent.TypeMismatch && it.detail.path == "$.data" })
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> Converter.Factory.responseConverter(
        type: Type = T::class.java
    ): Converter<ResponseBody, T> {
        return responseBodyConverter(type, emptyArray(), retrofit) as Converter<ResponseBody, T>
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> Converter.Factory.requestConverter(
        type: Type = T::class.java
    ): Converter<T, RequestBody> {
        return requestBodyConverter(type, emptyArray(), emptyArray(), retrofit) as Converter<T, RequestBody>
    }

    private fun jsonBody(json: String): ResponseBody {
        return json.toResponseBody(jsonMediaType)
    }

    private fun withLocalServer(vararg routes: Pair<String, HttpRoute>, block: (String) -> Unit) {
        val routeMap = routes.associate { (path, route) -> path.trimStart('/') to route }
        val running = AtomicBoolean(true)
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(start = true, isDaemon = true) {
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                socket.use { acceptedSocket ->
                    writeHttpResponse(acceptedSocket, routeMap)
                }
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}/")
        } finally {
            running.set(false)
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun writeHttpResponse(socket: Socket, routes: Map<String, HttpRoute>) {
        socket.soTimeout = 5_000
        val reader = InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1).buffered()
        val requestLine = reader.readLine().orEmpty()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val path = requestLine
            .split(" ")
            .getOrNull(1)
            ?.substringBefore("?")
            ?.trimStart('/')
            .orEmpty()
        val route = routes[path] ?: HttpRoute("""{"error":"not found"}""", status = 404)
        val bytes = route.encodedBodyBytes()
        val header = buildString {
            append("HTTP/1.1 ${route.status} OK\r\n")
            append("Content-Type: ${route.contentType}\r\n")
            if (route.bodyEncoding == BodyEncoding.Gzip) {
                append("Content-Encoding: gzip\r\n")
            }
            when (route.transferMode) {
                TransferMode.FixedLength -> append("Content-Length: ${bytes.size}\r\n")
                TransferMode.Chunked -> append("Transfer-Encoding: chunked\r\n")
                TransferMode.TruncatedFixedLength -> append("Content-Length: ${bytes.size + 16}\r\n")
                TransferMode.TruncatedChunked -> append("Transfer-Encoding: chunked\r\n")
            }
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        socket.getOutputStream().use { output ->
            output.write(header)
            when (route.transferMode) {
                TransferMode.FixedLength -> output.write(bytes)
                TransferMode.Chunked -> writeChunkedBody(output, bytes)
                TransferMode.TruncatedFixedLength -> output.write(bytes.take(bytes.size / 2).toByteArray())
                TransferMode.TruncatedChunked -> writeTruncatedChunkedBody(output, bytes)
            }
            output.flush()
        }
    }

    private fun HttpRoute.encodedBodyBytes(): ByteArray {
        val rawBytes = body.toByteArray(StandardCharsets.UTF_8)
        if (bodyEncoding == BodyEncoding.Identity) return rawBytes
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(rawBytes)
        }
        return output.toByteArray()
    }

    private fun writeChunkedBody(output: java.io.OutputStream, bytes: ByteArray) {
        val midpoint = (bytes.size / 2).coerceAtLeast(1)
        listOf(bytes.copyOfRange(0, midpoint), bytes.copyOfRange(midpoint, bytes.size))
            .filter { it.isNotEmpty() }
            .forEach { chunk ->
                output.write("${chunk.size.toString(16)}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                output.write(chunk)
                output.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        output.write("0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun writeTruncatedChunkedBody(output: java.io.OutputStream, bytes: ByteArray) {
        val promisedSize = bytes.size.coerceAtLeast(1)
        val partial = bytes.take((bytes.size / 2).coerceAtLeast(1)).toByteArray()
        output.write("${promisedSize.toString(16)}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(partial)
    }

    private fun fixedApiResponseFactory(response: ApiResponse): Converter.Factory {
        return object : Converter.Factory() {
            override fun responseBodyConverter(
                type: Type,
                annotations: Array<Annotation>,
                retrofit: Retrofit
            ): Converter<ResponseBody, *>? {
                if (type != ApiResponse::class.java) return null
                return Converter<ResponseBody, ApiResponse> { body ->
                    body.close()
                    response
                }
            }
        }
    }
}
