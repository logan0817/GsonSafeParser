package io.github.logan.gsonsafeparser.demo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import io.github.logan.gsonsafeparser.demo.page.DemoFeaturePage
import io.github.logan.gsonsafeparser.demo.page.DemoPageMode
import io.github.logan.gsonsafeparser.demo.page.DemoPageRegistry
import io.github.logan.gsonsafeparser.demo.databinding.ActivityMainBinding
import io.github.logan.gsonsafeparser.demo.support.DemoCase
import io.github.logan.gsonsafeparser.demo.support.DemoCustomValidator
import io.github.logan.gsonsafeparser.demo.support.DemoTextLocalizer
import io.github.logan.gsonsafeparser.demo.support.DemoRunResult
import io.github.logan.gsonsafeparser.demo.ui.DemoOutputPanelController
import io.github.logan.gsonsafeparser.demo.ui.DemoOutputPanelState
import io.github.logan.gsonsafeparser.demo.support.formatJsonForDisplay
import io.github.logan.gsonsafeparser.demo.support.normalizeReportText
import io.github.logan.gsonsafeparser.demo.support.sanitizeClipboardReportText
import io.github.logan.gsonsafeparser.demo.support.cleanDisplayText
import io.github.logan.gsonsafeparser.demo.support.shouldShowModelDefaultValueNote
import java.util.Locale

/**
 * GsonSafeParser 示例 App 主页面。
 *
 * 页面按功能页组织：快速体检、固定功能用例和用户 JSON 验证分开展示。
 * 这样真机测试时只需要先选页面，再执行当前页面的少量主操作，不需要在一个大面板里找按钮。
 */
