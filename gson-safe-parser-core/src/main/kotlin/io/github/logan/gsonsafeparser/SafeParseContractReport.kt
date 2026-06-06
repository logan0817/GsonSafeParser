package io.github.logan.gsonsafeparser

import com.google.gson.stream.JsonToken

/**
 * 解析契约报告。
 *
 * 它把 `SafeParseResult.events` 转成更适合日志、CI 和接口复盘阅读的问题清单。
 * 报告生成只消费事件，不重新解析 JSON，也不会影响已经得到的业务对象。
 *
 * @property issues 契约问题列表。一个接口响应里可能同时有多个字段错形。
 */
data class SafeParseContractReport(
    val issues: List<SafeParseContractIssue>
) {
    /**
     * 是否存在接口契约问题。
     *
     * 这里的“问题”不等于解析失败，只表示 SafeParser 观察到了错形、空响应或 rawJson 跳过等现象。
     */
    val hasIssues: Boolean
        get() = issues.isNotEmpty()

    /**
     * 按 JSON path 分组后的问题列表。
     *
     * 用它可以快速定位某个字段是否反复出现错形，例如 `$.data` 或 `$.items[0]`。
     */
    val issuesByPath: Map<String, List<SafeParseContractIssue>>
        get() = issues.filter { it.path != null }.groupBy { it.path!! }

    /**
     * 契约报告汇总信息。
     *
     * 这里给 CI、日志平台和启动期自检使用，避免调用方自己解析 Markdown。
     */
    val summary: SafeParseContractSummary
        get() = SafeParseContractSummary(
            issueCount = issues.size,
            warningCount = issues.count { it.severity == SafeParseContractIssueSeverity.Warning },
            infoCount = issues.count { it.severity == SafeParseContractIssueSeverity.Info },
            issuesByCategory = issues.groupingBy { it.category }.eachCount(),
            issuesBySeverity = issues.groupingBy { it.severity }.eachCount(),
            issuesByPath = issues.mapNotNull { issue -> issue.path }.groupingBy { it }.eachCount(),
            pathlessIssueCount = issues.count { it.path == null },
            backendActionableCount = issues.count { it.backendFixSuggestion != null }
        )

    /**
     * 生成 Markdown 文本。
     *
     * @return 可直接打印到日志、CI 或文档里的报告。
     */
    fun toMarkdown(): String {
        if (issues.isEmpty()) {
            return "No safe parse contract issues."
        }

        return buildString {
            appendLine("# Safe Parse Contract Report")
            issues.forEachIndexed { index, issue ->
                appendLine("${index + 1}. ${issue.category} [${issue.severity}] ${issue.message}")
                issue.details().takeIf { it.isNotEmpty() }?.let { details ->
                    appendLine("   ${details.joinToString(", ") { (key, value) -> "$key=$value" }}")
                }
            }
        }.trimEnd()
    }

    /**
     * 生成给后端或接口负责人的 Markdown 报告。
     *
     * 这个报告只保留契约修复所需的信息，不输出 rawJson、Throwable 或内部 Adapter 细节。
     *
     * @return 可直接贴给接口负责人的契约修复报告。
     */
    fun toBackendMarkdown(): String {
        val backendIssues = issues.filter { it.backendFixSuggestion != null }
        if (backendIssues.isEmpty()) {
            return "No backend JSON contract issues."
        }

        return buildString {
            appendLine("# Backend JSON Contract Report")
            backendIssues.forEachIndexed { index, issue ->
                appendLine("${index + 1}. ${issue.backendFixSuggestion}")
                issue.backendDetails().takeIf { it.isNotEmpty() }?.let { details ->
                    appendLine("   ${details.joinToString(", ") { (key, value) -> "$key=$value" }}")
                }
            }
        }.trimEnd()
    }

    /**
     * 生成机器可消费的结构化行。
     *
     * 这个输出适合写入日志、埋点和 CI 产物。它只包含脱敏字段，不输出 raw JSON、Throwable 或异常栈。
     *
     * @return 每个 issue 对应一行结构化字段。
     */
    fun toStructuredRows(): List<SafeParseContractRow> {
        return issues.map { issue ->
            SafeParseContractRow(
                stableKey = issue.stableKey,
                category = issue.category,
                severity = issue.severity,
                path = issue.path,
                fields = issue.structuredFields()
            )
        }
    }
}

