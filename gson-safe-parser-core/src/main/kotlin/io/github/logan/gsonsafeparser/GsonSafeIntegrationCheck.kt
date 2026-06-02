package io.github.logan.gsonsafeparser

import com.google.gson.reflect.TypeToken
import com.google.gson.annotations.SerializedName
import io.github.logan.gsonsafeparser.internal.runRecovering
import io.github.logan.gsonsafeparser.internal.toSafeTypeName
import java.lang.reflect.Field
import java.lang.reflect.Type

/**
 * 业务模型接入探针。
 *
 * 这个探针给老项目做 release 自检用：调用方挑少量关键响应模型，提供一份最小 JSON 和期望字段值。
 * 如果 release 混淆后字段名被 R8 改成 a/b/c，或者构造方法/字段反射信息被裁剪，探针会把风险写进
 * `GsonSafeIntegrationCheck.checks`，而不是让自检调用直接抛异常。
 *
 * @property name 探针名称，建议使用接口名或模型名，便于日志里定位。
 * @property json 用来解析目标模型的最小 JSON。
 * @property type 目标模型类型，支持 Class 和 Gson TypeToken。
 * @property expectedFields 解析后要核对的字段名和值。字段名应使用混淆前的业务模型字段名。
 */
data class GsonSafeModelProbe(
    val name: String,
    val json: String,
    val type: Type,
    val expectedFields: Map<String, Any?> = emptyMap()
)

/**
 * 接入自检结果。
 *
 * 自检只跑库内置的小样本，目的是确认 Safe Adapter 确实接入、对象错形兜底能生效、
 * 事件和契约报告也能正常生成。这里不会检查业务 Bean，也不会触发调用方传入的观察回调。
 *
 * @property diagnostics 基础诊断结果，复用 `GsonSafeParser.diagnostics()` 的输出。
 * @property safeAdapterAvailable Safe Adapter 是否可用，方便调用方不用再展开 diagnostics。
 * @property probeParsed 内置探针是否成功解析出对象。
 * @property fallbackWorking 内置探针是否验证到对象错形兜底生效。
 * @property events 探针解析时产生的 SafeParser 事件。
 * @property contractReport 探针事件转换出的契约报告。
 * @property checks 基础诊断项加上探针诊断项后的完整列表。
 * @property probeError 探针失败时的简短错误信息；成功时为 null。
 */
data class GsonSafeIntegrationCheck(
    val diagnostics: GsonSafeDiagnostics,
    val safeAdapterAvailable: Boolean,
    val probeParsed: Boolean,
    val fallbackWorking: Boolean,
    val events: List<SafeParserEvent>,
    val contractReport: SafeParseContractReport,
    val checks: List<GsonSafeDiagnosticCheck>,
    val probeError: String? = null
) {
    /**
     * 自检结果里是否包含阻断级错误。
     *
     * 调用方可以把这个值作为 CI 门禁或启动期告警条件。
     */
    val hasErrors: Boolean
        get() = checks.any { it.severity == DiagnosticSeverity.ERROR }
}

/**
 * 运行一次不会污染业务解析链路的集成自检。
 *
 * 探针固定使用 `{"data":[]}` 这种最常见的后端错形数据。成功时说明当前环境至少能处理
 * “`Object` 字段被返回成空数组” 这一类问题；失败时错误只进入结果对象，交给调用方在 CI 或日志里判断。
 *
 * @param config 自检时使用的配置。自检会复制一份配置并清空用户回调，避免触发业务日志或埋点。
 * @return 自检结果。调用方只需要看 `hasErrors` 就能知道是否存在阻断级问题。
 */
fun GsonSafeParser.integrationCheck(config: SafeParserConfig = SafeParserConfig()): GsonSafeIntegrationCheck {
    return runIntegrationCheck(
        config = config,
        modelProbes = emptyList<GsonSafeModelProbe>()
    )
}

/**
 * 使用默认配置运行集成自检，并可选验证少量业务模型 release 风险。
 *
 * 这个重载服务于最小接入写法：`GsonSafeParser.integrationCheck(modelProbes = probes)`。
 * 如果需要指定 production/debug/lowInterference 配置，可以继续使用 `config + modelProbes` 重载。
 *
 * @param modelProbes 可选业务模型探针。为空时保持轻量自检行为。
 * @return 自检结果。调用方只需要看 `hasErrors` 就能知道是否存在阻断级问题。
 */
