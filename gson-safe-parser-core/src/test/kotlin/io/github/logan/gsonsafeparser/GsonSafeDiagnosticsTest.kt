package io.github.logan.gsonsafeparser

import com.google.gson.GsonBuilder
import com.google.gson.ReflectionAccessFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.json.JSONObject
import java.net.URL

/**
 * 验证接入诊断。
 *
 * diagnostics 只做环境和配置风险检查，不能创建业务 Adapter、不能解析业务 JSON，
 * 也不能改变后续 Gson 的真实解析行为。
 */
class GsonSafeDiagnosticsTest {
    /** 测试模型：普通业务对象，应由 Safe Reflective Adapter 接管。 */
    data class DiagnosticResponse(val name: String = "local")

    /** 测试模型：显式交回 Gson 的业务对象。 */
    @SafeParseDelegateToGson
    data class DelegatedResponse(val name: String = "local")

    /** 测试接口：运行时会交回 Gson 原生策略。 */
    interface DiagnosticContract {
        val name: String
    }

    /** 测试抽象类：运行时会交回 Gson 原生策略。 */
    abstract class AbstractDiagnostic {
        abstract val name: String
    }

    /**
     * 测试方法说明：验证“diagnostics reports safe adapter availability”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `diagnostics reports safe adapter availability`() {
        val diagnostics = GsonSafeParser.diagnostics()

        assertTrue(diagnostics.safeAdapterAvailable)
        assertEquals(
            DiagnosticSeverity.OK,
            diagnostics.checks.single { it.name == "gsonBuilderCompatibility" }.severity
        )
        assertFalse(diagnostics.hasErrors)
    }

    /**
     * 测试方法说明：验证 diagnostics 会带上当前 Gson 版本线索，方便强制覆盖依赖后排查。
     */
    @Test
    fun `diagnostics reports gson runtime version clue`() {
        val diagnostics = GsonSafeParser.diagnostics()

        val compatibilityMessage = diagnostics.checks
            .single { it.name == "gsonBuilderCompatibility" }
            .message

        assertTrue(compatibilityMessage.contains("Gson version:"))
    }

    /**
     * 测试方法说明：验证 diagnostics 把 GsonBuilder 内部字段兼容性拆开报告，方便 Gson 升级后快速定位具体风险。
     */
    @Test
    fun `diagnostics reports field level gson builder compatibility checks`() {
        val diagnostics = GsonSafeParser.diagnostics()
        val checksByName = diagnostics.checks.associateBy { it.name }

        listOf(
            "gsonBuilderInstanceCreatorsCompatibility",
            "gsonBuilderObjectToNumberStrategyCompatibility",
            "gsonBuilderReflectionFiltersCompatibility",
            "gsonBuilderComplexMapKeySerializationCompatibility",
            "gsonBuilderUseJdkUnsafeCompatibility"
        ).forEach { checkName ->
            assertEquals(
                DiagnosticSeverity.OK,
                checksByName.getValue(checkName).severity,
                "$checkName should be readable on the verified Gson version"
            )
        }
        assertTrue(
            checksByName.getValue("gsonBuilderReflectionFiltersCompatibility").message.contains("critical")
        )
        assertTrue(
            checksByName.getValue("gsonBuilderInstanceCreatorsCompatibility").message.contains("optional")
        )
    }

    /**
     * 测试方法说明：验证外部普通 Gson 只能诊断为未启用字段级 Safe Adapter，不能让用户误以为已经接管。
     */
    @Test
    fun `external gson diagnostics reports missing safe adapter as warning`() {
        val diagnostics = GsonSafeParser.diagnostics(GsonBuilder().create())

        assertFalse(diagnostics.safeAdapterAvailable)
        assertFalse(diagnostics.hasErrors)
        assertEquals(
            DiagnosticSeverity.WARNING,
            diagnostics.checks.single { it.name == "externalGsonSafeAdapter" }.severity
        )
    }

    /**
     * 测试方法说明：验证外部 Gson 已经通过 enableSafeParser 创建时，诊断能确认字段级 Safe Adapter 已接管。
     */
    @Test
    fun `external gson diagnostics reports registered safe adapter`() {
        val gson = GsonBuilder()
            .enableSafeParser()
            .create()

        val diagnostics = GsonSafeParser.diagnostics(gson)

        assertTrue(diagnostics.safeAdapterAvailable)
        assertFalse(diagnostics.hasErrors)
        assertEquals(
            DiagnosticSeverity.OK,
            diagnostics.checks.single { it.name == "externalGsonSafeAdapter" }.severity
        )
    }

