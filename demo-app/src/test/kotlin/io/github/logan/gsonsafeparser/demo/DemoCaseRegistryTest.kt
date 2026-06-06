package io.github.logan.gsonsafeparser.demo

import com.google.gson.stream.JsonToken
import io.github.logan.gsonsafeparser.DiagnosticSeverity
import io.github.logan.gsonsafeparser.GsonSafeDiagnosticCheck
import io.github.logan.gsonsafeparser.GsonSafeDiagnostics
import io.github.logan.gsonsafeparser.demo.page.DemoPageRegistry
import io.github.logan.gsonsafeparser.demo.support.DemoCustomValidator
import io.github.logan.gsonsafeparser.demo.support.buildOutputBlockPreview
import io.github.logan.gsonsafeparser.demo.support.describeEvents
import io.github.logan.gsonsafeparser.demo.support.describe
import io.github.logan.gsonsafeparser.ParseExceptionKind
import io.github.logan.gsonsafeparser.SafeParserEvent
import io.github.logan.gsonsafeparser.TypeMismatchEvent
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * demo 用例注册表的本地单元测试。
 *
 * 它不替代真机页面测试，但能保证 demo 页面引用的每个用例默认输入都能实际跑通。
 */
class DemoCaseRegistryTest {
    @Test
    fun demoCasesCoverPublicFeatureEntrypoints() {
        assertTrue(DemoCaseRegistry.cases.size >= 12)
        val titles = DemoCaseRegistry.cases.map { it.title }
        val requiredTitles = setOf(
            "create + 对象字段错形兜底",
            "fromJson(Class) + NullOnly",
            "fromJson(Type) + 泛型 List",
            "fromJson(gson) + rawJson 捕获",
            "parser(config) + 复用 Gson",
            "parser(config) + 契约报告",
            "enableSafeParser + 既有 GsonBuilder",
            "fromJsonSafe<T> + reified 兜底",
            "parseSafe + 契约报告",
            "diagnostics + integrationCheck",
            "配置预设对比",
            "fromPolicies + 读策略/事件策略",
            "PrimitiveParsingPolicy.DelegateToGson",
            "FallbackPolicy.NullOnly 集合/Map",
            "兼容回调",
            "objectToNumberStrategy 自定义",
            "InstanceCreator 对象构造",
            "ReflectionAccessFilter + useJdkUnsafe",
            "Builder FieldNamingPolicy/Expose/Version 透传",
            "Builder InstanceCreator 与 Unsafe 透传",
            "ReflectionAccessFilter 优先级验证",
            "EnumSet/EnumMap 特殊容器",
            "Gson 内置类型交回原生",
            "根对象错形与 null 字段边界",
            "标量字符串兼容与非法值兜底",
            "Map 坏 key 归因与嵌套 entry",
            "集合 Map 运行时类型契约",
            "rawJson 截断标记",
            "diagnostics 高风险配置告警",
            "integrationCheck 平台类型告警",
            "Adapter 创建失败回退 Gson",
            "SerializedName + JsonAdapter",
            "Android 平台类型跳过",
            "Retrofit 空响应策略",
            "Retrofit Unit/Void 空响应",
            "Retrofit rawJson 捕获和跳过",
            "Retrofit rawJson 未知长度有界捕获",
            "Retrofit 观察者失败隔离",
            "Retrofit 请求体与字符串 Converter 委托",
            "Retrofit create(gson)",
            "Retrofit create(gson, config)"
        )

        assertTrue("demo 用例标题不能重复", titles.size == titles.toSet().size)
        assertTrue("缺少关键功能用例：${requiredTitles - titles.toSet()}", titles.containsAll(requiredTitles))
    }

    @Test
    fun demoCasesCoverDocumentedMismatchMatrixCapabilities() {
        val docs = listOf(
            java.io.File("../docs/mismatch-capability-matrix.md").readText(),
            java.io.File("../docs/en/mismatch-capability-matrix.md").readText()
        ).joinToString("\n")
        val documentedIds = Regex("capability-id:\\s*([a-z0-9-]+)").findAll(docs)
            .map { match -> match.groupValues[1] }
            .toSet()
        val caseIds = DemoCaseRegistry.cases.flatMap { demoCase -> demoCase.capabilityIds }.toSet()

        assertTrue("文档矩阵必须有 capability-id 标识", documentedIds.isNotEmpty())
        assertTrue("demo 用例必须覆盖文档矩阵能力：${documentedIds - caseIds}", documentedIds.all(caseIds::contains))
        assertTrue("demo 用例不能保留文档不存在的能力 ID：${caseIds - documentedIds}", caseIds.all(documentedIds::contains))
    }

    @Test
    fun allDemoCasesPassWithDefaultJson() {
        val results = DemoCaseRegistry.runAll()
        val failures = results.filterNot { (_, result) -> result.pass }

        assertTrue(
            failures.joinToString(separator = "\n\n") { (demoCase, result) ->
                "${demoCase.title}\nactual=${result.actual}\nevents=${result.events}\nerror=${result.error}"
            },
            failures.isEmpty()
        )
    }