fun GsonSafeParser.integrationCheck(
    modelProbes: List<GsonSafeModelProbe>
): GsonSafeIntegrationCheck {
    return runIntegrationCheck(
        config = SafeParserConfig(),
        modelProbes = modelProbes
    )
}

/**
 * 运行一次不会污染业务解析链路的集成自检，并可选验证少量业务模型 release 风险。
 *
 * `modelProbes` 是老项目的低成本 release 门禁：先挑关键响应模型，不要求第一天整理全项目 Bean。
 * 每个探针都会捕获解析、反射和字段访问异常，并转换成诊断项，避免自检本身因为混淆配置缺口成为新崩溃源。
 *
 * @param config 自检时使用的配置。自检会复制一份配置并清空用户回调，避免触发业务日志或埋点。
 * @param modelProbes 可选业务模型探针。为空时保持轻量自检行为。
 * @return 自检结果。调用方只需要看 `hasErrors` 就能知道是否存在阻断级问题。
 */
fun GsonSafeParser.integrationCheck(
    config: SafeParserConfig,
    modelProbes: List<GsonSafeModelProbe>
): GsonSafeIntegrationCheck {
    return runIntegrationCheck(
        config = config,
        modelProbes = modelProbes
    )
}

private fun runIntegrationCheck(
    config: SafeParserConfig,
    modelProbes: List<GsonSafeModelProbe>
): GsonSafeIntegrationCheck {
    val diagnostics = GsonSafeParser.diagnostics(config)
    // 自检不能调用用户的 onEvent/onTypeMismatch，否则一次健康检查就可能触发线上日志、埋点或异常上报。
    // probeConfig 是“探针专用配置”：它保留读写策略，但禁用 rawJson 和用户回调，避免一次自检产生业务副作用。
    val probeConfig = config.copy(
        captureRawJsonInCallbacks = false,
        onEvent = {},
        onAdapterCreationFailure = {},
        onTypeMismatch = {}
    )

    // probe 保存探针执行结果；fatal 会继续外抛，普通接入异常留在结果对象里。
    val probe = runRecovering {
        GsonSafeParser.parseSafe<IntegrationProbeResponse>(INTEGRATION_PROBE_JSON, probeConfig)
    }

    // probeResult 是解析成功时的结果，失败时为 null；后面所有判断都要能接受 null。
    val probeResult = probe.getOrNull()
    val probeError = probe.exceptionOrNull()?.let { it.message ?: it.javaClass.name }
    val contractReport = probeResult?.contractReport() ?: SafeParseContractReport(emptyList())
    // 这里同时检查解析结果和报告事件，避免只“没崩溃”却其实没有走到 Safe 兜底。
    val probeFallbackWorking = probeResult?.value == IntegrationProbeResponse() &&
        contractReport.issues.any {
            it.category == SafeParseContractIssueCategory.TypeMismatch && it.path == "$.data"
        }
    val probeParsed = probe.isSuccess && probeResult?.value != null
    val probeCheck = if (probeParsed && probeFallbackWorking) {
        GsonSafeDiagnosticCheck(
            name = "probeFallback",
            severity = DiagnosticSeverity.OK,
            message = "Safe parser probe parsed and preserved fallback values."
        )
    } else {
        GsonSafeDiagnosticCheck(
            name = "probeFallback",
            severity = DiagnosticSeverity.ERROR,
            message = probeError ?: "Safe parser probe did not preserve fallback values."
        )
    }

    val modelProbeChecks = modelProbes.flatMap { probe ->
        checkModelProbe(probe, probeConfig)
    }
    val checks = diagnostics.checks + probeCheck + modelProbeChecks
    return GsonSafeIntegrationCheck(
        diagnostics = diagnostics,
        safeAdapterAvailable = diagnostics.safeAdapterAvailable,
        probeParsed = probeParsed,
        fallbackWorking = probeFallbackWorking,
        events = probeResult?.events.orEmpty(),
        contractReport = contractReport,
        checks = checks,
        probeError = probeError
    )
}

private fun checkModelProbe(
    probe: GsonSafeModelProbe,
    config: SafeParserConfig
): List<GsonSafeDiagnosticCheck> {
    return runRecovering {
        checkModelProbeUnsafe(probe, config)
    }.getOrElse { error ->
        listOf(modelProbeFailureCheck(probe.safeName(), error))
    }
}

