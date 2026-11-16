package io.github.logan.gsonsafeparser

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/** 错形键和容器写入拒绝只能影响当前 entry，不能生成假键或伪装成调用方 Adapter 失败。 */
class SafeParserMapRecoveryTest {
    data class NumberKeyResponse(
        val values: Map<Int?, String> = emptyMap(),
        val next: String = "local"
    )

    data class ObjectKey(val id: Int = 7)

    data class ObjectKeyResponse(
        val values: Map<ObjectKey?, String> = emptyMap(),
        val next: String = "local"
    )

    @JsonAdapter(ScalarKeyAdapter::class)
    data class ScalarKey(val id: Int)

    class ScalarKeyAdapter : TypeAdapter<ScalarKey>() {
        override fun read(reader: JsonReader): ScalarKey = ScalarKey(reader.nextInt())

        override fun write(out: JsonWriter, value: ScalarKey?) {
            out.value(value?.id)
        }
    }

    data class ScalarKeyResponse(val values: Map<ScalarKey, String> = emptyMap())

    data class ConcurrentResponse(
        val values: ConcurrentMap<String, String?> = ConcurrentHashMap(),
        val next: String = "local"
    )

    data class ConcurrentNumberKeyResponse(
        val values: ConcurrentMap<Int?, String> = ConcurrentHashMap(),
        val next: String = "local"
    )

    data class CallerValue(val text: String)

    data class CallerValueResponse(
        val values: ConcurrentMap<String, CallerValue?> = ConcurrentHashMap(),
        val next: String = "local"
    )

    class FatalPutMap : LinkedHashMap<String, String?>() {
        override fun put(key: String, value: String?): String? {
            if (key == "bad") throw fatalFailure
            return super.put(key, value)
        }
    }

    class CancelledPutMap : LinkedHashMap<String, String?>() {
        override fun put(key: String, value: String?): String? {
            if (key == "bad") throw cancellationFailure
            return super.put(key, value)
        }
    }

    data class FatalPutResponse(val values: FatalPutMap = FatalPutMap())

    data class CancelledPutResponse(val values: CancelledPutMap = CancelledPutMap())

    @Test
    fun `array entries skip malformed numeric keys while retaining real null keys`() {
        for (fallback in FallbackPolicy.entries) {
            val result = GsonSafeParser.parseSafe<NumberKeyResponse>(
                """{"values":[[0,"before"],[{},"object"],[[],"array"],[true,"boolean"],[null,"explicit-null"],[1,"after"]],"next":"remote"}""",
                SafeParserConfig(
                    fallbackPolicy = fallback,
                    primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
                )
            )

            assertEquals(mapOf(0 to "before", null to "explicit-null", 1 to "after"), result.value?.values)
            assertEquals("remote", result.value?.next)
            val events = result.events.map { (it as SafeParserEvent.TypeMismatch).detail }
            assertEquals(listOf("$.values[1][0]", "$.values[2][0]", "$.values[3][0]"), events.map { it.path })
            assertTrue(events.all { it.kind == ParseExceptionKind.MAP_ITEM })
        }
    }

    @Test
    fun `array entries skip scalar object keys without inventing null keys`() {
        val result = GsonSafeParser.parseSafe<ObjectKeyResponse>(
            """{"values":[[123,"number"],[false,"boolean"],[{"id":1},"before"],[null,"explicit-null"],[{"id":2},"after"]],"next":"remote"}"""
        )

        assertEquals(
            mapOf(ObjectKey(1) to "before", null to "explicit-null", ObjectKey(2) to "after"),
            result.value?.values
        )
        assertEquals("remote", result.value?.next)
        val events = result.events.map { (it as SafeParserEvent.TypeMismatch).detail }
        assertEquals(listOf("$.values[0][0]", "$.values[1][0]"), events.map { it.path })
        assertTrue(events.all { it.kind == ParseExceptionKind.MAP_ITEM })
    }

    @Test
    fun `custom key adapters retain their scalar input shape`() {
        val result = GsonSafeParser.parseSafe<ScalarKeyResponse>(
            """{"values":[[1,"before"],[2,"after"]]}"""
        )

        assertEquals(mapOf(ScalarKey(1) to "before", ScalarKey(2) to "after"), result.value?.values)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `registered key adapters retain scalar and explicit null conversions`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonBuilder()
            .registerTypeAdapter(ObjectKey::class.java, object : TypeAdapter<ObjectKey>() {
                override fun read(reader: JsonReader): ObjectKey {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        return ObjectKey(-1)
                    }
                    return ObjectKey(reader.nextInt())
                }

                override fun write(out: JsonWriter, value: ObjectKey?) {
                    out.value(value?.id)
                }
            })
            .enableSafeParser(SafeParserConfig(onTypeMismatch = events::add))
            .create()

        val result = gson.fromJson(
            """{"values":[[1,"before"],[null,"from-adapter"],[2,"after"]],"next":"remote"}""",
            ObjectKeyResponse::class.java
        )

