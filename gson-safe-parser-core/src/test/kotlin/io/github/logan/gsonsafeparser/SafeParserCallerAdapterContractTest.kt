package io.github.logan.gsonsafeparser

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** 调用方明确提供的读写逻辑必须在字段、集合和 Map 中保持一致。 */
class SafeParserCallerAdapterContractTest {
    class NullStringAdapter : TypeAdapter<String>() {
        override fun read(reader: JsonReader): String {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                return "from-adapter"
            }
            return reader.nextString()
        }

        override fun write(out: JsonWriter, value: String?) {
            out.value(value)
        }
    }

    class RejectNullAdapter : TypeAdapter<String>() {
        override fun read(reader: JsonReader): String {
            check(reader.peek() != JsonToken.NULL) { "Null rejected by caller" }
            return reader.nextString()
        }

        override fun write(out: JsonWriter, value: String?) {
            out.value(value)
        }
    }

    class AnnotatedFields {
        @field:JsonAdapter(NullStringAdapter::class, nullSafe = false)
        var text: String = "local"
    }

    class RejectNullField {
        @field:JsonAdapter(RejectNullAdapter::class, nullSafe = false)
        var text: String = "local"
    }

    class StringFields {
        var text: String = "local"
        var nullable: String? = "local"
        var items: List<String> = emptyList()
        var map: Map<String, String> = emptyMap()
    }

    open class Base {
        var base: String = "base"
    }

    class Child : Base() {
        var child: String = "child"
    }

    class PolymorphicFields {
        var value: Base = Child()
        var items: List<Base> = listOf(Child())
        var array: Array<Base> = arrayOf(Child())
        var map: Map<String, Base> = mapOf("item" to Child())
    }

    @Test
    fun `field annotation can consume explicit null`() {
        val gson = GsonSafeParser.create()
        assertEquals(
            "from-adapter",
            gson.fromJson("""{"text":null}""", AnnotatedFields::class.java).text
        )
    }

    @Test
    fun `registered adapter consumes explicit null consistently across containers`() {
        for (policy in NullValuePolicy.entries) {
            val gson = GsonBuilder()
                .registerTypeAdapter(String::class.java, NullStringAdapter())
                .enableSafeParser(SafeParserConfig(nullValuePolicy = policy))
                .create()
            val result = gson.fromJson(
                """{"text":null,"nullable":null,"items":[null],"map":{"key":null}}""",
                StringFields::class.java
            )
            assertEquals("from-adapter", result.text, policy.name)
            assertEquals("from-adapter", result.nullable, policy.name)
            assertEquals(listOf("from-adapter"), result.items, policy.name)
            assertEquals(mapOf("key" to "from-adapter"), result.map, policy.name)
        }
    }

    @Test
    fun `field adapter explicit null failure is not silently recovered`() {
        val gson = GsonSafeParser.create()
        assertThrows(JsonSyntaxException::class.java) {
            gson.fromJson("""{"text":null}""", RejectNullField::class.java)
        }
    }

    @Test
    fun `deserializer registration does not lose runtime subtype fields on write`() {
        fun builder() = GsonBuilder().registerTypeAdapter(
            Base::class.java,
            JsonDeserializer<Base> { _, _, _ -> Base() }
        )
        val expected = builder().create().toJsonTree(PolymorphicFields())
        val actual = builder().enableSafeParser().create().toJsonTree(PolymorphicFields())
        assertEquals(expected, actual)
    }
}
