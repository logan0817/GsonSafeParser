package io.github.logan.gsonsafeparser

import android.content.res.ColorStateList
import com.google.gson.JsonElement
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import com.google.gson.ReflectionAccessFilter
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URL
import java.util.BitSet
import java.util.UUID

class GsonSafeParserNativeFallbackExampleTest {
    data class User(val name: String = "local")

    data class DynamicEnvelope(
        val anyValue: Any? = null,
        val tree: JsonElement? = null,
        val uuid: UUID? = null,
        val url: URL? = null,
        val bitSet: BitSet? = null
    )

    data class AnnotationEnvelope(
        @field:SafeParseSkip
        val cached: User = User("local-cache"),
        val user: User = User("local-user"),
        val native: NativeOnly = NativeOnly(),
        val adapted: AdapterOnly = AdapterOnly("local-adapter")
    )

    data class DefaultFallbackEnvelope(
        val data: User = User("local-data"),
        val users: List<User> = listOf(User("local-list")),
        val values: Map<String, User> = mapOf("local" to User("local-map"))
    )

    data class NullableFallbackEnvelope(
        val data: User? = User("local-data"),
        val users: List<User>? = listOf(User("local-list")),
        val values: Map<String, User>? = mapOf("local" to User("local-map"))
    )
    data class RequiredNullableFallbackEnvelope(
        val data: User?,
        val users: List<User>?,
        val values: Map<String, User>?
    )

    data class AndroidPlatformEnvelope(
        val title: String = "local",
        val colorStateList: ColorStateList? = null
    )

    data class PrivateFieldEnvelope(
        private val secret: String = "local"
    ) {
        fun exposedSecret(): String = secret
    }

    @SafeParseDelegateToGson
    class NativeOnly {
        var name: String = "local-native"
    }

    @JsonAdapter(AdapterOnlyAdapter::class)
    data class AdapterOnly(val name: String)

    class AdapterOnlyAdapter : TypeAdapter<AdapterOnly>() {
        override fun write(out: JsonWriter, value: AdapterOnly?) {
            out.value(value?.name)
        }

        override fun read(reader: JsonReader): AdapterOnly {
            return when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.skipValue()
                    }
                    reader.endArray()
                    AdapterOnly("adapter-read-array")
                }
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    var name = "adapter-object"
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "name" -> name = reader.nextString()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    AdapterOnly(name)
                }
                JsonToken.STRING -> AdapterOnly(reader.nextString())
                else -> {
                    reader.skipValue()
                    AdapterOnly("adapter-read-other")
                }
            }
        }
    }

    @Test
    fun `example keeps dynamic any json tree and gson built in type conversion rules`() {
        val result = GsonSafeParser.fromJsonSafe<DynamicEnvelope>(
            """
            {
              "anyValue": {"count": 2, "flag": true, "nested": ["x"]},
              "tree": {"raw": [1, 2]},
              "uuid": "00000000-0000-0000-0000-000000000123",
              "url": "https://example.com/path",
              "bitSet": [1, 0, 1]
            }
            """.trimIndent()
        )

        requireNotNull(result)
        val dynamic = result.anyValue as Map<*, *>
        assertEquals(2, dynamic["count"])
        assertEquals(true, dynamic["flag"])
        assertTrue(result.tree?.asJsonObject?.getAsJsonArray("raw")?.size() == 2)
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000123"), result.uuid)
        assertEquals("https://example.com/path", result.url?.toExternalForm())
        assertEquals(BitSet().apply { set(0); set(2) }, result.bitSet)
    }

    @Test
    fun `example keeps annotations and json adapters on native gson fallback path`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        val result = GsonSafeParser.parseSafe<AnnotationEnvelope>(
            """
            {
              "cached": {"name": "remote-cache"},
              "user": {"name": "remote-user"},
              "native": [],
              "adapted": [{"name": "remote-adapter"}]
            }
            """.trimIndent(),
            config
        )

        assertEquals(User("local-cache"), result.value?.cached)
        assertEquals(User("remote-user"), result.value?.user)
        assertEquals("local-native", result.value?.native?.name)
        assertEquals(AdapterOnly("adapter-read-array"), result.value?.adapted)
        assertFalse(result.events.any { it is SafeParserEvent.ShapeCoercion && it.detail.path == "$.adapted" })
    }

    @Test
    fun `example compares default fallback null only fallback and keep default null policy`() {
        val wrongShapeJson = """{"data":[],"users":{},"values":""}"""
        val defaultResult = GsonSafeParser.fromJsonSafe<DefaultFallbackEnvelope>(wrongShapeJson)
        val nullOnlyResult = GsonSafeParser.fromJsonSafe<RequiredNullableFallbackEnvelope>(
            wrongShapeJson,
            SafeParserConfig(fallbackPolicy = FallbackPolicy.NullOnly)
        )
        val nullOnlyKeepsConstructedDefaults = GsonSafeParser.fromJsonSafe<NullableFallbackEnvelope>(
            wrongShapeJson,
            SafeParserConfig(fallbackPolicy = FallbackPolicy.NullOnly)
        )
        val keepDefaults = GsonSafeParser.fromJsonSafe<NullableFallbackEnvelope>(
            """{"data":null,"users":null,"values":null}""",
            SafeParserConfig(nullValuePolicy = NullValuePolicy.KeepDefaults)
        )

        assertEquals(DefaultFallbackEnvelope(), defaultResult)
        assertNull(nullOnlyResult?.data)
        assertNull(nullOnlyResult?.users)
        assertNull(nullOnlyResult?.values)
        assertEquals(NullableFallbackEnvelope(), nullOnlyKeepsConstructedDefaults)
        assertEquals(NullableFallbackEnvelope(), keepDefaults)
    }

    @Test
    fun `example keeps unsupported platform fields and reflection boundaries on gson rules`() {
        val platform = GsonSafeParser.fromJsonSafe<AndroidPlatformEnvelope>(
            """{"title":"remote","colorStateList":{}}"""
        )
        val blockedGson = GsonSafeParser.create(
            SafeParserConfig(
                reflectionAccessFilters = listOf(
                    ReflectionAccessFilter { rawType ->
                        if (rawType == PrivateFieldEnvelope::class.java) {
                            ReflectionAccessFilter.FilterResult.BLOCK_INACCESSIBLE
                        } else {
                            ReflectionAccessFilter.FilterResult.INDECISIVE
                        }
                    }
                )
            )
        )

        assertEquals("remote", platform?.title)
        assertNull(platform?.colorStateList)
        assertThrows(JsonIOException::class.java) {
            blockedGson.fromJson("""{"secret":"remote"}""", PrivateFieldEnvelope::class.java)
        }
    }

    @Test
    fun `example does not swallow native gson syntax or delegated primitive failures`() {
        assertThrows(JsonSyntaxException::class.java) {
            GsonSafeParser.fromJsonSafe<User>("""{"name":""")
        }
        assertThrows(RuntimeException::class.java) {
            GsonSafeParser.fromJson(
                "{}",
                Int::class.java,
                SafeParserConfig(primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson)
            )
        }
    }

    @Test
    fun `example keeps skipped fields out of json serialization`() {
        val json = GsonSafeParser.create().toJson(
            AnnotationEnvelope(
                cached = User("secret-cache"),
                user = User("visible-user"),
                adapted = AdapterOnly("visible-adapter")
            )
        )

        assertFalse(json.contains("secret-cache"))
        assertTrue(json.contains("visible-user"))
        assertTrue(json.contains("visible-adapter"))
    }
}
