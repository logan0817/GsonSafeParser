package io.github.logan.gsonsafeparser

import com.google.gson.stream.JsonToken
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 可编译的 JVM 接入样例。
 *
 * 这里不是为了覆盖所有解析细节，而是把推荐接入姿势固定下来：
 * 线上用 production，联调用 debug，谨慎接入用 lowInterference，CI 用 integrationCheck。
 */
class SafeParserJvmCiSampleTest {
    /**
     * CI 示例里的接口响应外层模型。
     *
     * `code` 模拟业务状态码，`data` 默认给 User，用来验证后端返回 `[]` 时字段能保留默认对象。
     */
    data class ApiResponse(
        val code: Int = 0,
        val data: User = User()
    )

    /**
     * 低误伤示例里的接口响应模型。
     *
     * `data` 允许为 null，用来观察 NullOnly 策略是否更接近 Gson 原生失败语义。
     */
    data class NullableApiResponse(
        val code: Int = 0,
        val data: User? = null
    )

    /**
     * 示例里的业务用户模型。
     *
     * 默认值用来模拟 App 端本地兜底数据，解析异常时不能被错误 JSON 覆盖。
     */
    data class User(
        val id: Long = 0L,
        val name: String = "anonymous"
    )

    /**
     * 测试方法说明：验证“production sample keeps bean parsing alive and emits contract report”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `production sample keeps bean parsing alive and emits contract report`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<SafeParserEvent>()
        // config 是本用例特意设置的 SafeParser 配置，下面的解析行为会受它影响。
        val config = SafeParserConfig.production(
            observerPolicy = SafeObserverPolicy(
                onEvent = events::add
            )
        )

        // 这里故意把文档里定义为 Object 的 data 返回成 []。
        // 真实接口里这类问题通常只影响一个字段，业务更希望整棵 Bean 先解析出来，
        // 再通过事件和报告定位后端契约问题，而不是让 Gson 在第一个错形字段上直接抛异常。
        val result = GsonSafeParser.parseSafe<ApiResponse>(
            """{"code":200,"data":[]}""",
            config
        )

        // report 是根据事件生成的可读报告，用来验证观测结果是否完整。
        val report = result.contractReport()

        assertEquals(ApiResponse(code = 200), result.value)
        assertEquals(result.events, events)
        assertTrue(report.hasIssues)
        assertEquals("$.data", report.issues.single().path)
        assertTrue(report.toMarkdown().contains("Type mismatch at $.data"))
    }

    /**
     * 测试方法说明：验证“debug sample captures raw json only for troubleshooting”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `debug sample captures raw json only for troubleshooting`() {
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = GsonSafeParser.parseSafe<ApiResponse>(
            """{"code":200,"data":[]}""",
            SafeParserConfig.debug()
        )

        // debug() 会开启 rawJson 捕获，方便联调时把原始响应和字段路径一起落日志。
        // production() 默认关闭这件事，避免线上日志误带响应体或造成额外内存压力。
        val event = result.events.single() as SafeParserEvent.TypeMismatch

        assertEquals(JsonToken.BEGIN_ARRAY, event.detail.actualToken)
        assertEquals("""{"code":200,"data":[]}""", event.detail.rawJson)
        assertFalse(event.detail.rawJsonTruncated)
    }

    /**
     * 测试方法说明：验证“low interference sample stays closer to gson defaults”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `low interference sample stays closer to gson defaults`() {
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = GsonSafeParser.parseSafe<NullableApiResponse>(
            """{"code":200,"data":[]}""",
            SafeParserConfig.lowInterference()
        )

        // lowInterference() 适合刚接入、担心误伤的项目。
        // 它仍然记录错配事件，但字段兜底更保守：对象错形优先给 null，
        // 基础类型解析也尽量交回 Gson，方便团队逐步观察再决定是否切到 production()。
        assertEquals(200, result.value?.code)
        assertNull(result.value?.data)
        assertTrue(result.contractReport().hasIssues)
    }

    /**
     * 测试方法说明：验证“integration check sample is safe for jvm ci”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `integration check sample is safe for jvm ci`() {
        // check 是接入自检结果，测试会检查它是否发现阻断级问题。
        val check = assertDoesNotThrow<GsonSafeIntegrationCheck> {
            GsonSafeParser.integrationCheck(SafeParserConfig.production())
        }

        // integrationCheck() 只跑库内置 JVM 探针，不会碰业务 Bean、网络、Retrofit 服务或 Android 设备。
        // 它适合放在普通单元测试或 CI 自检里，失败时看 checks 和 probeError，而不是让接入脚本直接崩掉。
        assertTrue(check.safeAdapterAvailable)
        assertTrue(check.probeParsed)
        assertTrue(check.fallbackWorking)
        assertFalse(check.hasErrors)
        assertTrue(check.contractReport.hasIssues)
        assertTrue(check.checks.any { it.name == "probeFallback" })
    }

    /**
     * 测试方法说明：验证“model probe sample catches release obfuscation risk without crashing ci”这个具体行为。
     * 阅读时可以按准备关键业务模型、执行自检、断言诊断结果的顺序跟下来。
     */
    @Test
    fun `model probe sample catches release obfuscation risk without crashing ci`() {
        val modelProbe = GsonSafeModelProbe(
            name = "coreApiUser",
            json = """{"id":1001,"name":"Ada"}""",
            type = User::class.java,
            expectedFields = mapOf(
                "id" to 1001L,
                "name" to "Ada"
            )
        )

        val check = assertDoesNotThrow<GsonSafeIntegrationCheck> {
            GsonSafeParser.integrationCheck(
                config = SafeParserConfig.production(),
                modelProbes = listOf(modelProbe)
            )
        }

        // 老项目可以先给关键响应模型加包级 keep，再用 modelProbes 把 release 风险收进 CI。
        // 如果 R8 把字段名改成 a/b/c，探针会给出 diagnostics，而不是让自检调用直接抛异常。
        assertFalse(check.hasErrors)
        assertTrue(check.checks.any { it.name == "modelProbe" && it.message.contains("coreApiUser") })
    }

    /**
     * 测试方法说明：验证“observer failure sample isolates callback crashes”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `observer failure sample isolates callback crashes`() {
        // observerFailures 用来收集观察者自身抛出的异常，验证它不会影响主解析。
        val observerFailures = mutableListOf<ObserverFailureEvent>()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = assertDoesNotThrow<SafeParseResult<ApiResponse>> {
            GsonSafeParser.parseSafe<ApiResponse>(
                """{"code":200,"data":[]}""",
                SafeParserConfig.production(
                    observerPolicy = SafeObserverPolicy(
                        onEvent = {
                            error("日志系统暂时不可用")
                        },
                        onObserverFailure = observerFailures::add
                    )
                )
            )
        }

        // 业务上报、埋点、日志 SDK 都可能在回调里抛异常。
        // GsonSafeParser 不应该因为观察者失败影响解析结果，所以失败会进入单独的 observerFailureReport()，
        // 主解析事件和契约报告仍然照常可用。
        val observerReport = observerFailures.observerFailureReport()

        assertNotNull(result.value)
        assertTrue(result.contractReport().hasIssues)
        assertTrue(observerReport.hasFailures)
        assertEquals("onEvent", observerReport.failures.single().callbackName)
        assertTrue(observerReport.toMarkdown().contains("onEvent failed"))
    }
}
