package io.github.logan.gsonsafeparser

import com.google.gson.stream.JsonToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验证契约报告。
 *
 * contractReport 只消费 SafeParseResult 里的事件快照，不能重新解析 JSON，
 * 也不能把 Adapter 创建失败里的 Throwable 链路直接暴露给文档或日志。
 */
class SafeParseContractReportTest {
    /** 测试模型：契约报告用的外层响应。 */
    data class ApiResponse(val data: User = User())
    /** 测试模型：data 字段里的业务对象，默认值用于验证报告不改变解析结果。 */
    data class User(val id: Long = 0L)
    /** 测试模型：用于验证集合和 Map 的真实解析事件也能生成后端修复建议。 */
    data class MatrixResponse(
        val users: List<User> = emptyList(),
        val profiles: Map<String, User> = emptyMap()
    )

    /**
     * 测试方法说明：验证“contract report summarizes type mismatch events without changing parsed value”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `contract report summarizes type mismatch events without changing parsed value`() {
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = GsonSafeParser.parseSafe<ApiResponse>("""{"data":[]}""")

        // report 是根据事件生成的可读报告，用来验证观测结果是否完整。
        val report = result.contractReport()

        assertEquals(ApiResponse(User()), result.value)
        assertTrue(report.hasIssues)
        assertEquals(1, report.issues.size)

        val issue = report.issues.single()
        assertEquals(SafeParseContractIssueCategory.TypeMismatch, issue.category)
        assertEquals(SafeParseContractIssueSeverity.Warning, issue.severity)
        assertEquals("$.data", issue.path)
        assertEquals("data", issue.fieldName)
        assertEquals(User::class.java.name, issue.expectedType)
        assertEquals(JsonToken.BEGIN_ARRAY.name, issue.actualToken)
        assertEquals(ParseExceptionKind.OBJECT, issue.kind)
        assertTrue(issue.message.contains("$.data"))
        assertTrue(issue.message.contains("BEGIN_ARRAY"))
        assertTrue(report.issuesByPath.getValue("$.data").contains(issue))
        assertTrue(report.toMarkdown().contains("$.data"))
    }

    /**
     * 测试方法说明：验证“contract report gives backend readable evidence and fix suggestion”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `contract report gives backend readable evidence and fix suggestion`() {
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = GsonSafeParser.parseSafe<ApiResponse>("""{"data":[]}""")

        // issue 是给开发、后端和 CI 消费的结构化证据。
        val issue = result.contractReport().issues.single()

        assertEquals("JSON object", issue.expectedJsonShape)
        assertEquals("JSON array", issue.actualJsonShape)
        assertEquals("Skipped the mismatched object value and kept the configured field fallback.", issue.fallbackAction)
        assertEquals("The outer object can still be parsed; only this field is affected.", issue.clientImpact)
        assertEquals("Return a JSON object at $.data instead of a JSON array.", issue.backendFixSuggestion)

        val backendMarkdown = result.contractReport().toBackendMarkdown()

        assertTrue(backendMarkdown.contains("# Backend JSON Contract Report"))
        assertTrue(backendMarkdown.contains("$.data"))
        assertTrue(backendMarkdown.contains("Return a JSON object at $.data instead of a JSON array."))
        assertFalse(backendMarkdown.contains("rawJson"))
        assertFalse(backendMarkdown.contains("Throwable"))
    }

    @Test
    fun `contract report describes shape coercion success and fallback actions separately`() {
        val result = SafeParseResult<Unit>(
            value = Unit,
            events = listOf(
                SafeParserEvent.ShapeCoercion(
                    ShapeCoercionEvent(
                        expectedType = User::class.java.name,
                        actualToken = JsonToken.BEGIN_ARRAY,
                        path = "$.data",
                        action = ShapeCoercionAction.ObjectFromFirstArrayItem,
                        fieldName = "data"
                    )
                ),
                SafeParserEvent.ShapeCoercion(
                    ShapeCoercionEvent(
                        expectedType = User::class.java.name,
                        actualToken = JsonToken.BEGIN_ARRAY,
                        path = "$.data",
                        action = ShapeCoercionAction.ArrayExtraItemsSkipped,
                        fieldName = "data",
                        discardedItemCount = 2
                    )
                ),
                SafeParserEvent.ShapeCoercion(
                    ShapeCoercionEvent(
                        expectedType = User::class.java.name,
                        actualToken = JsonToken.BEGIN_ARRAY,
                        path = "$.empty",
                        action = ShapeCoercionAction.EmptyArrayForObjectSkipped,
                        fieldName = "empty"
                    )
                ),
                SafeParserEvent.ShapeCoercion(
                    ShapeCoercionEvent(
                        expectedType = User::class.java.name,
                        actualToken = JsonToken.BEGIN_ARRAY,
                        path = "$.bad",
                        action = ShapeCoercionAction.CoercionFailed,
                        fieldName = "bad",
                        reason = "First array item is NUMBER"
                    )
                )
            )
        )

        val issues = result.contractReport().issues

        assertEquals(
            "Read the object field from the first array item by explicit shape coercion.",
            issues[0].fallbackAction
        )
        assertEquals(
            "Skipped 2 extra array item(s) after reading the first object.",
            issues[1].fallbackAction
        )
        assertEquals(
            "Empty array cannot provide an object; kept the configured field fallback.",
            issues[2].fallbackAction
        )
        assertEquals(
            "Shape coercion failed; kept the configured field fallback.",
            issues[3].fallbackAction
        )
        assertTrue(issues[1].stableKey.contains("shapeCoercionAction=ArrayExtraItemsSkipped"))
        assertTrue(issues[1].stableKey.contains("discardedItemCount=2"))
    }

    /**
     * 测试方法说明：验证“contract report infers expected json shapes from generic collection and map types”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `contract report infers expected json shapes from generic collection and map types`() {
        // result 是手动构造的事件结果，用来锁定契约报告的泛型类型形状推导。
        val result = SafeParseResult<Unit>(
            value = Unit,
            events = listOf(
                SafeParserEvent.TypeMismatch(
                    TypeMismatchEvent(
                        expectedType = "java.util.List<com.example.User>",
                        actualToken = JsonToken.BEGIN_OBJECT,
                        path = "$.users",
                        reason = "Expected array",
                        kind = ParseExceptionKind.OBJECT,
                        fieldName = "users"
                    )
                ),
                SafeParserEvent.TypeMismatch(
                    TypeMismatchEvent(
                        expectedType = "java.util.Map<java.lang.String, com.example.User>",
                        actualToken = JsonToken.BEGIN_ARRAY,
                        path = "$.profiles",
                        reason = "Expected object",
                        kind = ParseExceptionKind.OBJECT,
                        fieldName = "profiles"
                    )
                )
            )
        )

        val issues = result.contractReport().issues

        assertEquals("JSON array", issues[0].expectedJsonShape)
        assertEquals("Return a JSON array at $.users instead of a JSON object.", issues[0].backendFixSuggestion)
        assertEquals("JSON object", issues[1].expectedJsonShape)
        assertEquals("Return a JSON object at $.profiles instead of a JSON array.", issues[1].backendFixSuggestion)
    }

    /**
     * 测试方法说明：验证“contract report keeps backend suggestions for real collection and map mismatches”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `contract report keeps backend suggestions for real collection and map mismatches`() {
        // result 是真实解析产生的事件结果，避免只验证手写事件。
        val result = GsonSafeParser.parseSafe<MatrixResponse>("""{"users":{},"profiles":""}""")

        val issuesByPath = result.contractReport().issuesByPath

        assertEquals(emptyList<User>(), result.value?.users)
        assertEquals(emptyMap<String, User>(), result.value?.profiles)
        assertEquals("JSON array", issuesByPath.getValue("$.users").single().expectedJsonShape)
        assertEquals("JSON object", issuesByPath.getValue("$.profiles").single().expectedJsonShape)
        assertEquals(
            "Return a JSON array at $.users instead of a JSON object.",
            issuesByPath.getValue("$.users").single().backendFixSuggestion
        )
        assertEquals(
            "Return a JSON object at $.profiles instead of a JSON string.",
            issuesByPath.getValue("$.profiles").single().backendFixSuggestion
        )
    }

    /**
     * 测试方法说明：验证“contract report summary exposes ci friendly counts and groups”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `contract report summary exposes ci friendly counts and groups`() {
        // report 是由多种事件生成的契约报告，用来验证 CI 和日志聚合所需的统计字段。
        val report = mixedEventReport()

        val summary = report.summary

        assertEquals(4, summary.issueCount)
        assertEquals(4, summary.totalIssueCount)
        assertEquals(3, summary.warningCount)
        assertEquals(1, summary.infoCount)
        assertEquals(3, summary.pathlessIssueCount)
        assertEquals(2, summary.backendActionableCount)
        assertTrue(summary.hasWarnings)
        assertEquals(1, summary.issuesByCategory.getValue(SafeParseContractIssueCategory.TypeMismatch))
        assertEquals(summary.issuesByCategory, summary.categoryCounts)
        assertEquals(1, summary.issuesByCategory.getValue(SafeParseContractIssueCategory.AdapterCreationFailure))
        assertEquals(1, summary.issuesByCategory.getValue(SafeParseContractIssueCategory.EmptyResponse))
        assertEquals(1, summary.issuesByCategory.getValue(SafeParseContractIssueCategory.RawJsonCaptureSkipped))
        assertEquals(3, summary.issuesBySeverity.getValue(SafeParseContractIssueSeverity.Warning))
        assertEquals(summary.issuesBySeverity, summary.severityCounts)
        assertEquals(1, summary.issuesBySeverity.getValue(SafeParseContractIssueSeverity.Info))
        assertEquals(1, summary.issuesByPath.getValue("$.data"))
        assertEquals(summary.issuesByPath, summary.pathCounts)
    }

    /**
     * 测试方法说明：验证“contract issue stable key groups same contract problems and separates different ones”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `contract issue stable key groups same contract problems and separates different ones`() {
        // sameA/sameB 表示同一类线上错形，stableKey 应该一致，方便日志平台聚合。
        val sameA = typeMismatchIssue(path = "$.data", token = JsonToken.BEGIN_ARRAY)
        val sameB = typeMismatchIssue(path = "$.data", token = JsonToken.BEGIN_ARRAY)
        val differentPath = typeMismatchIssue(path = "$.profile", token = JsonToken.BEGIN_ARRAY)
        val differentToken = typeMismatchIssue(path = "$.data", token = JsonToken.STRING)

        assertEquals(sameA.stableKey, sameB.stableKey)
        assertTrue(sameA.stableKey.contains("category=TypeMismatch"))
        assertTrue(sameA.stableKey.contains("path=$.data"))
        assertTrue(sameA.stableKey.contains("actualToken=BEGIN_ARRAY"))
        assertTrue(sameA.stableKey.contains("kind=OBJECT"))
        assertTrue(sameA.stableKey != differentPath.stableKey)
        assertTrue(sameA.stableKey != differentToken.stableKey)
    }

    /**
     * 测试方法说明：验证“contract report structured rows expose safe log fields without raw json or throwable details”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `contract report structured rows expose safe log fields without raw json or throwable details`() {
        // report 是由多种事件生成的契约报告，用来验证机器可消费字段。
        val report = mixedEventReport()

        val rows = report.toStructuredRows()

        assertEquals(4, rows.size)
        assertEquals(report.issues.first().stableKey, rows.first().stableKey)
        assertEquals(SafeParseContractIssueCategory.TypeMismatch, rows.first().category)
        assertEquals(SafeParseContractIssueSeverity.Warning, rows.first().severity)
        assertEquals("$.data", rows.first().path)
        assertEquals("JSON object", rows.first().fields.getValue("expectedJsonShape"))
        assertEquals("JSON array", rows.first().fields.getValue("actualJsonShape"))
        assertEquals("Return a JSON object at $.data instead of a JSON array.", rows.first().fields.getValue("backendFixSuggestion"))
        assertEquals(
            RawJsonCaptureSkipReason.ContentLengthExceedsLimit.name,
            rows.last().fields.getValue("captureSkipReason")
        )

        val allFields = rows.flatMap { row -> row.fields.entries }
        assertTrue(rows.all { row -> "message" !in row.fields })
        assertTrue(rows.all { row -> "reason" !in row.fields })
        assertTrue(allFields.none { (key, value) -> key.contains("rawJson") || value.contains("rawJson") })
        assertTrue(allFields.none { (_, value) -> value.contains("internal stack detail") })
        assertTrue(allFields.none { (_, value) -> value.contains("Throwable") })
    }

    /**
     * 测试方法说明：验证“contract report has no issues when safe parse has no events”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `contract report has no issues when safe parse has no events`() {
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = GsonSafeParser.parseSafe<ApiResponse>("""{"data":{"id":8}}""")

        // report 是根据事件生成的可读报告，用来验证观测结果是否完整。
        val report = result.contractReport()

        assertEquals(ApiResponse(User(8L)), result.value)
        assertFalse(report.hasIssues)
        assertTrue(report.issues.isEmpty())
        assertTrue(report.issuesByPath.isEmpty())
    }

    /**
     * 测试方法说明：验证“contract report keeps list and map mismatch metadata”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `contract report keeps list and map mismatch metadata`() {
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = SafeParseResult<Unit>(
            value = Unit,
            events = listOf(
                SafeParserEvent.TypeMismatch(
                    TypeMismatchEvent(
                        expectedType = "kotlin.Int",
                        actualToken = JsonToken.BEGIN_OBJECT,
                        path = "$.users[0].id",
                        reason = "Expected number",
                        kind = ParseExceptionKind.LIST_ITEM,
                        fieldName = "users"
                    )
                ),
                SafeParserEvent.TypeMismatch(
                    TypeMismatchEvent(
                        expectedType = "com.example.Profile",
                        actualToken = JsonToken.BEGIN_ARRAY,
                        path = "$.profiles.bad",
                        reason = "Unexpected JSON token",
                        kind = ParseExceptionKind.MAP_ITEM,
                        fieldName = "profiles",
                        mapItemKey = "bad"
                    )
                )
            )
        )

        // report 是根据事件生成的可读报告，用来验证观测结果是否完整。
        val report = result.contractReport()

        assertEquals(2, report.issues.size)
        assertEquals(ParseExceptionKind.LIST_ITEM, report.issues[0].kind)
        assertEquals("users", report.issues[0].fieldName)
        assertNull(report.issues[0].mapItemKey)
        assertEquals(ParseExceptionKind.MAP_ITEM, report.issues[1].kind)
        assertEquals("profiles", report.issues[1].fieldName)
        assertEquals("bad", report.issues[1].mapItemKey)
    }

    @Test
    fun `contract report redacts sensitive plain map item keys from outputs`() {
        val sensitiveKey = "user@example.com"
        val result = SafeParseResult<Unit>(
            value = Unit,
            events = listOf(
                SafeParserEvent.TypeMismatch(
                    TypeMismatchEvent(
                        expectedType = "com.example.Profile",
                        actualToken = JsonToken.BEGIN_ARRAY,
                        path = "$.profiles.$sensitiveKey",
                        reason = "Unexpected JSON token",
                        kind = ParseExceptionKind.MAP_ITEM,
                        fieldName = "profiles",
                        mapItemKey = sensitiveKey
                    )
                )
            )
        )

        val report = result.contractReport()
        val issue = report.issues.single()
        val markdown = report.toMarkdown()
        val backendMarkdown = report.toBackendMarkdown()
        val structuredFields = report.toStructuredRows().single().fields

        assertFalse(issue.stableKey.contains(sensitiveKey))
        assertFalse(markdown.contains(sensitiveKey))
        assertFalse(backendMarkdown.contains(sensitiveKey))
        assertTrue(structuredFields.values.none { value -> value.contains(sensitiveKey) })
        assertTrue(issue.mapItemKey?.startsWith("sha256:") == true)
        assertTrue(structuredFields.getValue("mapItemKey").startsWith("sha256:"))
    }

    @Test
    fun `contract report redacts only trailing map item key in path`() {
        val result = SafeParseResult<Unit>(
            value = Unit,
            events = listOf(
                SafeParserEvent.TypeMismatch(
                    TypeMismatchEvent(
                        expectedType = "com.example.Profile",
                        actualToken = JsonToken.BEGIN_ARRAY,
                        path = "$.tokens.token",
                        reason = "Unexpected JSON token",
                        kind = ParseExceptionKind.MAP_ITEM,
                        fieldName = "tokens",
                        mapItemKey = "token"
                    )
                )
            )
        )

        val issue = result.contractReport().issues.single()

        assertEquals("$.tokens", issue.path?.substringBeforeLast('.'))
        assertTrue(issue.path?.substringAfterLast('.')?.startsWith("sha256:") == true)
        assertTrue(issue.mapItemKey?.startsWith("sha256:") == true)
    }

    @Test
    fun `contract report redacts sensitive shape coercion path segments`() {
        val sensitiveKey = "user@example.com"
        val result = SafeParseResult<Unit>(
            value = Unit,
            events = listOf(
                SafeParserEvent.ShapeCoercion(
                    ShapeCoercionEvent(
                        expectedType = "com.example.Profile",
                        actualToken = JsonToken.BEGIN_ARRAY,
                        path = "$.profiles.$sensitiveKey",
                        action = ShapeCoercionAction.ObjectFromFirstArrayItem
                    )
                )
            )
        )

        val report = result.contractReport()
        val issue = report.issues.single()
        val markdown = report.toMarkdown()
        val backendMarkdown = report.toBackendMarkdown()
        val structuredFields = report.toStructuredRows().single().fields

        assertFalse(issue.stableKey.contains(sensitiveKey))
        assertFalse(issue.path.orEmpty().contains(sensitiveKey))
        assertFalse(markdown.contains(sensitiveKey))
        assertFalse(backendMarkdown.contains(sensitiveKey))
        assertTrue(structuredFields.values.none { value -> value.contains(sensitiveKey) })
        assertTrue(issue.path?.substringAfterLast('.')?.startsWith("sha256:") == true)
    }

    /**
     * 测试方法说明：验证“contract report maps non parsing events without exposing throwable”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `contract report maps non parsing events without exposing throwable`() {
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = SafeParseResult<Unit>(
            value = Unit,
            events = listOf(
                SafeParserEvent.AdapterCreationFailure(
                    AdapterCreationFailureEvent(
                        typeName = "com.example.Broken",
                        reason = "Duplicate JSON field name",
                        error = IllegalArgumentException("internal stack detail")
                    )
                ),
                SafeParserEvent.EmptyResponse(
                    EmptyResponseEvent(
                        typeName = "com.example.ApiResponse",
                        policy = EmptyResponsePolicy.DefaultValue
                    )
                ),
                SafeParserEvent.RawJsonCaptureSkipped(
                    RawJsonCaptureSkippedEvent(
                        typeName = "com.example.ApiResponse",
                        contentLength = 2048,
                        maxBytes = 1024,
                        reason = "Response body is too large"
                    )
                )
            )
        )

        // report 是根据事件生成的可读报告，用来验证观测结果是否完整。
        val report = result.contractReport()

        assertEquals(3, report.issues.size)
        assertEquals(SafeParseContractIssueCategory.AdapterCreationFailure, report.issues[0].category)
        assertEquals(SafeParseContractIssueSeverity.Warning, report.issues[0].severity)
        assertEquals("com.example.Broken", report.issues[0].typeName)
        assertEquals("Duplicate JSON field name", report.issues[0].reason)
        assertFalse(report.toMarkdown().contains("internal stack detail"))

        assertEquals(SafeParseContractIssueCategory.EmptyResponse, report.issues[1].category)
        assertEquals(SafeParseContractIssueSeverity.Info, report.issues[1].severity)
        assertEquals(EmptyResponsePolicy.DefaultValue, report.issues[1].emptyResponsePolicy)

        assertEquals(SafeParseContractIssueCategory.RawJsonCaptureSkipped, report.issues[2].category)
        assertEquals(2048, report.issues[2].contentLength)
        assertEquals(1024, report.issues[2].maxBytes)
        assertEquals(RawJsonCaptureSkipReason.ContentLengthExceedsLimit, report.issues[2].captureSkipReason)

        val backendMarkdown = report.toBackendMarkdown()
        assertTrue(backendMarkdown.contains("Return a response body that matches com.example.ApiResponse"))
        assertFalse(backendMarkdown.contains("Safe adapter creation failed"))
        assertFalse(backendMarkdown.contains("Raw JSON capture skipped"))
        assertFalse(backendMarkdown.contains("com.example.Broken"))
    }

    /**
     * 测试方法说明：验证“backend report ignores non backend actionable observer issues”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `backend report ignores non backend actionable observer issues`() {
        // result 只包含客户端观测类事件，不应该生成后端修接口报告。
        val result = SafeParseResult<Unit>(
            value = Unit,
            events = listOf(
                SafeParserEvent.AdapterCreationFailure(
                    AdapterCreationFailureEvent(
                        typeName = "com.example.Broken",
                        reason = "Duplicate JSON field name",
                        error = IllegalArgumentException("internal stack detail")
                    )
                ),
                SafeParserEvent.RawJsonCaptureSkipped(
                    RawJsonCaptureSkippedEvent(
                        typeName = "com.example.ApiResponse",
                        contentLength = 2048,
                        maxBytes = 1024,
                        reason = "Response body is too large"
                    )
                )
            )
        )

        assertEquals("No backend JSON contract issues.", result.contractReport().toBackendMarkdown())
    }

    private fun mixedEventReport(): SafeParseContractReport {
        return SafeParseResult<Unit>(
            value = Unit,
            events = listOf(
                SafeParserEvent.TypeMismatch(
                    TypeMismatchEvent(
                        expectedType = User::class.java.name,
                        actualToken = JsonToken.BEGIN_ARRAY,
                        path = "$.data",
                        reason = "Unexpected JSON token",
                        kind = ParseExceptionKind.OBJECT,
                        fieldName = "data",
                        rawJson = """{"data":[]}"""
                    )
                ),
                SafeParserEvent.AdapterCreationFailure(
                    AdapterCreationFailureEvent(
                        typeName = "com.example.Broken",
                        reason = "Duplicate JSON field name",
                        error = IllegalArgumentException("internal stack detail")
                    )
                ),
                SafeParserEvent.EmptyResponse(
                    EmptyResponseEvent(
                        typeName = "com.example.ApiResponse",
                        policy = EmptyResponsePolicy.DefaultValue
                    )
                ),
                SafeParserEvent.RawJsonCaptureSkipped(
                    RawJsonCaptureSkippedEvent(
                        typeName = "com.example.ApiResponse",
                        contentLength = 2048,
                        maxBytes = 1024,
                        reason = "Response body is too large"
                    )
                )
            )
        ).contractReport()
    }

    private fun typeMismatchIssue(path: String, token: JsonToken): SafeParseContractIssue {
        return SafeParseResult<Unit>(
            value = Unit,
            events = listOf(
                SafeParserEvent.TypeMismatch(
                    TypeMismatchEvent(
                        expectedType = User::class.java.name,
                        actualToken = token,
                        path = path,
                        reason = "Unexpected JSON token",
                        kind = ParseExceptionKind.OBJECT,
                        fieldName = path.substringAfterLast('.')
                    )
                )
            )
        ).contractReport().issues.single()
    }
}
