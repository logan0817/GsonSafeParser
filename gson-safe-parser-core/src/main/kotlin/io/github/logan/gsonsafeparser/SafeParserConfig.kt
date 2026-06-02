package io.github.logan.gsonsafeparser

import com.google.gson.InstanceCreator
import com.google.gson.ReflectionAccessFilter
import com.google.gson.ToNumberStrategy
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonReader
import io.github.logan.gsonsafeparser.internal.SafeParseEventContext
import io.github.logan.gsonsafeparser.internal.runRecovering
import java.lang.reflect.Type
import java.math.BigDecimal

/**
 * SafeParser 的完整配置。
 *
 * 配置默认值按照“契约优先、可观测优先”的原则设计：坏数据默认不主动改成安全业务值，
 * 基础类型交回 Gson 原生链路，字段错形优先返回 null 或保留构造默认值。这里的回调全部属于观察能力，不能影响解析结果。
 *
 * @property fallbackPolicy 字段错形后的兜底方式。默认只给 null，显式 Default 才会尽量给安全默认值。
 * @property emptyResponsePolicy Retrofit 空响应怎么处理，只对 retrofit 模块生效。
 * @property instanceCreators 调用方手动提供的对象创建器。key 可以是精确 Type，也可以是 raw Class；配置创建时会保存快照。
 * @property objectToNumberStrategy `Any/Object` 中数字的读取策略。默认使用本库的 Int/Long/Double 自动策略。
 * @property primitiveParsingPolicy 基础类型是否使用 SafeParser 的宽松解析。
 * @property reflectionAccessFilters Gson 的反射访问限制。这里会影响对象构造和字段访问；配置创建时会保存快照。
 * @property complexMapKeySerialization 是否启用 Gson 的复杂 Map key 数组写出形式。
 * @property useJdkUnsafe 是否允许最后使用 JDK Unsafe 绕过构造函数创建对象。
 * @property skippedPlatformTypePrefixes 默认跳过的平台类型包名前缀，例如 Android 平台对象；配置创建时会保存快照。
 * @property nullValuePolicy 后端显式返回 null 时是否写入 nullable 字段。
 * @property mapItemKeyPolicy Map item 事件里的 key 输出策略，默认输出稳定哈希。
 * @property captureRawJsonInCallbacks 是否在错配回调里附带原始 JSON。线上默认关闭。
 * @property maxRawJsonCaptureBytes rawJson 最大捕获长度。普通 Gson 解析按 UTF-8 字节数安全截断；
 * Retrofit 已知长度响应按 `contentLength` 判断，未知长度响应按 `max + 1` 字节有界探测，
 * 未超限才捕获，超限会发出 `RawJsonCaptureSkipped`。
 * @property onEvent 统一事件回调，所有可观测事件都会先进入这里。
 * @property onAdapterCreationFailure Adapter 创建失败的兼容回调，保留给已接入方。
 * @property onTypeMismatch 类型错配的兼容回调，保留给已接入方。
 * @property onObserverFailure 观察回调自身失败时的回调，用来隔离日志、埋点等外部异常。
 */