    @Test
    fun customValidatorProvidesUserJsonTargetsAndPolicies() {
        val targetTitles = DemoCustomValidator.targets.map { it.title }
        val policyTitles = DemoCustomValidator.policies.map { it.title }
        val entryTitles = DemoCustomValidator.entries.map { it.title }

        assertTrue(targetTitles.contains("ApiResponse<User>"))
        assertTrue(targetTitles.contains("List<User>"))
        assertTrue(targetTitles.contains("CollectionResponse"))
        assertTrue(targetTitles.contains("NullableCollectionMapResponse"))
        assertTrue(targetTitles.contains("MapEdgeResponse"))
        assertTrue(targetTitles.contains("EnumContainerResponse"))
        assertTrue(policyTitles.contains("默认契约优先"))
        assertTrue(policyTitles.contains("低误伤 NullOnly"))
        assertTrue(policyTitles.contains("调试 rawJson"))
        assertTrue(policyTitles.contains("基础类型交回 Gson"))
        assertTrue(policyTitles.contains("Object 数字 Long/Double"))
        assertTrue(policyTitles.contains("JSON 形态转换"))
        assertTrue(policyTitles.contains("rawJson 10 字节截断"))
        assertTrue(entryTitles.contains("Core fromJson"))
        assertTrue(entryTitles.contains("Retrofit Converter"))
    }

    @Test
    fun customValidatorComparesSafeParserWithNativeGson() {
        val apiResponseIndex = DemoCustomValidator.targets.indexOfFirst { it.title == "ApiResponse<User>" }
        val defaultPolicyIndex = DemoCustomValidator.policies.indexOfFirst { it.title == "默认契约优先" }
        val result = DemoCustomValidator.validate(
            json = """{"code":200,"data":[]}""",
            targetIndex = apiResponseIndex,
            policyIndex = defaultPolicyIndex
        )

        assertTrue(result.pass)
        assertTrue(result.actual.contains("SafeParser 解析成功"))
        assertTrue(result.actual.contains("原生 Gson 解析失败"))
        assertTrue(result.events.contains("$.data"))
    }

    @Test
    fun customValidatorCanEnableShapeCoercionForUserJson() {
        val apiResponseIndex = DemoCustomValidator.targets.indexOfFirst { it.title == "NullableApiResponse<User>" }
        val shapeCoercionPolicyIndex = DemoCustomValidator.policies.indexOfFirst { it.title == "JSON 形态转换" }
        val result = DemoCustomValidator.validate(
            json = """{"code":200,"data":[{"id":9,"name":"Tom"}]}""",
            targetIndex = apiResponseIndex,
            policyIndex = shapeCoercionPolicyIndex
        )

        assertTrue(result.pass)
        assertTrue(result.actual.contains("解析策略：JSON 形态转换"))
        assertTrue(result.previewOutput.orEmpty().contains("Tom"))
        assertTrue(result.events.contains("ShapeCoercion"))
        assertTrue(result.events.contains("$.data"))
    }

    @Test
    fun diagnosticsFormatterExplainsModelObfuscationRisk() {
        val text = GsonSafeDiagnostics(
            safeAdapterAvailable = true,
            checks = listOf(
                GsonSafeDiagnosticCheck(
                    name = "modelFieldObfuscationSuspected",
                    severity = DiagnosticSeverity.ERROR,
                    message = "Model probe 'user' may be affected by field obfuscation."
                )
            )
        ).describe()

        assertTrue(text.contains("疑似模型字段被混淆"))
        assertTrue(text.contains("包级 keep"))
        assertTrue(text.contains("@SerializedName"))
    }

    @Test
    fun customValidatorShowsBackendReportSummaryAndStructuredRowsForRealJson() {
        val apiResponseIndex = DemoCustomValidator.targets.indexOfFirst { it.title == "ApiResponse<User>" }
        val defaultPolicyIndex = DemoCustomValidator.policies.indexOfFirst { it.title == "默认契约优先" }
        val result = DemoCustomValidator.validate(
            json = """{"code":200,"data":[]}""",
            targetIndex = apiResponseIndex,
            policyIndex = defaultPolicyIndex
        )

        assertTrue(result.contractReport.contains("契约汇总："))
        assertTrue(result.contractReport.contains("warningCount=1"))
        assertTrue(result.contractReport.contains("后端报告："))
        assertTrue(result.contractReport.contains("# Backend JSON Contract Report"))
        assertTrue(result.contractReport.contains("结构化行："))
        assertTrue(result.contractReport.contains("stableKey=category=TypeMismatch"))
        assertTrue(result.contractReport.contains("expectedJsonShape=JSON object"))
    }

    @Test
    fun outputBlockPreviewTrimsLongContractReportsBeforeTextViewRendering() {
        val longReport = (1..80).joinToString(separator = "\n") { index ->
            "report line $index: ${"field=value, ".repeat(8)}"
        }
        val preview = buildOutputBlockPreview(longReport)

        assertTrue("主页面预览不应继续把完整长报告塞进 TextView", preview.length < longReport.length)
        assertTrue("主页面预览应保留开头信息方便快速判断", preview.contains("report line 1"))
        assertTrue("主页面预览不应包含后半段长文本", !preview.contains("report line 80"))
        assertTrue("主页面预览应显式标记还有全文", preview.endsWith("..."))
    }

    @Test
    fun mainActivityKeepsFullOutputDetailsOutOfPreviewTextViews() {
        val outputController = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/ui/DemoOutputPanelController.kt").readText()

        assertTrue("运行结果应先保存契约报告全文，供弹窗和复制使用", outputController.contains("contractReportDetailText = cleanDisplayText(state.contractReport)"))
        assertTrue("主页面契约报告 TextView 只应接收短预览，避免真机滑动时测量长文本", outputController.contains("resultBinding.contractReportView.text = buildOutputBlockPreview(contractReportDetailText)"))
        assertTrue("契约报告详情弹窗应读取全文，而不是读取 TextView 里的短预览", outputController.contains("resultBinding.contractReportView") && outputController.contains("contractReportDetailText"))
        assertTrue("预览和全文不同的时候要显示详情入口", outputController.contains("contentProvider().trimStart() != previewView.text.toString().trimStart()"))
    }

