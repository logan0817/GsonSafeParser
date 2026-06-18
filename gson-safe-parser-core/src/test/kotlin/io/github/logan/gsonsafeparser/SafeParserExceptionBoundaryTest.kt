package io.github.logan.gsonsafeparser

import android.annotation.SuppressLint
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.JsonAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.github.logan.gsonsafeparser.internal.TransportIoContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException

/**
 * Verifies exception boundaries that must not be blurred by SafeParser recovery.
 *
 * Recoverable malformed field values should be isolated locally. Fatal JVM/thread/cancellation
 * signals and caller adapter failures that cannot be safely isolated must escape to the caller.
 */
@OptIn(GsonSafeParserLowLevelApi::class)
class SafeParserExceptionBoundaryTest {
    data class ThreadDeathFieldContainer(
        val value: ThreadDeathFieldValue = ThreadDeathFieldValue()
    )

    data class ShapeThreadDeathFieldContainer(
        val value: ThreadDeathFieldValue? = null
    )

    @JsonAdapter(ThreadDeathFieldValueAdapter::class)
    data class ThreadDeathFieldValue(val text: String = "local")

    data class ShapeCancellationFieldContainer(
        val value: CancellationFieldValue? = null
    )

    @JsonAdapter(CancellationFieldValueAdapter::class)
    data class CancellationFieldValue(val text: String = "local")

    data class LinkageListContainer(
        val values: List<LinkageListValue> = emptyList()
    )

    data class ShapeLinkageListContainer(
        val values: List<LinkageListValue> = emptyList()
    )

    @JsonAdapter(LinkageListValueAdapter::class)
    data class LinkageListValue(val text: String = "local")

    data class CancellationMapKeyContainer(
        val values: Map<CancellationMapKey, String> = emptyMap()
    )

    @JsonAdapter(CancellationMapKeyAdapter::class)
    data class CancellationMapKey(val text: String = "local")

    data class IllegalStateMapKeyContainer(
        val values: Map<IllegalStateMapKey, String> = emptyMap()
    )

    @JsonAdapter(IllegalStateMapKeyAdapter::class)
    data class IllegalStateMapKey(val text: String = "local")

    data class InvocationFatalFieldContainer(
        val value: InvocationFatalValue = InvocationFatalValue()
    )

    @JsonAdapter(InvocationFatalValueAdapter::class)
    data class InvocationFatalValue(val text: String = "local")

    data class IOExceptionFieldContainer(
        val value: IOExceptionValue = IOExceptionValue("default"),
        val next: String = "local"
    )

    data class IOExceptionListContainer(
        val values: List<IOExceptionValue> = emptyList()
    )

    data class IOExceptionMapContainer(
        val values: Map<String, IOExceptionValue> = emptyMap()
    )

    data class ConnectionResetMessageFieldContainer(
        val value: ConnectionResetMessageValue = ConnectionResetMessageValue("default"),
        val next: String = "local"
    )

    @JsonAdapter(IOExceptionValueAdapter::class)
    data class IOExceptionValue(val text: String = "local")

    @JsonAdapter(ConnectionResetMessageValueAdapter::class)
    data class ConnectionResetMessageValue(val text: String = "local")

    data class StreamResetFieldContainer(
        val value: StreamResetValue = StreamResetValue("default"),
        val next: String = "local"
    )

    data class ShapeStreamResetFieldContainer(
        val value: StreamResetValue = StreamResetValue("default"),
        val next: String = "local"
    )

    data class ShapeNestedStreamResetFieldContainer(
        @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        val value: NestedStreamResetValue = NestedStreamResetValue(),
        val next: String = "local"
    )

    data class StreamResetListContainer(
        val values: List<StreamResetValue> = emptyList()
    )

    data class StreamResetArrayContainer(
        val values: Array<StreamResetValue> = emptyArray()
    )

    data class StreamResetMapContainer(
        val values: Map<String, StreamResetValue> = emptyMap()
    )

