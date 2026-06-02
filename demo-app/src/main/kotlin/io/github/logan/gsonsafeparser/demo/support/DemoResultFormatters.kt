package io.github.logan.gsonsafeparser.demo.support

import android.content.res.ColorStateList
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import com.google.gson.JsonIOException
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.google.gson.ReflectionAccessFilter
import com.google.gson.ToNumberPolicy
import com.google.gson.TypeAdapter
import com.google.gson.annotations.Expose
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.Since
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.github.logan.gsonsafeparser.EmptyResponsePolicy
import io.github.logan.gsonsafeparser.EmptyResponseEvent
import io.github.logan.gsonsafeparser.FallbackPolicy
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.ObserverFailureEvent
import io.github.logan.gsonsafeparser.ParseExceptionKind
import io.github.logan.gsonsafeparser.PrimitiveParsingPolicy
import io.github.logan.gsonsafeparser.SafeObserverPolicy
import io.github.logan.gsonsafeparser.SafeParseContractReport
import io.github.logan.gsonsafeparser.SafeParseResult
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.SafeParserEvent
import io.github.logan.gsonsafeparser.SafeParseDelegateToGson
import io.github.logan.gsonsafeparser.SafeParseSkip
import io.github.logan.gsonsafeparser.SafeReadPolicy
import io.github.logan.gsonsafeparser.SafeWritePolicy
import io.github.logan.gsonsafeparser.TypeMismatchEvent
import io.github.logan.gsonsafeparser.contractReport
import io.github.logan.gsonsafeparser.dispatchEvent
import io.github.logan.gsonsafeparser.enableSafeParser
import io.github.logan.gsonsafeparser.fromJsonSafe
import io.github.logan.gsonsafeparser.integrationCheck
import io.github.logan.gsonsafeparser.observerFailureReport
import io.github.logan.gsonsafeparser.parseSafe
import io.github.logan.gsonsafeparser.retrofit.GsonSafeConverterFactory
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import java.lang.reflect.Type
import java.math.BigDecimal
import java.net.URI
import java.net.URL
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.BitSet
import java.util.Date
import java.util.EnumMap
import java.util.EnumSet
import java.util.SortedSet
import java.util.TreeSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentMap

internal fun demoResult(
    pass: Boolean,
    actual: Any?,
    events: List<SafeParserEvent> = emptyList(),
    contractReport: String = DEFAULT_CONTRACT_REPORT_TEXT,
    expected: String
): DemoRunResult {
    val report = if (contractReport == DEFAULT_CONTRACT_REPORT_TEXT && events.isNotEmpty()) {
        events.toSafeResultReport()
    } else {
        contractReport
    }
    return DemoRunResult(
        pass = pass,
        actual = pretty(actual),
        expected = expected,
        events = events.describeEvents(),
        contractReport = report
    )
}

internal fun SafeParseContractReport.toDemoContractReport(): String {
    if (!hasIssues) return toMarkdown()
    return buildString {
        appendLine("契约汇总：")
        appendLine("issueCount=${summary.issueCount}")
        appendLine("warningCount=${summary.warningCount}")
        appendLine("infoCount=${summary.infoCount}")
        appendLine("backendActionableCount=${summary.backendActionableCount}")
        appendLine()
        appendLine("后端报告：")
        appendLine(toBackendMarkdown())
        appendLine()
        appendLine("结构化行：")
        toStructuredRows().forEachIndexed { index, row ->
            appendLine("${index + 1}. stableKey=${row.stableKey}")
            appendLine("   fields=${row.fields.entries.joinToString(", ") { (key, value) -> "$key=$value" }}")
        }
        appendLine()
        appendLine("契约报告原文：")
        appendLine(toMarkdown())
    }.trimEnd()
}

internal fun buildOutputBlockPreview(
    text: String,
    maxLines: Int = OUTPUT_BLOCK_PREVIEW_MAX_LINES,
    maxChars: Int = OUTPUT_BLOCK_PREVIEW_MAX_CHARS
): String {
    val cleanText = text.trimStart()
    if (cleanText.isBlank()) return cleanText

    val lines = cleanText.lines()
    val lineLimited = lines.size > maxLines
    val linePreview = if (lineLimited) {
        lines.take(maxLines).joinToString(separator = "\n")
    } else {
        cleanText
    }
    val charLimited = linePreview.length > maxChars
    val preview = if (charLimited) {
        linePreview.take(maxChars).trimEnd()
    } else {
        linePreview.trimEnd()
    }

    return if (lineLimited || charLimited) "$preview\n\n..." else cleanText
}