class SafeParserConfig(
    val fallbackPolicy: FallbackPolicy = FallbackPolicy.NullOnly,
    val emptyResponsePolicy: EmptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly,
    instanceCreators: Map<Type, InstanceCreator<*>> = emptyMap(),
    val objectToNumberStrategy: ToNumberStrategy = GsonSafeAutoNumberStrategy,
    val primitiveParsingPolicy: PrimitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson,
    reflectionAccessFilters: List<ReflectionAccessFilter> = emptyList(),
    val complexMapKeySerialization: Boolean = false,
    val useJdkUnsafe: Boolean = false,
    skippedPlatformTypePrefixes: Set<String> = setOf("android."),
    val nullValuePolicy: NullValuePolicy = NullValuePolicy.WriteExplicitNulls,
    val mapItemKeyPolicy: MapItemKeyPolicy = MapItemKeyPolicy.Hash,
    val captureRawJsonInCallbacks: Boolean = false,
    val maxRawJsonCaptureBytes: Int = 1024 * 1024,
    val onEvent: (SafeParserEvent) -> Unit = {},
    val onAdapterCreationFailure: (AdapterCreationFailureEvent) -> Unit = {},
    val onTypeMismatch: (TypeMismatchEvent) -> Unit = {},
    val onObserverFailure: (ObserverFailureEvent) -> Unit = {}
) {
    val instanceCreators: Map<Type, InstanceCreator<*>> = instanceCreators.toMap()
    val reflectionAccessFilters: List<ReflectionAccessFilter> = reflectionAccessFilters.toList()
    val skippedPlatformTypePrefixes: Set<String> = skippedPlatformTypePrefixes.toSet()

    /**
     * 创建当前配置的副本。
     *
     * 这里手写 `copy`，而不是继续使用 data class，是因为配置里包含多个回调 lambda。
     * lambda 没有稳定的业务相等语义，如果让 data class 自动生成 equals/hashCode，后续缓存或比较配置时容易误判。
     *
     * @param fallbackPolicy 字段错形后的兜底方式。
     * @param emptyResponsePolicy Retrofit 空响应处理方式。
     * @param instanceCreators 调用方提供的对象创建器。
     * @param objectToNumberStrategy `Any/Object` 数字读取策略。
     * @param primitiveParsingPolicy 基础类型读取策略。
     * @param reflectionAccessFilters Gson 反射访问限制。
     * @param complexMapKeySerialization 复杂 Map key 写出开关。
     * @param useJdkUnsafe 是否允许 Unsafe 兜底构造对象。
     * @param skippedPlatformTypePrefixes 需要跳过的平台类型包名前缀。
     * @param nullValuePolicy 后端显式返回 null 时是否写入 nullable 字段。
     * @param mapItemKeyPolicy Map item 事件里的 key 输出策略。
     * @param captureRawJsonInCallbacks 是否在回调里携带原始 JSON。
     * @param maxRawJsonCaptureBytes 按 UTF-8 字节数计算的 rawJson 捕获上限。
     * @param onEvent 统一事件回调。
     * @param onAdapterCreationFailure Adapter 创建失败回调。
     * @param onTypeMismatch 类型错配回调。
     * @param onObserverFailure 观察者自身失败回调。
     * @return 新配置实例。没有传入的参数会沿用当前配置。
     */
    fun copy(
        fallbackPolicy: FallbackPolicy = this.fallbackPolicy,
        emptyResponsePolicy: EmptyResponsePolicy = this.emptyResponsePolicy,
        instanceCreators: Map<Type, InstanceCreator<*>> = this.instanceCreators,
        objectToNumberStrategy: ToNumberStrategy = this.objectToNumberStrategy,
        primitiveParsingPolicy: PrimitiveParsingPolicy = this.primitiveParsingPolicy,
        reflectionAccessFilters: List<ReflectionAccessFilter> = this.reflectionAccessFilters,
        complexMapKeySerialization: Boolean = this.complexMapKeySerialization,
        useJdkUnsafe: Boolean = this.useJdkUnsafe,
        skippedPlatformTypePrefixes: Set<String> = this.skippedPlatformTypePrefixes,
        nullValuePolicy: NullValuePolicy = this.nullValuePolicy,
        mapItemKeyPolicy: MapItemKeyPolicy = this.mapItemKeyPolicy,
        captureRawJsonInCallbacks: Boolean = this.captureRawJsonInCallbacks,
        maxRawJsonCaptureBytes: Int = this.maxRawJsonCaptureBytes,
        onEvent: (SafeParserEvent) -> Unit = this.onEvent,
        onAdapterCreationFailure: (AdapterCreationFailureEvent) -> Unit = this.onAdapterCreationFailure,
        onTypeMismatch: (TypeMismatchEvent) -> Unit = this.onTypeMismatch,
        onObserverFailure: (ObserverFailureEvent) -> Unit = this.onObserverFailure
    ): SafeParserConfig {
        return SafeParserConfig(
            fallbackPolicy = fallbackPolicy,
            emptyResponsePolicy = emptyResponsePolicy,
            instanceCreators = instanceCreators,
            objectToNumberStrategy = objectToNumberStrategy,
            primitiveParsingPolicy = primitiveParsingPolicy,
            reflectionAccessFilters = reflectionAccessFilters,
            complexMapKeySerialization = complexMapKeySerialization,
            useJdkUnsafe = useJdkUnsafe,
            skippedPlatformTypePrefixes = skippedPlatformTypePrefixes,
            nullValuePolicy = nullValuePolicy,
            mapItemKeyPolicy = mapItemKeyPolicy,
            captureRawJsonInCallbacks = captureRawJsonInCallbacks,
            maxRawJsonCaptureBytes = maxRawJsonCaptureBytes,
            onEvent = onEvent,
            onAdapterCreationFailure = onAdapterCreationFailure,
            onTypeMismatch = onTypeMismatch,
            onObserverFailure = onObserverFailure
        )
    }

    companion object {
        /**
         * 线上推荐预设。
         *
         * 使用契约优先读策略、关闭 rawJson 捕获，并默认对 Map item key 做哈希脱敏。
         *
         * @param observerPolicy 观察策略。线上通常只接结构化事件，不开启 rawJson。
         * @param emptyResponsePolicy Retrofit 空响应策略，默认只有 Unit/Void 使用空值。
         * @return 一份适合生产环境直接使用的配置。
         */
        fun production(
            observerPolicy: SafeObserverPolicy = SafeObserverPolicy(
                mapItemKeyPolicy = MapItemKeyPolicy.Hash
            ),
            emptyResponsePolicy: EmptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly
        ): SafeParserConfig {
            return contractFirst(observerPolicy, emptyResponsePolicy)
        }

        /**
         * 契约优先预设。
         *
         * 默认不把坏数据主动改成安全业务值，优先保留后端契约问题的可观测证据。
         */
        fun contractFirst(
            observerPolicy: SafeObserverPolicy = SafeObserverPolicy(
                mapItemKeyPolicy = MapItemKeyPolicy.Hash
            ),
            emptyResponsePolicy: EmptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly
        ): SafeParserConfig {
            return fromPolicies(
                readPolicy = SafeReadPolicy(
                    fallbackPolicy = FallbackPolicy.NullOnly,
                    primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson,
                    useJdkUnsafe = false
                ),
                writePolicy = SafeWritePolicy(
                    complexMapKeySerialization = false
                ),
                observerPolicy = observerPolicy.copy(
                    captureRawJsonInCallbacks = false
                ),
                emptyResponsePolicy = emptyResponsePolicy
            )
        }

        /**
         * 联调和测试环境预设。
         *
         * 和 `production()` 的解析策略一致，但会捕获有限长度的 rawJson，方便定位后端返回了什么。
         *
         * @param observerPolicy 观察策略。联调时一般会把事件打印或上报到测试日志。
         * @param maxRawJsonCaptureBytes 按 UTF-8 字节数计算的 rawJson 捕获上限，默认 64 KiB，避免调试日志过大。
         * @param emptyResponsePolicy Retrofit 空响应策略。
         * @return 一份适合联调环境使用的配置。
         */
        fun debug(
            observerPolicy: SafeObserverPolicy = SafeObserverPolicy(
                mapItemKeyPolicy = MapItemKeyPolicy.PlainText
            ),
            maxRawJsonCaptureBytes: Int = 64 * 1024,
            emptyResponsePolicy: EmptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly
        ): SafeParserConfig {
            return fromPolicies(
                readPolicy = SafeReadPolicy(
                    fallbackPolicy = FallbackPolicy.NullOnly,
                    primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson,
                    useJdkUnsafe = false
                ),
                writePolicy = SafeWritePolicy(
                    complexMapKeySerialization = false
                ),
                observerPolicy = observerPolicy.copy(
                    captureRawJsonInCallbacks = true,
                    maxRawJsonCaptureBytes = maxRawJsonCaptureBytes
                ),
                emptyResponsePolicy = emptyResponsePolicy
            )
        }

        /**
         * 低误伤接入预设。
         *
         * 适合刚接入已有项目时先观察问题：字段错形尽量给 null，基础类型交给 Gson，
         * 关闭 Unsafe 构造，整体行为更接近原生 Gson。
         *
         * @param observerPolicy 观察策略。建议先收集事件，确认不会误伤业务模型。
         * @param emptyResponsePolicy Retrofit 空响应策略，默认直接给 null。
         * @return 一份偏保守、适合灰度观察的配置。
         */
        fun lowInterference(
            observerPolicy: SafeObserverPolicy = SafeObserverPolicy(),
            emptyResponsePolicy: EmptyResponsePolicy = EmptyResponsePolicy.Null
        ): SafeParserConfig {
            return fromPolicies(
                readPolicy = SafeReadPolicy(
                    fallbackPolicy = FallbackPolicy.NullOnly,
                    primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson,
                    useJdkUnsafe = false
                ),
                writePolicy = SafeWritePolicy(
                    complexMapKeySerialization = false
                ),
                observerPolicy = observerPolicy.copy(
                    captureRawJsonInCallbacks = false
                ),
                emptyResponsePolicy = emptyResponsePolicy
            )
        }

        /**
         * 分层策略入口。
         *
         * 读、写、观察三个方向分开传，主要是为了让接入方不用在一个大构造函数里找配置项。
         *
         * @param readPolicy 读 JSON 时的兜底和反射策略。
         * @param writePolicy 写 JSON 时的 Map key 策略。
         * @param observerPolicy 事件观察和 rawJson 捕获策略。
         * @param emptyResponsePolicy Retrofit 空响应策略。
         * @param instanceCreators 额外传入的对象创建器。
         * @param objectToNumberStrategy `Any/Object` 数字读取策略。
         * @return 合并后的完整配置。
         */
        fun fromPolicies(
            readPolicy: SafeReadPolicy = SafeReadPolicy(),
            writePolicy: SafeWritePolicy = SafeWritePolicy(),
            observerPolicy: SafeObserverPolicy = SafeObserverPolicy(),
            emptyResponsePolicy: EmptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly,
            instanceCreators: Map<Type, InstanceCreator<*>> = emptyMap(),
            objectToNumberStrategy: ToNumberStrategy = GsonSafeAutoNumberStrategy
        ): SafeParserConfig {
            return SafeParserConfig(
                fallbackPolicy = readPolicy.fallbackPolicy,
                emptyResponsePolicy = emptyResponsePolicy,
                instanceCreators = instanceCreators,
                objectToNumberStrategy = objectToNumberStrategy,
                primitiveParsingPolicy = readPolicy.primitiveParsingPolicy,
                reflectionAccessFilters = readPolicy.reflectionAccessFilters,
                complexMapKeySerialization = writePolicy.complexMapKeySerialization,
                useJdkUnsafe = readPolicy.useJdkUnsafe,
                skippedPlatformTypePrefixes = readPolicy.skippedPlatformTypePrefixes,
                nullValuePolicy = readPolicy.nullValuePolicy,
                mapItemKeyPolicy = observerPolicy.mapItemKeyPolicy,
                captureRawJsonInCallbacks = observerPolicy.captureRawJsonInCallbacks,
                maxRawJsonCaptureBytes = observerPolicy.maxRawJsonCaptureBytes,
                onEvent = observerPolicy.onEvent,
                onAdapterCreationFailure = observerPolicy.onAdapterCreationFailure,
                onTypeMismatch = observerPolicy.onTypeMismatch,
                onObserverFailure = observerPolicy.onObserverFailure
            )
        }
    }
}

