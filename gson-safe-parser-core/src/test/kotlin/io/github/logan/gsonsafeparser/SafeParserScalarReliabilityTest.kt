package io.github.logan.gsonsafeparser

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal
import java.math.BigInteger

class SafeParserScalarReliabilityTest {
    data class StringPayload(
        val title: String = "local",
        val items: List<String> = emptyList(),
        val labels: Map<String, String> = emptyMap(),
        val names: Array<String> = emptyArray()
    )

    data class NumericPayload(
        val count: Int = 7,
        val total: Long = 9L,
        val decimal: BigDecimal = BigDecimal.TEN,
        val integer: BigInteger = BigInteger.TEN,
        val enabled: Boolean = true,
        val tail: String = "local"
    )

    private val config = SafeParserConfig(
        primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
        fallbackPolicy = FallbackPolicy.Default
    )

    @Test
    fun `boolean strings match native Gson at root and inside containers`() {
        val parser = GsonSafeParser.parser(config)
        for (json in listOf("true", "false")) {
            val result = parser.parseSafe<String>(json)
            assertEquals(Gson().fromJson(json, String::class.java), result.value)
            assertTrue(result.events.isEmpty())
        }

        val result = parser.parseSafe<StringPayload>(
            """{"title":true,"items":[true,false],"labels":{"a":false},"names":[true,false]}"""
        )

        assertEquals("true", result.value?.title)
        assertEquals(listOf("true", "false"), result.value?.items)
        assertEquals(mapOf("a" to "false"), result.value?.labels)
        assertEquals(listOf("true", "false"), result.value?.names?.toList())
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `excessive numeric scales and lengths preserve defaults and following fields`() {
        val parser = GsonSafeParser.parser(config)
        for (number in listOf("1e10000", "1e-10000", "1e2147483648", "9".repeat(10_001))) {
            val result = parser.parseSafe<NumericPayload>(
                """{"count":"$number","total":"$number","decimal":"$number","integer":"$number","enabled":$number,"tail":"remote"}"""
            )

            assertEquals(NumericPayload(tail = "remote"), result.value)
            assertEquals(5, result.events.size)
            val paths = result.events.map { (it as SafeParserEvent.TypeMismatch).detail.path }.toSet()
            assertEquals(setOf("$.count", "$.total", "$.decimal", "$.integer", "$.enabled"), paths)
        }
    }

    @Test
    fun `root numeric failures preserve actual number token`() {
        val parser = GsonSafeParser.parser(config)
        val result = parser.parseSafe<Int>("1.5")

        assertEquals(0, result.value)
        val event = result.events.single() as SafeParserEvent.TypeMismatch
        assertEquals(JsonToken.NUMBER, event.detail.actualToken)
        assertEquals("$", event.detail.path)
    }

    @Test
    fun `root boolean rejects unsupported numeric scale with observable fallback`() {
        val result = GsonSafeParser.parser(config).parseSafe<Boolean>("1e2147483648")

        assertFalse(result.value!!)
        val event = result.events.single() as SafeParserEvent.TypeMismatch
        assertEquals(JsonToken.NUMBER, event.detail.actualToken)
        assertEquals("$", event.detail.path)
    }

    @Test
    fun `numeric boolean reading does not hide reader IO failures`() {
        val failure = IOException("numeric stream failed")
        val reader = object : JsonReader(StringReader("1")) {
            override fun peek(): JsonToken = JsonToken.NUMBER

            override fun nextString(): String = throw failure
        }
        val adapter = GsonSafeParser.create(config).getAdapter(Boolean::class.javaObjectType)

        assertSame(failure, assertThrows(IOException::class.java) { adapter.read(reader) })
    }

    @Test
    fun `supported numeric boundaries still parse exactly`() {
        val result = GsonSafeParser.parser(config).parseSafe<NumericPayload>(
            """{"count":2147483647,"total":9223372036854775807,"decimal":1e9999,"integer":12e2,"enabled":1e0}"""
        )

        assertEquals(Int.MAX_VALUE, result.value?.count)
        assertEquals(Long.MAX_VALUE, result.value?.total)
        assertEquals(BigDecimal("1e9999"), result.value?.decimal)
        assertEquals(BigInteger("1200"), result.value?.integer)
        assertEquals(true, result.value?.enabled)
        assertTrue(result.events.isEmpty())
    }
}