internal fun cleanDisplayText(text: String?): String {
    return text.orEmpty().trimStart()
}

internal fun pretty(value: Any?): String {
    return when (value) {
        null -> "null"
        is String -> value
        else -> prettyGson.toJson(value)
    }
}

internal val prettyGson: Gson = GsonBuilder()
    .serializeNulls()
    .setPrettyPrinting()
    .create()

internal const val OUTPUT_BLOCK_PREVIEW_MAX_LINES = 24
internal const val OUTPUT_BLOCK_PREVIEW_MAX_CHARS = 2400

internal const val MODEL_DEFAULT_VALUE_NOTE_CN =
    "DemoModels.kt 里的很多字段都有默认值。SafeParser 兜底时会把这些默认值真实展示出来，所以预期里的 User() 等价于 id=0、name=anonymous；实际输出展开成完整 JSON 是正常表现。"

/**
 * 判断这次结果是否需要提醒用户关注模型默认值。
 *
 * demo 里很多 Kotlin data class 都给字段写了默认值。只要预期里用了 `User()` 这种简写，
 * 或者实际 JSON 展开了默认 user，就在报告里补一句说明，避免用户误以为预期和实际不一致。
 */
internal fun shouldShowModelDefaultValueNote(result: DemoRunResult): Boolean {
    return result.expected.contains("User()") ||
        result.actual.contains("\"name\": \"anonymous\"") ||
        result.actual.contains("\"id\": 0")
}

/**
 * 规范化复制报告中的正文。
 *
 * 这里会去掉 Kotlin 多行字符串带来的公共缩进和多余空行，但不会破坏 JSON 自身的两格缩进。
 */