/**
 * 反序列化策略。
 *
 * 这里控制“读 JSON 时怎么兜底、什么时候回 Gson、是否允许反射和 Unsafe 构造”。
 *
 * @property fallbackPolicy 字段错形后的兜底方式。
 * @property primitiveParsingPolicy 基础类型读取方式。
 * @property reflectionAccessFilters 反射访问限制列表。
 * @property skippedPlatformTypePrefixes 需要跳过的系统平台类前缀。
 * @property useJdkUnsafe 是否允许 Unsafe 兜底构造对象。
 * @property nullValuePolicy 后端显式返回 null 时是否写入 nullable 字段。
 */
data class SafeReadPolicy(
    val fallbackPolicy: FallbackPolicy = FallbackPolicy.NullOnly,
    val primitiveParsingPolicy: PrimitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson,
    val reflectionAccessFilters: List<ReflectionAccessFilter> = emptyList(),
    val skippedPlatformTypePrefixes: Set<String> = setOf("android."),
    val useJdkUnsafe: Boolean = false,
    val nullValuePolicy: NullValuePolicy = NullValuePolicy.WriteExplicitNulls
)

/**
 * 序列化策略。
 *
 * 当前库主要目标是安全反序列化，写出策略只保留复杂 Map key 开关。
 *
 * @property complexMapKeySerialization 是否把复杂 Map key 写成 `[[key, value]]` 形式。
 */