/**
 * 契约报告汇总。
 *
 * @property issueCount 问题总数。
 * @property warningCount warning 级问题数。
 * @property infoCount info 级问题数。
 * @property issuesByCategory 按问题类别统计。
 * @property issuesBySeverity 按严重程度统计。
 * @property issuesByPath 按 JSON path 统计；没有 path 的问题不会进入这里。
 */
data class SafeParseContractSummary(
    val issueCount: Int,
    val warningCount: Int,
    val infoCount: Int,
    val issuesByCategory: Map<SafeParseContractIssueCategory, Int>,
    val issuesBySeverity: Map<SafeParseContractIssueSeverity, Int>,
    val issuesByPath: Map<String, Int>,
    val pathlessIssueCount: Int,
    val backendActionableCount: Int
) {
    /**
     * 是否存在 warning 级问题。
     */
    val hasWarnings: Boolean
        get() = warningCount > 0

    /**
     * 问题总数的明确命名别名，适合日志字段使用。
     */
    val totalIssueCount: Int
        get() = issueCount

    /**
     * 按类别统计的明确命名别名。
     */
    val categoryCounts: Map<SafeParseContractIssueCategory, Int>
        get() = issuesByCategory

    /**
     * 按严重程度统计的明确命名别名。
     */
    val severityCounts: Map<SafeParseContractIssueSeverity, Int>
        get() = issuesBySeverity

    /**
     * 按 JSON path 统计的明确命名别名。
     */
    val pathCounts: Map<String, Int>
        get() = issuesByPath
}

/**
 * 契约报告结构化行。
 *
 * @property stableKey 稳定指纹，用于日志平台聚合同类问题。
 * @property category 问题类别。
 * @property severity 问题等级。
 * @property path JSON path；非字段类事件可能为空。
 * @property fields 脱敏字段表，适合直接写入日志或 CI 产物。
 */
data class SafeParseContractRow(
    val stableKey: String,
    val category: SafeParseContractIssueCategory,
    val severity: SafeParseContractIssueSeverity,
    val path: String?,
    val fields: Map<String, String>
)

/**
 * 单个契约问题。
 *
 * 字段设计偏结构化，方便业务侧上报到日志平台后按 path、类型、事件类别做聚合分析。
 *
 * @property category 问题类别，例如类型错配或 Adapter 创建失败。
 * @property severity 问题等级。当前用于提醒，不直接决定解析是否失败。
 * @property message 给人看的完整说明。
 * @property path JSON 路径，例如 `$.data.profile`。
 * @property fieldName 字段名，方便按业务字段聚合。
 * @property expectedType 代码期望的类型。
 * @property actualToken 实际遇到的 JSON token。
 * @property kind 错配位置类型。
 * @property mapItemKey Map item 错配时对应的 key。
 * @property reason 原始原因说明。
 * @property typeName Adapter、空响应或 rawJson 事件里的目标类型名。
 * @property emptyResponsePolicy 空响应策略，仅空响应事件使用。
 * @property contentLength 响应体长度，仅 rawJson 跳过事件使用。
 * @property maxBytes rawJson 捕获上限，仅 rawJson 跳过事件使用。
 * @property rawJsonTruncated rawJson 是否被截断。
 */
