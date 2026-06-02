package io.github.logan.gsonsafeparser

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException

/**
 * Verifies exception boundaries that must not be blurred by SafeParser recovery.
 *
 * Recoverable malformed field values should be isolated locally. Fatal JVM/thread/cancellation
 * signals and caller adapter failures that cannot be safely isolated must escape to the caller.
 */
class SafeParserExceptionBoundaryTest {
    data class ThreadDeathFieldContainer(
        val value: ThreadDeathFieldValue = ThreadDeathFieldValue()
    )

    @JsonAdapter(ThreadDeathFieldValueAdapter::class)
    data class ThreadDeathFieldValue(val text: String = "local")

    data class LinkageListContainer(
        val values: List<LinkageListValue> = emptyList()
    )

    @JsonAdapter(LinkageListValueAdapter::class)
    data class LinkageListValue(val text: String = "local")

    data class CancellationMapKeyContainer(
        val values: Map<CancellationMapKey, String> = emptyMap()
    )

    @JsonAdapter(CancellationMapKeyAdapter::class)
    data class CancellationMapKey(val text: String = "local")

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

    @JsonAdapter(IOExceptionValueAdapter::class)
    data class IOExceptionValue(val text: String = "local")

    data class IllegalStateFieldContainer(
        val value: IllegalStateValue = IllegalStateValue("default")
    )

    @JsonAdapter(IllegalStateValueAdapter::class)
    data class IllegalStateValue(val text: String = "local")

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

    class ScalarAdapterValueAdapter : TypeAdapter<ScalarAdapterValue>() {
        override fun write(out: JsonWriter, value: ScalarAdapterValue?) {
            out.value(value?.text)
        }

        override fun read(reader: JsonReader): ScalarAdapterValue {
            return ScalarAdapterValue(reader.nextString())
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
     * IOException from a field adapter is recoverable at field scope and must not block following fields.
     */
    @Test
    fun `field adapter io exception preserves default value and continues object parsing`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        val result = gson.fromJson(
            """{"value":"bad","next":"remote"}""",
            IOExceptionFieldContainer::class.java
        )

        assertEquals(IOExceptionFieldContainer(next = "remote"), result)
        assertEquals("value", events.single().fieldName)
    }

    /**
     * IOException from one list item is isolated so following items remain readable.
     */
    @Test
    fun `list item io exception skips only the failed item`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        val result = gson.fromJson(
            """{"values":["ok","bad","later"]}""",
            IOExceptionListContainer::class.java
        )

        assertEquals(listOf(IOExceptionValue("ok"), IOExceptionValue("later")), result.values)
        assertEquals(ParseExceptionKind.LIST_ITEM, events.single().kind)
    }

    /**
     * IOException from one map value is isolated so following entries remain readable.
     */
    @Test
    fun `map value io exception skips only the failed entry`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        val result = gson.fromJson(
            """{"values":{"ok":"ok","bad":"bad","later":"later"}}""",
            IOExceptionMapContainer::class.java
        )

        assertEquals(IOExceptionValue("ok"), result.values["ok"])
        assertEquals(IOExceptionValue("later"), result.values["later"])
        assertFalse(result.values.containsKey("bad"))
        assertEquals(ParseExceptionKind.MAP_ITEM, events.single().kind)
    }

    /**
     * Ordinary runtime failures remain recoverable when they are safely scoped to one field.
     */
    @Test
    fun `field adapter ordinary runtime exception preserves default value`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        val result = gson.fromJson("""{"value":"bad"}""", IllegalStateFieldContainer::class.java)

        assertEquals(IllegalStateFieldContainer(), result)
        assertEquals("business value failure", events.single().reason)
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
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

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
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

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
     * parseSafe uses the same event bridge and must not swallow cancellation from observers.
     */
    @Test
    fun `parse safe observer cancellation is rethrown`() {
        assertThrows(CancellationException::class.java) {
            GsonSafeParser.parseSafe<IllegalStateFieldContainer>(
                json = """{"value":"bad"}""",
                config = SafeParserConfig(
                    onEvent = {
                        throw CancellationException("event bridge cancelled")
                    }
                )
            )
        }
    }
}