data class SafeWritePolicy(
    val complexMapKeySerialization: Boolean = false
)

/**
 * 观察策略。
 *
 * 所有回调都只做通知。即使回调自己抛异常，也会被隔离到 `onObserverFailure`，不会打断解析。
 *
 * @property captureRawJsonInCallbacks 是否在错配事件里附带原始 JSON。
 * @property maxRawJsonCaptureBytes rawJson 最大捕获长度。普通 Gson 按 UTF-8 字节安全截断；
 * Retrofit 未知长度响应会先做有界探测，避免为了 rawJson 观测读入超大响应。
 * @property mapItemKeyPolicy Map item 事件里的 key 输出策略。
 * @property onEvent 统一事件回调。
 * @property onAdapterCreationFailure Adapter 创建失败兼容回调。
 * @property onTypeMismatch 类型错配兼容回调。
 * @property onObserverFailure 观察者失败回调。
 */
class SafeObserverPolicy(
    val captureRawJsonInCallbacks: Boolean = false,
    val maxRawJsonCaptureBytes: Int = 1024 * 1024,
    val mapItemKeyPolicy: MapItemKeyPolicy = MapItemKeyPolicy.Hash,
    val onEvent: (SafeParserEvent) -> Unit = {},
    val onAdapterCreationFailure: (AdapterCreationFailureEvent) -> Unit = {},
    val onTypeMismatch: (TypeMismatchEvent) -> Unit = {},
    val onObserverFailure: (ObserverFailureEvent) -> Unit = {}
) {
    /**
     * 创建观察策略副本。
     *
     * 和 `SafeParserConfig.copy` 一样，这里保留 copy 用法，但不让 lambda 参与 data class 自动相等性计算。
     *
     * @param captureRawJsonInCallbacks 是否在回调里携带原始 JSON。
     * @param maxRawJsonCaptureBytes 按 UTF-8 字节数计算的 rawJson 捕获上限。
     * @param mapItemKeyPolicy Map item 事件里的 key 输出策略。
     * @param onEvent 统一事件回调。
     * @param onAdapterCreationFailure Adapter 创建失败回调。
     * @param onTypeMismatch 类型错配回调。
     * @param onObserverFailure 观察者自身失败回调。
     * @return 新观察策略实例。没有传入的参数会沿用当前策略。
     */
    fun copy(
        captureRawJsonInCallbacks: Boolean = this.captureRawJsonInCallbacks,
        maxRawJsonCaptureBytes: Int = this.maxRawJsonCaptureBytes,
        mapItemKeyPolicy: MapItemKeyPolicy = this.mapItemKeyPolicy,
        onEvent: (SafeParserEvent) -> Unit = this.onEvent,
        onAdapterCreationFailure: (AdapterCreationFailureEvent) -> Unit = this.onAdapterCreationFailure,
        onTypeMismatch: (TypeMismatchEvent) -> Unit = this.onTypeMismatch,
        onObserverFailure: (ObserverFailureEvent) -> Unit = this.onObserverFailure
    ): SafeObserverPolicy {
        return SafeObserverPolicy(
            captureRawJsonInCallbacks = captureRawJsonInCallbacks,
            maxRawJsonCaptureBytes = maxRawJsonCaptureBytes,
            mapItemKeyPolicy = mapItemKeyPolicy,
            onEvent = onEvent,
            onAdapterCreationFailure = onAdapterCreationFailure,
            onTypeMismatch = onTypeMismatch,
            onObserverFailure = onObserverFailure
        )
    }
}