    @Test
    fun customValidatorEnglishDisplayDoesNotLeakChineseSentences() {
        val apiResponseIndex = DemoCustomValidator.targets.indexOfFirst { it.title == "ApiResponse<User>" }
        val defaultPolicyIndex = DemoCustomValidator.policies.indexOfFirst { it.title == "默认契约优先" }
        val result = DemoCustomValidator.validate(
            json = """{"code":200,"data":[]}""",
            targetIndex = apiResponseIndex,
            policyIndex = defaultPolicyIndex
        )
        val englishActual = MainActivity.localizeTechnicalTextForDisplay(result.actual, isChineseDemo = false)

        assertTrue(
            "英文 demo 展示层不应继续泄漏中文说明句子：\n$englishActual",
            !Regex("\\p{IsHan}").containsMatchIn(englishActual)
        )
    }

    @Test
    fun customValidatorReportsInvalidJson() {
        val result = DemoCustomValidator.validate(
            json = "{",
            targetIndex = 0,
            policyIndex = 0
        )

        assertTrue(!result.pass)
        assertTrue(result.actual.contains("SafeParser 解析失败"))
        assertTrue(result.actual.contains("原生 Gson 解析失败"))
        assertTrue(result.error != null)
    }

    @Test
    fun customValidatorCanUseRetrofitConverterEntry() {
        val apiResponseIndex = DemoCustomValidator.targets.indexOfFirst { it.title == "ApiResponse<User>" }
        val defaultPolicyIndex = DemoCustomValidator.policies.indexOfFirst { it.title == "默认契约优先" }
        val retrofitEntryIndex = DemoCustomValidator.entries.indexOfFirst { it.title == "Retrofit Converter" }
        val result = DemoCustomValidator.validate(
            json = """{"code":200,"data":[]}""",
            targetIndex = apiResponseIndex,
            policyIndex = defaultPolicyIndex,
            entryIndex = retrofitEntryIndex
        )

        assertTrue(result.pass)
        assertTrue(result.actual.contains("验证入口：Retrofit Converter"))
        assertTrue(result.actual.contains("SafeParser 解析成功"))
        assertTrue(result.events.contains("$.data"))
    }

    @Test
    fun customValidatorExposesConcisePreviewOutputForQuickCompare() {
        val apiResponseIndex = DemoCustomValidator.targets.indexOfFirst { it.title == "ApiResponse<User>" }
        val defaultPolicyIndex = DemoCustomValidator.policies.indexOfFirst { it.title == "默认契约优先" }
        val result = DemoCustomValidator.validate(
            json = """{"code":200,"data":[]}""",
            targetIndex = apiResponseIndex,
            policyIndex = defaultPolicyIndex
        )

        assertTrue(result.previewOutput.isNullOrBlank().not())
        assertTrue("简洁预览不应该把完整对比报告也塞进去", !result.previewOutput!!.contains("原生 Gson"))
        assertTrue("完整 actual 仍然要保留原生 Gson 对比，方便继续排查", result.actual.contains("原生 Gson"))
    }

    @Test
    fun customValidatorPreviewTreatsNullSuccessAsSuccessNotFailure() {
        val apiResponseIndex = DemoCustomValidator.targets.indexOfFirst { it.title == "ApiResponse<User>" }
        val defaultPolicyIndex = DemoCustomValidator.policies.indexOfFirst { it.title == "默认契约优先" }
        val result = DemoCustomValidator.validate(
            json = "null",
            targetIndex = apiResponseIndex,
            policyIndex = defaultPolicyIndex
        )

        assertTrue("null 成功结果也应该有简洁预览", result.previewOutput?.isNotBlank() == true)
        assertTrue("成功返回 null 时预览不应该写成失败", !result.previewOutput!!.contains("解析失败"))
        assertTrue("成功返回 null 时预览应该直接展示 null", result.previewOutput.trim() == "null")
    }

    @Test
    fun runAllDetailedReportIncludesEveryCaseOutput() {
        val results = DemoCaseRegistry.runAll()
        val report = DemoCaseRegistry.buildDetailedReport(results)

        assertTrue(report.contains("总用例数：${DemoCaseRegistry.cases.size}"))
        assertTrue(report.contains("create + 对象字段错形兜底"))
        assertTrue(report.contains("解析输出："))
        assertTrue(report.contains("事件流："))
    }

    @Test
    fun detailedReportPlacesDefaultValueNoteOnceNearReportSummary() {
        val report = DemoCaseRegistry.buildDetailedReport(
            DemoCaseRegistry.runAll()
        )
        val summaryIndex = report.indexOf("失败：")
        val noteIndex = report.indexOf("默认值说明：")
        val firstCaseIndex = report.indexOf("1. ")
        val noteCount = Regex("默认值说明：").findAll(report).count()

        assertTrue("默认值说明应该在报告总览后出现一次，避免每个用例重复刷屏", summaryIndex >= 0 && noteIndex > summaryIndex)
        assertTrue("默认值说明应该放在第一个用例之前，先解释默认值，再看用例细节", firstCaseIndex > noteIndex)
        assertTrue("完整报告里默认值说明只需要出现一次", noteCount == 1)
    }

    @Test
    fun detailedReportAddsChineseContractSummaryBeforeRawMarkdown() {
        val report = DemoCaseRegistry.buildDetailedReport(
            listOf(DemoCaseRegistry.cases.first() to DemoCaseRegistry.cases.first().run("""{"code":200,"data":[]}"""))
        )
        val summaryIndex = report.indexOf("契约摘要：")
        val rawIndex = report.indexOf("契约报告原文：")

        assertTrue("中文详细报告应先给小白能读懂的契约摘要，再保留原始报告", summaryIndex >= 0 && rawIndex > summaryIndex)
        assertTrue("契约摘要应说明类型错配和字段路径", report.contains("类型错配：字段 $.data"))
        assertTrue("原始 Markdown 报告仍要保留，方便开发者定位完整细节", report.contains("# Safe Parse Contract Report"))
    }

