package io.github.logan.gsonsafeparser

import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Map key 是任意业务数据；不能用邮箱、手机号等启发式规则代替显式输出策略。 */
class SafeParserMapPrivacyTest {
    data class User(
        val name: String = "",
        val profile: Profile? = null,
        val profiles: List<Profile> = emptyList()
    )
    data class Profile(val id: Int = 0)
    data class CharacterValue(val value: Char = 'x')
    data class Envelope(
        val users: Map<String, User> = emptyMap(),
        val groups: Map<String, Map<String, User>> = emptyMap(),
        val characters: Map<String, CharacterValue> = emptyMap(),
        val nestedCharacters: Map<String, Map<String, CharacterValue>> = emptyMap(),
        @SerializedName("customer_42") val ordinaryField: String = ""
    )

    @Test
    fun `omit hides arbitrary keys from direct and nested map mismatch events and reports`() {
        val result = GsonSafeParser.parser(
            SafeParserConfig(mapItemKeyPolicy = MapItemKeyPolicy.Omit)
        ).parseSafe(
            """{"users":{"customer_42":[]},"groups":{"account_42":{"customer_17":[]}}}""",
            Envelope::class.java
        )

        assertEquals(2, result.events.size)
        assertPrivate(result, listOf("customer_42", "account_42", "customer_17"))
        val paths = result.events.map { (it as SafeParserEvent.TypeMismatch).detail.path }
        assertEquals(listOf("$.users.[map-key]", "$.groups.[map-key].[map-key]"), paths)
    }

    @Test
    fun `hash replaces every nested key while preserving ordinary field paths`() {
        val result = GsonSafeParser.parser(
            SafeParserConfig(
                mapItemKeyPolicy = MapItemKeyPolicy.Hash,
                primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
            )
        ).parseSafe(
            """{"groups":{"account_42":{"customer_17":[]}},"customer_42":{}}""",
            Envelope::class.java
        )

        val events = result.events.map { (it as SafeParserEvent.TypeMismatch).detail }
        assertEquals("$.groups.${hash("account_42")}.${hash("customer_17")}", events[0].path)
        assertEquals(hash("customer_17"), events[0].mapItemKey)
        assertEquals("$.customer_42", events[1].path)
        assertEquals("customer_42", events[1].fieldName)
        assertPrivate(result, listOf("account_42", "customer_17"))
    }

    @Test
    fun `map policies hide keys from primitive and shape coercion descendants and callbacks`() {
        listOf(MapItemKeyPolicy.Omit, MapItemKeyPolicy.Hash).forEach { policy ->
            val observed = mutableListOf<SafeParserEvent>()
            val mismatches = mutableListOf<TypeMismatchEvent>()
            val config = SafeParserConfig(
                mapItemKeyPolicy = policy,
                primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
                onEvent = observed::add,
                onTypeMismatch = mismatches::add
            ).withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
            val result = GsonSafeParser.parser(config).parseSafe(
                """{"users":{"customer_42":{"name":{},"profile":[],"profiles":{"id":1}}}}""",
                Envelope::class.java
            )

            assertEquals(3, result.events.size)
            assertEquals(result.events, observed)
            assertEquals(1, mismatches.size)
            assertPrivate(result, listOf("customer_42"))
            assertFalse(mismatches.toString().contains("customer_42"))
        }
    }

    @Test
    fun `shape coercion cannot expose a map key through inferred field name`() {
        listOf(MapItemKeyPolicy.Omit, MapItemKeyPolicy.Hash).forEach { policy ->
            val result = GsonSafeParser.parser(
                SafeParserConfig(mapItemKeyPolicy = policy)
                    .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
            ).parseSafe("""{"users":{"customer_42":[]}}""", Envelope::class.java)

            assertEquals(1, result.events.size)
            assertPrivate(result, listOf("customer_42"))
        }
    }

    @Test
    fun `map keys containing path punctuation or blank text are structurally redacted`() {
        val key = "customer.with[brackets]"
        listOf(MapItemKeyPolicy.Omit, MapItemKeyPolicy.Hash).forEach { policy ->
            val result = GsonSafeParser.parser(
                SafeParserConfig(mapItemKeyPolicy = policy)
            ).parseSafe(
                """{"users":{"$key":[],"":[],"   ":[]}}""",
                Envelope::class.java
            )

            val paths = result.events.map { (it as SafeParserEvent.TypeMismatch).detail.path }
            val replacements = listOf(key, "", "   ").map {
                if (policy == MapItemKeyPolicy.Omit) "[map-key]" else hash(it)
            }
            assertEquals(replacements.map { "$.users.$it" }, paths)
            assertPrivate(result, listOf(key))
        }
    }