/**
 * 字段错形后的兜底策略。
 */
enum class FallbackPolicy {
    /** 尽量使用安全默认值，例如空集合、空 Map、数字 0 或构造默认对象。 */
    Default,
    /** 只返回 null，适合希望尽量贴近 Gson 原生失败语义的接入阶段。 */
    NullOnly
}

/**
 * Retrofit 空响应策略。
 */
enum class EmptyResponsePolicy {
    /** 空响应构造默认对象，`Unit/Void` 使用各自的空值。 */
    DefaultValue,
    /** 只给 `Unit/Void` 使用 Retrofit 空值，普通业务模型空响应返回 null。 */
    DefaultValueForUnitOrVoidOnly,
    /** 空响应直接返回 null。 */
    Null,
    /** 普通业务模型空响应交给 GsonConverterFactory 原生处理；`Unit/Void` 固定返回 Retrofit 空值。 */
    DelegateToGson
}

/**
 * 基础类型解析策略。
 */
enum class PrimitiveParsingPolicy {
    /** 使用 SafeParser 的宽松基础类型解析，结构错形时只兜底当前字段。 */
    Safe,
    /** 基础类型交回 Gson 原生 Adapter，适合低误伤观察阶段。 */
    DelegateToGson
}

/**
 * JSON 显式 null 写入策略。
 */
enum class NullValuePolicy {
    /** 后端明确返回 null 时，nullable 字段写入 null；非 nullable 字段保留构造默认值。 */
    WriteExplicitNulls,
    /** 后端明确返回 null 时也保留构造默认值。 */
    KeepDefaults
}