data class SafeParseContractIssue(
    val category: SafeParseContractIssueCategory,
    val severity: SafeParseContractIssueSeverity,
    val message: String,
    val path: String? = null,
    val fieldName: String? = null,
    val expectedType: String? = null,
    val actualToken: String? = null,
    val kind: ParseExceptionKind? = null,
    val mapItemKey: String? = null,
    val reason: String? = null,
    val typeName: String? = null,
    val emptyResponsePolicy: EmptyResponsePolicy? = null,
    val contentLength: Long? = null,
    val maxBytes: Int? = null,
    val rawJsonTruncated: Boolean = false
) {
    /**
     * 后端应该返回的 JSON 形状。
     *
     * 这是从已发布字段推导出的计算属性，避免新增报告字段改变 data class 构造签名。
     */
    val expectedJsonShape: String?
        get() = when (category) {
            SafeParseContractIssueCategory.TypeMismatch -> expectedType?.let { expectedJsonShape(it, kind) }
            else -> null
        }

    /**
     * 实际收到的 JSON 形状。
     */
    val actualJsonShape: String?
        get() = when (category) {
            SafeParseContractIssueCategory.TypeMismatch -> actualToken?.let(::actualJsonShape)
            SafeParseContractIssueCategory.EmptyResponse -> "empty response body"
            else -> null
        }

    /**
     * GsonSafeParser 已经采取的兜底动作。
     */
    val fallbackAction: String?
        get() = when (category) {
            SafeParseContractIssueCategory.TypeMismatch ->
                shapeCoercionAction?.let { action -> shapeCoercionFallbackAction(action, discardedItemCount) }
                    ?: kind?.let(::fallbackAction)
            SafeParseContractIssueCategory.AdapterCreationFailure ->
                "Safe adapter creation failed; parsing falls back to native Gson when available."
            SafeParseContractIssueCategory.EmptyResponse ->
                "Handled the empty Retrofit response with ${emptyResponsePolicy?.name.orEmpty()}."
            SafeParseContractIssueCategory.RawJsonCaptureSkipped ->
                "Skipped raw JSON capture and continued with the normal Retrofit converter path."
        }

    /**
     * 对 Android 客户端的实际影响范围。
     */
    val clientImpact: String?
        get() = when (category) {
            SafeParseContractIssueCategory.TypeMismatch ->
                shapeCoercionAction?.let(::shapeCoercionClientImpact) ?: kind?.let(::clientImpact)
            SafeParseContractIssueCategory.AdapterCreationFailure ->
                "This type may not receive field-level defensive parsing until the adapter issue is fixed."
            SafeParseContractIssueCategory.EmptyResponse ->
                "The Retrofit call receives the configured empty-response value instead of an uncategorized parse failure."
            SafeParseContractIssueCategory.RawJsonCaptureSkipped ->
                "Parsing can continue, but callbacks will not include the original response body for this event."
        }

    /**
     * 给接口负责人的修复建议。
     */
    val backendFixSuggestion: String?
        get() = when (category) {
            SafeParseContractIssueCategory.TypeMismatch -> {
                val location = path ?: return null
                val expectedShape = expectedJsonShape ?: return null
                val actualShape = actualJsonShape ?: return null
                backendFixSuggestion(location, expectedShape, actualShape)
            }
            SafeParseContractIssueCategory.EmptyResponse ->
                "Return a response body that matches ${typeName.orEmpty()}, or document this endpoint as intentionally empty."
            else -> null
        }

    /**
     * shape coercion 动作。它从已发布的 `reason` 字段推导，避免改动 data class 主构造签名。
     */
    val shapeCoercionAction: ShapeCoercionAction?
        get() = shapeCoercionAction(reason)

    /**
     * shape coercion 跳过的额外数组元素数量。它从已发布的 `reason` 字段推导。
     */
    val discardedItemCount: Int
        get() = shapeCoercionDiscardedItemCount(reason)

    /**
     * rawJson 捕获跳过原因，只有 `RawJsonCaptureSkipped` 类问题有值。
     */
    val captureSkipReason: RawJsonCaptureSkipReason?
        get() = when (category) {
            SafeParseContractIssueCategory.RawJsonCaptureSkipped ->
                RawJsonCaptureSkipReason.from(contentLength ?: -1L, reason.orEmpty())
            else -> null
        }

    /**
     * 稳定指纹。
     *
     * 只使用结构化归因字段，不使用 message/reason/rawJson/Throwable，保证同类问题在日志平台中可以稳定聚合。
     */
    val stableKey: String
        get() = listOf(
            "category" to category.name,
            "path" to path.orEmpty(),
            "expectedType" to expectedType.orEmpty(),
            "actualToken" to actualToken.orEmpty(),
            "kind" to kind?.name.orEmpty(),
            "mapItemKey" to mapItemKey.orEmpty(),
            "typeName" to typeName.orEmpty(),
            "emptyResponsePolicy" to emptyResponsePolicy?.name.orEmpty(),
            "captureSkipReason" to captureSkipReason?.name.orEmpty(),
            "shapeCoercionAction" to shapeCoercionAction?.name.orEmpty(),
            "discardedItemCount" to discardedItemCount.toString()
        ).joinToString("|") { (key, value) -> "$key=$value" }
}

