package io.github.logan.gsonsafeparser

import com.google.gson.annotations.SerializedName
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import com.google.gson.ReflectionAccessFilter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonToken
import io.github.logan.gsonsafeparser.internal.SafeTypeAdapterFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Type

/**
 * 验证统一事件流和分层策略配置。
 *
 * 新事件流必须兼容已有回调；分层策略必须只改变配置组织方式，不能丢掉原有读写行为。
 */
class SafeParserEventPolicyTest {
    /** 测试模型：外层响应，用来触发类型错配事件。 */
    data class ApiResponse(val data: User = User())
    /** 测试模型：用户对象，默认 id 用于确认兜底结果。 */
    data class User(val id: Long = 0L)
    /** 测试模型：复杂 Map key，用来覆盖写出策略配置。 */
    data class ComplexKey(val name: String)

    /** 测试模型：重复字段名用来触发 Adapter 创建失败事件。 */
    data class DuplicateNamesForFallback(
        @SerializedName("same")
        val first: String = "",
        @SerializedName("same")
        val second: String = ""
    )

    /** 测试事件：模拟未来版本新增的事件类型。 */
    data class CustomAuditEvent(
        override val eventName: String = "CustomAudit"
    ) : SafeParserEvent

    /**
     * 测试方法说明：验证“unified event stream receives type mismatch while compatibility callback still works”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `unified event stream receives type mismatch while compatibility callback still works`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<SafeParserEvent>()
        val compatibilityEvents = mutableListOf<TypeMismatchEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                onEvent = events::add,
                onTypeMismatch = compatibilityEvents::add
            )
        )

        assertEquals(ApiResponse(), gson.fromJson("""{"data":[]}""", ApiResponse::class.java))

        val event = events.single() as SafeParserEvent.TypeMismatch
        assertEquals(JsonToken.BEGIN_ARRAY, event.detail.actualToken)
        assertEquals(User::class.java.name, event.detail.expectedType)
        assertEquals(1, compatibilityEvents.size)
        assertEquals(event.detail, compatibilityEvents.single())
    }

    /**
     * 测试方法说明：验证“unified event stream receives adapter creation failure while compatibility callback still works”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `unified event stream receives adapter creation failure while compatibility callback still works`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<SafeParserEvent>()
        val compatibilityEvents = mutableListOf<AdapterCreationFailureEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                onEvent = events::add,
                onAdapterCreationFailure = compatibilityEvents::add
            )
        )

        runCatching {
            gson.getAdapter(DuplicateNamesForFallback::class.java)
        }

        val event = events.single() as SafeParserEvent.AdapterCreationFailure
        assertEquals(DuplicateNamesForFallback::class.java.name, event.detail.typeName)
        assertEquals(1, compatibilityEvents.size)
        assertEquals(event.detail, compatibilityEvents.single())
    }

    /**
     * 测试方法说明：验证“policy factory creates config without losing old behavior”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `policy factory creates config without losing old behavior`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<SafeParserEvent>()
        // config 是本用例特意设置的 SafeParser 配置，下面的解析行为会受它影响。
        val config = SafeParserConfig.fromPolicies(
            readPolicy = SafeReadPolicy(
                fallbackPolicy = FallbackPolicy.NullOnly,
                primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson
            ),
            writePolicy = SafeWritePolicy(complexMapKeySerialization = true),
            observerPolicy = SafeObserverPolicy(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 128,
                onEvent = events::add
            )
        )
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(config)
        val type = object : TypeToken<Map<ComplexKey, String>>() {}.type

        val json = gson.toJson(mapOf(ComplexKey("remote") to "value"), type)

        assertEquals(FallbackPolicy.NullOnly, config.fallbackPolicy)
        assertEquals(PrimitiveParsingPolicy.DelegateToGson, config.primitiveParsingPolicy)
        assertEquals("""[[{"name":"remote"},"value"]]""", json)
        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证“config captures mutable collection inputs as snapshots”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `config captures mutable collection inputs as snapshots`() {
        val creator = InstanceCreator { "created" }
        val instanceCreators = linkedMapOf<Type, InstanceCreator<*>>(String::class.java to creator)
        val filter = ReflectionAccessFilter { ReflectionAccessFilter.FilterResult.INDECISIVE }
        val filters = mutableListOf(filter)
        val skippedPrefixes = mutableSetOf("android.")

        val config = SafeParserConfig(
            instanceCreators = instanceCreators,
            reflectionAccessFilters = filters,
            skippedPlatformTypePrefixes = skippedPrefixes
        )

        instanceCreators.clear()
        filters.clear()
        skippedPrefixes.clear()

        assertEquals(setOf(String::class.java), config.instanceCreators.keys)
        assertEquals(listOf(filter), config.reflectionAccessFilters)
        assertEquals(setOf("android."), config.skippedPlatformTypePrefixes)
    }

    /**
     * 测试方法说明：验证“enable safe parser is idempotent on the same builder”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `enable safe parser is idempotent on the same builder`() {
        val builder = GsonBuilder()
            .enableSafeParser()
            .enableSafeParser()

        assertEquals(1, builder.safeTypeAdapterFactoryCount())
    }

    /**
     * 测试方法说明：验证外部手动派发事件不会混入正在解析的 `parseSafe` 快照。
     */
    @OptIn(GsonSafeParserLowLevelApi::class)
    @Test
    fun `manual dispatch inside observer does not pollute parser parse safe snapshot`() {
        lateinit var config: SafeParserConfig
        config = SafeParserConfig(
            onEvent = { event ->
                if (event is SafeParserEvent.TypeMismatch) {
                    config.dispatchEvent(
                        SafeParserEvent.EmptyResponse(
                            EmptyResponseEvent(
                                typeName = "ManualProbe",
                                policy = EmptyResponsePolicy.DefaultValue
                            )
                        )
                    )
                }
            }
        )
        val parser = GsonSafeParser.parser(config)

        val result = parser.parseSafe<ApiResponse>("""{"data":[]}""")

        assertEquals(1, result.events.size)
        assertTrue(result.events.single() is SafeParserEvent.TypeMismatch)
    }