/**
 * Map item 事件里的 key 输出策略。
 */
enum class MapItemKeyPolicy {
    /** 明文输出，适合本地联调。 */
    PlainText,
    /** 输出稳定哈希，适合线上聚合。 */
    Hash,
    /** 不输出 key，适合高敏感场景。 */
    Omit
}

/**
 * 类型错配事件。
 *
 * 这类事件表示 JSON token 和目标类型不一致，库已经尝试局部兜底或跳过当前 item。
 *
 * @property expectedType 代码里期望读取的目标类型。
 * @property actualToken 实际 JSON token，例如 BEGIN_ARRAY、BEGIN_OBJECT、STRING。
 * @property path Gson Reader 当前路径，例如 `$.data[0]`。
 * @property reason 错配原因，通常是 token 不符合预期或 delegate 读取失败。
 * @property kind 错配发生的位置，是普通 `Object` 字段、List item 还是 Map item。
 * @property fieldName 归因到的字段名，便于日志聚合。
 * @property mapItemKey Map item 错配时对应的 key。
 * @property rawJson 可选原始 JSON，只在配置开启时出现。
 * @property rawJsonTruncated rawJson 是否被截断。
 */
data class TypeMismatchEvent(
    val expectedType: String,
    val actualToken: JsonToken,
    val path: String,
    val reason: String,
    val kind: ParseExceptionKind = ParseExceptionKind.OBJECT,
    val fieldName: String? = null,
    val mapItemKey: String? = null,
    val rawJson: String? = null,
    val rawJsonTruncated: Boolean = false
)

/**
 * Safe Adapter 创建失败事件。
 *
 * 只要配置允许，创建失败后会返回 null 让 Gson 使用默认 Adapter；事件用于提醒接入方这个类型没有被 SafeParser 接管。
 *
 * @property typeName 创建 Adapter 失败的目标类型名。
 * @property reason 简短失败原因，适合日志展示。
 * @property error 原始异常对象，只应该在本地调试使用，不建议直接上报完整堆栈。
 */
data class AdapterCreationFailureEvent(
    val typeName: String,
    val reason: String,
    val error: Throwable
)

/**
 * 低层 SafeParser API。
 *
 * 标记给框架桥接层或明确知道事件来源的调用方使用。普通业务接入优先使用 `parseSafe`、`onEvent`
 * 和 Retrofit 工厂入口，不直接手动注入事件。
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is a low-level SafeParser API. Prefer parseSafe/onEvent unless you are bridging framework events."
)
annotation class GsonSafeParserLowLevelApi

/**
 * 统一事件流。
 *
 * 事件流只负责观测，不应该反向改变解析策略；后续扩展事件时也要保持这个约束。
 * 这个接口保持开放，方便后续版本增加事件类型，也允许扩展层接入自定义事件。
 */
interface SafeParserEvent {
    /** 稳定事件名，用于日志、观察者失败报告和自定义事件归因。 */
    val eventName: String
        get() = javaClass.simpleName.ifBlank { javaClass.name }

    data class TypeMismatch(val detail: TypeMismatchEvent) : SafeParserEvent {
        override val eventName: String = "TypeMismatch"
    }

    data class AdapterCreationFailure(val detail: AdapterCreationFailureEvent) : SafeParserEvent {
        override val eventName: String = "AdapterCreationFailure"
    }

    data class EmptyResponse(val detail: EmptyResponseEvent) : SafeParserEvent {
        override val eventName: String = "EmptyResponse"
    }

    data class RawJsonCaptureSkipped(val detail: RawJsonCaptureSkippedEvent) : SafeParserEvent {
        override val eventName: String = "RawJsonCaptureSkipped"
    }
}

/**
 * 观察者失败事件。
 *
 * 它描述的是 `onEvent`、`onTypeMismatch` 这类观察回调自身抛异常，不代表 JSON 解析失败。
 *
 * @property callbackName 失败的回调名称，例如 `onEvent`。
 * @property eventName 当时正在处理的事件名称。
 * @property sourceEvent 触发回调的原始事件。
 * @property reason 回调异常的简短原因。
 * @property error 回调抛出的原始异常。
 */
data class ObserverFailureEvent(
    val callbackName: String,
    val eventName: String,
    val sourceEvent: SafeParserEvent,
    val reason: String,
    val error: Throwable
)

/**
 * Retrofit 空响应事件。
 *
 * @property typeName Retrofit 当前期望转换的响应类型。
 * @property policy 空响应使用的处理策略。
 */