/**
 * 契约问题类别。
 */
enum class SafeParseContractIssueCategory {
    TypeMismatch,
    AdapterCreationFailure,
    EmptyResponse,
    RawJsonCaptureSkipped
}

/**
 * 契约问题等级。
 *
 * 当前库只做增强和观测，不替调用方决定是否失败；严重程度用于接入方自行制定 CI 或告警规则。
 */
enum class SafeParseContractIssueSeverity {
    Info,
    Warning
}

/**
 * 从解析结果生成契约报告。
 *
 * 这个扩展函数故意挂在 `SafeParseResult` 上，提醒调用方先完成解析，再把事件转成报告。
 *
 * @return 当前解析结果对应的契约报告。
 */
fun SafeParseResult<*>.contractReport(): SafeParseContractReport {
    return SafeParseContractReport(
        issues = events.mapNotNull { event -> event.toContractIssueOrNull() }
    )
}

/**
 * 把统一事件转换成契约报告问题。
 *
 * @return 当前事件对应的报告条目；不属于契约问题时返回 null。
 */
private fun SafeParserEvent.toContractIssueOrNull(): SafeParseContractIssue? {
    return when (this) {
        is SafeParserEvent.TypeMismatch -> detail.toContractIssue()
        is SafeParserEvent.ShapeCoercion -> detail.toContractIssue()
        is SafeParserEvent.AdapterCreationFailure -> detail.toContractIssue()
        is SafeParserEvent.EmptyResponse -> detail.toContractIssue()
        is SafeParserEvent.RawJsonCaptureSkipped -> detail.toContractIssue()
        else -> null
    }
}

/**
 * 把类型错配事件转换成报告条目。
 *
 * @return 带 path、字段名、期望类型和实际 token 的问题条目。
 */
private fun TypeMismatchEvent.toContractIssue(): SafeParseContractIssue {
    // location 是报告里展示的 JSON 路径；如果 reader 没给路径，就用根路径 `$` 兜底。
    val location = path.takeIf { it.isNotBlank() } ?: "$"
    return SafeParseContractIssue(
        category = SafeParseContractIssueCategory.TypeMismatch,
        severity = SafeParseContractIssueSeverity.Warning,
        message = "Type mismatch at $location: expected $expectedType, actual ${actualToken.name}; $reason",
        path = location,
        fieldName = fieldName,
        expectedType = expectedType,
        actualToken = actualToken.name,
        kind = kind,
        mapItemKey = mapItemKey,
        reason = reason,
        rawJsonTruncated = rawJsonTruncated
    )
}

/**
 * 把 shape coercion 事件转换成报告条目。
 */
private fun ShapeCoercionEvent.toContractIssue(): SafeParseContractIssue {
    val location = path.takeIf { it.isNotBlank() } ?: "$"
    return SafeParseContractIssue(
        category = SafeParseContractIssueCategory.TypeMismatch,
        severity = SafeParseContractIssueSeverity.Warning,
        message = "ShapeCoercion at $location: action=${action.name}, expected $expectedType, actual ${actualToken.name}; $reason",
        path = location,
        fieldName = fieldName,
        expectedType = expectedType,
        actualToken = actualToken.name,
        kind = ParseExceptionKind.OBJECT,
        reason = shapeCoercionReportReason(action, discardedItemCount, reason)
    )
}