    @JsonAdapter(StreamResetValueAdapter::class)
    data class StreamResetValue(val text: String = "local")

    data class NestedStreamResetValue(val child: StreamResetValue = StreamResetValue("default"))

    class StreamResetException : IOException("stream was reset: CANCEL")

    data class IllegalStateFieldContainer(
        val value: IllegalStateValue = IllegalStateValue("default")
    )

    @JsonAdapter(IllegalStateValueAdapter::class)
    data class IllegalStateValue(val text: String = "local")

    data class IllegalArgumentArrayContainer(
        val values: Array<IllegalArgumentArrayValue> = emptyArray()
    )

    @JsonAdapter(IllegalArgumentArrayValueAdapter::class)
    data class IllegalArgumentArrayValue(val text: String = "local")

    data class PlainObjectContainer(
        val value: PlainObjectValue = PlainObjectValue(),
        val next: String = "local"
    )

    data class PlainObjectValue(val text: String = "local")

    data class NestedFieldAdapterParent(
        val child: NestedFieldAdapterChild = NestedFieldAdapterChild(),
        val next: String = "local"
    )

    data class NestedFieldAdapterListContainer(
        val values: List<NestedFieldAdapterChild> = emptyList()
    )

    data class NestedFieldAdapterMapContainer(
        val values: Map<String, NestedFieldAdapterChild> = emptyMap()
    )

    data class NestedFieldAdapterArrayContainer(
        val values: Array<NestedFieldAdapterChild> = emptyArray()
    )

    data class DirectListFieldAdapterParent(
        val child: DirectListFieldAdapterChild = DirectListFieldAdapterChild(),
        val next: String = "local"
    )

    data class DirectMapFieldAdapterParent(
        val child: DirectMapFieldAdapterChild = DirectMapFieldAdapterChild(),
        val next: String = "local"
    )

    data class DirectArrayFieldAdapterParent(
        val child: DirectArrayFieldAdapterChild = DirectArrayFieldAdapterChild(),
        val next: String = "local"
    )

    data class DirectListFieldAdapterChild(
        @JsonAdapter(ThrowingListFieldAdapter::class)
        val values: List<NestedThrowingValue> = emptyList(),
        val sibling: String = "local"
    )

    data class DirectMapFieldAdapterChild(
        @JsonAdapter(ThrowingMapFieldAdapter::class)
        val values: Map<String, NestedThrowingValue> = emptyMap(),
        val sibling: String = "local"
    )

    data class DirectArrayFieldAdapterChild(
        @JsonAdapter(ThrowingArrayFieldAdapter::class)
        val values: Array<NestedThrowingValue> = emptyArray(),
        val sibling: String = "local"
    )

    data class NestedFieldAdapterChild(
        @JsonAdapter(NestedThrowingValueAdapter::class)
        val value: NestedThrowingValue = NestedThrowingValue("local"),
        val sibling: String = "local"
    )

    data class NestedThrowingValue(val text: String = "local")

    data class ScalarAdapterListContainer(
        val values: List<ScalarAdapterValue> = emptyList()
    )

    data class ScalarAdapterMapContainer(
        val values: Map<String, ScalarAdapterValue> = emptyMap()
    )

    @JsonAdapter(ScalarAdapterValueAdapter::class)
    data class ScalarAdapterValue(val text: String = "local")

    data class IntListContainer(val values: List<Int> = emptyList())
    data class IntMapContainer(val values: Map<String, Int> = emptyMap())

    data class EnumContainer(
        val value: BoundaryEnum = BoundaryEnum.OK
    )

    data class EnumArrayContainer(
        val values: Array<BoundaryEnum> = emptyArray()
    )

    enum class BoundaryEnum {
        OK
    }

    class ThreadDeathFieldValueAdapter : TypeAdapter<ThreadDeathFieldValue>() {
        override fun write(out: JsonWriter, value: ThreadDeathFieldValue?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): ThreadDeathFieldValue {
            throw ThreadDeath()
        }
    }