    /**
     * 测试方法说明：验证未知事件也能进入统一观察链路，并在观察者失败报告里落到 Unknown 分类。
     */
    @OptIn(GsonSafeParserLowLevelApi::class)
    @Test
    fun `manual custom event reports unknown observer failure source`() {
        val observerFailures = mutableListOf<ObserverFailureEvent>()
        val config = SafeParserConfig(
            onEvent = { error("observer failed") },
            onObserverFailure = observerFailures::add
        )

        config.dispatchEvent(CustomAuditEvent())

        val failure = observerFailures.single()
        val report = observerFailures.observerFailureReport()
        assertEquals("CustomAudit", failure.eventName)
        assertEquals(SafeParserEventCategory.Unknown, report.failures.single().sourceCategory)
    }

    /**
     * 测试方法说明：验证内置事件名是固定字符串，不依赖 release 混淆后的类名。
     */
    @Test
    fun `built in event names stay stable without relying on class names`() {
        val events = listOf(
            SafeParserEvent.TypeMismatch(
                TypeMismatchEvent(
                    expectedType = User::class.java.name,
                    actualToken = JsonToken.BEGIN_ARRAY,
                    path = "$.data",
                    reason = "Expected object."
                )
            ),
            SafeParserEvent.AdapterCreationFailure(
                AdapterCreationFailureEvent(
                    typeName = DuplicateNamesForFallback::class.java.name,
                    reason = "Duplicate fields.",
                    error = IllegalArgumentException("Duplicate fields.")
                )
            ),
            SafeParserEvent.EmptyResponse(
                EmptyResponseEvent(
                    typeName = ApiResponse::class.java.name,
                    policy = EmptyResponsePolicy.DefaultValue
                )
            ),
            SafeParserEvent.RawJsonCaptureSkipped(
                RawJsonCaptureSkippedEvent(
                    typeName = ApiResponse::class.java.name,
                    contentLength = 2048,
                    maxBytes = 1024,
                    reason = RawJsonCaptureSkipReason.ContentLengthExceedsLimit.message
                )
            )
        )

        assertEquals(
            listOf("TypeMismatch", "AdapterCreationFailure", "EmptyResponse", "RawJsonCaptureSkipped"),
            events.map { event -> event.eventName }
        )
    }

    private fun GsonBuilder.safeTypeAdapterFactoryCount(): Int {
        val field = GsonBuilder::class.java.getDeclaredField("factories").apply {
            isAccessible = true
        }
        val factories = field.get(this) as List<*>
        return factories.count { factory -> factory is SafeTypeAdapterFactory }
    }
}
