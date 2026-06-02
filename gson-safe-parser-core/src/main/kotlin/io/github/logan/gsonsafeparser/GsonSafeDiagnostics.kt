package io.github.logan.gsonsafeparser

/**
 * 接入诊断结果。
 *
 * 这个结果只描述 GsonSafeParser 能不能安全接管当前环境，不参与实际解析流程。
 * 调用方可以在应用启动、自测脚本或 CI 里读取它；即使发现风险，也应该由业务决定是否阻断发布。
 *
 * @property safeAdapterAvailable Safe Adapter 当前是否具备注册条件。`false` 通常表示 GsonBuilder 内部信息读取失败。
 * @property checks 具体诊断项列表。每个诊断项都会告诉你哪个配置或运行环境存在风险。
 */
data class GsonSafeDiagnostics(
    val safeAdapterAvailable: Boolean,
    val checks: List<GsonSafeDiagnosticCheck>
) {
    /**
     * 是否存在阻断级诊断项。
     *
     * 新接入项目可以优先看这个布尔值：为 true 时说明 Safe Adapter 不适合继续接管，
     * 为 false 时也可能有 warning，但不会直接阻止 Gson 默认链路工作。
     */
    val hasErrors: Boolean
        get() = checks.any { it.severity == DiagnosticSeverity.ERROR }
}

/**
 * 单个诊断项。
 *
 * `name` 用来做机器侧归类，`message` 用来给人看；不要把异常栈、原始 JSON 或业务数据直接放进这里。
 *
 * @property name 诊断项名称，适合日志平台做聚合，例如 `gsonBuilderCompatibility`。
 * @property severity 当前诊断项的严重程度。
 * @property message 给开发者看的说明，尽量写清楚风险和原因。
 */
data class GsonSafeDiagnosticCheck(
    val name: String,
    val severity: DiagnosticSeverity,
    val message: String
)

/**
 * 单个类型的 SafeParser 接管说明。
 */
data class GsonSafeTypeExplanation(
    val typeName: String,
    val handling: SafeTypeHandling,
    val checks: List<GsonSafeDiagnosticCheck>
) {
    val hasErrors: Boolean
        get() = checks.any { it.severity == DiagnosticSeverity.ERROR }
}

/**
 * SafeParser 对目标类型的处理方式。
 */
enum class SafeTypeHandling {
    SafePrimitive,
    SafeCollection,
    SafeMap,
    SafeReflective,
    SafeOrgJson,
    SafeTypeWrapper,
    DelegateToGson,
    AdapterCreationFailure
}

/**
 * 诊断等级。
 *
 * `WARNING` 表示配置上有风险但仍可继续；只有 `ERROR` 才表示 Safe Adapter 当前不可用或探针失败。
 */
enum class DiagnosticSeverity {
    /** 当前检查正常。 */
    OK,
    /** 当前检查有风险，但 SafeParser 仍可按配置继续运行或回到 Gson。 */
    WARNING,
    /** 当前检查失败，通常应该避免注册 Safe Adapter。 */
    ERROR
}
