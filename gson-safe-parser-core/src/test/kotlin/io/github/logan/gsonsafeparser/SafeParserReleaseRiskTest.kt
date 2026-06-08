package io.github.logan.gsonsafeparser

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 发布前风险回归。
 *
 * 这里专门锁住容易在集成期出事故的边界：配置复制、ThreadLocal 恢复、隐私脱敏、
 * primitive array 边界，以及外部 Gson 包装入口的配置所有权。
 */
class SafeParserReleaseRiskTest {
    data class User(val id: Long = 0L, val name: String = "")
    data class UserResponse(val data: User? = null)
    data class UserListResponse(val users: List<User> = emptyList())
    data class MixedShapeResponse(
        val first: User? = null,
        val second: List<User> = emptyList(),
        val third: User? = null
    )
    data class PrimitiveArrayResponse(val values: IntArray = intArrayOf(9))
    data class MapValueResponse(val values: Map<String, User> = emptyMap())
    data class RawJsonResponse(val child: User = User())
    data class SensitiveReasonResponse(val value: SensitiveReasonValue = SensitiveReasonValue())
    data class GenericBox<T>(val value: T? = null)
    data class ExplicitJsonAdapterShapeResponse(
        @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        val value: NativeJsonAdapterOnly? = null
    )

    @SafeParseDelegateToGson
    class NativeOnly {
        var name: String = "local"
    }

    @JsonAdapter(NativeJsonAdapterOnlyAdapter::class)
    class NativeJsonAdapterOnly {
        var name: String = "local"
    }

    @JsonAdapter(SensitiveReasonValueAdapter::class)
    data class SensitiveReasonValue(val text: String = "local")

    class NativeJsonAdapterOnlyAdapter : TypeAdapter<NativeJsonAdapterOnly>() {
        override fun write(out: JsonWriter, value: NativeJsonAdapterOnly?) {
            out.beginObject()
            out.name("name").value(value?.name)
            out.endObject()
        }

        override fun read(reader: JsonReader): NativeJsonAdapterOnly {
            reader.beginObject()
            val result = NativeJsonAdapterOnly()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> result.name = reader.nextString()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return result
        }
    }

    class SensitiveReasonValueAdapter : TypeAdapter<SensitiveReasonValue>() {
        override fun write(out: JsonWriter, value: SensitiveReasonValue?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): SensitiveReasonValue {
            val text = reader.nextString()
            throw IllegalStateException(
                "adapter failed token=$text password=hunter2 Authorization: Bearer abc.def Cookie: sid=xyz"
            )
        }
    }

    @Test
    fun `shape coercion policy survives config copy`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig()
            .withShapeCoercionPolicy(ShapeCoercionPolicy.CollectionFromSingleObject)
            .copy(onEvent = events::add)

        val result = GsonSafeParser.fromJson(
            """{"users":{"id":7,"name":"Tom"}}""",
            UserListResponse::class.java,
            config
        )

