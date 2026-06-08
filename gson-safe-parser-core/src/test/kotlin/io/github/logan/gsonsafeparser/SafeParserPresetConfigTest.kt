package io.github.logan.gsonsafeparser

import com.google.gson.stream.JsonToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验证预设配置。
 *
 * 这组用例固定 default、production、debug、lowInterference 四个接入档位的关键差异，
 * 防止后续新增配置时把线上、联调和低误伤策略混在一起。
 */
class SafeParserPresetConfigTest {
    /** 测试模型：预设配置测试里的响应外层对象。 */
    data class ApiResponse(val data: User = User())
    /** 测试模型：用户对象，默认 id 用来判断兜底策略是否生效。 */
    data class User(val id: Long = 0L)

    /**
     * 测试方法说明：验证“default config is contract first instead of eager fallback”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `default config is contract first instead of eager fallback`() {
        val config = SafeParserConfig()

        assertEquals(FallbackPolicy.NullOnly, config.fallbackPolicy)
        assertEquals(PrimitiveParsingPolicy.DelegateToGson, config.primitiveParsingPolicy)
        assertEquals(EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly, config.emptyResponsePolicy)
        assertEquals(NullValuePolicy.WriteExplicitNulls, config.nullValuePolicy)
        assertEquals(RequiredConstructorParameterPolicy.GsonCompatible, config.requiredConstructorParameterPolicy)
        assertEquals(MapItemKeyPolicy.Omit, config.mapItemKeyPolicy)
        assertFalse(config.useJdkUnsafe)
    }

    /**
     * 测试方法说明：验证“production preset follows contract first defaults without high risk warnings”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `production preset follows contract first defaults without high risk warnings`() {
        // config 是本用例特意设置的 SafeParser 配置，下面的解析行为会受它影响。
        val config = SafeParserConfig.production()
        val diagnostics = GsonSafeParser.diagnostics(config)

        assertEquals(FallbackPolicy.NullOnly, config.fallbackPolicy)
        assertEquals(PrimitiveParsingPolicy.DelegateToGson, config.primitiveParsingPolicy)
        assertEquals(EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly, config.emptyResponsePolicy)
        assertEquals(NullValuePolicy.WriteExplicitNulls, config.nullValuePolicy)
        assertEquals(RequiredConstructorParameterPolicy.GsonCompatible, config.requiredConstructorParameterPolicy)
        assertEquals(MapItemKeyPolicy.Omit, config.mapItemKeyPolicy)
        assertFalse(config.useJdkUnsafe)
        assertFalse(config.captureRawJsonInCallbacks)
        assertFalse(diagnostics.checks.any { it.name == "skippedPlatformTypePrefixes" })
    }

    /**
     * 测试方法说明：验证“debug preset captures raw json and keeps callback hooks”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `debug preset captures raw json and keeps callback hooks`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<SafeParserEvent>()
        // config 是本用例特意设置的 SafeParser 配置，下面的解析行为会受它影响。
        val config = SafeParserConfig.debug(
            observerPolicy = SafeObserverPolicy(
                onEvent = events::add
            )
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = GsonSafeParser.parseSafe<ApiResponse>(
            """{"data":[]}""",
            config
        )

        val event = result.events.single() as SafeParserEvent.TypeMismatch
        assertTrue(config.captureRawJsonInCallbacks)
        assertEquals(64 * 1024, config.maxRawJsonCaptureBytes)
        assertEquals(FallbackPolicy.NullOnly, config.fallbackPolicy)
        assertEquals(RequiredConstructorParameterPolicy.GsonCompatible, config.requiredConstructorParameterPolicy)
        assertEquals(JsonToken.BEGIN_ARRAY, event.detail.actualToken)
        assertEquals(1, events.size)
        assertEquals(1, result.events.size)
    }

    /**
     * 测试方法说明：验证“low interference preset prefers gson fallback over eager safe behavior”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `low interference preset prefers gson fallback over eager safe behavior`() {
        // config 是本用例特意设置的 SafeParser 配置，下面的解析行为会受它影响。
        val config = SafeParserConfig.lowInterference()
        val diagnostics = GsonSafeParser.diagnostics(config)

        assertEquals(FallbackPolicy.NullOnly, config.fallbackPolicy)
        assertEquals(PrimitiveParsingPolicy.DelegateToGson, config.primitiveParsingPolicy)
        assertEquals(RequiredConstructorParameterPolicy.GsonCompatible, config.requiredConstructorParameterPolicy)
        assertFalse(config.useJdkUnsafe)
        assertFalse(config.captureRawJsonInCallbacks)
        assertFalse(diagnostics.hasErrors)
    }
}
