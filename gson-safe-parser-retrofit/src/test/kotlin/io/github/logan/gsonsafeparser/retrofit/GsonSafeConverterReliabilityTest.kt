package io.github.logan.gsonsafeparser.retrofit

import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.SafeParserEvent
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.lang.reflect.Type

class GsonSafeConverterReliabilityTest {
    data class Response(val id: Int = 0)

    data class MismatchResponse(val data: Payload = Payload(), val next: String = "")

    data class Payload(val name: String = "default")

    @Test
    fun `raw capture preserves official converter strictness and full consumption`() {
        val strictnessModes = listOf(
            null,
            Strictness.LEGACY_STRICT,
            Strictness.STRICT,
            Strictness.LENIENT
        )
        val inputs = listOf(
            """{"id":7}""",
            """{id:7}""",
            """/* comment */ {"id":7}""",
            """{"id":7} true""",
            "null true",
            """{"id":"""
        )
        strictnessModes.forEach { strictness ->
            val gson = GsonBuilder().apply {
                if (strictness != null) setStrictness(strictness)
            }.create()
            val official = converter(GsonConverterFactory.create(gson))
            listOf(false, true).forEach { capture ->
                val safe = converter(
                    GsonSafeConverterFactory.create(
                        gson,
                        SafeParserConfig(captureRawJsonInCallbacks = capture)
                    )
                )
                inputs.forEach { json ->
                    val expected = runCatching { official.convert(json.toResponseBody(JSON)) }
                    val actual = runCatching { safe.convert(json.toResponseBody(JSON)) }
                    val context = "capture=$capture, strictness=$strictness, json=$json"
                    assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass, context)
                    assertEquals(expected.exceptionOrNull()?.message, actual.exceptionOrNull()?.message, context)
                    if (expected.isSuccess) {
                        assertEquals(expected.getOrNull(), actual.getOrNull(), context)
                    }
                }
            }
        }
    }

    @Test
    fun `raw capture preserves BOM aware decoding and callback contents`() {
        val json = """{"data":[],"next":"中文😀"}"""
        val encodings = listOf(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + json.toByteArray(Charsets.UTF_8),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + json.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + json.toByteArray(Charsets.UTF_16BE)
        )
        encodings.forEach { bytes ->
            val events = mutableListOf<SafeParserEvent>()
            val factory = GsonSafeConverterFactory.create(
                SafeParserConfig(captureRawJsonInCallbacks = true, onEvent = events::add)
            )
            val result = converter(factory, MismatchResponse::class.java)
                .convert(bytes.toResponseBody("application/json; charset=utf-8".toMediaType()))

            assertEquals(MismatchResponse(next = "中文😀"), result)
            val event = events.single() as SafeParserEvent.TypeMismatch
            assertEquals(json, event.detail.rawJson)
            assertEquals("$.data", event.detail.path)
        }
    }

    @Test
    fun `all response types close their body when the first read fails`() {
        listOf(Response::class.java, Unit::class.java, Void::class.java, Void.TYPE).forEach { type ->
            val events = mutableListOf<SafeParserEvent>()
            val failure = IOException("first read failed")
            val body = TrackingBody("{}", readFailure = failure)
            val factory = GsonSafeConverterFactory.create(SafeParserConfig(onEvent = events::add))

            val actual = assertThrows(IOException::class.java) {
                converter(factory, type).convert(body)
            }

            assertSame(failure, actual)
            assertTrue(body.closed, "type=$type")
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `raw capture probe failure closes the body without hiding the read error`() {
        val failure = IOException("raw capture probe failed")
        val closeFailure = IllegalStateException("close failed")
        val body = TrackingBody("{}", failureOnRead = 2, readFailure = failure, closeFailure = closeFailure)
        val factory = GsonSafeConverterFactory.create(SafeParserConfig(captureRawJsonInCallbacks = true))

        val actual = assertThrows(IOException::class.java) {
            converter(factory).convert(body)
        }

        assertSame(failure, actual)
        assertTrue(body.closed)
        assertTrue(actual.suppressed.any { it === closeFailure })
    }

    @Test
    fun `fatal observer failures close empty and capture skipped response bodies`() {
        listOf("", """{"id":7}""").forEach { json ->
            val failure = AssertionError("observer failed")
            val body = TrackingBody(json)
            val factory = GsonSafeConverterFactory.create(
                SafeParserConfig(
                    captureRawJsonInCallbacks = true,
                    maxRawJsonCaptureBytes = 1,
                    onEvent = { throw failure }
                )
            )

            val actual = assertThrows(AssertionError::class.java) {
                converter(factory).convert(body)
            }

            assertSame(failure, actual)
            assertTrue(body.closed)
        }
    }

    private fun converter(
        factory: Converter.Factory,
        type: Type = Response::class.java
    ): Converter<ResponseBody, *> {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(factory)
            .build()
        return requireNotNull(factory.responseBodyConverter(type, emptyArray(), retrofit))
    }

    private class TrackingBody(
        json: String,
        failureOnRead: Int = 1,
        readFailure: Throwable? = null,
        closeFailure: Throwable? = null
    ) : ResponseBody() {
        var closed = false
            private set

        private val bufferedSource = object : ForwardingSource(Buffer().writeUtf8(json)) {
            private var readCount = 0

            override fun read(sink: Buffer, byteCount: Long): Long {
                readCount += 1
                if (readCount == failureOnRead && readFailure != null) {
                    throw readFailure
                }
                return super.read(sink, minOf(byteCount, 1L))
            }

            override fun close() {
                closed = true
                if (closeFailure != null) throw closeFailure
                super.close()
            }
        }.buffer()

        override fun contentLength(): Long = -1

        override fun contentType(): MediaType = JSON

        override fun source(): BufferedSource = bufferedSource
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