        assertEquals(listOf(User(7L, "Tom")), result?.users)
        val event = events.single() as SafeParserEvent.ShapeCoercion
        assertEquals(ShapeCoercionAction.CollectionFromSingleObject, event.detail.action)
    }

    @Test
    fun `shape coercion field context is restored after failed object coercion`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(onEvent = events::add)
            .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)

        val result = GsonSafeParser.fromJson(
            """{"first":[1],"second":{"id":2},"third":[{"id":3}]}""",
            MixedShapeResponse::class.java,
            config
        )

        assertNull(result?.first)
        assertEquals(listOf(User(2L)), result?.second)
        assertEquals(User(3L), result?.third)
        val actions = events.filterIsInstance<SafeParserEvent.ShapeCoercion>()
            .map { event -> event.detail.action }
        assertEquals(
            listOf(
                ShapeCoercionAction.CoercionFailed,
                ShapeCoercionAction.CollectionFromSingleObject,
                ShapeCoercionAction.ObjectFromFirstArrayItem
            ),
            actions
        )
    }

    @Test
    fun `primitive array field is not shape coerced from object`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(onEvent = events::add)
            .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)

        val result = GsonSafeParser.fromJson(
            """{"values":{"0":1}}""",
            PrimitiveArrayResponse::class.java,
            config
        )

        assertArrayEquals(intArrayOf(9), result?.values)
        assertTrue(events.none { event -> event is SafeParserEvent.ShapeCoercion })
        val mismatch = events.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.values", mismatch.detail.path)
    }

    @Test
    fun `global shape coercion does not override generic delegate annotation field`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(onEvent = events::add)
            .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        val type = object : TypeToken<GenericBox<NativeOnly>>() {}.type

        val result = GsonSafeParser.fromJson<GenericBox<NativeOnly>>(
            """{"value":[{"name":"remote"}]}""",
            type,
            config
        )

        assertNull(result?.value)
        assertTrue(events.none { event -> event is SafeParserEvent.ShapeCoercion })
        val mismatch = events.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.value", mismatch.detail.path)
    }

    @Test
    fun `global shape coercion does not override generic class json adapter field`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(onEvent = events::add)
            .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        val type = object : TypeToken<GenericBox<NativeJsonAdapterOnly>>() {}.type

        val result = GsonSafeParser.fromJson<GenericBox<NativeJsonAdapterOnly>>(
            """{"value":[{"name":"remote"}]}""",
            type,
            config
        )

        assertNull(result?.value)
        assertTrue(events.none { event -> event is SafeParserEvent.ShapeCoercion })
        val mismatch = events.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.value", mismatch.detail.path)
    }

    @Test
    fun `field annotation can explicitly shape coerce class json adapter field`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(onEvent = events::add)

        val result = GsonSafeParser.fromJson(
            """{"value":[{"name":"remote"}]}""",
            ExplicitJsonAdapterShapeResponse::class.java,
            config
        )

        assertEquals("remote", result?.value?.name)
        val event = events.single() as SafeParserEvent.ShapeCoercion
        assertEquals(ShapeCoercionAction.ObjectFromFirstArrayItem, event.detail.action)
        assertEquals("$.value", event.detail.path)
    }

    @Test
    fun `map item key policy can expose plain text only when explicitly configured`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                mapItemKeyPolicy = MapItemKeyPolicy.PlainText,
                onTypeMismatch = events::add
            )
        )

        val result = gson.fromJson("""{"values":{"main":[]}}""", MapValueResponse::class.java)

        assertEquals(emptyMap<String, User>(), result.values)
        assertEquals("main", events.single().mapItemKey)
    }

    @Test
    fun `map item key policy can omit sensitive keys`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                mapItemKeyPolicy = MapItemKeyPolicy.Omit,
                onTypeMismatch = events::add
            )
        )

        val result = gson.fromJson("""{"values":{"user@example.com":[]}}""", MapValueResponse::class.java)

        assertEquals(emptyMap<String, User>(), result.values)
        assertNull(events.single().mapItemKey)
    }

    @Test
    fun `default and production configs omit map item keys for release safety`() {
        val defaultEvents = mutableListOf<TypeMismatchEvent>()
        val productionEvents = mutableListOf<TypeMismatchEvent>()

        GsonSafeParser.fromJson(
            """{"values":{"user@example.com":[]}}""",
            MapValueResponse::class.java,
            SafeParserConfig(onTypeMismatch = defaultEvents::add)
        )
        GsonSafeParser.fromJson(
            """{"values":{"user@example.com":[]}}""",
            MapValueResponse::class.java,
            SafeParserConfig.production(
                observerPolicy = SafeObserverPolicy(onTypeMismatch = productionEvents::add)
            )
        )

        assertNull(defaultEvents.single().mapItemKey)
        assertNull(productionEvents.single().mapItemKey)
    }

    @Test
    fun `event reasons redact sensitive values before callbacks and parse safe snapshots`() {
        val callbackEvents = mutableListOf<SafeParserEvent>()
        val compatibilityEvents = mutableListOf<TypeMismatchEvent>()
        val parser = GsonSafeParser.parser(
            SafeParserConfig(
                onEvent = callbackEvents::add,
                onTypeMismatch = compatibilityEvents::add
            )
        )

        val result = parser.parseSafe<SensitiveReasonResponse>(
            """{"value":"secret-token"}""",
            SensitiveReasonResponse::class.java
        )

        val callbackReason = (callbackEvents.single() as SafeParserEvent.TypeMismatch).detail.reason
        val compatibilityReason = compatibilityEvents.single().reason
        val snapshotReason = (result.events.single() as SafeParserEvent.TypeMismatch).detail.reason
        listOf(callbackReason, compatibilityReason, snapshotReason).forEach { reason ->
            assertFalse(reason.contains("secret-token"))
            assertFalse(reason.contains("hunter2"))
            assertFalse(reason.contains("abc.def"))
            assertFalse(reason.contains("sid=xyz"))
            assertTrue(reason.contains("[REDACTED]"))
        }
    }

    @OptIn(GsonSafeParserLowLevelApi::class)
    @Test
    fun `manual low level events redact sensitive reasons before observers`() {
        val events = mutableListOf<SafeParserEvent>()
        val adapterFailures = mutableListOf<AdapterCreationFailureEvent>()
        val observerFailures = mutableListOf<ObserverFailureEvent>()
        val config = SafeParserConfig(
            onEvent = { event ->
                events += event
                error("logger failed api_key=observer-secret")
            },
            onAdapterCreationFailure = adapterFailures::add,
            onObserverFailure = observerFailures::add
        )

        config.dispatchEvent(
            SafeParserEvent.AdapterCreationFailure(
                AdapterCreationFailureEvent(
                    typeName = SensitiveReasonResponse::class.java.name,
                    reason = "duplicate field password=adapter-secret Authorization: Bearer adapter-token",
                    error = IllegalArgumentException("duplicate field password=adapter-secret")
                )
            )
        )

        val event = events.single() as SafeParserEvent.AdapterCreationFailure
        assertFalse(event.detail.reason.contains("adapter-secret"))
        assertFalse(event.detail.reason.contains("adapter-token"))
        assertEquals(event.detail.reason, adapterFailures.single().reason)
        assertFalse(observerFailures.single().reason.contains("observer-secret"))
        assertTrue(observerFailures.single().reason.contains("[REDACTED]"))
    }

    @OptIn(GsonSafeParserLowLevelApi::class)
    @Test
    fun `reason redaction keeps ordinary basic diagnostic wording`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(onEvent = events::add)

        config.dispatchEvent(
            SafeParserEvent.TypeMismatch(
                TypeMismatchEvent(
                    expectedType = SensitiveReasonResponse::class.java.name,
                    actualToken = com.google.gson.stream.JsonToken.STRING,
                    path = "$.value",
                    reason = "Expected basic type value but got a nested object"
                )
            )
        )

        val reason = (events.single() as SafeParserEvent.TypeMismatch).detail.reason
        assertEquals("Expected basic type value but got a nested object", reason)
    }

    @Test
    fun `raw json context is restored after malformed json failure`() {
        val malformedConfig = SafeParserConfig(captureRawJsonInCallbacks = true)
        assertThrows(JsonParseException::class.java) {
            GsonSafeParser.fromJson("{", RawJsonResponse::class.java, malformedConfig)
        }
        val events = mutableListOf<TypeMismatchEvent>()

        GsonSafeParser.fromJson(
            """{"child":[]}""",
            RawJsonResponse::class.java,
            SafeParserConfig(onTypeMismatch = events::add)
        )

        assertNull(events.single().rawJson)
    }

    @Test
    fun `plain external gson is not upgraded by wrapper config shape coercion`() {
        val parser = GsonSafeParser.parserWithExternalGson(
            gson = GsonBuilder().create(),
            config = SafeParserConfig()
                .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        )

        assertThrows(JsonSyntaxException::class.java) {
            parser.fromJson("""{"data":[{"id":1}]}""", UserResponse::class.java)
        }
    }

    @Test
    fun `safe external gson keeps its own shape coercion config`() {
        val eventsFromGsonConfig = mutableListOf<SafeParserEvent>()
        val eventsFromWrapperConfig = mutableListOf<SafeParserEvent>()
        val gsonConfig = SafeParserConfig(onEvent = eventsFromGsonConfig::add)
            .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        val wrapperConfig = SafeParserConfig(onEvent = eventsFromWrapperConfig::add)
        val parser = GsonSafeParser.parserWithExternalGson(
            gson = GsonBuilder().enableSafeParser(gsonConfig).create(),
            config = wrapperConfig
        )

        val result = parser.fromJson("""{"data":[{"id":5}]}""", UserResponse::class.java)

        assertEquals(User(5L), result?.data)
        assertEquals(1, eventsFromGsonConfig.size)
        assertTrue(eventsFromGsonConfig.single() is SafeParserEvent.ShapeCoercion)
        assertTrue(eventsFromWrapperConfig.isEmpty())
    }

    @Test
    fun `debug preset captures raw json while production preset does not`() {
        val debugEvents = mutableListOf<TypeMismatchEvent>()
        val productionEvents = mutableListOf<TypeMismatchEvent>()

        GsonSafeParser.fromJson(
            """{"child":[]}""",
            RawJsonResponse::class.java,
            SafeParserConfig.debug(
                observerPolicy = SafeObserverPolicy(onTypeMismatch = debugEvents::add),
                maxRawJsonCaptureBytes = 128
            )
        )
        GsonSafeParser.fromJson(
            """{"child":[]}""",
            RawJsonResponse::class.java,
            SafeParserConfig.production(
                observerPolicy = SafeObserverPolicy(onTypeMismatch = productionEvents::add)
            )
        )

        assertEquals("""{"child":[]}""", debugEvents.single().rawJson)
        assertFalse(debugEvents.single().rawJsonTruncated)
        assertNull(productionEvents.single().rawJson)
    }

    @Test
    fun `diagnostics warns whenever raw json capture is explicitly enabled`() {
        val diagnostics = GsonSafeParser.diagnostics(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 1024
            )
        )

        val warning = diagnostics.checks.single { it.name == "rawJsonCaptureEnabled" }

        assertEquals(DiagnosticSeverity.WARNING, warning.severity)
        assertTrue(warning.message.contains("Raw JSON capture is enabled"))
        assertFalse(diagnostics.hasErrors)
    }
}
