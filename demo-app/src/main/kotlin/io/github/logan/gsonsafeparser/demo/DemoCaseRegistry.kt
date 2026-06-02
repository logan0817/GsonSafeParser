package io.github.logan.gsonsafeparser.demo

import io.github.logan.gsonsafeparser.demo.cases.*
import io.github.logan.gsonsafeparser.demo.support.DemoCase
import io.github.logan.gsonsafeparser.demo.support.DemoRunResult
import io.github.logan.gsonsafeparser.demo.support.MODEL_DEFAULT_VALUE_NOTE_CN
import io.github.logan.gsonsafeparser.demo.support.appendDetailedReportSection
import io.github.logan.gsonsafeparser.demo.support.buildChineseContractSummary
import io.github.logan.gsonsafeparser.demo.support.shouldShowModelDefaultValueNote

/**
 * demo app 的功能用例注册表。
 *
 * 这个文件只保留用例聚合、运行全部和报告生成逻辑。具体用例、模型、用户 JSON 验证器和格式化工具
 * 分别放在 cases、model、support 等文件里，方便从目录就看出项目结构。
 */
object DemoCaseRegistry {
    /**
     * 页面展示和“运行全部”使用的用例列表。
     *
     * 新增公开能力时，优先在对应 cases 文件中补一个可运行用例，再把它加入这里。
     */
    val cases: List<DemoCase> = listOf(
        objectFieldFallbackCase(),
        gsonSafeParserFromJsonClassCase(),
        gsonSafeParserFromJsonTypeCase(),
        gsonSafeParserFromJsonWithGsonCase(),
        gsonSafeParserReusableParserCase(),
        gsonSafeParserReusableParserParseSafeCase(),
        enableSafeParserCase(),
        kotlinFromJsonSafeCase(),
        parseSafeContractReportCase(),
        diagnosticsIntegrationCheckCase(),
        presetConfigCompareCase(),
        fromPoliciesCase(),
        builderFieldNamingExposeVersionCase(),
        builderInstanceCreatorUnsafeCase(),
        reflectionAccessFilterPriorityCase(),
        primitiveMismatchCase(),
        primitiveDelegateToGsonCase(),
        fallbackNullOnlyCollectionMapCase(),
        callbackCompatibilityCase(),
        collectionMapMismatchCase(),
        rootShapeAndNullFieldBoundaryCase(),
        scalarStringFallbackCase(),
        mapBadKeyNestedEntryCase(),
        containerRuntimeContractCase(),
        rawJsonTruncationCase(),
        diagnosticsRiskWarningCase(),
        integrationCheckPlatformWarningCase(),
        mapEntryAndSerializationCase(),
        objectNumberStrategyCase(),
        customObjectNumberStrategyCase(),
        instanceCreatorCase(),
        reflectionAccessAndUnsafeCase(),
        enumSetEnumMapCase(),
        adapterCreationFallbackCase(),
        serializedNameAndJsonAdapterCase(),
        platformTypeSkipCase(),
        orgJsonCase(),
        gsonBuiltInTypesCase(),
        annotationSkipCase(),
        annotationDelegateToGsonCase(),
        observerFailureReportCase(),
        dispatchEventCase(),
        retrofitEmptyResponseCase(),
        retrofitUnitVoidEmptyResponseCase(),
        retrofitRawJsonCase(),
        retrofitRawJsonUnknownLengthCase(),
        retrofitObserverFailureIsolationCase(),
        retrofitConverterDelegationCase(),
        retrofitCreateWithGsonCase(),
        retrofitCreateWithGsonAndConfigCase()
    )

    /**
     * 运行全部用例。
     *
     * @return 每个用例和它的运行结果，页面会据此生成总览。
     */
    fun runAll(): List<Pair<DemoCase, DemoRunResult>> {
        return cases.map { demoCase -> demoCase to demoCase.run(demoCase.defaultJson) }
    }

    /**
     * 把全部默认用例结果整理成可复制的完整报告。
     *
     * @param results `runAll()` 的运行结果，传入参数是为了避免页面想复制报告时重复执行解析。
     * @return 包含每个用例输入、预期、输出、事件、报告和异常的纯文本。
     */
    fun buildDetailedReport(results: List<Pair<DemoCase, DemoRunResult>> = runAll()): String {
        val failed = results.filterNot { (_, result) -> result.pass }
        return buildString {
            appendLine("GsonSafeParser Demo 全量报告")
            appendLine("总用例数：${results.size}")
            appendLine("通过：${results.size - failed.size}")
            appendLine("失败：${failed.size}")
            if (results.any { (_, result) -> shouldShowModelDefaultValueNote(result) }) {
                appendLine()
                appendDetailedReportSection("默认值说明：", MODEL_DEFAULT_VALUE_NOTE_CN)
            }
            results.forEachIndexed { index, (demoCase, result) ->
                appendLine()
                appendLine("${index + 1}. ${if (result.pass) "通过" else "失败"} - ${demoCase.title}")
                appendDetailedReportSection("功能分类：", demoCase.category)
                appendDetailedReportSection("覆盖入口：", demoCase.entryPoint)
                appendDetailedReportSection("输入 JSON：", demoCase.defaultJson.ifBlank { "<空响应>" })
                appendDetailedReportSection("预期结果：", result.expected)
                appendDetailedReportSection("解析输出：", result.actual)
                appendDetailedReportSection("事件流：", result.events)
                appendDetailedReportSection("契约摘要：", buildChineseContractSummary(result.events, result.contractReport))
                appendDetailedReportSection("契约报告原文：", result.contractReport)
                appendDetailedReportSection("观察者失败报告：", result.observerReport)
                appendDetailedReportSection("诊断信息：", result.diagnostics)
                appendDetailedReportSection("异常详情：", result.error ?: "无未捕获运行异常；场景内捕获异常请看解析输出。")
            }
        }.trimEnd()
    }
}