    @Test
    fun detailedReportNormalizesCopiedTextWhitespace() {
        val report = DemoCaseRegistry.buildDetailedReport(
            listOf(DemoCaseRegistry.cases.first() to DemoCaseRegistry.cases.first().run("""{"code":200,"data":[]}"""))
        )

        assertTrue("完整报告不应出现连续三行以上空白", !report.contains("\n\n\n"))
        assertTrue("完整报告不应保留 Kotlin 模板缩进导致的大段行首空格", !Regex("(?m)^ {8,}\\S").containsMatchIn(report))
    }

    @Test
    fun detailedReportKeepsEventStreamConsistentForManualDispatchCase() {
        val demoCase = DemoCaseRegistry.cases.first { it.title == "dispatchEvent 低层事件桥接" }
        val result = demoCase.run(demoCase.defaultJson)
        val report = DemoCaseRegistry.buildDetailedReport(listOf(demoCase to result))

        assertTrue("dispatchEvent 用例本身就是验证低层事件桥接，事件流不应该显示无事件", result.events.contains("空响应"))
        assertTrue("详细报告应展示 EmptyResponse 事件流", report.contains("事件流：\n空响应（EmptyResponse）"))
    }

    @Test
    fun observableDemoCasesShowEventsWhenFallbackActuallyHappens() {
        val titles = listOf(
            "fromJsonSafe<T> + reified 兜底",
            "Retrofit create(gson)"
        )
        titles.forEach { title ->
            val demoCase = DemoCaseRegistry.cases.first { it.title == title }
            val result = demoCase.run(demoCase.defaultJson)

            assertTrue("$title 发生兜底时应该在 demo 事件流里展示 $.data", result.events.contains("$.data"))
        }
    }

    @Test
    fun demoFeaturePagesReduceSinglePanelComplexity() {
        val pages = DemoPageRegistry.pages
        val pageTitles = pages.map { it.title }

        assertTrue(pageTitles.contains("快速体检"))
        assertTrue(pageTitles.contains("用户 JSON 验证"))
        assertTrue(pageTitles.contains("核心解析"))
        assertTrue(pageTitles.contains("配置与接入"))
        assertTrue(pageTitles.contains("可观测性"))
        assertTrue(pageTitles.contains("Retrofit"))
        assertTrue("每个页面最多保留 3 个主要操作，避免重新变成按钮堆", pages.all { it.primaryActions.size <= 3 })
        assertTrue("固定用例必须被功能页完整覆盖", pages.flatMap { it.cases }.toSet().containsAll(DemoCaseRegistry.cases))
    }

    @Test
    fun demoRegistryFileStaysSmallAfterSplit() {
        val registryFile = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/DemoCaseRegistry.kt")

        assertTrue("DemoCaseRegistry.kt 应只保留聚合逻辑，不能继续塞模型、验证器和所有 case", registryFile.readLines().size <= 180)
    }

    @Test
    fun mainActivityUsesViewBindingAndDelegatesBulkyUiWork() {
        val mainActivity = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/MainActivity.kt").readText()
        val buildGradle = java.io.File("build.gradle.kts").readText()
        val demoRoot = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo")

        assertTrue("Demo App 应开启 ViewBinding，避免 Activity 里继续堆 findViewById", buildGradle.contains("viewBinding = true"))
        assertTrue("MainActivity 应通过 ActivityMainBinding 持有页面根布局", mainActivity.contains("ActivityMainBinding"))
        assertTrue("MainActivity 不应再手动 findViewById，dialog 也应改成 binding", !mainActivity.contains("findViewById"))
        assertTrue("MainActivity 不应继续保留 bindViews 大函数", !mainActivity.contains("private fun bindViews"))
        assertTrue("输出块绑定、预览、详情弹窗应拆出控制器", java.io.File(demoRoot, "ui/DemoOutputPanelController.kt").exists())
        assertTrue("中文/英文技术文案转换应拆出工具类", java.io.File(demoRoot, "support/DemoTextLocalizer.kt").exists())
        assertTrue("MainActivity 应控制在 850 行以内，避免继续承担过多职责", mainActivity.lines().size <= 850)
    }

    @Test
    fun mainLayoutUsesPreviewFriendlyPageIncludes() {
        val layoutDir = java.io.File("src/main/res/layout")
        val mainLayout = java.io.File(layoutDir, "activity_main.xml").readText()

        assertTrue("activity_main.xml 应控制在 150 行以内，复杂面板拆到 include 文件", mainLayout.lines().size <= 150)
        assertTrue(mainLayout.contains("tools:context"))
        assertTrue(mainLayout.contains("@layout/include_full_check_panel"))
        assertTrue(mainLayout.contains("@layout/include_fixed_case_panel"))
        assertTrue(mainLayout.contains("@layout/include_custom_json_panel"))
        assertTrue(mainLayout.contains("@layout/include_output_preview_panel"))
        assertTrue(mainLayout.contains("@layout/include_result_panel"))
        assertTrue(
            "输出预览模块应该放在 jsonInput 后、详情面板前",
            mainLayout.indexOf("@layout/include_output_preview_panel") < mainLayout.indexOf("@layout/include_result_panel")
        )
        assertTrue(java.io.File(layoutDir, "include_full_check_panel.xml").exists())
        assertTrue(java.io.File(layoutDir, "include_fixed_case_panel.xml").exists())
        assertTrue(java.io.File(layoutDir, "include_custom_json_panel.xml").exists())
        assertTrue(java.io.File(layoutDir, "include_output_preview_panel.xml").exists())
        assertTrue(java.io.File(layoutDir, "include_result_panel.xml").exists())
    }

