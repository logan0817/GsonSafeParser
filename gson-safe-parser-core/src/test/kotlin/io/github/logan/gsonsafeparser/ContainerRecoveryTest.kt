package io.github.logan.gsonsafeparser

import com.google.gson.JsonParseException
import com.google.gson.GsonBuilder
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
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
import java.util.ArrayDeque
import java.util.Queue
import java.util.SortedSet
import java.util.TreeSet

class ContainerRecoveryTest {
    data class CharacterResponse(val value: Char = 'x', val next: String = "local")

    data class RestrictedCollections(
        val queue: Queue<String> = ArrayDeque(),
        val sorted: SortedSet<String> = TreeSet(),
        val next: String = "local"
    )

    data class User(val id: Int = 0)

    data class ArrayResponse(
        val numbers: Array<Int> = emptyArray(),
        val users: Array<User> = emptyArray(),
        val next: String = "local"
    )

    data class PrimitiveArrayResponse(val numbers: IntArray = intArrayOf(9), val next: String = "local")

    data class NullableArrayResponse(val users: Array<User?> = emptyArray())

    data class ThrowingArrayResponse(val values: Array<CallerValue> = emptyArray())

    open class Parent(val name: String = "parent")

    class Child(val extra: String = "child") : Parent()

    data class PolymorphicArray(val values: Array<Parent> = arrayOf(Child()))

    @JsonAdapter(CallerValueAdapter::class)
    data class CallerValue(val id: Int = 0)

    class CallerValueAdapter : TypeAdapter<CallerValue>() {
        override fun write(out: JsonWriter, value: CallerValue?) {
            out.value(value?.id)
        }

        override fun read(reader: JsonReader): CallerValue {
            throw callerFailure
        }
    }

    private val safeNumbers = SafeParserConfig(primitiveParsingPolicy = PrimitiveParsingPolicy.Safe)

    @Test
    fun `consumed invalid character does not skip the next field`() {
        val result = GsonSafeParser.parseSafe<CharacterResponse>(
            """{"value":"invalid","next":"remote"}"""
        )

        assertEquals(CharacterResponse(next = "remote"), result.value)
        val event = result.events.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.value", event.detail.path)
    }

    @Test
    fun `queue and sorted set retain valid items around rejected nulls`() {
        val result = GsonSafeParser.parseSafe<RestrictedCollections>(
            """{"queue":["before",null,"after"],"sorted":["before",null,"after"],"next":"remote"}"""
        )

        assertEquals(listOf("before", "after"), result.value?.queue?.toList())
        assertEquals(listOf("after", "before"), result.value?.sorted?.toList())
        assertEquals("remote", result.value?.next)
        val events = result.events.map { (it as SafeParserEvent.TypeMismatch).detail }
        assertEquals(setOf("$.queue[1]", "$.sorted[1]"), events.map { it.path }.toSet())
        assertTrue(events.all { it.kind == ParseExceptionKind.LIST_ITEM })
    }

    @Test
    fun `object arrays skip only malformed items and preserve following fields`() {
        val result = GsonSafeParser.parseSafe<ArrayResponse>(
            """{"numbers":[1,"invalid",2],"users":[{"id":1},false,{"id":2}],"next":"remote"}""",
            safeNumbers
        )

        assertEquals(listOf(1, 2), result.value?.numbers?.toList())
        assertEquals(listOf(User(1), User(2)), result.value?.users?.toList())
        assertEquals("remote", result.value?.next)
        val events = result.events.map { (it as SafeParserEvent.TypeMismatch).detail }
        assertEquals(setOf("$.numbers[1]", "$.users[1]"), events.map { it.path }.toSet())
        assertTrue(events.all { it.kind == ParseExceptionKind.LIST_ITEM })
    }

    @Test
    fun `primitive arrays skip malformed and null items without losing valid numbers`() {
        val result = GsonSafeParser.parseSafe<PrimitiveArrayResponse>(
            """{"numbers":[1,"invalid",null,2],"next":"remote"}""",
            safeNumbers
        )

        assertEquals(listOf(1, 2), result.value?.numbers?.toList())
        assertEquals("remote", result.value?.next)
        val events = result.events.map { (it as SafeParserEvent.TypeMismatch).detail }
        assertEquals(setOf("$.numbers[1]", "$.numbers[2]"), events.map { it.path }.toSet())
        assertTrue(events.all { it.kind == ParseExceptionKind.LIST_ITEM })
    }

    @Test
    fun `object arrays retain explicit null values`() {
        val result = GsonSafeParser.parseSafe<NullableArrayResponse>(
            """{"users":[{"id":1},null,{"id":2}]}"""
        )

        assertEquals(listOf(User(1), null, User(2)), result.value?.users?.toList())
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `whole array mismatch keeps the configured null fallback`() {
        val result = GsonSafeParser.parseSafe<Array<User>>("""{"id":1}""")

        assertNull(result.value)
        assertEquals(1, result.events.size)
    }

    @Test
    fun `custom array element adapter failures remain visible`() {
        val events = mutableListOf<SafeParserEvent>()
        val error = assertThrows(JsonParseException::class.java) {
            GsonSafeParser.fromJson(
                """{"values":[{"id":1}]}""",
                ThrowingArrayResponse::class.java,
                SafeParserConfig(onEvent = events::add)
            )
        }

        assertSame(callerFailure, error.cause ?: error)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `primitive arrays preserve caller adapter null conversion`() {
        val gson = GsonBuilder()
            .registerTypeAdapter(Int::class.javaPrimitiveType, object : TypeAdapter<Int>() {
                override fun write(out: JsonWriter, value: Int?) {
                    out.value(value)
                }

                override fun read(reader: JsonReader): Int {
                    return if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        -1
                    } else {
                        reader.nextInt()
                    }
                }
            })
            .enableSafeParser(safeNumbers)
            .create()

        assertEquals(listOf(1, -1, 2), gson.fromJson("[1,null,2]", IntArray::class.java).toList())
    }

    @Test
    fun `array serialization retains runtime subtype fields`() {
        val value = PolymorphicArray()
        val actual = GsonSafeParser.create().toJson(value)

        assertEquals(GsonBuilder().create().toJson(value), actual)
        assertTrue(actual.contains("extra"))
    }

    @Test
    fun `array serialization preserves declared custom serializer over reflective subtype`() {
        val builder = GsonBuilder().registerTypeAdapter(
            Parent::class.java,
            JsonSerializer<Parent> { _, _, _ -> JsonPrimitive("parent-adapter") }
        )
        val value = PolymorphicArray()
        val expected = builder.create().toJson(value)
        val actual = builder.enableSafeParser().create().toJson(value)

        assertEquals("""{"values":["parent-adapter"]}""", actual)
        assertEquals(expected, actual)
    }

    private companion object {
        val callerFailure = JsonParseException("caller adapter failure")
    }
}