    @Test
    fun `plain text policy retains nested map paths and keys`() {
        val result = GsonSafeParser.parser(
            SafeParserConfig(mapItemKeyPolicy = MapItemKeyPolicy.PlainText)
        ).parseSafe(
            """{"groups":{"account_42":{"customer_17":[]}}}""",
            Envelope::class.java
        )

        val event = (result.events.single() as SafeParserEvent.TypeMismatch).detail
        assertEquals("$.groups.account_42.customer_17", event.path)
        assertEquals("customer_17", event.mapItemKey)
    }

    @Test
    fun `root map keys do not escape through inferred container field names`() {
        val result = GsonSafeParser.parser(
            SafeParserConfig(mapItemKeyPolicy = MapItemKeyPolicy.Omit)
        ).parseSafe<Map<String, User>>(
            """{"customer.with[brackets]":[]}""",
            object : TypeToken<Map<String, User>>() {}.type
        )

        assertEquals(1, result.events.size)
        assertPrivate(result, listOf("customer", "brackets"))
    }

    @Test
    fun `observer reentry retains unrelated paths and restores enclosing map context`() {
        var nested: SafeParseResult<Envelope>? = null
        var reentered = false
        val innerParser = GsonSafeParser.parser(
            SafeParserConfig(
                mapItemKeyPolicy = MapItemKeyPolicy.Omit,
                primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
            )
        )
        val outerParser = GsonSafeParser.parser(
            SafeParserConfig(
                mapItemKeyPolicy = MapItemKeyPolicy.Omit,
                onEvent = {
                    if (!reentered) {
                        reentered = true
                        nested = innerParser.parseSafe("""{"customer_42":{}}""", Envelope::class.java)
                    }
                }
            )
        )
        val result = outerParser.parseSafe<Map<String, User>>(
            """{"customer_42":[],"second_customer":[]}""",
            object : TypeToken<Map<String, User>>() {}.type
        )

        assertEquals("$.customer_42", (nested!!.events.single() as SafeParserEvent.TypeMismatch).detail.path)
        assertPrivate(result, listOf("customer_42", "second_customer"))
    }

    @Test
    fun `invalid numeric map key does not leak through failure reason`() {
        listOf(MapItemKeyPolicy.Omit, MapItemKeyPolicy.Hash).forEach { policy ->
            val result = GsonSafeParser.parser(
                SafeParserConfig(mapItemKeyPolicy = policy)
            ).parseSafe<Map<Int, User>>(
                """{"customer_42":{}}""",
                object : TypeToken<Map<Int, User>>() {}.type
            )

            assertPrivate(result, listOf("customer_42"))
        }
    }

    @Test
    fun `character adapter failure reasons redact map paths and nested map paths`() {
        listOf(MapItemKeyPolicy.Omit, MapItemKeyPolicy.Hash).forEach { policy ->
            val result = GsonSafeParser.parser(
                SafeParserConfig(mapItemKeyPolicy = policy)
            ).parseSafe(
                """{"characters":{"customer_42":{"value":"bad"}},"nestedCharacters":{"account_42":{"customer_17":{"value":"bad"}}}}""",
                Envelope::class.java
            )

            val events = result.events.map { (it as SafeParserEvent.TypeMismatch).detail }
            assertEquals(2, events.size)
            assertPrivate(result, listOf("customer_42", "account_42", "customer_17"))
            val firstKey = if (policy == MapItemKeyPolicy.Omit) "[map-key]" else hash("customer_42")
            assertTrue(events[0].reason.contains("$.characters.$firstKey.value"))
            assertEquals("value", events[0].fieldName)
        }
    }

    @Test
    fun `empty parent key is replaced only once in nested failure reason paths`() {
        val result = GsonSafeParser.parser(
            SafeParserConfig(mapItemKeyPolicy = MapItemKeyPolicy.Omit)
        ).parseSafe(
            """{"nestedCharacters":{"":{"customer_42":{"value":"bad"}}}}""",
            Envelope::class.java
        )

        val event = (result.events.single() as SafeParserEvent.TypeMismatch).detail
        assertEquals("$.nestedCharacters.[map-key].[map-key].value", event.path)
        assertTrue(event.reason.contains("$.nestedCharacters.[map-key].[map-key].value"))
        assertFalse(event.reason.contains("[map-key][map-key]"))
        assertPrivate(result, listOf("customer_42"))
    }

    private fun assertPrivate(result: SafeParseResult<*>, keys: List<String>) {
        val outputs = result.events.toString() + result.contractReport().toMarkdown()
        keys.forEach { key -> assertFalse(outputs.contains(key), "Key escaped into observation output: $key") }
        assertTrue(result.events.isNotEmpty())
    }

    private fun hash(value: String): String = "sha256:" +
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