    @Test
    fun demoUiProtectsTitleFromStatusBarAndKeepsJsonInputBounded() {
        val mainLayout = java.io.File("src/main/res/layout/activity_main.xml").readText()

        assertTrue("页面根布局要处理状态栏安全区，避免标题和内容被状态栏遮住", mainLayout.contains("android:fitsSystemWindows=\"true\""))
        assertTrue("页面顶部标题应该有稳定 id，方便人工和自动化验证首屏标题", mainLayout.contains("android:id=\"@+id/screenTitle\""))
        assertTrue("固定用例和用户输入都应共用同一个 JSON 输入框", mainLayout.contains("android:id=\"@+id/jsonInput\""))
        assertTrue("JSON 输入框需要最大高度，长 JSON 在输入框内部滚动", mainLayout.contains("android:maxHeight=\"@dimen/demo_json_input_max_height\""))
        assertTrue("JSON 输入框需要显示纵向滚动条，方便真机查看长 JSON", mainLayout.contains("android:scrollbars=\"vertical\""))
    }

    @Test
    fun demoUiUsesCustomButtonStylesAndBoundedErrorArea() {
        val layoutDir = java.io.File("src/main/res/layout")
        val buttonPanelText = listOf(
            "include_full_check_panel.xml",
            "include_fixed_case_panel.xml",
            "include_custom_json_panel.xml",
            "include_result_panel.xml"
        ).joinToString(separator = "\n") { fileName ->
            java.io.File(layoutDir, fileName).readText()
        }
        val buttonCount = Regex("<Button\\b").findAll(buttonPanelText).count()
        val styledButtonCount = Regex("<Button[\\s\\S]*?style=\"@style/Demo(Primary|Secondary)Button\"")
            .findAll(buttonPanelText)
            .count()
        val resultPanel = java.io.File(layoutDir, "include_result_panel.xml").readText()

        assertTrue("demo 里的 Button 不应使用系统默认样式", buttonCount > 0 && buttonCount == styledButtonCount)
        assertTrue("主按钮背景 drawable 需要存在", java.io.File("src/main/res/drawable/bg_demo_button_primary.xml").exists())
        assertTrue("次按钮背景 drawable 需要存在", java.io.File("src/main/res/drawable/bg_demo_button_secondary.xml").exists())
        assertTrue("异常信息区域应和其他输出块一样做预览，避免页面内嵌套滚动", resultPanel.contains("android:id=\"@+id/errorView\""))
        assertTrue("全文查看应交给单独弹窗，而不是在主页面里继续套滚动容器", java.io.File(layoutDir, "dialog_output_detail.xml").exists())
        assertTrue("一键自检页的复制按钮要明确说明复制的是详细报告", java.io.File("src/main/res/values/strings.xml").readText().contains("Copy detailed self-check report"))
        assertTrue("一键自检页的复制按钮要和“复制当前结果”区分开", java.io.File("src/main/res/values/strings.xml").readText().contains("Copy current panel content"))
    }

    @Test
    fun demoFullCheckCopyButtonAndDialogHeightAreMoreExplicit() {
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        val zhStrings = java.io.File("src/main/res/values-zh/strings.xml").readText()
        val dimens = java.io.File("src/main/res/values/dimens.xml").readText()
        val dialogLayout = java.io.File("src/main/res/layout/dialog_output_detail.xml").readText()

        assertTrue("英文按钮文案要直接说清楚复制的是一键自检详细报告", strings.contains("Copy detailed self-check report"))
        assertTrue("中文按钮文案要直接说清楚复制的是一键自检详细报告", zhStrings.contains("复制一键自检详细报告"))
        assertTrue("当前结果按钮要明确是当前面板，不要和自检详细报告混在一起", zhStrings.contains("复制当前面板内容"))
        assertTrue("主页面输出块的最大高度也要扩大一倍", dimens.contains("demo_output_max_height\">360dp"))
        assertTrue("主页面预览行数要扩大一倍，减少频繁点详情弹窗", java.io.File("src/main/res/layout/include_result_panel.xml").readText().contains("android:maxLines=\"16\""))
        assertTrue("输出详情弹窗的高度要保持适中，不要过高", dimens.contains("demo_detail_dialog_height\">360dp"))
        assertTrue("输出详情弹窗要继续引用统一的高度维度", dialogLayout.contains("@dimen/demo_detail_dialog_height"))
    }

    @Test
    fun demoUiHidesDeveloperOnlyCaseIndexAndTrimsResultText() {
        val mainActivity = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/MainActivity.kt").readText()
        val zhStrings = java.io.File("src/main/res/values-zh/strings.xml").readText()

        assertTrue("用例说明不应展示“当前用例 1/22”这类内部页码", !mainActivity.contains("当前用例：$"))
        assertTrue("解析输出、事件、报告和异常展示前应统一去掉开头空白", mainActivity.contains("cleanDisplayText("))
        assertTrue("填入示例应说明示例已经写入同一个输入区域", zhStrings.contains("示例 JSON 已填入下方输入框"))
    }

    @Test
    fun demoEventLogsUseChineseLabelsForBeginners() {
        val report = listOf(
            SafeParserEvent.TypeMismatch(
                TypeMismatchEvent(
                    expectedType = "User",
                    actualToken = JsonToken.BEGIN_ARRAY,
                    path = "$.data",
                    reason = "Expected object",
                    kind = ParseExceptionKind.OBJECT,
                    fieldName = "data",
                    rawJson = """{"data":[]}""",
                    rawJsonTruncated = false
                )
            )
        ).describeEvents()

        assertTrue("事件标题要用中文说明问题类型", report.contains("类型错配"))
        assertTrue("字段路径要用中文标签，小白才能知道看哪里", report.contains("字段路径=$.data"))
        assertTrue("期望类型要用中文标签", report.contains("期望类型=User"))
        assertTrue("实际 JSON 类型要用中文标签", report.contains("实际 JSON 类型=BEGIN_ARRAY"))
        assertTrue("日志里不应只暴露英文内部事件名", !report.contains("TypeMismatch\n"))
    }