internal fun normalizeReportText(text: String?): String {
    return text.orEmpty()
        .trim()
        .trimIndent()
        .lines()
        .joinToString(separator = "\n") { line -> line.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
}

/**
 * 给完整报告追加一个标题和正文。
 *
 * @receiver 正在构建的报告文本。
 * @param title 中文报告里的分区标题。
 * @param body 分区正文，写入前会统一清理缩进和多余空行。
 */
internal fun StringBuilder.appendDetailedReportSection(title: String, body: String?) {
    appendLine(title)
    appendLine(normalizeReportText(body))
}

/**
 * 为中文完整报告生成一段更容易读懂的契约摘要。
 *
 * 原始 contract report 仍然保留在下一段，方便开发者看完整 Markdown；这里优先把事件流转成中文短句，
 * 让第一次看 demo 的用户能先知道“哪个字段、什么问题、怎么处理”。
 */
internal fun buildChineseContractSummary(events: String, contractReport: String): String {
    val normalizedEvents = normalizeReportText(events)
    val normalizedContract = normalizeReportText(contractReport)
    if (normalizedEvents == "无事件" && normalizedContract == DEFAULT_CONTRACT_REPORT_TEXT) {
        return "未发现契约问题。"
    }
    val summaries = normalizedEvents
        .split(Regex("\n\\s*\n"))
        .mapNotNull { block -> block.toChineseContractSummaryLine() }
    return summaries.ifEmpty {
        listOf("已生成原始契约报告，请查看下方原文。")
    }.joinToString(separator = "\n")
}

private fun String.toChineseContractSummaryLine(): String? {
    return when {
        startsWith("类型错配") -> {
            val path = fieldValue("字段路径=").orEmpty()
            val expectedType = fieldValue("期望类型=").orEmpty()
            val actualToken = fieldValue("实际 JSON 类型=").orEmpty()
            val reason = fieldValue("原因=").orEmpty()
            "类型错配：字段 $path 期望 $expectedType，实际 JSON 是 $actualToken；原因：$reason"
        }
        startsWith("Adapter 创建失败") -> {
            val typeName = fieldValue("目标类型=").orEmpty()
            val reason = fieldValue("原因=").orEmpty()
            "Adapter 创建失败：$typeName 创建 Safe Adapter 失败，已回到 Gson 默认链路；原因：$reason"
        }
        startsWith("空响应") -> {
            val typeName = fieldValue("目标类型=").orEmpty()
            val policy = fieldValue("处理策略=").orEmpty()
            "空响应：目标类型 $typeName 使用策略 $policy。"
        }
        startsWith("跳过原始 JSON 捕获") -> {
            val typeName = fieldValue("目标类型=").orEmpty()
            val reason = fieldValue("原因=").orEmpty()
            "跳过原始 JSON 捕获：$typeName 为了控制内存和日志体积跳过 rawJson；原因：$reason"
        }
        else -> null
    }
}

private fun String.fieldValue(prefix: String): String? {
    return lineSequence()
        .firstOrNull { line -> line.startsWith(prefix) }
        ?.substringAfter(prefix)
        ?.trim()
}

/**
 * 把输入框里的 JSON 格式化成人更容易查看的缩进形式。
 *
 * 用户可能会直接从 Charles、日志或接口文档里复制一整行 JSON。
 * demo 在填入示例和运行前会调用这里，如果内容不是合法 JSON，就保持原文，
 * 避免因为格式化失败影响用户继续观察 Gson 自己的异常。
 */
internal fun formatJsonForDisplay(json: String): String {
    val trimmed = json.trim()
    if (trimmed.isEmpty()) return trimmed
    return runCatching {
        prettyGson.toJson(JsonParser.parseString(trimmed))
    }.getOrElse {
        trimmed
    }
}

internal fun List<SafeParserEvent>.hasTypeMismatch(path: String, token: String? = null): Boolean {
    return any { event ->
        val detail = event.typeMismatchDetail()
        detail?.path == path && (token == null || detail.actualToken.name == token)
    }
}

internal fun SafeParserEvent.typeMismatchDetail(): TypeMismatchEvent? {
    return (this as? SafeParserEvent.TypeMismatch)?.detail
}

internal fun List<SafeParserEvent>.describeEvents(): String {
    if (isEmpty()) return "无事件"
    return joinToString(separator = "\n\n") { event -> event.describe() }
}

internal fun SafeParserEvent.describe(): String {
    return when (this) {
        is SafeParserEvent.TypeMismatch -> """
            类型错配（TypeMismatch）
            字段路径=${detail.path}
            字段名=${detail.fieldName ?: "无"}
            期望类型=${detail.expectedType}
            实际 JSON 类型=${detail.actualToken}
            问题位置=${detail.kind.toChineseLabel()}
            Map 条目 key=${detail.mapItemKey ?: "无"}
            原始 JSON=${detail.rawJson ?: "未捕获"}
            原始 JSON 是否截断=${detail.rawJsonTruncated.toChineseLabel()}
            原因=${detail.reason}
        """.trimIndent()
        is SafeParserEvent.AdapterCreationFailure -> """
            Adapter 创建失败（AdapterCreationFailure）
            目标类型=${detail.typeName}
            处理结果=已按配置回到 Gson 默认 Adapter，避免 SafeParser 扩展能力影响原生解析链路。
            原因=${detail.reason}
        """.trimIndent()
        is SafeParserEvent.EmptyResponse -> """
            空响应（EmptyResponse）
            目标类型=${detail.typeName}
            处理策略=${detail.policy.toChineseLabel()}
        """.trimIndent()
        is SafeParserEvent.RawJsonCaptureSkipped -> """
            跳过原始 JSON 捕获（RawJsonCaptureSkipped）
            目标类型=${detail.typeName}
            响应体大小=${detail.contentLength} 字节
            捕获上限=${detail.maxBytes} 字节
            处理结果=继续解析，只是不把完整原始 JSON 放进事件，避免日志和内存压力过大。
            原因=${detail.reason}
        """.trimIndent()
        else -> """
            自定义事件（${eventName}）
            处理结果=已进入统一事件流；当前 Demo 不解析这类事件的内部字段。
        """.trimIndent()
    }
}

internal fun List<SafeParserEvent>.toSafeResultReport(): String {
    return io.github.logan.gsonsafeparser.SafeParseResult<Any?>(null, this).contractReport().toDemoContractReport()
}

internal fun io.github.logan.gsonsafeparser.GsonSafeDiagnostics.describe(): String {
    return buildString {
        appendLine("Safe Adapter 是否可用=${safeAdapterAvailable.toChineseLabel()}（safeAdapterAvailable=$safeAdapterAvailable）")
        appendLine("是否存在阻断错误=${hasErrors.toChineseLabel()}（hasErrors=$hasErrors）")
        checks.forEach { check ->
            appendLine("检查级别=${check.severity}，检查项=${check.name}，说明=${check.toDemoDiagnosticMessage()}")
        }
    }.trimEnd()
}

internal fun io.github.logan.gsonsafeparser.GsonSafeIntegrationCheck.describe(): String {
    return buildString {
        appendLine("Safe Adapter 是否可用=${safeAdapterAvailable.toChineseLabel()}（safeAdapterAvailable=$safeAdapterAvailable）")
        appendLine("探针 JSON 是否解析成功=${probeParsed.toChineseLabel()}（probeParsed=$probeParsed）")
        appendLine("失败回退 Gson 是否正常=${fallbackWorking.toChineseLabel()}（fallbackWorking=$fallbackWorking）")
        appendLine("是否存在阻断错误=${hasErrors.toChineseLabel()}（hasErrors=$hasErrors）")
        appendLine("探针错误=${probeError ?: "无"}")
        checks.forEach { check ->
            appendLine("检查级别=${check.severity}，检查项=${check.name}，说明=${check.toDemoDiagnosticMessage()}")
        }
    }.trimEnd()
}

private fun io.github.logan.gsonsafeparser.GsonSafeDiagnosticCheck.toDemoDiagnosticMessage(): String {
    return when (name) {
        "modelFieldObfuscationSuspected" -> "疑似模型字段被混淆：release 包里的业务字段名可能已经被 R8 改成 a/b/c。老项目先给 bean、model、entity、response、dto 等响应模型包加包级 keep，稳定后再逐步补 @SerializedName 并收窄规则。原始说明：$message"
        "modelConstructorUnavailable" -> "疑似模型构造信息被裁剪：release 包可能缺少 Kotlin Metadata 或构造方法 keep。先保留模型构造方法和 kotlin.Metadata，再用同一份 JSON 对比 debug/release。原始说明：$message"
        "modelProbeFailure" -> "业务模型探针执行失败：自检已把异常转成 diagnostics，没有让调用方直接崩溃。优先检查模型包 keep、构造方法、字段名、@SerializedName 和自定义 Adapter。原始说明：$message"
        else -> message
    }
}

/**
 * 把内部错配位置枚举转换成适合 demo 页面展示的中文说明。
 */
private fun ParseExceptionKind.toChineseLabel(): String {
    return when (this) {
        ParseExceptionKind.OBJECT -> "对象字段"
        ParseExceptionKind.LIST_ITEM -> "集合元素"
        ParseExceptionKind.MAP_ITEM -> "Map 条目"
    }
}

/**
 * 把布尔值转换成中文，保留 true/false 的地方会单独放在括号里，方便开发者搜索。
 */
private fun Boolean.toChineseLabel(): String {
    return if (this) "是" else "否"
}

/**
 * 把 Retrofit 空响应策略转换成中文说明，让 demo 日志不只出现枚举名。
 */
private fun EmptyResponsePolicy.toChineseLabel(): String {
    return when (this) {
        EmptyResponsePolicy.DefaultValue -> "返回默认对象"
        EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly -> "仅 Unit/Void 返回空值"
        EmptyResponsePolicy.Null -> "返回 null"
        EmptyResponsePolicy.DelegateToGson -> "交回 Gson 默认处理"
    }
}

internal inline fun <reified T> convertWithRetrofit(
    bodyText: String,
    config: SafeParserConfig
): Result<T?> {
    return runCatching {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(GsonSafeConverterFactory.create(config))
            .build()
        val converter = retrofit.nextResponseBodyConverter<T?>(null, typeOf<T>(), emptyArray())
        converter.convert(bodyText.toResponseBody())
    }
}

internal fun convertWithRetrofit(
    bodyText: String,
    type: Type,
    config: SafeParserConfig
): Result<Any?> {
    return runCatching {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://example.com/")
            .addConverterFactory(GsonSafeConverterFactory.create(config))
            .build()
        val converter = retrofit.nextResponseBodyConverter<Any?>(null, type, emptyArray())
        converter.convert(bodyText.toResponseBody())
    }
}

internal inline fun <reified T> typeOf(): Type {
    return object : TypeToken<T>() {}.type
}

@Suppress("DEPRECATION")
internal fun String.toResponseBody(): ResponseBody {
    return ResponseBody.create(MediaType.parse("application/json; charset=utf-8"), this)
}

@Suppress("DEPRECATION")
internal fun String.toUnknownLengthResponseBody(): ResponseBody {
    val json = this
    return object : ResponseBody() {
        override fun contentLength(): Long = -1

        override fun contentType(): MediaType? = MediaType.parse("application/json; charset=utf-8")

        override fun source(): BufferedSource = Buffer().writeUtf8(json)
    }
}
