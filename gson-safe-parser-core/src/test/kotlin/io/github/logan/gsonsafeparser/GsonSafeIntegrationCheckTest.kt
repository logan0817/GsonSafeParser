package io.github.logan.gsonsafeparser

import com.google.gson.ReflectionAccessFilter
import com.google.gson.annotations.SerializedName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Type

/**
 * 验证接入自检入口。
 *
 * integrationCheck 只运行库内置 JVM 探针，并把失败转换成结果对象。
 * 它必须避免触发用户观察回调，否则 CI 自检会变成真实业务上报。
 */
class GsonSafeIntegrationCheckTest {
    data class LegacyProbeModel(
        val id: Long = 0L,
        val name: String = ""
    )

    data class ObfuscatedLikeProbeModel(
        val a: Long = 0L
    )

    /**
     * 测试方法说明：验证“integration probe models declare serialized names for release obfuscation”这个具体行为。
     * 阅读时可以按加载探针模型、读取字段注解、断言 JSON 名称的顺序跟下来。
     */
    @Test
    fun `integration probe models declare serialized names for release obfuscation`() {
        val responseClass = Class.forName("io.github.logan.gsonsafeparser.IntegrationProbeResponse")
        val childClass = Class.forName("io.github.logan.gsonsafeparser.IntegrationProbeChild")

        val dataName = responseClass.getDeclaredField("data").getAnnotation(SerializedName::class.java)
        val idName = childClass.getDeclaredField("id").getAnnotation(SerializedName::class.java)

        assertNotNull(dataName)
        assertEquals("data", requireNotNull(dataName).value)
        assertNotNull(idName)
        assertEquals("id", requireNotNull(idName).value)
    }

    /**
     * 测试方法说明：验证“integration check verifies safe parser fallback without invoking user callbacks”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `integration check verifies safe parser fallback without invoking user callbacks`() {
        val userEvents = mutableListOf<SafeParserEvent>()
        val userTypeMismatches = mutableListOf<TypeMismatchEvent>()
        val userAdapterFailures = mutableListOf<AdapterCreationFailureEvent>()
        // check 是接入自检结果，测试会检查它是否发现阻断级问题。
        val check = GsonSafeParser.integrationCheck(
            SafeParserConfig(
                onEvent = userEvents::add,
                onTypeMismatch = userTypeMismatches::add,
                onAdapterCreationFailure = userAdapterFailures::add
            )
        )

        assertTrue(check.safeAdapterAvailable)
        assertTrue(check.probeParsed)
        assertTrue(check.fallbackWorking)
        assertFalse(check.hasErrors)
        assertTrue(check.contractReport.hasIssues)
        assertEquals(1, check.events.size)
        assertEquals("$.data", check.contractReport.issues.single().path)
        assertTrue(check.checks.any { it.name == "probeFallback" && it.severity == DiagnosticSeverity.OK })
        assertTrue(userEvents.isEmpty())
        assertTrue(userTypeMismatches.isEmpty())
        assertTrue(userAdapterFailures.isEmpty())
    }

    /**
     * 测试方法说明：验证“integration check reports risky config warnings without throwing”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `integration check reports risky config warnings without throwing`() {
        // check 是接入自检结果，测试会检查它是否发现阻断级问题。
        val check = GsonSafeParser.integrationCheck(
            SafeParserConfig(
                skippedPlatformTypePrefixes = emptySet()
            )
        )

        val warnings = check.checks.filter { it.severity == DiagnosticSeverity.WARNING }
            .map { it.name }

        assertTrue("skippedPlatformTypePrefixes" in warnings)
        assertFalse(check.hasErrors)
    }

    /**
     * 测试方法说明：验证“integration check accepts null only fallback policy”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `integration check accepts null only fallback policy`() {
        // check 是接入自检结果，测试会检查它是否发现阻断级问题。
        val check = GsonSafeParser.integrationCheck(
            SafeParserConfig(
                fallbackPolicy = FallbackPolicy.NullOnly
            )
        )

        assertTrue(check.probeParsed)
        assertTrue(check.fallbackWorking)
        assertFalse(check.hasErrors)
        assertTrue(check.contractReport.hasIssues)
    }

    /**
     * 测试方法说明：验证“integration check converts probe failure to error result”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `integration check converts probe failure to error result`() {
        // check 是接入自检结果，测试会检查它是否发现阻断级问题。
        val check = GsonSafeParser.integrationCheck(
            SafeParserConfig(
                reflectionAccessFilters = listOf(
                    ReflectionAccessFilter { rawClass ->
                        if (rawClass.name.contains("IntegrationProbe")) {
                            ReflectionAccessFilter.FilterResult.BLOCK_ALL
                        } else {
                            ReflectionAccessFilter.FilterResult.INDECISIVE
                        }
                    }
                )
            )
        )

        assertFalse(check.probeParsed)
        assertFalse(check.fallbackWorking)
        assertTrue(check.hasErrors)
        assertTrue(check.checks.any { it.name == "probeFallback" && it.severity == DiagnosticSeverity.ERROR })
        assertNotNull(check.probeError)
    }

    /**
     * 测试方法说明：验证“integration check model probes accept legacy models with package keep”这个具体行为。
     * 阅读时可以按准备业务模型探针、执行自检、断言诊断项的顺序跟下来。
     */
    @Test
    fun `integration check model probes accept legacy models with package keep`() {
        val probe = GsonSafeModelProbe(
            name = "legacyUser",
            json = """{"id":7,"name":"Ada"}""",
            type = LegacyProbeModel::class.java,
            expectedFields = mapOf(
                "id" to 7L,
                "name" to "Ada"
            )
        )

        val check = GsonSafeParser.integrationCheck(
            config = SafeParserConfig.production(),
            modelProbes = listOf(probe)
        )

        assertFalse(check.hasErrors)
        assertTrue(
            check.checks.any {
                it.name == "modelProbe" &&
                    it.severity == DiagnosticSeverity.OK &&
                    it.message.contains("legacyUser")
            }
        )
    }