    @Test
    fun demoStaticTextsMoveToEnglishAndChineseStringResources() {
        val valuesDir = java.io.File("src/main/res/values")
        val zhValuesDir = java.io.File("src/main/res/values-zh")
        val strings = java.io.File(valuesDir, "strings.xml")
        val zhStrings = java.io.File(zhValuesDir, "strings.xml")
        val mainLayout = java.io.File("src/main/res/layout/activity_main.xml").readText()
        val resultPanel = java.io.File("src/main/res/layout/include_result_panel.xml").readText()

        assertTrue("默认 strings.xml 应提供英文 demo 文案", strings.exists())
        assertTrue("values-zh/strings.xml 应提供中文 demo 文案", zhStrings.exists())
        assertTrue("英文资源里要有 demo 标题", strings.readText().contains("GsonSafeParser Demo"))
        assertTrue("中文资源里要有 demo 标题", zhStrings.readText().contains("GsonSafeParser 示例"))
        assertTrue("英文资源里要有简洁输出预览标题", strings.readText().contains("Parsed result preview"))
        assertTrue("中文资源里要有简洁输出预览标题", zhStrings.readText().contains("解析结果预览"))
        assertTrue("主页面标题应引用 string 资源", mainLayout.contains("@string/demo_screen_title"))
        assertTrue("输出预览模块标题应引用 string 资源", java.io.File("src/main/res/layout/include_output_preview_panel.xml").readText().contains("@string/demo_output_preview_title"))
        assertTrue("结果面板标题应引用 string 资源", resultPanel.contains("@string/demo_result_title"))
    }

    @Test
    fun demoChineseStringsUseLocalizedDeveloperLanguage() {
        val zhStrings = java.io.File("src/main/res/values-zh/strings.xml").readText()

        assertTrue("中文标题应按国内开发者习惯叫“示例”，不要直译成演示", zhStrings.contains("GsonSafeParser 示例"))
        assertTrue("功能页下拉应说“测试场景”，比功能页更像调试工具", zhStrings.contains("选择测试场景"))
        assertTrue("用户自定义 JSON 页面应强调接口 JSON 调试", zhStrings.contains("接口 JSON 调试"))
        assertTrue("目标类型应翻成本地化说明", zhStrings.contains("要解析成的类型"))
        assertTrue("解析策略应翻成更贴近兜底框架的说法", zhStrings.contains("兜底策略"))
        assertTrue("按钮应使用国内工具常见动作词", zhStrings.contains("开始验证"))
        assertTrue("全量检查动作应更像测试入口", zhStrings.contains("一键自检"))
        assertTrue("中文资源不应保留生硬的“用户 JSON 验证”", !zhStrings.contains(">用户 JSON 验证<"))
        assertTrue("中文资源不应保留生硬的“全量体检”", !zhStrings.contains(">全量体检<"))
        assertTrue("中文资源不应保留生硬的“点击选择”标签", !zhStrings.contains("点击选择"))
        assertTrue("中文资源不应保留“原始用例预期”这种翻译腔", !zhStrings.contains("原始用例预期"))
    }

    @Test
    fun demoSpinnersLookClickableAndExplainCurrentSelection() {
        val layoutDir = java.io.File("src/main/res/layout")
        val mainLayout = java.io.File(layoutDir, "activity_main.xml").readText()
        val fixedCasePanel = java.io.File(layoutDir, "include_fixed_case_panel.xml").readText()
        val customPanel = java.io.File(layoutDir, "include_custom_json_panel.xml").readText()
        val mainActivity = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/MainActivity.kt").readText()

        assertTrue("Spinner 需要统一样式，让用户看出这里可以点击", mainLayout.contains("style=\"@style/DemoSpinner\""))
        assertTrue("固定用例 Spinner 需要统一样式", fixedCasePanel.contains("style=\"@style/DemoSpinner\""))
        assertTrue("用户 JSON 验证区的 Spinner 需要统一样式", customPanel.contains("style=\"@style/DemoSpinner\""))
        assertTrue("Spinner 背景需要有下拉箭头或点击暗示", java.io.File("src/main/res/drawable/bg_demo_spinner.xml").exists())
        assertTrue("自定义验证区需要展示当前入口、目标类型和策略说明", customPanel.contains("android:id=\"@+id/customSelectionInfoView\""))
        assertTrue("选择入口、目标类型或策略后要刷新说明", mainActivity.contains("updateCustomSelectionInfo()"))
    }

    @Test
    fun demoFormatsJsonAndSeparatesProblemFromOutputExplanation() {
        val result = DemoCustomValidator.validate(
            json = """{"code":200,"data":[]}""",
            targetIndex = 0,
            policyIndex = 0
        )
        val mainActivity = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/MainActivity.kt").readText()

        assertTrue("用户输入 JSON 应在填入示例和运行前格式化", mainActivity.contains("formatJsonForDisplay("))
        assertTrue("解析输出面板要先说明问题，再展示结果", mainActivity.contains("buildActualDisplayText("))
        assertTrue("自定义验证输出要有问题描述分区", result.actual.contains("问题描述："))
        assertTrue("自定义验证输出要有 SafeParser 结果分区", result.actual.contains("SafeParser 结果："))
        assertTrue("自定义验证输出要有原生 Gson 对比分区", result.actual.contains("原生 Gson 对比："))
        assertTrue("自定义验证输出要有接入建议分区", result.actual.contains("接入建议："))
    }