        assertEquals(
            mapOf(ObjectKey(1) to "before", ObjectKey(-1) to "from-adapter", ObjectKey(2) to "after"),
            result.values
        )
        assertEquals("remote", result.next)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `custom key read failures are still rethrown instead of shape filtered`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        val error = assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"values":[["not-an-integer","value"]]}""",
                ScalarKeyResponse::class.java
            )
        }

        assertTrue(error.cause is NumberFormatException)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `concurrent maps skip rejected values with every primitive and fallback policy`() {
        for (primitive in PrimitiveParsingPolicy.entries) {
            for (fallback in FallbackPolicy.entries) {
                for ((values, expectedPath) in rejectedNullValues) {
                    val result = GsonSafeParser.parseSafe<ConcurrentResponse>(
                        """{"values":$values,"next":"remote"}""",
                        SafeParserConfig(
                            primitiveParsingPolicy = primitive,
                            fallbackPolicy = fallback,
                            mapItemKeyPolicy = MapItemKeyPolicy.PlainText
                        )
                    )

                    assertEquals(mapOf("before" to "first", "after" to "last"), result.value?.values)
                    assertEquals("remote", result.value?.next)
                    val event = (result.events.single() as SafeParserEvent.TypeMismatch).detail
                    assertEquals(ParseExceptionKind.MAP_ITEM, event.kind)
                    assertEquals(expectedPath, event.path)
                    assertEquals("bad", event.mapItemKey)
                }
            }
        }
    }

    @Test
    fun `concurrent maps locally reject real null keys without skipping the next entry`() {
        val result = GsonSafeParser.parseSafe<ConcurrentNumberKeyResponse>(
            """{"values":[[1,"before"],[null,"rejected"],[2,"after"]],"next":"remote"}"""
        )

        assertEquals(mapOf(1 to "before", 2 to "after"), result.value?.values)
        assertEquals("remote", result.value?.next)
        val event = (result.events.single() as SafeParserEvent.TypeMismatch).detail
        assertEquals(ParseExceptionKind.MAP_ITEM, event.kind)
        assertEquals("$.values[1][1]", event.path)
    }

    @Test
    fun `successful custom value reads remain separate from container rejection`() {
        for ((values, expectedPath) in rejectedNullValues) {
            val events = mutableListOf<TypeMismatchEvent>()
            val gson = GsonBuilder()
                .registerTypeAdapter(CallerValue::class.java, object : TypeAdapter<CallerValue>() {
                    override fun read(reader: JsonReader): CallerValue? {
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            return null
                        }
                        return CallerValue(reader.nextString())
                    }

                    override fun write(out: JsonWriter, value: CallerValue?) {
                        out.value(value?.text)
                    }
                })
                .enableSafeParser(SafeParserConfig(onTypeMismatch = events::add))
                .create()

            val result = gson.fromJson(
                """{"values":$values,"next":"remote"}""",
                CallerValueResponse::class.java
            )

            assertEquals(
                mapOf("before" to CallerValue("first"), "after" to CallerValue("last")),
                result.values
            )
            assertEquals("remote", result.next)
            assertEquals(
                if (expectedPath == "$.values.bad") "$.values.[map-key]" else expectedPath,
                events.single().path
            )
            assertEquals(ParseExceptionKind.MAP_ITEM, events.single().kind)
            assertNull(events.single().mapItemKey)
        }
    }

    @Test
    fun `custom value read failures are still rethrown for both map encodings`() {
        val failure = IllegalArgumentException("caller value failure")
        for ((values, _) in rejectedNullValues) {
            val events = mutableListOf<TypeMismatchEvent>()
            val gson = GsonBuilder()
                .registerTypeAdapter(CallerValue::class.java, object : TypeAdapter<CallerValue>() {
                    override fun read(reader: JsonReader): CallerValue {
                        throw failure
                    }

                    override fun write(out: JsonWriter, value: CallerValue?) {
                        out.value(value?.text)
                    }
                })
                .enableSafeParser(SafeParserConfig(onTypeMismatch = events::add))
                .create()

            val error = assertThrows(JsonParseException::class.java) {
                gson.fromJson("""{"values":$values}""", CallerValueResponse::class.java)
            }

            assertSame(failure, error.cause)
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `container write fatal and cancellation failures remain visible`() {
        for ((values, _) in rejectedNullValues) {
            val events = mutableListOf<TypeMismatchEvent>()
            val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))
            val json = """{"values":$values}"""

            val fatal = assertThrows(AssertionError::class.java) {
                gson.fromJson(json, FatalPutResponse::class.java)
            }
            assertSame(fatalFailure, fatal.cause ?: fatal)
            // CancellationException 继承 IllegalStateException，Gson 会包装，但必须保留原始 cause。
            val cancellation = assertThrows(JsonSyntaxException::class.java) {
                gson.fromJson(json, CancelledPutResponse::class.java)
            }
            assertSame(cancellationFailure, cancellation.cause)
            assertTrue(events.isEmpty())
        }
    }

    private companion object {
        val fatalFailure = AssertionError("container fatal failure")
        val cancellationFailure = CancellationException("container cancelled")
        val rejectedNullValues = listOf(
            """{"before":"first","bad":null,"after":"last"}""" to "$.values.bad",
            """[["before","first"],["bad",null],["after","last"]]""" to "$.values[1][1]"
        )
    }
}