/**
 * 把 Adapter 创建失败事件转换成报告条目。
 *
 * @return 用来提醒某个类型没有被 Safe Adapter 接管的问题条目。
 */
private fun AdapterCreationFailureEvent.toContractIssue(): SafeParseContractIssue {
    return SafeParseContractIssue(
        category = SafeParseContractIssueCategory.AdapterCreationFailure,
        severity = SafeParseContractIssueSeverity.Warning,
        message = "Safe adapter creation failed for $typeName: $reason",
        typeName = typeName,
        reason = reason
    )
}

/**
 * 把 Retrofit 空响应事件转换成报告条目。
 *
 * @return 记录目标类型和空响应策略的信息条目。
 */
private fun EmptyResponseEvent.toContractIssue(): SafeParseContractIssue {
    return SafeParseContractIssue(
        category = SafeParseContractIssueCategory.EmptyResponse,
        severity = SafeParseContractIssueSeverity.Info,
        message = "Empty response for $typeName, policy=${policy.name}",
        typeName = typeName,
        emptyResponsePolicy = policy
    )
}

/**
 * 把 rawJson 捕获跳过事件转换成报告条目。
 *
 * @return 记录响应长度、配置上限和跳过原因的问题条目。
 */
private fun RawJsonCaptureSkippedEvent.toContractIssue(): SafeParseContractIssue {
    return SafeParseContractIssue(
        category = SafeParseContractIssueCategory.RawJsonCaptureSkipped,
        severity = SafeParseContractIssueSeverity.Warning,
        message = "Raw JSON capture skipped for $typeName: $reason",
        typeName = typeName,
        contentLength = contentLength,
        maxBytes = maxBytes,
        reason = reason
    )
}

/**
 * 生成单个契约问题的详情字段。
 *
 * @return 固定顺序的 key/value 列表，适合 Markdown 报告展示。
 */
private fun SafeParseContractIssue.details(): List<Pair<String, String>> {
    return buildList {
        // details 只放结构化字段，不放 rawJson 和 Throwable，避免报告里出现敏感响应体或超长堆栈。
        path?.let { add("path" to it) }
        fieldName?.let { add("fieldName" to it) }
        expectedType?.let { add("expectedType" to it) }
        actualToken?.let { add("actualToken" to it) }
        kind?.let { add("kind" to it.name) }
        mapItemKey?.let { add("mapItemKey" to it) }
        typeName?.let { add("typeName" to it) }
        emptyResponsePolicy?.let { add("emptyResponsePolicy" to it.name) }
        contentLength?.let { add("contentLength" to it.toString()) }
        maxBytes?.let { add("maxBytes" to it.toString()) }
        shapeCoercionAction?.let { add("shapeCoercionAction" to it.name) }
        if (discardedItemCount > 0) {
            add("discardedItemCount" to discardedItemCount.toString())
        }
        if (rawJsonTruncated) {
            add("rawJsonTruncated" to "true")
        }
        captureSkipReason?.let { add("captureSkipReason" to it.name) }
        expectedJsonShape?.let { add("expectedJsonShape" to it) }
        actualJsonShape?.let { add("actualJsonShape" to it) }
        fallbackAction?.let { add("fallbackAction" to it) }
        clientImpact?.let { add("clientImpact" to it) }
        backendFixSuggestion?.let { add("backendFixSuggestion" to it) }
    }
}

/**
 * 生成后端报告里的详情字段。
 *
 * @return 面向接口修复的最小字段集合。
 */