private fun checkModelProbeUnsafe(
    probe: GsonSafeModelProbe,
    config: SafeParserConfig
): List<GsonSafeDiagnosticCheck> {
    val probeName = probe.safeName()
    val parsed = runRecovering {
        GsonSafeParser.fromJson<Any?>(
            json = probe.json,
            type = probe.type,
            config = config
        )
    }
    val value = parsed.getOrElse { error ->
        return listOf(modelProbeFailureCheck(probeName, error))
    }
    if (value == null) {
        return listOf(
            GsonSafeDiagnosticCheck(
                name = "modelConstructorUnavailable",
                severity = DiagnosticSeverity.ERROR,
                message = "Model probe '$probeName' parsed null. Release minification may have removed constructors or required model metadata; keep constructors and Kotlin Metadata."
            )
        )
    }

    val rawClass = runRecovering { TypeToken.get(probe.type).rawType }.getOrElse { value.javaClass }
    val fieldChecks = probe.expectedFields.mapNotNull { (fieldName, expectedValue) ->
        val field = rawClass.findField(fieldName)
            ?: return@mapNotNull suspectedModelFieldObfuscation(
                probeName = probeName,
                fieldName = fieldName,
                detail = "field is not found by its original name"
            )
        val actualValue = runRecovering {
            field.isAccessible = true
            field.get(value)
        }.getOrElse { error ->
            return@mapNotNull suspectedModelFieldObfuscation(
                probeName = probeName,
                fieldName = fieldName,
                detail = "field cannot be read: ${error.safeMessage()}"
            )
        }
        if (actualValue == expectedValue) {
            null
        } else {
            suspectedModelFieldObfuscation(
                probeName = probeName,
                fieldName = fieldName,
                detail = "expected <$expectedValue> but parsed <$actualValue>"
            )
        }
    }
    if (fieldChecks.isNotEmpty()) {
        return fieldChecks
    }
    return listOf(
        GsonSafeDiagnosticCheck(
            name = "modelProbe",
            severity = DiagnosticSeverity.OK,
            message = "Model probe '$probeName' parsed expected fields. Legacy projects can keep using package-level keep rules first, then narrow the model scope gradually."
        )
    )
}

private fun GsonSafeModelProbe.safeName(): String {
    return runRecovering { name.ifBlank { type.toSafeTypeName() } }.getOrDefault("<unknown-model-probe>")
}

private fun modelProbeFailureCheck(
    probeName: String,
    error: Throwable
): GsonSafeDiagnosticCheck {
    return GsonSafeDiagnosticCheck(
        name = "modelProbeFailure",
        severity = DiagnosticSeverity.ERROR,
        message = "Model probe '$probeName' failed during parsing or verification: ${error.safeMessage()}. Check release keep rules, constructors, Kotlin Metadata, and Gson adapters."
    )
}

private fun Class<*>.findField(fieldName: String): Field? {
    var current: Class<*>? = this
    while (current != null && current != Any::class.java) {
        val field = runRecovering { current.getDeclaredField(fieldName) }.getOrNull()
        if (field != null) {
            return field
        }
        current = current.superclass
    }
    return null
}

private fun suspectedModelFieldObfuscation(
    probeName: String,
    fieldName: String,
    detail: String
): GsonSafeDiagnosticCheck {
    return GsonSafeDiagnosticCheck(
        name = "modelFieldObfuscationSuspected",
        severity = DiagnosticSeverity.ERROR,
        message = "Model probe '$probeName' suggests release model field obfuscation for '$fieldName': $detail. Add package-level keep for legacy model fields first, or add @SerializedName before narrowing keep rules."
    )
}

private fun Throwable.safeMessage(): String {
    return message ?: javaClass.name
}

/**
 * 自检专用模型。
 *
 * 保持私有是为了避免它变成外部契约；字段默认值用于判断错形兜底是否保住了原始构造结果。
 */
private data class IntegrationProbeResponse(
    @SerializedName("data")
    val data: IntegrationProbeChild = IntegrationProbeChild()
)

/**
 * 自检子对象。
 *
 * `id` 默认值用于确认 `data` 字段错形时没有被错误覆盖。
 */
private data class IntegrationProbeChild(
    @SerializedName("id")
    val id: Long = 1L
)

/**
 * 自检固定 JSON。
 *
 * 这里故意把 `Object` 字段 `data` 写成空数组，用来验证 SafeParser 最核心的错形兜底链路。
 */
private const val INTEGRATION_PROBE_JSON = """{"data":[]}"""
