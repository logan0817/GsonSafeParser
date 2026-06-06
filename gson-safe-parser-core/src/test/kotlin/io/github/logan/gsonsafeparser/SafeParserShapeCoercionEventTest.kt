package io.github.logan.gsonsafeparser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验证 JSON 形态转换事件。
 *
 * 转换不是静默修复。调用方必须能通过事件知道哪个字段发生了 shape coercion。
 */
class SafeParserShapeCoercionEventTest {
    data class UserResponse(val data: User? = null)
    data class UserListResponse(val users: List<User> = emptyList())
    data class User(val id: Long = 0L)

    @Test
    fun `shape coercion emits event with path and action`() {
        val result = GsonSafeParser.parseSafe<UserResponse>(
            """{"data":[{"id":1},{"id":2}]}""",
            SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        )

        val events = result.events.filterIsInstance<SafeParserEvent.ShapeCoercion>()
        val structuredRows = result.contractReport().toStructuredRows()

        assertEquals(2, events.size)
        assertEquals("$.data", events.first().detail.path)
        assertEquals("data", events.first().detail.fieldName)
        assertEquals(ShapeCoercionAction.ObjectFromFirstArrayItem, events.first().detail.action)
        assertEquals(ShapeCoercionAction.ArrayExtraItemsSkipped, events.last().detail.action)
        assertEquals(1, events.last().detail.discardedItemCount)
        assertTrue(structuredRows.any { row ->
            row.fields["shapeCoercionAction"] == "ArrayExtraItemsSkipped" &&
                row.fields["discardedItemCount"] == "1"
        })
    }

    @Test
    fun `contract report includes shape coercion issue`() {
        val result = GsonSafeParser.parseSafe<UserListResponse>(
            """{"users":{"id":1}}""",
            SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.CollectionFromSingleObject)
        )

        val markdown = result.contractReport().toMarkdown()
        val structuredRow = result.contractReport().toStructuredRows().single()

        assertTrue(markdown.contains("ShapeCoercion"))
        assertTrue(markdown.contains("$.users"))
        assertEquals(SafeParseContractIssueCategory.TypeMismatch, structuredRow.category)
        assertEquals("CollectionFromSingleObject", structuredRow.fields["shapeCoercionAction"])
    }

    @Test
    fun `object from array emits failed event when first item is not object`() {
        val result = GsonSafeParser.parseSafe<UserResponse>(
            """{"data":[1]}""",
            SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        )

        val events = result.events.filterIsInstance<SafeParserEvent.ShapeCoercion>()

        assertEquals(null, result.value?.data)
        assertEquals(1, events.size)
        assertEquals(ShapeCoercionAction.CoercionFailed, events.single().detail.action)
        assertTrue(events.single().detail.reason.contains("First array item"))
    }

    @Test
    fun `object from array emits failed event when first item is null`() {
        val result = GsonSafeParser.parseSafe<UserResponse>(
            """{"data":[null]}""",
            SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        )

        val events = result.events.filterIsInstance<SafeParserEvent.ShapeCoercion>()

        assertEquals(null, result.value?.data)
        assertEquals(1, events.size)
        assertEquals(ShapeCoercionAction.CoercionFailed, events.single().detail.action)
        assertTrue(events.single().detail.reason.contains("NULL"))
    }
}
