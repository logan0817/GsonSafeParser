package io.github.logan.gsonsafeparser.demo.page

import io.github.logan.gsonsafeparser.demo.DemoCaseRegistry
import io.github.logan.gsonsafeparser.demo.support.DemoCase

/**
 * demo 的功能页注册表。
 *
 * 这个对象只负责把大量固定用例按用户理解成本更低的方式分成几个页面。Activity 根据这里的
 * mode 决定展示哪个面板，避免把固定用例、用户 JSON 验证和全量体检全堆在一个滚动页里。
 */
object DemoPageRegistry {
    private val allCases: List<DemoCase> = DemoCaseRegistry.cases

    /**
     * 页面列表。
     *
     * “快速体检”和“用户 JSON 验证”是操作型页面，不承载固定用例；其余页面按功能域承载固定用例。
     */
    val pages: List<DemoFeaturePage> = listOf(
        DemoFeaturePage(
            title = "快速体检",
            description = "一键运行全部默认用例，适合真机安装后先确认 demo 和库能力是否整体正常。",
            mode = DemoPageMode.FullCheck,
            primaryActions = listOf("一键自检", "复制一键自检详细报告")
        ),
        DemoFeaturePage(
            title = "用户 JSON 验证",
            description = "粘贴真实接口返回，选择接近的目标类型和解析策略，对比 SafeParser 与原生 Gson。",
            mode = DemoPageMode.CustomJson,
            primaryActions = listOf("填入示例", "验证 JSON", "复制当前面板内容")
        ),
        DemoFeaturePage(
            title = "核心解析",
            description = "验证对象、基础类型、集合、Map、Object 数字策略和兼容能力。",
            mode = DemoPageMode.FixedCases,
            cases = allCases.filter { demoCase ->
                demoCase.category in setOf("核心解析", "Map", "兼容能力")
            },
            primaryActions = listOf("运行用例", "恢复 JSON", "复制当前面板内容")
        ),
        DemoFeaturePage(
            title = "配置与接入",
            description = "验证公开 API、Kotlin API、预设配置、Builder 透传、注解和安全回退策略。",
            mode = DemoPageMode.FixedCases,
            cases = allCases.filter { demoCase ->
                demoCase.category in setOf("公开 API", "Kotlin API", "配置", "接入自检", "安全回退", "注解")
            },
            primaryActions = listOf("运行用例", "恢复 JSON", "复制当前面板内容")
        ),
        DemoFeaturePage(
            title = "可观测性",
            description = "验证事件流、契约报告、观察者失败隔离和低层事件桥接。",
            mode = DemoPageMode.FixedCases,
            cases = allCases.filter { demoCase ->
                demoCase.category == "可观测性"
            },
            primaryActions = listOf("运行用例", "恢复 JSON", "复制当前面板内容")
        ),
        DemoFeaturePage(
            title = "Retrofit",
            description = "验证 Retrofit 空响应、rawJson 捕获、观察者失败隔离和 converter 委托。",
            mode = DemoPageMode.FixedCases,
            cases = allCases.filter { demoCase ->
                demoCase.category == "Retrofit"
            },
            primaryActions = listOf("运行用例", "恢复 JSON", "复制当前面板内容")
        )
    )
}

/**
 * demo 首页里的一个功能页。
 *
 * @property title 页面标题，直接显示在功能页选择器里。
 * @property description 页面说明，帮助用户判断当前页适合验证什么。
 * @property mode 页面类型，Activity 用它决定显示固定用例、用户 JSON 还是全量体检面板。
 * @property cases 当前页包含的固定用例；操作型页面可以为空。
 * @property primaryActions 当前页主要操作，用于测试约束按钮数量，避免页面再次变乱。
 */
data class DemoFeaturePage(
    val title: String,
    val description: String,
    val mode: DemoPageMode,
    val cases: List<DemoCase> = emptyList(),
    val primaryActions: List<String>
)

/**
 * demo 页面类型。
 */
enum class DemoPageMode {
    FullCheck,
    FixedCases,
    CustomJson
}