data class EmptyResponseEvent(
    val typeName: String,
    val policy: EmptyResponsePolicy
)

/**
 * Retrofit rawJson 捕获被跳过的事件。
 *
 * 通常是响应体太大，继续读取会增加内存压力，所以记录事件后交回普通 converter。
 *
 * @property typeName Retrofit 当前期望转换的响应类型。
 * @property contentLength 响应体声明的字节长度。
 * @property maxBytes 配置允许捕获的最大字节数。
 * @property reason 跳过 rawJson 捕获的原因。
 */
data class RawJsonCaptureSkippedEvent(
    val typeName: String,
    val contentLength: Long,
    val maxBytes: Int,
    val reason: String
) {
    /**
     * 跳过 rawJson 捕获的稳定枚举原因，方便日志平台和 CI 不再解析 reason 文本。
     */
    val skipReason: RawJsonCaptureSkipReason
        get() = RawJsonCaptureSkipReason.from(contentLength, reason)
}

/**
 * rawJson 捕获跳过原因。
 *
 * @property message 面向日志和报告展示的英文说明。
 */
enum class RawJsonCaptureSkipReason(val message: String) {
    /** 已知 Content-Length 超过配置上限。 */
    ContentLengthExceedsLimit("Response body exceeds maxRawJsonCaptureBytes."),
    /** gzip、chunked 或缺少 Content-Length 的响应在有界探测中超过配置上限。 */
    UnknownLengthExceedsLimit(
        "Response body length is unknown and exceeds maxRawJsonCaptureBytes during bounded probe."
    ),
    /** 无法从旧事件 reason 中归类的跳过原因。 */
    Unknown("Raw JSON capture was skipped.");

    companion object {
        /**
         * 从已发布字段推导枚举原因，避免给 `RawJsonCaptureSkippedEvent` 增加构造参数。
         */
        fun from(contentLength: Long, reason: String): RawJsonCaptureSkipReason {
            return when {
                contentLength >= 0L -> ContentLengthExceedsLimit
                reason.contains("length is unknown", ignoreCase = true) -> UnknownLengthExceedsLimit
                else -> Unknown
            }
        }
    }
}

/**
 * 错配发生的位置类型。
 */
enum class ParseExceptionKind {
    /** 普通 `Object` 字段错配。 */
    OBJECT,
    /** List/Set/Queue 中的单个元素错配。 */
    LIST_ITEM,
    /** Map 中的单个 key 或 value 错配。 */
    MAP_ITEM
}

/**
 * 分发类型错配事件。
 *
 * @param event 已经组装好的错配详情。
 */
internal fun SafeParserConfig.dispatchTypeMismatch(event: TypeMismatchEvent) {
    // 把类型错配兼容回调统一转换成事件流，后面再分发给 onEvent 和 onTypeMismatch。
    dispatchParserEvent(SafeParserEvent.TypeMismatch(event))
}

/**
 * 分发 Adapter 创建失败事件。
 *
 * @param event 创建失败详情，默认会作为观测信息上报，不直接抛给业务调用方。
 */
internal fun SafeParserConfig.dispatchAdapterCreationFailure(event: AdapterCreationFailureEvent) {
    // Adapter 创建失败也走统一事件流，这样 diagnostics、日志和兼容回调都能看到同一份信息。
    dispatchParserEvent(SafeParserEvent.AdapterCreationFailure(event))
}

/**
 * 分发 Retrofit 空响应事件。
 *
 * @param event 空响应详情。
 */
internal fun SafeParserConfig.dispatchEmptyResponse(event: EmptyResponseEvent) {
    // 空响应来自 Retrofit 层，不属于类型错配，所以只进入统一事件流。
    dispatchParserEvent(SafeParserEvent.EmptyResponse(event))
}

/**
 * 分发 rawJson 捕获被跳过的事件。
 *
 * @param event 跳过捕获的原因和长度信息。
 */
internal fun SafeParserConfig.dispatchRawJsonCaptureSkipped(event: RawJsonCaptureSkippedEvent) {
    // rawJson 捕获被跳过是观测事件，不应该影响 converter 继续解析。
    dispatchParserEvent(SafeParserEvent.RawJsonCaptureSkipped(event))
}

/**
 * 分发 SafeParser 事件。
 *
 * 这是低层事件注入口，主要给 Retrofit 这类桥接层复用统一观察链路。手动调用只会触发观察回调，
 * 不会写入当前 `parseSafe` 事件快照，也不代表真实解析已经发生。
 *
 * @param event 本次要通知给外部观察者的事件。
 */