    /**
     * 测试方法说明：验证“diagnostics reports risky config warnings without blocking default strategy”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `diagnostics reports risky config warnings without blocking default strategy`() {
        val diagnostics = GsonSafeParser.diagnostics(
            SafeParserConfig(
                skippedPlatformTypePrefixes = emptySet(),
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 0
            )
        )

        val warnings = diagnostics.checks.filter { it.severity == DiagnosticSeverity.WARNING }
            .map { it.name }

        assertTrue("skippedPlatformTypePrefixes" in warnings)
        assertTrue("maxRawJsonCaptureBytes" in warnings)
        assertFalse(diagnostics.hasErrors)
    }

    /**
     * 测试方法说明：验证 rawJson 捕获上限过大时诊断会提示风险，但不会阻断调试配置继续解析。
     */
    @Test
    fun `diagnostics warns when raw json capture limit is too large`() {
        val diagnostics = GsonSafeParser.diagnostics(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 64 * 1024 * 1024
            )
        )

        val warning = diagnostics.checks.single { it.name == "maxRawJsonCaptureBytesTooLarge" }

        assertEquals(DiagnosticSeverity.WARNING, warning.severity)
        assertTrue(warning.message.contains("64 MiB"))
        assertTrue(warning.message.contains("Raw JSON"))
        assertFalse(diagnostics.hasErrors)
    }

    /**
     * 测试方法说明：验证“type explanation reports whether a model is safe handled or delegated”这个具体行为。
     */
    @Test
    fun `type explanation reports safe handled and delegated models`() {
        val safe = GsonSafeParser.explainType(DiagnosticResponse::class.java)
        val delegated = GsonSafeParser.explainType(DelegatedResponse::class.java)
        val builtIn = GsonSafeParser.explainType(URL::class.java)
        val orgJson = GsonSafeParser.explainType(JSONObject::class.java)
        val objectArray = GsonSafeParser.explainType(Array<DiagnosticResponse>::class.java)

        assertEquals(SafeTypeHandling.SafeReflective, safe.handling)
        assertEquals(SafeTypeHandling.DelegateToGson, delegated.handling)
        assertEquals(SafeTypeHandling.DelegateToGson, builtIn.handling)
        assertEquals(SafeTypeHandling.SafeOrgJson, orgJson.handling)
        assertEquals(SafeTypeHandling.SafeTypeWrapper, objectArray.handling)
        assertFalse(safe.hasErrors)
        assertFalse(delegated.hasErrors)
        assertFalse(builtIn.hasErrors)
        assertFalse(orgJson.hasErrors)
        assertFalse(objectArray.hasErrors)
    }

    /**
     * 测试方法说明：验证 explainType 不能把真实创建失败的反射 Adapter 报成 SafeReflective。
     */
    @Test
    fun `type explanation reports adapter creation failure when reflective adapter is blocked`() {
        val blockDiagnosticResponse = ReflectionAccessFilter { rawType ->
            if (rawType == DiagnosticResponse::class.java) {
                ReflectionAccessFilter.FilterResult.BLOCK_ALL
            } else {
                ReflectionAccessFilter.FilterResult.INDECISIVE
            }
        }

        val explanation = GsonSafeParser.explainType(
            DiagnosticResponse::class.java,
            SafeParserConfig(reflectionAccessFilters = listOf(blockDiagnosticResponse))
        )

        assertEquals(SafeTypeHandling.AdapterCreationFailure, explanation.handling)
        assertTrue(explanation.hasErrors)
        assertEquals(
            DiagnosticSeverity.ERROR,
            explanation.checks.single { it.name == "adapterCreation" }.severity
        )
    }

    /**
     * 测试方法说明：验证 explainType 对接口和抽象类型的说明要和真实 Adapter 分发一致。
     */
    @Test
    fun `type explanation delegates interface and abstract types to Gson`() {
        val interfaceExplanation = GsonSafeParser.explainType(DiagnosticContract::class.java)
        val abstractExplanation = GsonSafeParser.explainType(AbstractDiagnostic::class.java)

        assertEquals(SafeTypeHandling.DelegateToGson, interfaceExplanation.handling)
        assertEquals(SafeTypeHandling.DelegateToGson, abstractExplanation.handling)
        assertFalse(interfaceExplanation.hasErrors)
        assertFalse(abstractExplanation.hasErrors)
    }
}
