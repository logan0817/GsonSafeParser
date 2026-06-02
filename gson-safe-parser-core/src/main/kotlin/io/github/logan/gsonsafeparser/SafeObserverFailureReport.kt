package io.github.logan.gsonsafeparser

/**
 * 观察者失败报告。
 *
 * 这里记录的是回调本身失败，例如日志 SDK、埋点 SDK 或业务上报代码抛异常。
 * 它和解析错配报告分开，是为了避免“观察链路坏了”被误认为“JSON 解析坏了”。
 *
 * @property failures 观察者失败列表。一个解析事件可能触发多个回调失败。
 */
data class SafeObserverFailureReport(
    val failures: List<SafeObserverFailureIssue>
) {
    /**
     * 是否真的发生过观察者失败。
     *
     * 调用方生成报告前可以先看这个值，避免没有问题时还打印一大段空报告。
     */
    val hasFailures: Boolean
        get() = failures.isNotEmpty()

    /**
     * 按回调名称分组后的失败列表。
     *
     * 例如可以快速看出是 `onEvent` 坏了，还是兼容回调 `onTypeMismatch` 坏了。
     */
    val failuresByCallback: Map<String, List<SafeObserverFailureIssue>>
        get() = failures.groupBy { it.callbackName }

    /**
     * 生成 Markdown 文本。
     *
     * @return 可读报告，适合放进日志、CI 产物或问题复盘里。
     */
    fun toMarkdown(): String {
        if (failures.isEmpty()) {
            return "No safe parser observer failures."
        }

        return buildString {
            appendLine("# Safe Parser Observer Failure Report")
            failures.forEachIndexed { index, failure ->
                appendLine("${index + 1}. ${failure.callbackName} failed while handling ${failure.eventName}")
                failure.details().takeIf { it.isNotEmpty() }?.let { details ->
                    appendLine("   ${details.joinToString(", ") { (key, value) -> "$key=$value" }}")
                }
            }
        }.trimEnd()
    }
}

/**
 * 单个观察者失败条目。
 *
 * 字段只保留归因所需的轻量信息，不直接暴露 Throwable、原始 JSON 或 sourceEvent 的完整字符串。
 *
 * @property callbackName 失败的回调名称，例如 `onEvent`。
 * @property eventName 当时正在处理的事件名称。
 * @property sourceCategory 原始事件类别。
 * @property errorType 回调抛出的异常类型名。
 * @property sourcePath 原始事件中的 JSON 路径。
 * @property sourceFieldName 原始事件中的字段名。
 * @property sourceTypeName 原始事件中的类型名。
 * @property sourceActualToken 原始事件中的实际 token。
 * @property rawJsonTruncated 原始事件里的 rawJson 是否被截断。
 */
data class SafeObserverFailureIssue(
    val callbackName: String,
    val eventName: String,
    val sourceCategory: SafeParserEventCategory,
    val errorType: String,
    val sourcePath: String? = null,
    val sourceFieldName: String? = null,
    val sourceTypeName: String? = null,
    val sourceActualToken: String? = null,
    val rawJsonTruncated: Boolean = false
)

/**
 * 观察者失败关联的事件类别。
 *
 * 已知类别和内置 `SafeParserEvent` 保持一一对应；Unknown 用来承接扩展层自定义事件。
 */
enum class SafeParserEventCategory {
    TypeMismatch,
    AdapterCreationFailure,
    EmptyResponse,
    RawJsonCaptureSkipped,
    Unknown
}

/**
 * 把调用方收集到的观察者失败转换成脱敏报告。
 *
 * 这个函数不重新解析 JSON，也不读取业务对象，只消费已经产生的事件。
 *
 * @return 观察者失败报告。
 */
fun Iterable<ObserverFailureEvent>.observerFailureReport(): SafeObserverFailureReport {
    return SafeObserverFailureReport(
        failures = map { it.toObserverFailureIssue() }
    )
}

/**
 * 把原始观察者失败事件转换成脱敏报告条目。
 *
 * @return 可以安全展示给日志或 CI 的失败条目。
 */
private fun ObserverFailureEvent.toObserverFailureIssue(): SafeObserverFailureIssue {
    // source 是从原始事件里摘出的最小定位信息，避免把完整事件对象直接带进报告。
    val source = sourceEvent.toObserverFailureSource()
    return SafeObserverFailureIssue(
        callbackName = callbackName,
        eventName = eventName.takeIf { it.isNotBlank() } ?: source.category.name,
        sourceCategory = source.category,
        errorType = error.javaClass.name,
        sourcePath = source.path,
        sourceFieldName = source.fieldName,
        sourceTypeName = source.typeName,
        sourceActualToken = source.actualToken,
        rawJsonTruncated = source.rawJsonTruncated
    )
}

/**
 * 从原始事件里摘出最小定位信息。
 *
 * 不把 `sourceEvent.toString()` 写进报告，是为了避免 rawJson 或异常细节通过日志侧漏出去。
 */
private data class ObserverFailureSource(
    val category: SafeParserEventCategory,
    val path: String? = null,
    val fieldName: String? = null,
    val typeName: String? = null,
    val actualToken: String? = null,
    val rawJsonTruncated: Boolean = false
)

/**
 * 把不同类型的 SafeParser 事件统一压缩成观察者报告可使用的定位信息。
 *
 * @return 最小化后的事件来源信息。
 */
private fun SafeParserEvent.toObserverFailureSource(): ObserverFailureSource {
    return when (this) {
        is SafeParserEvent.TypeMismatch -> ObserverFailureSource(
            category = SafeParserEventCategory.TypeMismatch,
            path = detail.path,
            fieldName = detail.fieldName,
            typeName = detail.expectedType,
            actualToken = detail.actualToken.name,
            rawJsonTruncated = detail.rawJsonTruncated
        )
        is SafeParserEvent.AdapterCreationFailure -> ObserverFailureSource(
            category = SafeParserEventCategory.AdapterCreationFailure,
            typeName = detail.typeName
        )
        is SafeParserEvent.EmptyResponse -> ObserverFailureSource(
            category = SafeParserEventCategory.EmptyResponse,
            typeName = detail.typeName
        )
        is SafeParserEvent.RawJsonCaptureSkipped -> ObserverFailureSource(
            category = SafeParserEventCategory.RawJsonCaptureSkipped,
            typeName = detail.typeName
        )
        else -> ObserverFailureSource(
            category = SafeParserEventCategory.Unknown,
            typeName = eventName
        )
    }
}

/**
 * 生成单条观察者失败的详情字段。
 *
 * @return 保持固定顺序的 key/value 列表，方便测试断言和日志对比。
 */
private fun SafeObserverFailureIssue.details(): List<Pair<String, String>> {
    return buildList {
        // details 的顺序保持稳定，便于测试断言和日志对比。
        add("sourceCategory" to sourceCategory.name)
        add("errorType" to errorType)
        sourcePath?.let { add("sourcePath" to it) }
        sourceFieldName?.let { add("sourceFieldName" to it) }
        sourceTypeName?.let { add("sourceTypeName" to it) }
        sourceActualToken?.let { add("sourceActualToken" to it) }
        if (rawJsonTruncated) {
            add("rawJsonTruncated" to "true")
        }
    }
}
