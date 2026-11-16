package io.github.logan.gsonsafeparser

import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** 观察者自己的解析不能获得外层原文，也不能污染外层的事件快照。 */
class SafeParserObserverContextTest {
    data class Child(val id: Int = 0)
    data class Inner(val child: Child = Child())
    data class Outer(val users: Map<String, Child> = emptyMap())

    private enum class Observer { Event, Mismatch, Failure }
    private enum class Entry { Gson, Parser, ParseSafe }

    @Test
    fun `event callback isolates raw json and event collection for all nested entries`() {
        Entry.values().forEach { assertIsolated(Observer.Event, it) }
    }

    @Test
    fun `mismatch callback isolates raw json and event collection for all nested entries`() {
        Entry.values().forEach { assertIsolated(Observer.Mismatch, it) }
    }

    @Test
    fun `failure callback isolates raw json and event collection for all nested entries`() {
        Entry.values().forEach { assertIsolated(Observer.Failure, it) }
    }

    @Test
    fun `ordinary callback failures restore outer contexts before the next entry`() {
        Observer.values().forEach { assertIsolated(it, Entry.Gson, failAfterNestedParse = true) }
    }

    @Test
    fun `nested parse safe can establish its own raw capture and event collector`() {
        val nested = mutableListOf<SafeParseResult<Inner>>()
        val innerParser = GsonSafeParser.parser(SafeParserConfig(captureRawJsonInCallbacks = true))
        val outerParser = GsonSafeParser.parser(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                onEvent = { nested += innerParser.parseSafe(INNER_JSON, Inner::class.java) }
            )
        )

        val result = outerParser.parseSafe(OUTER_JSON, Outer::class.java)

        assertEquals(2, result.events.size)
        assertEquals(2, nested.size)
        nested.forEach { inner ->
            val event = (inner.events.single() as SafeParserEvent.TypeMismatch).detail
            assertEquals("$.child", event.path)
            assertEquals(INNER_JSON, event.rawJson)
        }
        assertOuterEvents(result)
    }

    @Test
    fun `fatal and cancellation callback failures propagate and leave no context behind`() {
        Observer.values().forEach { observer ->
            listOf(CancellationException("cancel observation"), AssertionError("fatal observation")).forEach { failure ->
                val config = SafeParserConfig(
                    captureRawJsonInCallbacks = true,
                    onEvent = {
                        if (observer == Observer.Event) throw failure
                        if (observer == Observer.Failure) throw IllegalStateException("observer failed")
                    },
                    onTypeMismatch = { if (observer == Observer.Mismatch) throw failure },
                    onObserverFailure = { if (observer == Observer.Failure) throw failure }
                )
                if (failure is CancellationException) {
                    val thrown = assertThrows(CancellationException::class.java) {
                        GsonSafeParser.parser(config).parseSafe(OUTER_JSON, Outer::class.java)
                    }
                    assertSame(failure, thrown)
                } else {
                    val thrown = assertThrows(AssertionError::class.java) {
                        GsonSafeParser.parser(config).parseSafe(OUTER_JSON, Outer::class.java)
                    }
                    // Gson 为 AssertionError 增加版本诊断，但必须保留原始致命异常作为 cause。
                    assertSame(failure, thrown.cause)
                }

                val nextEvents = mutableListOf<TypeMismatchEvent>()
                val nextParser = GsonSafeParser.parser(
                    SafeParserConfig(onTypeMismatch = nextEvents::add)
                )
                assertEquals(Inner(), nextParser.gson.fromJson(INNER_JSON, Inner::class.java))
                assertNull(nextEvents.single().rawJson)
                assertEquals("$.child", nextEvents.single().path)
                val nextResult = nextParser.parseSafe(INNER_JSON, Inner::class.java)
                assertEquals(1, nextResult.events.size)
            }
        }
    }

    private fun assertIsolated(
        observer: Observer,
        entry: Entry,
        failAfterNestedParse: Boolean = false
    ) {
        val innerEvents = mutableListOf<TypeMismatchEvent>()
        val nestedResults = mutableListOf<SafeParseResult<Inner>>()
        val innerParser = GsonSafeParser.parser(
            SafeParserConfig(captureRawJsonInCallbacks = false, onTypeMismatch = innerEvents::add)
        )
        val parseInCallback = {
            when (entry) {
                Entry.Gson -> assertEquals(Inner(), innerParser.gson.fromJson(INNER_JSON, Inner::class.java))
                Entry.Parser -> assertEquals(Inner(), innerParser.fromJson(INNER_JSON, Inner::class.java))
                Entry.ParseSafe -> nestedResults += innerParser.parseSafe(INNER_JSON, Inner::class.java)
            }
            if (failAfterNestedParse) throw IllegalStateException("callback failed after parsing")
        }
        val failures = mutableListOf<ObserverFailureEvent>()
        val config = SafeParserConfig(
            captureRawJsonInCallbacks = true,
            onEvent = {
                if (observer == Observer.Event) parseInCallback()
                if (observer == Observer.Failure) throw IllegalStateException("start failure callback")
            },
            onTypeMismatch = { if (observer == Observer.Mismatch) parseInCallback() },
            onObserverFailure = {
                failures += it
                if (observer == Observer.Failure) parseInCallback()
            }
        )

        val result = GsonSafeParser.parser(config).parseSafe(OUTER_JSON, Outer::class.java)

        assertOuterEvents(result)
        assertEquals(2, innerEvents.size)
        innerEvents.forEach {
            assertNull(it.rawJson)
            assertFalse(it.rawJsonTruncated)
            assertEquals("$.child", it.path)
        }
        if (entry == Entry.ParseSafe) {
            assertEquals(2, nestedResults.size)
            nestedResults.forEach { assertEquals(1, it.events.size) }
        }
        assertEquals(if (observer == Observer.Failure || failAfterNestedParse) 2 else 0, failures.size)
        failures.forEach {
            assertEquals(OUTER_JSON, (it.sourceEvent as SafeParserEvent.TypeMismatch).detail.rawJson)
        }
    }

    private fun assertOuterEvents(result: SafeParseResult<Outer>) {
        assertEquals(2, result.events.size)
        result.events.forEach {
            val event = (it as SafeParserEvent.TypeMismatch).detail
            assertEquals("$.users.[map-key]", event.path)
            assertEquals(OUTER_JSON, event.rawJson)
            assertNull(event.mapItemKey)
        }
    }

    private companion object {
        const val INNER_JSON = """{"child":[]}"""
        const val OUTER_JSON = """{"users":{"first_customer":[],"second_customer":[]},"privateBody":"outer-only"}"""
    }
}