class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var outputPanelController: DemoOutputPanelController

    private val pages: List<DemoFeaturePage> = DemoPageRegistry.pages
    private var currentPage: DemoFeaturePage = pages.first()
    private var currentCases: List<DemoCase> = emptyList()
    private var currentCaseTitle: String = ""
    private var currentProblemDescription: String = ""
    private var lastCopyText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        outputPanelController = DemoOutputPanelController(this, binding.outputPreviewPanel, binding.resultPanel)
        outputPanelController.initialize(
            getString(
                R.string.demo_default_value_note_format,
                getString(R.string.demo_default_value_note_section),
                getString(R.string.demo_default_value_note)
            )
        )
        setupBoundedTextAreas()
        setupPageSelector()
        setupCustomValidationPanel()
        setupActions()
        showPage(pages.first())
    }

    /**
     * 让 JSON 输入框在外层页面里保持独立滚动手感。
     *
     * 结果输出区不再做页面内嵌套滚动，只保留预览；长内容统一点开弹窗查看。
     * 因此主页面只需要处理 JSON 输入框这一处真正的内部滚动。
     */
    private fun setupBoundedTextAreas() {
        binding.jsonInput.keepParentFromStealingVerticalScroll()
    }

    /**
     * 初始化功能页选择器。
     */
    private fun setupPageSelector() {
        binding.pageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            pages.map { page -> localizedPageTitle(page) }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.pageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showPage(pages[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * 初始化用户 JSON 验证区。
     *
     * 这个区只在“用户 JSON 验证”页面展示，避免固定用例页面同时出现三组下拉框。
     */
    private fun setupCustomValidationPanel() {
        val customPanel = binding.customJsonPanel
        customPanel.customEntrySpinner.adapter = simpleSpinnerAdapter(DemoCustomValidator.entries.indices.map(::localizedEntryTitle))
        customPanel.customTargetSpinner.adapter = simpleSpinnerAdapter(DemoCustomValidator.targets.indices.map(::localizedTargetTitle))
        customPanel.customPolicySpinner.adapter = simpleSpinnerAdapter(DemoCustomValidator.policies.indices.map(::localizedPolicyTitle))
        val selectionListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCustomSelectionInfo()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        customPanel.customEntrySpinner.onItemSelectedListener = selectionListener
        customPanel.customTargetSpinner.onItemSelectedListener = selectionListener
        customPanel.customPolicySpinner.onItemSelectedListener = selectionListener
        updateCustomSelectionInfo()
    }

    /**
     * 绑定当前页面会用到的按钮。
     */
    private fun setupActions() {
        binding.fixedCasePanel.runButton.setOnClickListener {
            runSelectedCase()
        }
        binding.fixedCasePanel.resetButton.setOnClickListener {
            selectedCaseOrNull()?.let(::showCase)
        }
        binding.customJsonPanel.customSampleButton.setOnClickListener {
            fillCustomSampleJson()
        }
        binding.customJsonPanel.customValidateButton.setOnClickListener {
            runCustomValidation()
        }
        binding.fullCheckPanel.runAllButton.setOnClickListener {
            runAllCases()
        }
        binding.fullCheckPanel.copyAllReportButton.setOnClickListener {
            copyAllDetailedReport()
        }
        binding.resultPanel.copyResultButton.setOnClickListener {
            copyLastResult()
        }
    }

    /**
     * 切换功能页。
     *
     * @param page 用户当前选中的功能页。
     */
    private fun showPage(page: DemoFeaturePage) {
        currentPage = page
        currentProblemDescription = localizedPageDescription(page)
        binding.pageInfoView.text = getString(
            R.string.demo_page_info_format,
            localizedPageTitle(page),
            localizedPageDescription(page),
            localizedPageActions(page).joinToString(localizedListSeparator())
        )
        binding.fullCheckPanel.root.visibleWhen(page.mode == DemoPageMode.FullCheck)
        binding.fixedCasePanel.root.visibleWhen(page.mode == DemoPageMode.FixedCases)
        binding.customJsonPanel.root.visibleWhen(page.mode == DemoPageMode.CustomJson)
        binding.jsonPanel.visibleWhen(page.mode != DemoPageMode.FullCheck)
        binding.outputPreviewPanel.root.visibleWhen(page.mode != DemoPageMode.FullCheck)

        when (page.mode) {
            DemoPageMode.FullCheck -> showFullCheckPage()
            DemoPageMode.FixedCases -> updateCaseSelector(page.cases)
            DemoPageMode.CustomJson -> showCustomJsonPage()
        }
    }

    /**
     * 展示快速体检页的初始状态。
     */
    private fun showFullCheckPage() {
        currentCases = emptyList()
        currentCaseTitle = localizedPageTitle(currentPage)
        currentProblemDescription = localizedPageDescription(currentPage)
        binding.fixedCasePanel.caseInfoView.text = ""
        setPendingResult(
            expected = getString(R.string.demo_full_check_expected),
            actual = getString(R.string.demo_full_check_actual)
        )
    }

    /**
     * 展示用户 JSON 验证页的初始状态。
     */
    private fun showCustomJsonPage() {
        currentCases = emptyList()
        fillCustomSampleJson()
    }

    /**
     * 按当前功能页刷新固定用例下拉列表。
     *
     * @param cases 当前功能页下的固定用例。
     */
    private fun updateCaseSelector(cases: List<DemoCase>) {
        currentCases = cases
        binding.fixedCasePanel.caseSpinner.onItemSelectedListener = null
        binding.fixedCasePanel.caseSpinner.adapter = simpleSpinnerAdapter(cases.mapIndexed { index, demoCase ->
            getString(R.string.demo_case_label_format, index + 1, localizedCaseTitle(demoCase))
        })
        binding.fixedCasePanel.caseSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentCases.getOrNull(position)?.let(::showCase)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        if (cases.isNotEmpty()) {
            showCase(cases.first())
        } else {
            setPendingResult(
                expected = getString(R.string.demo_no_cases_expected),
                actual = getString(R.string.demo_no_cases_actual)
            )
        }
    }

    /**
     * 展示选中固定用例的说明和默认 JSON。
     */
    private fun showCase(demoCase: DemoCase) {
        currentCaseTitle = localizedCaseTitle(demoCase)
        currentProblemDescription = localizedCaseDescription(demoCase)
        binding.fixedCasePanel.caseInfoView.text = getString(
            R.string.demo_case_info_format,
            localizedCaseTitle(demoCase),
            localizedPageTitle(currentPage),
            demoCase.entryPoint,
            localizedCaseDescription(demoCase)
        )
        setJsonInputText(demoCase.defaultJson)
        setPendingResult(
            expected = demoCase.expected,
            actual = getString(R.string.demo_after_run_placeholder)
        )
        lastCopyText = buildPendingCopyText(demoCase.defaultJson)
    }

    /**
     * 运行当前选中的固定用例。
     */
    private fun runSelectedCase() {
        val demoCase = selectedCaseOrNull() ?: return
        renderResult(demoCase.run(normalizeJsonInputForRun()))
    }

    /**
     * 读取当前选中的固定用例。
     *
     * @return 当前功能页里的选中用例；列表为空或位置异常时返回 null。
     */
    private fun selectedCaseOrNull(): DemoCase? {
        val position = binding.fixedCasePanel.caseSpinner.selectedItemPosition
        return currentCases.getOrNull(position)
    }

    /**
     * 把当前自定义目标类型的示例 JSON 填入输入框。
     */
    private fun fillCustomSampleJson() {
        val targetPosition = binding.customJsonPanel.customTargetSpinner.selectedItemPosition
        val target = DemoCustomValidator.targets.getOrNull(targetPosition) ?: return
        val targetTitle = localizedTargetTitle(targetPosition)
        setJsonInputText(target.sampleJson)
        currentCaseTitle = getString(R.string.demo_custom_title_format, targetTitle)
        currentProblemDescription = localizedTargetDescription(targetPosition)
        setPendingResult(
            expected = getString(R.string.demo_custom_sample_expected),
            actual = getString(R.string.demo_custom_sample_actual_format, targetTitle)
        )
        lastCopyText = buildPendingCopyText(target.sampleJson)
    }

    /**
     * 使用用户输入 JSON 做一次自定义验证。
     */
    private fun runCustomValidation() {
        val customPanel = binding.customJsonPanel
        val targetPosition = customPanel.customTargetSpinner.selectedItemPosition
        val target = DemoCustomValidator.targets.getOrNull(targetPosition)
        currentCaseTitle = getString(
            R.string.demo_custom_title_format,
            target?.let { localizedTargetTitle(targetPosition) } ?: getString(R.string.demo_unknown_target)
        )
        currentProblemDescription = localizedTargetDescription(targetPosition)
        renderResult(
            DemoCustomValidator.validate(
                json = normalizeJsonInputForRun(),
                targetIndex = targetPosition,
                policyIndex = customPanel.customPolicySpinner.selectedItemPosition,
                entryIndex = customPanel.customEntrySpinner.selectedItemPosition
            )
        )
    }

    /**
     * 运行全部用例并展示总览。
     */
    private fun runAllCases() {
        val results = DemoCaseRegistry.runAll()
        val failed = results.filterNot { (_, result) -> result.pass }
        val summary = buildString {
            appendLine(if (isChineseDemo()) "总用例数：${results.size}" else "Total cases: ${results.size}")
            appendLine(if (isChineseDemo()) "通过：${results.size - failed.size}" else "Passed: ${results.size - failed.size}")
            appendLine(if (isChineseDemo()) "失败：${failed.size}" else "Failed: ${failed.size}")
            appendLine()
            results.forEachIndexed { index, (demoCase, result) ->
                appendLine("${index + 1}. ${localizedPassLabel(result.pass)} - ${localizedCaseTitle(demoCase)}")
            }
        }.trimEnd()
        currentCaseTitle = localizedPageTitle(currentPage)
        currentProblemDescription = localizedPageDescription(currentPage)
        renderResult(
            DemoRunResult(
                pass = failed.isEmpty(),
                actual = summary,
                expected = getString(R.string.demo_full_check_expected_short),
                events = getString(R.string.demo_full_check_events),
                contractReport = failed.joinToString("\n\n") { (demoCase, result) ->
                    "${localizedCaseTitle(demoCase)}\n${localizeTechnicalText(result.contractReport)}"
                }.ifBlank { getString(R.string.demo_no_failed_cases) },
                error = failed.joinToString("\n\n") { (demoCase, result) ->
                    "${localizedCaseTitle(demoCase)}\n${localizeTechnicalText(result.error ?: result.actual)}"
                }.ifBlank { null }
            )
        )
    }

    /**
     * 把运行结果渲染到页面。
     */
    private fun renderResult(result: DemoRunResult) {
        outputPanelController.render(
            DemoOutputPanelState(
                statusText = if (result.pass) getString(R.string.demo_status_pass) else getString(R.string.demo_status_fail),
                statusColor = if (result.pass) Color.rgb(0, 110, 80) else Color.rgb(180, 0, 0),
                outputPreview = buildPreviewOutputText(result),
                showDefaultValueNote = shouldShowModelDefaultValueNote(result),
                actual = buildActualDisplayText(result),
                expected = buildExpectedDisplayText(result.expected),
                events = result.events,
                contractReport = result.contractReport,
                observerReport = result.observerReport,
                diagnostics = result.diagnostics,
                error = result.error?.let(::cleanDisplayText)?.ifBlank { getString(R.string.demo_no_exception) }
                    ?: getString(R.string.demo_no_exception)
            )
        )
        lastCopyText = buildCopyText(result)
    }

    /**
     * 设置当前页面尚未运行时的结果区。
     *
     * @param expected 当前页或当前用例的预期说明。
     * @param actual 当前页的待运行提示。
     */
    private fun setPendingResult(expected: String, actual: String) {
        outputPanelController.render(
            DemoOutputPanelState(
                statusText = getString(R.string.demo_status_pending),
                statusColor = Color.rgb(80, 80, 80),
                outputPreview = getString(R.string.demo_output_preview_placeholder),
                showDefaultValueNote = false,
                actual = buildPendingActualDisplayText(actual),
                expected = expected,
                events = getString(R.string.demo_run_after_placeholder),
                contractReport = getString(R.string.demo_run_after_placeholder),
                observerReport = getString(R.string.demo_run_after_placeholder),
                diagnostics = getString(R.string.demo_run_after_placeholder),
                error = getString(R.string.demo_no_exception)
            )
        )
    }

    /**
     * 复制当前页面展示的完整结果。
     */
    private fun copyLastResult() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GsonSafeParser Demo Result", lastCopyText))
        Toast.makeText(this, getString(R.string.demo_copy_result_toast), Toast.LENGTH_SHORT).show()
    }

    /**
     * 运行全部默认用例并复制完整报告。
     */
    private fun copyAllDetailedReport() {
        val results = DemoCaseRegistry.runAll()
        val localizedReport = buildLocalizedDetailedReport(results)
        val failed = results.count { (_, result) -> !result.pass }
        currentCaseTitle = localizedPageTitle(currentPage)
        currentProblemDescription = localizedPageDescription(currentPage)
        lastCopyText = localizedReport
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GsonSafeParser Demo Full Report", localizedReport))
        Toast.makeText(this, getString(R.string.demo_copy_all_toast, failed), Toast.LENGTH_SHORT).show()
        renderResult(
            DemoRunResult(
                pass = failed == 0,
                actual = localizedReport,
                expected = getString(R.string.demo_full_report_expected),
                events = getString(R.string.demo_full_report_events)
            )
        )
        lastCopyText = localizedReport
    }

    /**
     * 生成适合复制给协作者的纯文本报告。
     */
    private fun buildCopyText(result: DemoRunResult): String {
        return buildString {
            appendLine("${if (isChineseDemo()) "用例" else "Case"}：$currentCaseTitle")
            appendLine("${if (isChineseDemo()) "状态" else "Status"}：${localizedPassLabel(result.pass)}")
            appendSection(getString(R.string.demo_input_json_section), binding.jsonInput.text.toString(), redactForClipboard = true)
            appendSection(getString(R.string.demo_actual_output_section), localizeTechnicalText(result.actual), redactForClipboard = true)
            appendSection(getString(R.string.demo_expected_output_section), localizeTechnicalText(result.expected), redactForClipboard = true)
            appendSection(getString(R.string.demo_events_section), localizeTechnicalText(result.events), redactForClipboard = true)
            appendSection(getString(R.string.demo_contract_section), localizeTechnicalText(result.contractReport), redactForClipboard = true)
            appendSection(getString(R.string.demo_observer_section), localizeTechnicalText(result.observerReport), redactForClipboard = true)
            appendSection(getString(R.string.demo_diagnostics_section), localizeTechnicalText(result.diagnostics), redactForClipboard = true)
            appendSection(
                getString(R.string.demo_error_section),
                localizeTechnicalText(result.error ?: getString(R.string.demo_no_exception)),
                redactForClipboard = true
            )
        }.trimEnd()
    }

    /**
     * 创建普通 Spinner Adapter。
     *
     * @param items 下拉选项文本。
     */
    private fun simpleSpinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    /**
     * 按当前功能页和用例生成解析输出面板文本。
     *
     * @param result 当前运行结果。
     * @return 带“问题描述、解析输出、结果解释”的展示文本。
     */
    private fun buildActualDisplayText(result: DemoRunResult): String {
        val explanation = if (result.pass) {
            getString(R.string.demo_status_pass)
        } else {
            getString(R.string.demo_status_fail)
        }
        return buildString {
            appendSection(getString(R.string.demo_problem_section), currentProblemDescription.ifBlank { currentCaseTitle })
            appendSection(getString(R.string.demo_output_section), localizeTechnicalText(result.actual))
            appendSection(getString(R.string.demo_explanation_section), explanation)
        }.trimEnd()
    }

    /**
     * 生成主页面顶部的简洁输出预览。
     *
     * 这里不放事件、报告和异常，只保留最关键的解析结果，方便用户先对比输入和输出。
     * 自定义 JSON 验证页会显式传入 SafeParser 的简洁结果；其他页面则回退到 `actual`。
     *
     * @param result 当前运行结果。
     * @return 可直接放到黑底预览块里的简洁文本。
     */
    private fun buildPreviewOutputText(result: DemoRunResult): String {
        return localizeTechnicalText(result.previewOutput ?: result.actual)
    }

    /**
     * 尚未运行时也保持和运行结果一致的分区结构，用户能提前知道这里会展示什么。
     */
    private fun buildPendingActualDisplayText(actual: String): String {
        return buildString {
            appendSection(getString(R.string.demo_problem_section), currentProblemDescription.ifBlank { currentCaseTitle })
            appendSection(getString(R.string.demo_output_section), cleanDisplayText(actual))
        }.trimEnd()
    }

    /**
     * 渲染预期结果。
     *
     * 英文系统下固定用例不会逐条翻译所有历史中文预期，而是先给一段英文判断标准，
     * 再保留原始用例预期，方便国内维护者和国外用户同时对照。
     */
    private fun buildExpectedDisplayText(expected: String): String {
        if (isChineseDemo()) {
            return buildString {
                appendSection(getString(R.string.demo_expected_behavior_section), cleanDisplayText(expected))
            }.trimEnd()
        }
        return buildString {
            appendSection(getString(R.string.demo_expected_behavior_section), getString(R.string.demo_expected_fixed_case_english))
            appendSection(getString(R.string.demo_original_case_expected_section), localizeTechnicalText(expected))
        }.trimEnd()
    }

    /**
     * 设置 JSON 输入框内容，并在展示前做缩进格式化。
     */
    private fun setJsonInputText(json: String) {
        val formatted = formatJsonForDisplay(json)
        preserveRootScrollWhileUpdatingJsonInput {
            binding.jsonInput.clearFocus()
            binding.jsonInput.setText(formatted)
        }
    }

    /**
     * 运行前再格式化一次输入框内容。
     *
     * 用户可能手动粘贴了一行 JSON，这里会把合法 JSON 改成缩进格式，非法 JSON 保持原样交给 Gson 报错。
     */
    private fun normalizeJsonInputForRun(): String {
        val formatted = formatJsonForDisplay(binding.jsonInput.text.toString())
        if (formatted != binding.jsonInput.text.toString()) {
            preserveRootScrollWhileUpdatingJsonInput {
                binding.jsonInput.clearFocus()
                binding.jsonInput.setText(formatted)
            }
        }
        return formatted
    }

    /**
     * 更新 JSON 输入框时保留页面当前位置。
     *
     * EditText 内容变化后，系统可能因为焦点和可见性重新调整外层 ScrollView，
     * 用户点击“填入示例”时就会看到页面自动下滑。这里先记住外层滚动位置，
     * 内容更新和布局请求完成后再恢复，避免用户正在看的选择说明被顶出屏幕。
     *
     * @param update 真正修改 JSON 输入框内容的动作。
     */
    private fun preserveRootScrollWhileUpdatingJsonInput(update: () -> Unit) {
        val beforeX = binding.rootScrollView.scrollX
        val beforeY = binding.rootScrollView.scrollY
        update()
        binding.rootScrollView.post {
            binding.rootScrollView.scrollTo(beforeX, beforeY)
        }
    }

    /**
     * 刷新用户 JSON 验证页的当前选择说明。
     */
    private fun updateCustomSelectionInfo() {
        val customPanel = binding.customJsonPanel
        binding.customJsonPanel.customSelectionInfoView.text = getString(
            R.string.demo_custom_selection_format,
            localizedEntryTitle(customPanel.customEntrySpinner.selectedItemPosition),
            localizedEntryDescription(customPanel.customEntrySpinner.selectedItemPosition),
            localizedTargetTitle(customPanel.customTargetSpinner.selectedItemPosition),
            localizedTargetDescription(customPanel.customTargetSpinner.selectedItemPosition),
            localizedPolicyTitle(customPanel.customPolicySpinner.selectedItemPosition),
            localizedPolicyDescription(customPanel.customPolicySpinner.selectedItemPosition)
        )
    }

    /**
     * 生成尚未运行时可复制的基础内容。
     */
    private fun buildPendingCopyText(json: String): String {
        return buildString {
            appendLine("${if (isChineseDemo()) "用例" else "Case"}：$currentCaseTitle")
            appendLine("${if (isChineseDemo()) "状态" else "Status"}：${getString(R.string.demo_status_pending)}")
            appendSection(getString(R.string.demo_input_json_section), formatJsonForDisplay(json), redactForClipboard = true)
        }.trimEnd()
    }

    /**
     * 按当前系统语言生成完整报告。
     *
     * `DemoCaseRegistry` 保留中文维护信息，页面复制报告时再做展示层本地化，
     * 这样不会把 Android Context 传进用例注册表，也方便后续继续补英文文案。
     */
    private fun buildLocalizedDetailedReport(results: List<Pair<DemoCase, DemoRunResult>>): String {
        if (isChineseDemo()) return DemoCaseRegistry.buildDetailedReport(results)
        val failed = results.filterNot { (_, result) -> result.pass }
        return buildString {
            appendLine("GsonSafeParser Demo Full Report")
            appendLine("Total cases: ${results.size}")
            appendLine("Passed: ${results.size - failed.size}")
            appendLine("Failed: ${failed.size}")
            results.forEachIndexed { index, (demoCase, result) ->
                appendLine()
                appendLine("${index + 1}. ${localizedPassLabel(result.pass)} - ${localizedCaseTitle(demoCase)}")
                appendSection("Feature category:", localizeTechnicalText(demoCase.category))
                appendSection("Covered API:", demoCase.entryPoint)
                appendSection("Input JSON:", demoCase.defaultJson.ifBlank { "<empty response>" }, redactForClipboard = true)
                appendSection("Expected result:", localizeTechnicalText(result.expected), redactForClipboard = true)
                appendSection("Parsed output:", localizeTechnicalText(result.actual), redactForClipboard = true)
                appendSection("Event stream:", localizeTechnicalText(result.events), redactForClipboard = true)
                appendSection("Contract report:", localizeTechnicalText(result.contractReport), redactForClipboard = true)
                appendSection("Observer failure report:", localizeTechnicalText(result.observerReport), redactForClipboard = true)
                appendSection("Diagnostics:", localizeTechnicalText(result.diagnostics), redactForClipboard = true)
                appendSection(
                    "Exception details:",
                    localizeTechnicalText(result.error ?: getString(R.string.demo_no_exception)),
                    redactForClipboard = true
                )
            }
        }.trimEnd()
    }

    /**
     * 把用例里历史沉淀的中文技术文案转换成英文展示层。
     *
     * 固定用例文件仍然保留中文注释和中文预期，方便国内维护者看懂；
     * 英文系统下展示和复制时会走这里，避免国外用户打开 demo 只能看到中文日志。
     */
    private fun localizeTechnicalText(text: String?): String {
        return localizeTechnicalTextForDisplay(text, isChineseDemo())
    }

    /**
     * 给 `buildString` 追加一个“标题 + 内容”的分区。
     *
     * 内容直接从行首开始追加，不使用 Kotlin 三引号模板，避免多行 JSON 让整段文本带上模板缩进。
     */
    private fun StringBuilder.appendSection(title: String, body: String, redactForClipboard: Boolean = false) {
        if (isNotEmpty()) appendLine().appendLine()
        appendLine(title)
        val normalized = normalizeReportText(cleanDisplayText(body))
        appendLine(if (redactForClipboard) sanitizeClipboardReportText(normalized) else normalized)
    }

    private fun localizedPassLabel(pass: Boolean): String {
        return if (pass) getString(R.string.demo_status_pass) else getString(R.string.demo_status_fail)
    }

    private fun localizedPageTitle(page: DemoFeaturePage): String {
        return getString(DemoTextLocalizer.pageTitleResIds[pages.indexOf(page).coerceAtLeast(0)])
    }

    private fun localizedPageDescription(page: DemoFeaturePage): String {
        return getString(DemoTextLocalizer.pageDescriptionResIds[pages.indexOf(page).coerceAtLeast(0)])
    }

    private fun localizedPageActions(page: DemoFeaturePage): List<String> {
        return DemoTextLocalizer.pageActionResIds[pages.indexOf(page).coerceAtLeast(0)].map(::getString)
    }

    private fun localizedCaseTitle(demoCase: DemoCase): String {
        return if (isChineseDemo()) demoCase.title else demoCase.entryPoint
    }

    private fun localizedCaseDescription(demoCase: DemoCase): String {
        return if (isChineseDemo()) {
            demoCase.description
        } else {
            "Runs this malformed or edge-case JSON through ${demoCase.entryPoint}. Check parsed output, events, reports, and exception details to decide whether the behavior matches your project."
        }
    }

    private fun localizedEntryTitle(index: Int): String {
        val itemIndex = index.coerceIn(DemoCustomValidator.entries.indices)
        return if (isChineseDemo()) DemoCustomValidator.entries[itemIndex].title else getString(DemoTextLocalizer.entryTitleResIds[itemIndex])
    }

    private fun localizedEntryDescription(index: Int): String {
        val itemIndex = index.coerceIn(DemoCustomValidator.entries.indices)
        return if (isChineseDemo()) DemoCustomValidator.entries[itemIndex].description else getString(DemoTextLocalizer.entryDescriptionResIds[itemIndex])
    }

    private fun localizedTargetTitle(index: Int): String {
        val itemIndex = index.coerceIn(DemoCustomValidator.targets.indices)
        return if (isChineseDemo()) DemoCustomValidator.targets[itemIndex].title else getString(DemoTextLocalizer.targetTitleResIds[itemIndex])
    }

    private fun localizedTargetDescription(index: Int): String {
        val itemIndex = index.coerceIn(DemoCustomValidator.targets.indices)
        return if (isChineseDemo()) DemoCustomValidator.targets[itemIndex].description else getString(DemoTextLocalizer.targetDescriptionResIds[itemIndex])
    }

    private fun localizedPolicyTitle(index: Int): String {
        val itemIndex = index.coerceIn(DemoCustomValidator.policies.indices)
        return if (isChineseDemo()) DemoCustomValidator.policies[itemIndex].title else getString(DemoTextLocalizer.policyTitleResIds[itemIndex])
    }

    private fun localizedPolicyDescription(index: Int): String {
        val itemIndex = index.coerceIn(DemoCustomValidator.policies.indices)
        return if (isChineseDemo()) DemoCustomValidator.policies[itemIndex].description else getString(DemoTextLocalizer.policyDescriptionResIds[itemIndex])
    }

    private fun isChineseDemo(): Boolean {
        return Locale.getDefault().language.equals("zh", ignoreCase = true)
    }

    private fun localizedListSeparator(): String {
        return if (isChineseDemo()) "、" else ", "
    }

    /**
     * 给嵌套在页面 ScrollView 内部的可滚动控件处理触摸冲突。
     *
     * @receiver 需要自己处理纵向滚动的控件，例如 JSON 输入框或异常信息 ScrollView。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.keepParentFromStealingVerticalScroll() {
        setOnTouchListener { view, event ->
            val shouldKeepTouch = event.action != MotionEvent.ACTION_UP &&
                event.action != MotionEvent.ACTION_CANCEL
            view.parent?.requestDisallowInterceptTouchEvent(shouldKeepTouch)
            false
        }
    }

    /**
     * 根据条件显示或隐藏 View。
     *
     * @param visible 条件为 true 时显示，否则隐藏并释放页面空间。
     */
    private fun View.visibleWhen(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    companion object {
        internal fun localizeTechnicalTextForDisplay(text: String?, isChineseDemo: Boolean): String {
            return DemoTextLocalizer.localizeTechnicalTextForDisplay(text, isChineseDemo)
        }
    }
}