    class CancellationFieldValueAdapter : TypeAdapter<CancellationFieldValue>() {
        override fun write(out: JsonWriter, value: CancellationFieldValue?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): CancellationFieldValue {
            throw CancellationException("shape coercion cancelled")
        }
    }

    class LinkageListValueAdapter : TypeAdapter<LinkageListValue>() {
        override fun write(out: JsonWriter, value: LinkageListValue?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): LinkageListValue {
            throw LinkageError("class loading failed")
        }
    }

    class CancellationMapKeyAdapter : TypeAdapter<CancellationMapKey>() {
        override fun write(out: JsonWriter, value: CancellationMapKey?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): CancellationMapKey {
            throw CancellationException("map key parse cancelled")
        }
    }

    class IllegalStateMapKeyAdapter : TypeAdapter<IllegalStateMapKey>() {
        override fun write(out: JsonWriter, value: IllegalStateMapKey?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): IllegalStateMapKey {
            val text = reader.nextString()
            if (text == "bad") {
                throw IllegalStateException("map key adapter failure")
            }
            return IllegalStateMapKey(text)
        }
    }

    class InvocationFatalValueAdapter : TypeAdapter<InvocationFatalValue>() {
        override fun write(out: JsonWriter, value: InvocationFatalValue?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): InvocationFatalValue {
            throw InvocationTargetException(LinkageError("wrapped fatal failure"))
        }
    }

    class IOExceptionValueAdapter : TypeAdapter<IOExceptionValue>() {
        override fun write(out: JsonWriter, value: IOExceptionValue?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): IOExceptionValue {
            val text = reader.nextString()
            if (text == "bad") {
                throw IOException("transient value read failure")
            }
            return IOExceptionValue(text)
        }
    }

    class ConnectionResetMessageValueAdapter : TypeAdapter<ConnectionResetMessageValue>() {
        override fun write(out: JsonWriter, value: ConnectionResetMessageValue?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): ConnectionResetMessageValue {
            val text = reader.nextString()
            if (text == "bad") {
                throw IOException("connection reset by business adapter")
            }
            return ConnectionResetMessageValue(text)
        }
    }

    class StreamResetValueAdapter : TypeAdapter<StreamResetValue>() {
        override fun write(out: JsonWriter, value: StreamResetValue?) {
            out.value(value?.text)
        }

        @SuppressLint("CheckResult")
        override fun read(reader: JsonReader): StreamResetValue {
            reader.nextString()
            throw TransportIoContext.mark(StreamResetException())
        }
    }

    class IllegalStateValueAdapter : TypeAdapter<IllegalStateValue>() {
        override fun write(out: JsonWriter, value: IllegalStateValue?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): IllegalStateValue {
            val text = reader.nextString()
            if (text == "bad") {
                throw IllegalStateException("business value failure")
            }
            return IllegalStateValue(text)
        }
    }

    class IllegalArgumentArrayValueAdapter : TypeAdapter<IllegalArgumentArrayValue>() {
        override fun write(out: JsonWriter, value: IllegalArgumentArrayValue?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): IllegalArgumentArrayValue {
            reader.nextString()
            throw IllegalArgumentException("array item adapter failure")
        }
    }

    class ScalarAdapterValueAdapter : TypeAdapter<ScalarAdapterValue>() {
        override fun write(out: JsonWriter, value: ScalarAdapterValue?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): ScalarAdapterValue {
            return ScalarAdapterValue(reader.nextString())
        }
    }

    class NestedThrowingValueAdapter : TypeAdapter<NestedThrowingValue>() {
        override fun write(out: JsonWriter, value: NestedThrowingValue?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): NestedThrowingValue {
            throw JsonParseException("nested field adapter failed")
        }
    }

    class ThrowingListFieldAdapter : TypeAdapter<List<NestedThrowingValue>>() {
        override fun write(out: JsonWriter, value: List<NestedThrowingValue>?) {
            out.beginArray()
            value.orEmpty().forEach { out.value(it.text) }
            out.endArray()
        }