    @Test
    fun demoSubdirectoriesUseMatchingKotlinPackages() {
        val sourceRoot = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo")
        val expectedPackages = mapOf(
            "model/DemoModels.kt" to "package io.github.logan.gsonsafeparser.demo.model",
            "support/DemoCaseTypes.kt" to "package io.github.logan.gsonsafeparser.demo.support",
            "support/DemoCustomValidator.kt" to "package io.github.logan.gsonsafeparser.demo.support",
            "support/DemoResultFormatters.kt" to "package io.github.logan.gsonsafeparser.demo.support",
            "page/DemoPageRegistry.kt" to "package io.github.logan.gsonsafeparser.demo.page"
        )
        val caseFiles = java.io.File(sourceRoot, "cases").listFiles { file -> file.name.endsWith("DemoCases.kt") }.orEmpty()

        expectedPackages.forEach { (fileName, expectedPackage) ->
            val firstLine = java.io.File(sourceRoot, fileName).readLines().first()
            assertTrue("$fileName 的 package 应和目录一致，避免 IDE 导包混乱", firstLine == expectedPackage)
        }
        assertTrue(
            "cases 目录下的用例文件都应声明 .cases 包",
            caseFiles.isNotEmpty() && caseFiles.all { file ->
                file.readLines().first() == "package io.github.logan.gsonsafeparser.demo.cases"
            }
        )
    }

    @Test
    fun demoOutputAreasAreBoundedScrollableAndLogcatStyled() {
        val resultPanel = java.io.File("src/main/res/layout/include_result_panel.xml").readText()
        val styles = java.io.File("src/main/res/values/styles.xml").readText()
        val dimens = java.io.File("src/main/res/values/dimens.xml").readText()

        assertTrue("解析输出、预期、事件、报告、诊断和异常都应是黑底预览 TextView", Regex("style=\"@style/DemoOutputBlock\"").findAll(resultPanel).count() >= 8)
        assertTrue("输出预览需要限制行数，避免主页面被长日志撑爆", Regex("android:maxLines=\"16\"").findAll(resultPanel).count() >= 7)
        assertTrue("输出预览还要有统一的最大高度限制，避免少量超长内容把页面拉太长", styles.contains("@dimen/demo_output_max_height"))
        assertTrue("普通输出区最大高度也要翻倍，方便先在页面里看更多内容", dimens.contains("demo_output_max_height\">360dp"))
        assertTrue("输出预览需要省略长文本，全文交给详情弹窗查看", Regex("android:ellipsize=\"end\"").findAll(resultPanel).count() >= 7)
        assertTrue("输出块背景应接近 Android Studio Logcat 深色背景", styles.contains("<item name=\"android:background\">#1E1E1E</item>"))
        assertTrue("输出文字应使用浅色，保证深色背景可读", styles.contains("<item name=\"android:textColor\">#D7E7E7</item>"))
        assertTrue("输出块不应允许文本选择，避免系统复制浮层打断页面滑动", styles.contains("<item name=\"android:textIsSelectable\">false</item>"))
    }

    @Test
    fun demoDoesNotForceScrollWhenSpinnerSelectionChanges() {
        val mainActivity = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/MainActivity.kt").readText()

        assertTrue("Spinner 选择后不应通过 EditText 光标强制页面滚动", !mainActivity.contains("jsonInput.setSelection(formatted.length)"))
        assertTrue("选择变化后应只刷新说明，不应滚动到输入框或结果区", mainActivity.contains("updateCustomSelectionInfo()"))
    }

    @Test
    fun demoTextBuildersDoNotKeepKotlinTemplateIndentation() {
        val validator = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/support/DemoCustomValidator.kt").readText()
        val mainActivity = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/MainActivity.kt").readText()

        assertTrue("自定义验证输出要用 buildString 组装，避免 JSON 多行缩进让 trimIndent 失效", validator.contains("buildActualText") && validator.contains("buildString"))
        assertTrue("复制文本要用 buildString 组装，避免复制结果每行带一大段空格", mainActivity.contains("buildCopyText") && mainActivity.contains("appendLine"))
    }

    @Test
    fun demoHasEnglishDisplayLayerForFixedCasesAndReports() {
        val mainActivity = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/MainActivity.kt").readText()

        assertTrue("固定用例输出需要英文展示层，不能只显示 xxDemoCase 里的中文文案", mainActivity.contains("localizeTechnicalText("))
        assertTrue("全量报告需要按当前语言生成，不能直接复制中文 DemoCaseRegistry 报告", mainActivity.contains("buildLocalizedDetailedReport("))
    }

    @Test
    fun demoFillSamplePreservesCurrentPageScroll() {
        val mainActivity = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/MainActivity.kt").readText()

        assertTrue("Activity 应通过 binding 持有 rootScrollView，填入示例时才能恢复用户当前阅读位置", mainActivity.contains("binding.rootScrollView"))
        assertTrue("设置示例 JSON 前后应保留外层页面 scrollY，避免点击填入示例后自动下滑", mainActivity.contains("preserveRootScrollWhile"))
        assertTrue("恢复滚动要在布局更新后执行，否则 setText 后的布局请求仍可能把页面带到输入框", mainActivity.contains("binding.rootScrollView.post"))
        assertTrue("填入示例前应清理输入框焦点，避免 EditText 抢焦点触发 ScrollView 自动对齐", mainActivity.contains("jsonInput.clearFocus()"))
        assertTrue("输出块不应再参与页面内嵌套滚动手势", !mainActivity.contains("actualView.keepParentFromStealingVerticalScroll()"))
    }