private fun SafeParseContractIssue.backendDetails(): List<Pair<String, String>> {
    return buildList {
        path?.let { add("path" to it) }
        fieldName?.let { add("fieldName" to it) }
        expectedJsonShape?.let { add("expected" to it) }
        actualJsonShape?.let { add("actual" to it) }
        fallbackAction?.let { add("fallback" to it) }
        clientImpact?.let { add("clientImpact" to it) }
        mapItemKey?.let { add("mapItemKey" to it) }
        typeName?.let { add("typeName" to it) }
        emptyResponsePolicy?.let { add("emptyResponsePolicy" to it.name) }
        contentLength?.let { add("contentLength" to it.toString()) }
        maxBytes?.let { add("maxBytes" to it.toString()) }
        shapeCoercionAction?.let { add("shapeCoercionAction" to it.name) }
        if (discardedItemCount > 0) {
            add("discardedItemCount" to discardedItemCount.toString())
        }
    }
}

/**
 * 生成结构化日志字段。
 *
 * @return 固定命名的脱敏字段表。
 */
private fun SafeParseContractIssue.structuredFields(): Map<String, String> {
    return buildMap {
        put("stableKey", stableKey)
        put("category", category.name)
        put("severity", severity.name)
        path?.let { put("path", it) }
        fieldName?.let { put("fieldName", it) }
        expectedType?.let { put("expectedType", it) }
        actualToken?.let { put("actualToken", it) }
        kind?.let { put("kind", it.name) }
        mapItemKey?.let { put("mapItemKey", it) }
        typeName?.let { put("typeName", it) }
        emptyResponsePolicy?.let { put("emptyResponsePolicy", it.name) }
        contentLength?.let { put("contentLength", it.toString()) }
        maxBytes?.let { put("maxBytes", it.toString()) }
        shapeCoercionAction?.let { put("shapeCoercionAction", it.name) }
        if (discardedItemCount > 0) {
            put("discardedItemCount", discardedItemCount.toString())
        }
        if (rawJsonTruncated) {
            put("rawJsonTruncated", "true")
        }
        captureSkipReason?.let { put("captureSkipReason", it.name) }
        expectedJsonShape?.let { put("expectedJsonShape", it) }
        actualJsonShape?.let { put("actualJsonShape", it) }
        fallbackAction?.let { put("fallbackAction", it) }
        clientImpact?.let { put("clientImpact", it) }
        backendFixSuggestion?.let { put("backendFixSuggestion", it) }
    }
}

private fun expectedJsonShape(expectedType: String, kind: ParseExceptionKind?): String {
    val erasedName = expectedType.substringBefore('<')
        .substringBefore('[')
        .substringAfterLast('.')
    return when {
        erasedName in setOf("String", "Char", "Character") -> "JSON string"
        erasedName in setOf("Boolean", "boolean") -> "JSON boolean"
        erasedName in setOf(
            "Byte",
            "Short",
            "Int",
            "Integer",
            "Long",
            "Float",
            "Double",
            "BigDecimal",
            "BigInteger",
            "Number"
        ) -> "JSON number"
        erasedName in setOf("List", "Set", "Collection", "Queue", "ArrayList", "LinkedList") ||
            expectedType.contains("[]") -> "JSON array"
        erasedName in setOf("Map", "HashMap", "LinkedHashMap", "SortedMap") -> "JSON object"
        kind == ParseExceptionKind.LIST_ITEM -> "JSON value matching $expectedType"
        kind == ParseExceptionKind.MAP_ITEM -> "JSON value matching $expectedType"
        else -> "JSON object"
    }
}

private fun actualJsonShape(tokenName: String): String {
    return when (tokenName) {
        JsonToken.BEGIN_ARRAY.name, JsonToken.END_ARRAY.name -> "JSON array"
        JsonToken.BEGIN_OBJECT.name, JsonToken.END_OBJECT.name -> "JSON object"
        JsonToken.STRING.name -> "JSON string"
        JsonToken.NUMBER.name -> "JSON number"
        JsonToken.BOOLEAN.name -> "JSON boolean"
        JsonToken.NULL.name -> "JSON null"
        JsonToken.NAME.name -> "JSON property name"
        JsonToken.END_DOCUMENT.name -> "end of JSON document"
        else -> "JSON token $tokenName"
    }
}