        override fun read(reader: JsonReader): List<NestedThrowingValue> {
            throw JsonParseException("list field adapter failed")
        }
    }

    class ThrowingMapFieldAdapter : TypeAdapter<Map<String, NestedThrowingValue>>() {
        override fun write(out: JsonWriter, value: Map<String, NestedThrowingValue>?) {
            out.beginObject()
            value.orEmpty().forEach { (key, item) ->
                out.name(key)
                out.value(item.text)
            }
            out.endObject()
        }

        override fun read(reader: JsonReader): Map<String, NestedThrowingValue> {
            throw JsonParseException("map field adapter failed")
        }
    }

    class ThrowingArrayFieldAdapter : TypeAdapter<Array<NestedThrowingValue>>() {
        override fun write(out: JsonWriter, value: Array<NestedThrowingValue>?) {
            out.beginArray()
            value.orEmpty().forEach { out.value(it.text) }
            out.endArray()
        }

        override fun read(reader: JsonReader): Array<NestedThrowingValue> {
            throw JsonParseException("array field adapter failed")
        }
    }

    class CancellationEnumAdapterFactory : TypeAdapterFactory {
        @Suppress("UNCHECKED_CAST")
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            if (type.rawType != BoundaryEnum::class.java) return null
            return object : TypeAdapter<BoundaryEnum>() {
                override fun write(out: JsonWriter, value: BoundaryEnum?) {
                    out.value(value?.name)
                }

                override fun read(reader: JsonReader): BoundaryEnum {
                    throw CancellationException("enum parse cancelled")
                }
            } as TypeAdapter<T>
        }
    }

    class WrappedFatalEnumAdapterFactory : TypeAdapterFactory {
        @Suppress("UNCHECKED_CAST")
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            if (type.rawType != BoundaryEnum::class.java) return null
            return object : TypeAdapter<BoundaryEnum>() {
                override fun write(out: JsonWriter, value: BoundaryEnum?) {
                    out.value(value?.name)
                }

                override fun read(reader: JsonReader): BoundaryEnum {
                    throw JsonParseException(InvocationTargetException(LinkageError("wrapped enum fatal")))
                }
            } as TypeAdapter<T>
        }
    }

    /**
     * A real `ThreadDeath` from a field adapter must escape instead of becoming a field fallback.
     */
    @Test
    fun `field adapter thread death is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(ThreadDeath::class.java) {
            gson.fromJson("""{"value":"remote"}""", ThreadDeathFieldContainer::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A class loading/linkage failure inside a list item adapter must not be treated as one bad item.
     */
    @Test
    fun `list item linkage error is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(LinkageError::class.java) {
            gson.fromJson("""{"values":["remote"]}""", LinkageListContainer::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * Cancellation from a map key adapter is a control-flow signal, not recoverable malformed data.
     */
    @Test
    fun `map key cancellation is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(onTypeMismatch = events::add)
        val gson = GsonSafeParser.create(config)

        assertThrows(CancellationException::class.java) {
            GsonSafeParser.fromJson<CancellationMapKeyContainer>(
                gson = gson,
                json = """{"values":{"remote":"value"}}""",
                type = CancellationMapKeyContainer::class.java,
                config = config
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * Reflection wrappers must be inspected so wrapped fatal causes still escape.
     */
    @Test
    fun `invocation target exception wrapping fatal is rethrown as fatal cause`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(LinkageError::class.java) {
            gson.fromJson("""{"value":"remote"}""", InvocationFatalFieldContainer::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * IOException from a caller-owned field adapter follows native Gson semantics instead of becoming a fallback value.
     */
    @Test
    fun `field adapter io exception is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonSyntaxException::class.java) {
            gson.fromJson(
                """{"value":"bad","next":"remote"}""",
                IOExceptionFieldContainer::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A custom business adapter may use network-like wording in a local IOException; it still follows Gson semantics.
     */
    @Test
    fun `field adapter io exception with connection reset wording is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonSyntaxException::class.java) {
            gson.fromJson(
                """{"value":"bad","next":"remote"}""",
                ConnectionResetMessageFieldContainer::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * IOException from a caller-owned list item adapter follows native Gson semantics.
     */
    @Test
    fun `list item io exception is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"values":["ok","bad","later"]}""",
                IOExceptionListContainer::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * IOException from a caller-owned map value adapter follows native Gson semantics.
     */
    @Test
    fun `map value io exception is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"values":{"ok":"ok","bad":"bad","later":"later"}}""",
                IOExceptionMapContainer::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A caller-owned map key adapter in object form follows native Gson semantics.
     */
    @Test
    fun `map object key json adapter failure is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"values":{"bad":"value"}}""",
                IllegalStateMapKeyContainer::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A caller-owned map key adapter in array-entry form must not be downgraded to one skipped entry.
     */
    @Test
    fun `map array entry key json adapter failure is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"values":[["bad","value"]]}""",
                IllegalStateMapKeyContainer::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A caller-owned field adapter inside a nested object must escape through the parent reflective adapter.
     */
    @Test
    fun `nested field adapter failure in child object is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"child":{"value":"bad","next":"remote"},"next":"remote"}""",
                NestedFieldAdapterParent::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A caller-owned field adapter inside a list item must not be downgraded to one skipped item.
     */
    @Test
    fun `nested field adapter failure in list item is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"values":[{"value":"bad","next":"remote"}]}""",
                NestedFieldAdapterListContainer::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A caller-owned field adapter inside a map value must not be downgraded to one skipped entry.
     */
    @Test
    fun `nested field adapter failure in map value is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"values":{"bad":{"value":"bad","next":"remote"}}}""",
                NestedFieldAdapterMapContainer::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A caller-owned field adapter inside an array item must not be downgraded to an array fallback.
     */
    @Test
    fun `nested field adapter failure in array item is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"values":[{"value":"bad","next":"remote"}]}""",
                NestedFieldAdapterArrayContainer::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A caller-owned array item adapter may throw ordinary RuntimeException; it must not become field fallback.
     */
    @Test
    fun `array item ordinary runtime exception is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"values":["bad"]}""",
                IllegalArgumentArrayContainer::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A caller-owned adapter directly attached to a list field must escape through nested parent adapters.
     */
    @Test
    fun `direct list field adapter failure in nested object is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"child":{"values":[{"text":"bad"}],"sibling":"remote"},"next":"remote"}""",
                DirectListFieldAdapterParent::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A caller-owned adapter directly attached to a map field must not be downgraded to one bad child field.
     */
    @Test
    fun `direct map field adapter failure in nested object is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"child":{"values":{"bad":{"text":"bad"}},"sibling":"remote"},"next":"remote"}""",
                DirectMapFieldAdapterParent::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A caller-owned adapter directly attached to an array field must not be downgraded to array fallback.
     */
    @Test
    fun `direct array field adapter failure in nested object is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonParseException::class.java) {
            gson.fromJson(
                """{"child":{"values":[{"text":"bad"}],"sibling":"remote"},"next":"remote"}""",
                DirectArrayFieldAdapterParent::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A business adapter may use an exception class named StreamResetException; adapter failures still follow Gson semantics.
     */
    @Test
    fun `business adapter stream reset simple name is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonSyntaxException::class.java) {
            gson.fromJson(
                """{"value":"remote","next":"remote"}""",
                StreamResetFieldContainer::class.java
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A marked transport stream reset is not a field-level malformed JSON value.
     */
    @Test
    fun `marked field adapter stream reset is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(onTypeMismatch = events::add)
        val gson = GsonSafeParser.create(config)

        TransportIoContext.withTransportIoMarkers {
            assertThrows(IOException::class.java) {
                GsonSafeParser.fromJson<StreamResetFieldContainer>(
                    gson = gson,
                    json = """{"value":"remote","next":"remote"}""",
                    type = StreamResetFieldContainer::class.java,
                    config = config
                )
            }
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A marked stream reset inside one list item must not be treated as a recoverable bad item.
     */
    @Test
    fun `marked list item stream reset is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(onTypeMismatch = events::add)
        val gson = GsonSafeParser.create(config)

        TransportIoContext.withTransportIoMarkers {
            assertThrows(IOException::class.java) {
                GsonSafeParser.fromJson<StreamResetListContainer>(
                    gson = gson,
                    json = """{"values":["remote"]}""",
                    type = StreamResetListContainer::class.java,
                    config = config
                )
            }
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A marked stream reset inside an object array is still transport I/O, not an array shape fallback.
     */
    @Test
    fun `marked array item stream reset is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(onTypeMismatch = events::add)
        val gson = GsonSafeParser.create(config)

        TransportIoContext.withTransportIoMarkers {
            assertThrows(IOException::class.java) {
                GsonSafeParser.fromJson<StreamResetArrayContainer>(
                    gson = gson,
                    json = """{"values":["remote"]}""",
                    type = StreamResetArrayContainer::class.java,
                    config = config
                )
            }
        }

        assertTrue(events.isEmpty())
    }

    /**
     * Root object arrays use SafeArrayAdapterFactory too, so transport I/O must not be wrapped as JsonIOException.
     */
    @Test
    fun `marked root array stream reset is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(onTypeMismatch = events::add)
        val gson = GsonSafeParser.create(config)

        TransportIoContext.withTransportIoMarkers {
            assertThrows(IOException::class.java) {
                GsonSafeParser.fromJson<Array<StreamResetValue>>(
                    gson = gson,
                    json = """["remote"]""",
                    type = Array<StreamResetValue>::class.java,
                    config = config
                )
            }
        }

        assertTrue(events.isEmpty())
    }

    /**
     * A marked stream reset inside one map value must not be hidden as a malformed map entry.
     */
    @Test
    fun `marked map value stream reset is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(onTypeMismatch = events::add)
        val gson = GsonSafeParser.create(config)

        TransportIoContext.withTransportIoMarkers {
            assertThrows(IOException::class.java) {
                GsonSafeParser.fromJson<StreamResetMapContainer>(
                    gson = gson,
                    json = """{"values":{"remote":"remote"}}""",
                    type = StreamResetMapContainer::class.java,
                    config = config
                )
            }
        }

        assertTrue(events.isEmpty())
    }

    /**
     * Interrupted I/O is a transport/control-flow boundary and must not become a fallback value.
     */
    @Test
    fun `interrupted io is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(onTypeMismatch = events::add)
        val gson = GsonBuilder()
            .registerTypeAdapter(
                StreamResetValue::class.java,
                object : TypeAdapter<StreamResetValue>() {
                    override fun write(out: JsonWriter, value: StreamResetValue?) {
                        out.value(value?.text)
                    }

                    override fun read(reader: JsonReader): StreamResetValue {
                        throw InterruptedIOException("socket timeout")
                    }
                }
            )
            .enableSafeParser(config)
            .create()

        assertThrows(InterruptedIOException::class.java) {
            GsonSafeParser.fromJson<StreamResetFieldContainer>(
                gson = gson,
                json = """{"value":"remote"}""",
                type = StreamResetFieldContainer::class.java,
                config = config
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * Interrupted I/O from an array item must keep the original transport/control-flow signal.
     */
    @Test
    fun `array item interrupted io is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(onTypeMismatch = events::add)
        val gson = GsonBuilder()
            .registerTypeAdapter(
                StreamResetValue::class.java,
                object : TypeAdapter<StreamResetValue>() {
                    override fun write(out: JsonWriter, value: StreamResetValue?) {
                        out.value(value?.text)
                    }

                    override fun read(reader: JsonReader): StreamResetValue {
                        throw InterruptedIOException("socket timeout")
                    }
                }
            )
            .enableSafeParser(config)
            .create()

        assertThrows(InterruptedIOException::class.java) {
            GsonSafeParser.fromJson<StreamResetArrayContainer>(
                gson = gson,
                json = """{"values":["remote"]}""",
                type = StreamResetArrayContainer::class.java,
                config = config
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * Ordinary runtime failures from caller-owned adapters follow native Gson semantics.
     */
    @Test
    fun `field adapter ordinary runtime exception is rethrown without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(JsonSyntaxException::class.java) {
            gson.fromJson("""{"value":"bad"}""", IllegalStateFieldContainer::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * Class-level `@JsonAdapter` may legally read scalar list items even when the raw type is object-like.
     */
    @Test
    fun `list item json adapter can read legal scalar value`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        val result = gson.fromJson("""{"values":["one","two"]}""", ScalarAdapterListContainer::class.java)

        assertEquals(listOf(ScalarAdapterValue("one"), ScalarAdapterValue("two")), result.values)
        assertTrue(events.isEmpty())
    }

    /**
     * Class-level `@JsonAdapter` may legally read scalar map values without SafeParser pre-filtering them.
     */
    @Test
    fun `map value json adapter can read legal scalar value`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        val result = gson.fromJson("""{"values":{"first":"one","second":"two"}}""", ScalarAdapterMapContainer::class.java)

        assertEquals(ScalarAdapterValue("one"), result.values["first"])
        assertEquals(ScalarAdapterValue("two"), result.values["second"])
        assertTrue(events.isEmpty())
    }

    /**
     * Number format failures in list values should skip only the malformed number.
     */
    @Test
    fun `list number format exception skips only invalid number item`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
                onTypeMismatch = events::add
            )
        )

        val result = gson.fromJson("""{"values":["bad",1,2]}""", IntListContainer::class.java)

        assertEquals(listOf(1, 2), result.values)
        assertEquals(ParseExceptionKind.LIST_ITEM, events.single().kind)
    }

    /**
     * Number format failures in map values should skip only the malformed entry.
     */
    @Test
    fun `map number format exception skips only invalid number value`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
                onTypeMismatch = events::add
            )
        )

        val result = gson.fromJson("""{"values":{"bad":"bad","ok":1}}""", IntMapContainer::class.java)

        assertEquals(1, result.values["ok"])
        assertFalse(result.values.containsKey("bad"))
        assertEquals(ParseExceptionKind.MAP_ITEM, events.single().kind)
    }

    /**
     * SafeTypeAdapter wraps delegated adapters too; it must not recover cancellation as IllegalStateException.
     */
    @Test
    fun `safe type adapter delegated cancellation is rethrown without fallback`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(onTypeMismatch = events::add)
        val gson = GsonBuilder()
            .registerTypeAdapterFactory(CancellationEnumAdapterFactory())
            .enableSafeParser(config)
            .create()

        assertThrows(CancellationException::class.java) {
            GsonSafeParser.fromJson<EnumContainer>(
                gson = gson,
                json = """{"value":"OK"}""",
                type = EnumContainer::class.java,
                config = config
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * Direct Gson calls keep Gson's native top-level wrapping, while SafeParser entries unwrap fatal causes.
     */
    @Test
    fun `safe parser entry unwraps cancellation while direct gson keeps native wrapper`() {
        val config = SafeParserConfig()
        val gson = GsonBuilder()
            .registerTypeAdapterFactory(CancellationEnumAdapterFactory())
            .enableSafeParser(config)
            .create()

        val direct = assertThrows(JsonSyntaxException::class.java) {
            gson.fromJson("""{"value":"OK"}""", EnumContainer::class.java)
        }
        assertTrue(direct.cause is CancellationException)

        assertThrows(CancellationException::class.java) {
            GsonSafeParser.fromJson<EnumContainer>(
                gson = gson,
                json = """{"value":"OK"}""",
                type = EnumContainer::class.java,
                config = config
            )
        }
    }

    /**
     * Fatal causes wrapped in JsonParseException from delegated adapters must still escape.
     */
    @Test
    fun `safe type adapter delegated wrapped fatal is rethrown as fatal cause`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonBuilder()
            .registerTypeAdapterFactory(WrappedFatalEnumAdapterFactory())
            .enableSafeParser(SafeParserConfig(onTypeMismatch = events::add))
            .create()

        assertThrows(LinkageError::class.java) {
            gson.fromJson("""{"value":"OK"}""", EnumContainer::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * JsonParseException raised while reading an array item may still wrap a fatal cause.
     */
    @Test
    fun `array item wrapped fatal is rethrown as fatal cause without mismatch event`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(onTypeMismatch = events::add)
        val gson = GsonBuilder()
            .registerTypeAdapterFactory(WrappedFatalEnumAdapterFactory())
            .enableSafeParser(config)
            .create()

        assertThrows(LinkageError::class.java) {
            GsonSafeParser.fromJson<EnumArrayContainer>(
                gson = gson,
                json = """{"values":["OK"]}""",
                type = EnumArrayContainer::class.java,
                config = config
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * parseSafe uses the same event bridge and must not swallow cancellation from observers.
     */
    @Test
    fun `parse safe observer cancellation is rethrown`() {
        assertThrows(CancellationException::class.java) {
            GsonSafeParser.parseSafe<PlainObjectContainer>(
                json = """{"value":[],"next":"remote"}""",
                config = SafeParserConfig(
                    onEvent = {
                        throw CancellationException("event bridge cancelled")
                    }
                )
            )
        }
    }

    /**
     * Shape coercion 只负责对象和数组形态恢复，不能吞掉字段 Adapter 的 fatal 信号。
     */
    @Test
    fun `shape coercion object from array rethrows thread death without event`() {
        val events = mutableListOf<SafeParserEvent>()
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                onEvent = events::add
            ).withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        )

        assertThrows(ThreadDeath::class.java) {
            gson.fromJson("""{"value":[{"text":"remote"}]}""", ShapeThreadDeathFieldContainer::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * Shape coercion 不能把取消信号包装成字段级兜底。
     */
    @Test
    fun `shape coercion object from array rethrows cancellation without event`() {
        val events = mutableListOf<SafeParserEvent>()
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                onEvent = events::add
            ).withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        )

        assertThrows(CancellationException::class.java) {
            GsonSafeParser.fromJson<ShapeCancellationFieldContainer>(
                gson = gson,
                json = """{"value":[{"text":"remote"}]}""",
                type = ShapeCancellationFieldContainer::class.java,
                config = SafeParserConfig(
                    onEvent = events::add
                ).withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 集合字段把对象包装成单元素容器时，元素 Adapter 的 fatal 仍然必须外抛。
     */
    @Test
    fun `shape coercion collection from object rethrows linkage error without event`() {
        val events = mutableListOf<SafeParserEvent>()
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                onEvent = events::add
            ).withShapeCoercionPolicy(ShapeCoercionPolicy.CollectionFromSingleObject)
        )

        assertThrows(LinkageError::class.java) {
            gson.fromJson("""{"values":{"text":"remote"}}""", ShapeLinkageListContainer::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * shape coercion 内部遇到被 Retrofit 标记过的传输 I/O，仍然按不可恢复边界外抛。
     */
    @Test
    fun `shape coercion marked transport io is rethrown without event`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(
            onEvent = events::add
        ).withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        val gson = GsonSafeParser.create(config)

        TransportIoContext.withTransportIoMarkers {
            assertThrows(IOException::class.java) {
                GsonSafeParser.fromJson<ShapeNestedStreamResetFieldContainer>(
                    gson = gson,
                    json = """{"value":[{"child":"remote"}],"next":"remote"}""",
                    type = ShapeNestedStreamResetFieldContainer::class.java,
                    config = config
                )
            }
        }

        assertTrue(events.isEmpty())
    }
}