    @Test
    fun demoOutputPanelsWrapContentButStopAtMaxHeight() {
        val resultPanel = java.io.File("src/main/res/layout/include_result_panel.xml").readText()
        val styles = java.io.File("src/main/res/values/styles.xml").readText()
        val outputController = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/ui/DemoOutputPanelController.kt").readText()

        assertTrue("结果面板不应继续嵌套多个 ScrollView，避免手势互相抢占", !resultPanel.contains("BoundedScrollView"))
        assertTrue("输出块右上角应有提示图标，告诉用户这里可点开全文", Regex("@style/DemoOutputHintButton").findAll(resultPanel).count() >= 7)
        assertTrue("输出块应保留黑底预览，不再自带内部滚动容器", resultPanel.contains("android:maxLines=\"16\""))
        assertTrue("输出块应有统一的最大高度上限", styles.contains("@dimen/demo_output_max_height"))
        assertTrue("输出块应使用省略方式展示预览，不应在页面里自带滚动条", resultPanel.contains("android:ellipsize=\"end\""))
        assertTrue("输出块提示图标要带可读的无障碍说明", resultPanel.contains("@string/demo_output_hint_button_desc") && java.io.File("src/main/res/values/strings.xml").readText().contains("Open details"))
        assertTrue("输出块不应再允许文本选择，否则系统会弹出复制浮层", !styles.contains("android:textIsSelectable\">true</item>"))
        assertTrue("点击输出块应该进入全文详情弹窗，而不是留在嵌套滚动里", outputController.contains("showOutputDetailDialog("))
        assertTrue("右上角提示图标也应绑定同一个详情弹窗行为", outputController.contains("bindOutputPreview("))
        assertTrue("详情弹窗需要复制当前块内容，方便单独查看和比对", outputController.contains("copySectionButton"))
    }

    @Test
    fun demoOutputHintIconUsesExpandMeaningAndHidesWhenContentFits() {
        val resultPanel = java.io.File("src/main/res/layout/include_result_panel.xml").readText()
        val outputController = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/ui/DemoOutputPanelController.kt").readText()

        assertTrue("提示图标应该表达展开或查看详情，而不是感叹号", resultPanel.contains("@drawable/ic_demo_open_detail"))
        assertTrue("提示图标默认应该先隐藏，只有内容放不下时再显示", Regex("android:visibility=\"gone\"").findAll(resultPanel).count() >= 7)
        assertTrue("提示图标需要有按内容是否截断来显示的判断逻辑", outputController.contains("updateOutputHintVisibility("))
    }

    @Test
    fun demoOutputHintVisibilityIsRefreshedAfterTextChanges() {
        val mainActivity = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/MainActivity.kt").readText()
        val outputController = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/ui/DemoOutputPanelController.kt").readText()

        assertTrue("输出块文字变化后要主动刷新提示图标，不能只靠 layout 变化", outputController.contains("refreshOutputHintVisibility()"))
        assertTrue("运行结果和待运行占位都应刷新提示图标", mainActivity.contains("renderResult(result: DemoRunResult)") && mainActivity.contains("setPendingResult(expected: String, actual: String)"))
    }

    @Test
    fun demoEnglishLocalizationKeepsRawJsonValuesUntouched() {
        val input = """
            问题描述：
            这次验证会把同一份 JSON 分别交给 SafeParser 和原生 Gson。

            输出：
            {
              "message": "失败"
            }

            接入建议：
            先看 JSON，再看事件。
        """.trimIndent()

        val localized = MainActivity.localizeTechnicalTextForDisplay(input, isChineseDemo = false)

        assertTrue("结构性文案应该翻成英文", localized.contains("Problem description:"))
        assertTrue("JSON 里的字符串值不应该被翻译掉", localized.contains("\"message\": \"失败\""))
        assertTrue("JSON 里的字符串值不应该被替换成英文", !localized.contains("\"message\": \"Fail\""))
    }

    @Test
    fun demoExplainsModelDefaultValuesNearExpectedOutput() {
        val mainActivity = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/MainActivity.kt").readText()
        val outputController = java.io.File("src/main/kotlin/io/github/logan/gsonsafeparser/demo/ui/DemoOutputPanelController.kt").readText()
        val previewLayout = java.io.File("src/main/res/layout/include_output_preview_panel.xml").readText()
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        val zhStrings = java.io.File("src/main/res/values-zh/strings.xml").readText()
        val previewFunction = mainActivity.substringAfter("private fun buildPreviewOutputText").substringBefore("private fun buildPendingActualDisplayText")
        val expectedFunction = mainActivity.substringAfter("private fun buildExpectedDisplayText").substringBefore("private fun setJsonInputText")

        assertTrue("默认值说明应在解析结果预览块外独立展示，不应混入黑底输出内容", previewLayout.contains("android:id=\"@+id/outputPreviewNoteView\""))
        assertTrue("解析结果预览文本只展示解析输出，不应再拼接默认值说明", !previewFunction.contains("demo_default_value_note"))
        assertTrue("Activity 需要把默认值说明交给输出控制器", outputController.contains("outputPreviewNoteView"))
        assertTrue("预期结果面板不应继续显示默认值说明", !expectedFunction.contains("demo_default_value_note"))
        assertTrue("英文 demo 也要说明默认值，避免国外用户误以为预期和实际不一致", strings.contains("Default value note"))
        assertTrue("中文 demo 要用人话说明 DemoModels.kt 默认值和实际 JSON 输出的关系", zhStrings.contains("默认值说明"))
        assertTrue("说明里要给出 User() 等价展开示例，用户才能马上对上实际输出", zhStrings.contains("User() 等价"))
    }
}