private const val SHAPE_COERCION_ACTION_KEY = "shapeCoercionAction="
private const val SHAPE_COERCION_DISCARDED_KEY = "discardedItemCount="
private const val SHAPE_COERCION_REASON_KEY = "reason="

private fun shapeCoercionReportReason(
    action: ShapeCoercionAction,
    discardedItemCount: Int,
    reason: String
): String {
    return "$SHAPE_COERCION_ACTION_KEY${action.name};" +
        "$SHAPE_COERCION_DISCARDED_KEY$discardedItemCount;" +
        "$SHAPE_COERCION_REASON_KEY$reason"
}

private fun shapeCoercionAction(reason: String?): ShapeCoercionAction? {
    val actionName = reason?.substringAfter(SHAPE_COERCION_ACTION_KEY, missingDelimiterValue = "")
        ?.substringBefore(';')
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return ShapeCoercionAction.values().firstOrNull { action -> action.name == actionName }
}

private fun shapeCoercionDiscardedItemCount(reason: String?): Int {
    return reason?.substringAfter(SHAPE_COERCION_DISCARDED_KEY, missingDelimiterValue = "")
        ?.substringBefore(';')
        ?.toIntOrNull()
        ?: 0
}

private fun fallbackAction(kind: ParseExceptionKind): String {
    return when (kind) {
        ParseExceptionKind.OBJECT -> "Skipped the mismatched object value and kept the configured field fallback."
        ParseExceptionKind.LIST_ITEM -> "Skipped the mismatched list item and kept parsing the collection."
        ParseExceptionKind.MAP_ITEM -> "Skipped the mismatched map item and kept parsing the map."
    }
}

private fun shapeCoercionFallbackAction(
    action: ShapeCoercionAction,
    discardedItemCount: Int
): String {
    return when (action) {
        ShapeCoercionAction.ObjectFromFirstArrayItem ->
            "Read the object field from the first array item by explicit shape coercion."
        ShapeCoercionAction.CollectionFromSingleObject ->
            "Wrapped the single JSON object as a one-item collection by explicit shape coercion."
        ShapeCoercionAction.ArrayFromSingleObject ->
            "Wrapped the single JSON object as a one-item array by explicit shape coercion."
        ShapeCoercionAction.EmptyArrayForObjectSkipped ->
            "Empty array cannot provide an object; kept the configured field fallback."
        ShapeCoercionAction.ArrayExtraItemsSkipped ->
            "Skipped $discardedItemCount extra array item(s) after reading the first object."
        ShapeCoercionAction.CoercionFailed ->
            "Shape coercion failed; kept the configured field fallback."
    }
}

private fun clientImpact(kind: ParseExceptionKind): String {
    return when (kind) {
        ParseExceptionKind.OBJECT -> "The outer object can still be parsed; only this field is affected."
        ParseExceptionKind.LIST_ITEM -> "The collection can still be parsed; only this item is affected."
        ParseExceptionKind.MAP_ITEM -> "The map can still be parsed; only this entry is affected."
    }
}

private fun shapeCoercionClientImpact(action: ShapeCoercionAction): String {
    return when (action) {
        ShapeCoercionAction.EmptyArrayForObjectSkipped,
        ShapeCoercionAction.CoercionFailed ->
            "The field kept its configured fallback; backend contract drift is observable."
        else ->
            "The field was parsed by an explicit shape coercion policy; backend contract drift is observable."
    }
}

private fun backendFixSuggestion(
    location: String,
    expectedShape: String,
    actualShape: String
): String {
    return "Return ${article(expectedShape)} $expectedShape at $location instead of ${article(actualShape)} $actualShape."
}

private fun article(shape: String): String {
    return if (shape.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) {
        "an"
    } else {
        "a"
    }
}