    /**
     * 测试方法说明：验证“integration check model probes can be called without explicit config”这个具体行为。
     * 阅读时可以按准备业务模型探针、使用最小 API、断言诊断项的顺序跟下来。
     */
    @Test
    fun `integration check model probes can be called without explicit config`() {
        val probe = GsonSafeModelProbe(
            name = "legacyUserMinimalApi",
            json = """{"id":7,"name":"Ada"}""",
            type = LegacyProbeModel::class.java,
            expectedFields = mapOf(
                "id" to 7L,
                "name" to "Ada"
            )
        )

        val check = GsonSafeParser.integrationCheck(modelProbes = listOf(probe))

        assertFalse(check.hasErrors)
        assertTrue(
            check.checks.any {
                it.name == "modelProbe" &&
                    it.severity == DiagnosticSeverity.OK &&
                    it.message.contains("legacyUserMinimalApi")
            }
        )
    }

    /**
     * 测试方法说明：验证“integration check model probes report suspected field obfuscation without throwing”这个具体行为。
     * 阅读时可以按模拟混淆后字段名、执行自检、断言不会向外抛异常的顺序跟下来。
     */
    @Test
    fun `integration check model probes report suspected field obfuscation without throwing`() {
        val probe = GsonSafeModelProbe(
            name = "obfuscatedUser",
            json = """{"id":7}""",
            type = ObfuscatedLikeProbeModel::class.java,
            expectedFields = mapOf("id" to 7L)
        )

        val check = assertDoesNotThrow<GsonSafeIntegrationCheck> {
            GsonSafeParser.integrationCheck(
                config = SafeParserConfig.production(),
                modelProbes = listOf(probe)
            )
        }

        assertTrue(check.hasErrors)
        assertTrue(
            check.checks.any {
                it.name == "modelFieldObfuscationSuspected" &&
                    it.severity == DiagnosticSeverity.ERROR &&
                    it.message.contains("obfuscatedUser") &&
                    it.message.contains("keep")
            }
        )
    }

    /**
     * 测试方法说明：验证“integration check model probes report null model construction as diagnostics”这个具体行为。
     * 阅读时可以按准备 null 模型、执行自检、断言构造不可用诊断的顺序跟下来。
     */
    @Test
    fun `integration check model probes report null construction result without throwing`() {
        val probe = GsonSafeModelProbe(
            name = "nullUser",
            json = "null",
            type = LegacyProbeModel::class.java,
            expectedFields = mapOf("id" to 7L)
        )

        val check = assertDoesNotThrow<GsonSafeIntegrationCheck> {
            GsonSafeParser.integrationCheck(
                config = SafeParserConfig.production(),
                modelProbes = listOf(probe)
            )
        }

        assertTrue(check.hasErrors)
        assertTrue(
            check.checks.any {
                it.name == "modelConstructorUnavailable" &&
                    it.severity == DiagnosticSeverity.ERROR &&
                    it.message.contains("nullUser")
            }
        )
    }

    /**
     * 测试方法说明：验证“integration check model probes convert probe exceptions to diagnostics”这个具体行为。
     * 阅读时可以按准备异常 JSON、执行自检、断言错误进入 checks 的顺序跟下来。
     */
    @Test
    fun `integration check model probes convert probe exceptions to diagnostics`() {
        val probe = GsonSafeModelProbe(
            name = "brokenPayload",
            json = """{"id":""",
            type = LegacyProbeModel::class.java,
            expectedFields = mapOf("id" to 7L)
        )

        val check = assertDoesNotThrow<GsonSafeIntegrationCheck> {
            GsonSafeParser.integrationCheck(
                config = SafeParserConfig.production(),
                modelProbes = listOf(probe)
            )
        }

        assertTrue(check.hasErrors)
        assertTrue(
            check.checks.any {
                it.name == "modelProbeFailure" &&
                    it.severity == DiagnosticSeverity.ERROR &&
                    it.message.contains("brokenPayload")
            }
        )
    }

    /**
     * 测试方法说明：验证“integration check model probes convert unexpected probe metadata failures to diagnostics”这个具体行为。
     * 阅读时可以按准备异常 Type、执行自检、断言兜底诊断的顺序跟下来。
     */
    @Test
    fun `integration check model probes convert unexpected probe metadata failures to diagnostics`() {
        val brokenType = object : Type {
            override fun getTypeName(): String {
                error("type metadata unavailable")
            }
        }
        val probe = GsonSafeModelProbe(
            name = "",
            json = """{}""",
            type = brokenType,
            expectedFields = mapOf("id" to 7L)
        )

        val check = assertDoesNotThrow<GsonSafeIntegrationCheck> {
            GsonSafeParser.integrationCheck(
                config = SafeParserConfig.production(),
                modelProbes = listOf(probe)
            )
        }

        assertTrue(check.hasErrors)
        assertTrue(
            check.checks.any {
                it.name == "modelProbeFailure" &&
                    it.severity == DiagnosticSeverity.ERROR
            }
        )
    }
}