@GsonSafeParserLowLevelApi
fun SafeParserConfig.dispatchEvent(event: SafeParserEvent) {
    dispatchEvent(event, collectForParseSafe = false)
}

private fun SafeParserConfig.dispatchParserEvent(event: SafeParserEvent) {
    dispatchEvent(event, collectForParseSafe = true)
}

private fun SafeParserConfig.dispatchEvent(
    event: SafeParserEvent,
    collectForParseSafe: Boolean
) {
    if (collectForParseSafe) {
        // 只收集解析链路产生的真实事件；外部手动注入事件不进入 parseSafe 快照。
        SafeParseEventContext.emit(event)
    }
    // 每个观察者独立执行，一个回调坏了不能影响另一个回调，更不能影响主解析流程。
    notifyObserver("onEvent", event) {
        onEvent(event)
    }
    when (event) {
        is SafeParserEvent.TypeMismatch -> notifyObserver("onTypeMismatch", event) {
            onTypeMismatch(event.detail)
        }
        is SafeParserEvent.AdapterCreationFailure -> notifyObserver("onAdapterCreationFailure", event) {
            onAdapterCreationFailure(event.detail)
        }
        is SafeParserEvent.EmptyResponse,
        is SafeParserEvent.RawJsonCaptureSkipped -> Unit
        else -> Unit
    }
}

/**
 * 安全调用一个观察者回调。
 *
 * @param callbackName 回调名称，用来在失败事件里告诉用户是哪一个回调坏了。
 * @param sourceEvent 当前正在分发的 SafeParser 事件。
 * @param callback 真正的用户回调代码。
 */
private fun SafeParserConfig.notifyObserver(
    callbackName: String,
    sourceEvent: SafeParserEvent,
    callback: () -> Unit
) {
    // callbackName 用来告诉用户到底是哪一个回调失败，不让他们在日志里猜。
    runRecovering(callback).onFailure { error ->
        notifyObserverFailure(
            ObserverFailureEvent(
                callbackName = callbackName,
                eventName = sourceEvent.eventName,
                sourceEvent = sourceEvent,
                reason = error.message ?: error.javaClass.name,
                error = error
            )
        )
    }
}

/**
 * 通知观察者回调自身失败。
 *
 * @param event 观察者失败详情。这里再次出现异常时会被吞掉，避免回调失败递归放大。
 */
private fun SafeParserConfig.notifyObserverFailure(event: ObserverFailureEvent) {
    // 观察者失败报告本身也可能接日志系统；这里吞掉异常，避免形成递归失败。
    runRecovering {
        onObserverFailure(event)
    }
}

/**
 * 本库默认的 Object 数字策略。
 *
 * `Any/Object` 中的整数优先落到 Int，再用 Long，超过 Long 范围时保留 BigInteger，小数使用 Double。
 */
object GsonSafeAutoNumberStrategy : ToNumberStrategy {
    /** Int 最小值，提前转成 BigDecimal，避免每次读数字时重复创建边界对象。 */
    private val intMinValue = BigDecimal.valueOf(Int.MIN_VALUE.toLong())

    /** Int 最大值，提前转成 BigDecimal，正负边界用同一套比较逻辑。 */
    private val intMaxValue = BigDecimal.valueOf(Int.MAX_VALUE.toLong())

    /** Long 最小值，提前转成 BigDecimal，避免超大整数落到 Long 时截断。 */
    private val longMinValue = BigDecimal.valueOf(Long.MIN_VALUE)

    /** Long 最大值，提前转成 BigDecimal，避免超大整数落到 Long 时截断。 */
    private val longMaxValue = BigDecimal.valueOf(Long.MAX_VALUE)

    /**
     * 读取 `Any/Object` 里的数字。
     *
     * @param reader Gson 当前数字 token 的 reader。
     * @return Int、Long、BigInteger 或 Double。这个返回值会直接进入业务的 Any 字段。
     */
    override fun readNumber(reader: JsonReader): Number {
        // value 先用 BigDecimal 保存，避免一开始就转 Double 丢精度。
        val value = BigDecimal(reader.nextString())
        if (value.scale() > 0) {
            return value.toDouble()
        }
        if (value >= intMinValue && value <= intMaxValue) return value.toInt()
        if (value >= longMinValue && value <= longMaxValue) return value.toLong()
        return value.toBigIntegerExact()
    }
}
