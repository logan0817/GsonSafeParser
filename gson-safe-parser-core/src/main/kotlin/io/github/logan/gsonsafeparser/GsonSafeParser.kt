package io.github.logan.gsonsafeparser

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import com.google.gson.JsonSyntaxException
import com.google.gson.ReflectionAccessFilter
import com.google.gson.ToNumberStrategy
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.logan.gsonsafeparser.internal.FallbackValues
import io.github.logan.gsonsafeparser.internal.GsonBuiltInTypes
import io.github.logan.gsonsafeparser.internal.RawJsonContext
import io.github.logan.gsonsafeparser.internal.SafeParseEventContext
import io.github.logan.gsonsafeparser.internal.SafeTypeAdapterFactory
import io.github.logan.gsonsafeparser.internal.TokenRules
import io.github.logan.gsonsafeparser.internal.adapter.SafeReflectiveAdapterFactory
import io.github.logan.gsonsafeparser.internal.runRecovering
import io.github.logan.gsonsafeparser.internal.throwIfFatal
import io.github.logan.gsonsafeparser.internal.toSafeTypeName
import java.io.StringReader
import java.lang.reflect.Type

/**
 * GsonSafeParser 的统一入口。
 *
 * 这个对象只负责创建带 Safe Adapter 的 Gson、执行诊断和提供带 rawJson 上下文的解析辅助方法。
 * 真正的解析仍然走 Gson 的 TypeAdapter 链路；遇到当前库不确定能处理的类型时，默认交回 Gson。
 */
object GsonSafeParser {
    /**
     * 创建可复用的安全解析器。
     *
     * 这个入口适合高频手动解析场景：Parser 内部只创建一次 Gson，后续每次 `fromJson` 都复用同一个 Gson，
     * 避免直接反复调用便利入口 `GsonSafeParser.fromJson(json, type, config)` 时重复创建 Gson。
     *
     * @param config SafeParser 配置。Parser 会用它创建并持有一个安全 Gson。
     * @return 可复用 Parser 实例。Parser 是轻量包装，可以作为单例、DI 对象或 Repository 成员长期持有。
     * Parser 和内部持有的 Gson 可以跨线程复用；配置里的观察回调会在实际解析调用线程同步触发。
     */
    fun parser(config: SafeParserConfig = SafeParserConfig()): Parser {
        return Parser(
            gson = create(config),
            config = config
        )
    }

    /**
     * 用调用方的 GsonBuilder 创建可复用安全解析器。
     *
     * 这个入口会在 Builder 创建 Gson 前注册 Safe Adapter，适合既要保留项目原有 Gson 配置，
     * 又希望这份 config 真正控制字段级 Safe 解析的场景。
     */
    fun parser(
        builder: GsonBuilder,
        config: SafeParserConfig = SafeParserConfig()
    ): Parser {
        return Parser(
            gson = builder.enableSafeParser(config).create(),
            config = config
        )
    }

    /**
     * 包装调用方已经创建好的 Gson，生成可复用 Parser。
     *
     * 这个入口不会重新创建、替换或补注册传入的 Gson。字段级 Safe Adapter 仍由这份 Gson 自己决定；
     * 如果需要字段级安全解析，调用方应在创建 Gson 前对同一个 GsonBuilder 调用 `enableSafeParser(config)`。
     * 字段级 Safe Adapter 事件会进入创建这份 Gson 时传给 `enableSafeParser(...)` 的配置回调；
     * 这里传入的 config 主要控制 rawJson 捕获、根基础类型兜底和 `parseSafe` 事件快照。
     *
     * @param gson 调用方持有的 Gson 实例。
     * @param config 包装层 SafeParser 配置，用来控制 rawJson 捕获、根基础类型兜底和 `parseSafe` 事件快照。
     * @return 包装后的可复用 Parser。它不会替换或重新创建传入的 Gson。
     */
    fun parserWithExternalGson(
        gson: Gson,
        config: SafeParserConfig = SafeParserConfig()
    ): Parser {
        return Parser(
            gson = gson,
            config = config
        )
    }

    /**
     * 可复用安全解析器。
     *
     * 这个类只做一件事：持有一份 Gson 和一份 SafeParserConfig，并把后续解析统一委托给
     * `GsonSafeParser.fromJson(gson, json, type, config)`。它不做全局缓存，也不比较配置里的 lambda，
     * 所以不会引入隐藏生命周期问题。
     *
     * @property gson 当前 Parser 复用的 Gson 实例。暴露出来方便接 Retrofit 或做测试断言。
     * @property config 当前 Parser 复用的 SafeParser 配置。
     */
    class Parser internal constructor(
        val gson: Gson,
        val config: SafeParserConfig
    ) {
        /**
         * 按 Java Class 解析 JSON。
         *
         * @param json 原始 JSON 字符串。
         * @param type 目标类型的 Java Class。
         * @return 解析结果。可恢复错形会按配置返回兜底值或 null；不可恢复 Gson 异常会继续抛出。
         */
        fun <T> fromJson(
            json: String,
            type: Class<T>
        ): T? {
            return fromJson(json, type as Type)
        }

        /**
         * 按反射 Type 解析 JSON。
         *
         * @param json 原始 JSON 字符串。
         * @param type 目标类型，支持 `TypeToken<List<User>>() {}.type` 这类泛型。
         * @return 解析结果。泛型信息会完整传给 Gson；不可恢复 Gson 异常会继续抛出。
         */
        fun <T> fromJson(
            json: String,
            type: Type
        ): T? {
            return GsonSafeParser.fromJson(gson, json, type, config)
        }

        /**
         * 按 Java Class 解析 JSON，并返回本次解析期间产生的事件快照。
         *
         * @param json 原始 JSON 字符串。
         * @param type 目标类型的 Java Class。
         * @return 业务对象和本次事件快照。`parseSafe` 只收集事件，不会吞掉不可恢复 Gson 异常。
         */
        fun <T> parseSafe(
            json: String,
            type: Class<T>
        ): SafeParseResult<T> {
            return parseSafe(json, type as Type)
        }

        /**
         * 按反射 Type 解析 JSON，并返回本次解析期间产生的事件快照。
         *
         * @param json 原始 JSON 字符串。
         * @param type 目标类型，支持泛型。
         * @return 业务对象和本次事件快照。事件来自解析期 ThreadLocal 桥，不会要求重新创建 Gson；
         * 不可恢复 Gson 异常会继续抛出。
         */
        fun <T> parseSafe(
            json: String,
            type: Type
        ): SafeParseResult<T> {
            val events = mutableListOf<SafeParserEvent>()
            val value = SafeParseEventContext.collectInto(events) {
                fromJson<T>(json, type)
            }
            return SafeParseResult(value, events.toList())
        }
    }

    /**
     * 创建一个已经注册 Safe Adapter 的 Gson。
     *
     * 默认配置偏生产可用：尽量兜底常见错形字段，同时在 Adapter 创建失败时回到 Gson 默认策略。
     * 这个默认入口直接使用 [SafeParserConfig] 注册 Safe Adapter，不读取 `GsonBuilder` 内部字段；
     * 只有调用方传入已有 `GsonBuilder` 的 builder-first 入口才需要继承 Builder 内部配置。
     *
     * @param config SafeParser 配置。默认配置适合先在普通业务接口里试用。
     * @return 已经注册 Safe Adapter 的 Gson 实例。
     */
    fun create(config: SafeParserConfig = SafeParserConfig()): Gson {
        return GsonBuilder()
            .registerSafeParserDirect(config)
            .create()
    }

    /**
     * 检查当前运行环境和配置是否适合接入。
     *
     * 这里不会解析业务 JSON，也不会修改传入配置；它只读 GsonBuilder 的兼容字段，并给出可观测的风险项。
     *
     * @param config 当前准备使用的 SafeParser 配置。
     * @return 诊断结果。`hasErrors=true` 时说明 Safe Adapter 当前不适合接管。
     */
    fun diagnostics(config: SafeParserConfig = SafeParserConfig()): GsonSafeDiagnostics {
        val snapshot = GsonBuilder().compatibilitySnapshot()
        // checks 是给开发者看的诊断清单。每发现一个环境或配置风险，就往这里追加一项。
        val checks = mutableListOf<GsonSafeDiagnosticCheck>()
        checks += snapshot.summaryDiagnosticCheck()
        checks += snapshot.fieldDiagnosticChecks()
        checks += kotlinReflectCheck()
        if (config.skippedPlatformTypePrefixes.isEmpty()) {
            checks += GsonSafeDiagnosticCheck(
                name = "skippedPlatformTypePrefixes",
                severity = DiagnosticSeverity.WARNING,
                message = "Platform type skipping is disabled."
            )
        }
        if (config.captureRawJsonInCallbacks && config.maxRawJsonCaptureBytes <= 0) {
            checks += GsonSafeDiagnosticCheck(
                name = "maxRawJsonCaptureBytes",
                severity = DiagnosticSeverity.WARNING,
                message = "Raw JSON capture is enabled but maxRawJsonCaptureBytes is not positive."
            )
        }
        return GsonSafeDiagnostics(
            safeAdapterAvailable = snapshot.safeAdapterRegistrationAvailable,
            checks = checks
        )
    }

    /**
     * 检查调用方已经创建好的 Gson 是否真的带有字段级 Safe Adapter。
     *
     * 这个入口只做反射诊断，不会修改传入 Gson，也不会补注册 Safe Adapter。普通外部 Gson 会得到 WARNING，
     * 表示它仍然保持 Gson 原生字段解析行为；需要字段级安全解析时，应优先使用 `parser(builder, config)`、
     * `create(config)` 或在创建 Gson 前调用 `GsonBuilder.enableSafeParser(config)`。
     *
     * @param gson 调用方已经创建好的 Gson。
     * @param config 当前准备使用的 SafeParser 配置。
     * @return 外部 Gson 接管状态和运行环境风险。
     */
    fun diagnostics(
        gson: Gson,
        config: SafeParserConfig = SafeParserConfig()
    ): GsonSafeDiagnostics {
        val base = diagnostics(config)
        val externalCheck = gson.safeAdapterRegistrationCheck()
        return GsonSafeDiagnostics(
            safeAdapterAvailable = base.safeAdapterAvailable &&
                externalCheck.severity == DiagnosticSeverity.OK,
            checks = base.checks + externalCheck
        )
    }

    /**
     * 解释某个类型会被 SafeParser 如何处理。
     *
     * 这个 API 只做静态接管决策说明，不解析业务 JSON，也不触发用户回调。
     */
    fun explainType(
        type: Type,
        config: SafeParserConfig = SafeParserConfig()
    ): GsonSafeTypeExplanation {
        val rawType = runRecovering { TypeToken.get(type).rawType }.getOrNull()
            ?: return GsonSafeTypeExplanation(
                typeName = type.toSafeTypeName(),
                handling = SafeTypeHandling.AdapterCreationFailure,
                checks = listOf(
                    GsonSafeDiagnosticCheck(
                        name = "typeResolution",
                        severity = DiagnosticSeverity.ERROR,
                        message = "Unable to resolve raw type for ${type.toSafeTypeName()}."
                    )
                )
            )
        val handling = when {
            rawType.name.startsWith("com.google.gson.") -> SafeTypeHandling.DelegateToGson
            rawType.getAnnotation(SafeParseDelegateToGson::class.java) != null -> SafeTypeHandling.DelegateToGson
            config.primitiveParsingPolicy == PrimitiveParsingPolicy.Safe &&
                (TokenRules.isString(rawType) || TokenRules.isBoolean(rawType) || TokenRules.isNumber(rawType)) -> {
                SafeTypeHandling.SafePrimitive
            }
            config.primitiveParsingPolicy == PrimitiveParsingPolicy.DelegateToGson &&
                (TokenRules.isString(rawType) || TokenRules.isBoolean(rawType) || TokenRules.isNumber(rawType)) -> {
                SafeTypeHandling.DelegateToGson
            }
            rawType.getAnnotation(com.google.gson.annotations.JsonAdapter::class.java) != null -> SafeTypeHandling.DelegateToGson
            GsonBuiltInTypes.contains(rawType) -> SafeTypeHandling.DelegateToGson
            rawType == org.json.JSONObject::class.java || rawType == org.json.JSONArray::class.java -> SafeTypeHandling.SafeOrgJson
            rawType.isArray && !rawType.componentType.isPrimitive -> SafeTypeHandling.SafeTypeWrapper
            java.util.Collection::class.java.isAssignableFrom(rawType) -> SafeTypeHandling.SafeCollection
            java.util.Map::class.java.isAssignableFrom(rawType) -> SafeTypeHandling.SafeMap
            rawType.isInterface || java.lang.reflect.Modifier.isAbstract(rawType.modifiers) -> SafeTypeHandling.DelegateToGson
            TokenRules.isObjectLike(rawType) && !rawType.isInterface &&
                !java.lang.reflect.Modifier.isAbstract(rawType.modifiers) -> {
                return explainReflectiveType(type, config)
            }
            else -> SafeTypeHandling.SafeTypeWrapper
        }
        return GsonSafeTypeExplanation(
            typeName = type.toSafeTypeName(),
            handling = handling,
            checks = listOf(
                GsonSafeDiagnosticCheck(
                    name = "typeHandling",
                    severity = DiagnosticSeverity.OK,
                    message = "${type.toSafeTypeName()} uses $handling."
                )
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun explainReflectiveType(
        type: Type,
        config: SafeParserConfig
    ): GsonSafeTypeExplanation {
        val quietConfig = config.copy(
            onEvent = {},
            onAdapterCreationFailure = {},
            onTypeMismatch = {},
            onObserverFailure = {}
        )
        val result = runRecovering {
            SafeReflectiveAdapterFactory.create(
                gson = GsonBuilder().create(),
                type = TypeToken.get(type) as TypeToken<Any>,
                config = quietConfig
            )
        }
        return result.fold(
            onSuccess = {
                GsonSafeTypeExplanation(
                    typeName = type.toSafeTypeName(),
                    handling = SafeTypeHandling.SafeReflective,
                    checks = listOf(
                        GsonSafeDiagnosticCheck(
                            name = "typeHandling",
                            severity = DiagnosticSeverity.OK,
                            message = "${type.toSafeTypeName()} uses ${SafeTypeHandling.SafeReflective}."
                        )
                    )
                )
            },
            onFailure = { error ->
                GsonSafeTypeExplanation(
                    typeName = type.toSafeTypeName(),
                    handling = SafeTypeHandling.AdapterCreationFailure,
                    checks = listOf(
                        GsonSafeDiagnosticCheck(
                            name = "adapterCreation",
                            severity = DiagnosticSeverity.ERROR,
                            message = error.message ?: error.javaClass.name
                        )
                    )
                )
            }
        )
    }

    /**
     * 按 Java Class 解析 JSON。
     *
     * @param json 原始 JSON 字符串。
     * @param type 目标类型的 Java Class，例如 `ApiResponse::class.java`。
     * @param config SafeParser 配置。
     * @return 解析结果。可恢复错形会按配置返回兜底值或 null；不可恢复 Gson 异常会继续抛出。
     */
    fun <T> fromJson(
        json: String,
        type: Class<T>,
        config: SafeParserConfig = SafeParserConfig()
    ): T? {
        return fromJson(json, type as Type, config)
    }

    /**
     * 按反射 Type 解析 JSON。
     *
     * @param json 原始 JSON 字符串。
     * @param type 目标类型，支持 `TypeToken<List<User>>() {}.type` 这类泛型。
     * @param config SafeParser 配置。
     * @return 解析结果。泛型信息会完整传给 Gson；不可恢复 Gson 异常会继续抛出。
     */
    fun <T> fromJson(
        json: String,
        type: Type,
        config: SafeParserConfig = SafeParserConfig()
    ): T? {
        return fromJson(create(config), json, type, config)
    }

    /**
     * 使用调用方传入的 Gson 解析 JSON。
     *
     * @param gson 已经创建好的 Gson。它通常已经通过 `enableSafeParser()` 注册过 Safe Adapter。
     * @param json 原始 JSON 字符串。
     * @param type 目标类型。
     * @param config SafeParser 配置，用来决定 rawJson 捕获和回调行为。
     * @return 解析结果。不可恢复 Gson 异常会继续抛出。
     */
    fun <T> fromJson(
        gson: Gson,
        json: String,
        type: Type,
        config: SafeParserConfig = SafeParserConfig()
    ): T? {
        // rawJson 通过 ThreadLocal 透传给错配事件，只在本次解析期间可见，避免污染后续解析。
        val rawJson = RawJsonContext.snapshot(json, config)
        return RawJsonContext.withRawJson(rawJson) {
            try {
                gson.fromJson<T>(json, type)
            } catch (error: RuntimeException) {
                val recoverableRootToken = recoverableRootPrimitiveTokenOrNull(type, config, json, gson, error)
                if (recoverableRootToken != null) {
                    dispatchRootPrimitiveFailure(
                        type = type,
                        actualToken = recoverableRootToken,
                        error = error,
                        config = config,
                        rawJson = rawJson
                    )
                    fallbackValue(type, config)
                } else {
                    throw error
                }
            }
        }
    }

    private fun shouldRecoverRootPrimitive(type: Type, config: SafeParserConfig): Boolean {
        if (config.primitiveParsingPolicy != PrimitiveParsingPolicy.Safe) return false
        val rawType = TypeToken.get(type).rawType
        return TokenRules.isString(rawType) || TokenRules.isBoolean(rawType) || TokenRules.isNumber(rawType)
    }

    private fun recoverableRootPrimitiveTokenOrNull(
        type: Type,
        config: SafeParserConfig,
        json: String,
        gson: Gson,
        error: RuntimeException
    ): JsonToken? {
        error.throwIfFatal()
        if (!shouldRecoverRootPrimitive(type, config)) return null
        if (!usesNativeRootPrimitiveAdapter(gson, type)) return null
        val rootToken = completeRootTokenOrNull(json) ?: return null
        return when (rootToken) {
            JsonToken.BEGIN_OBJECT,
            JsonToken.BEGIN_ARRAY -> if (error.hasNativeExpectedTokenCause(rootToken)) rootToken else null
            JsonToken.STRING,
            JsonToken.NUMBER,
            JsonToken.BOOLEAN -> if (error.hasNumberFormatCause()) rootToken else null
            JsonToken.NULL,
            JsonToken.NAME,
            JsonToken.END_ARRAY,
            JsonToken.END_OBJECT,
            JsonToken.END_DOCUMENT -> null
        }
    }

    private fun completeRootTokenOrNull(json: String): JsonToken? {
        return runRecovering {
            val reader = JsonReader(StringReader(json))
            val token = reader.peek()
            reader.skipValue()
            if (reader.peek() == JsonToken.END_DOCUMENT) token else null
        }.getOrNull()
    }

    private fun usesNativeRootPrimitiveAdapter(gson: Gson, type: Type): Boolean {
        val adapter = runRecovering { gson.getAdapter(TypeToken.get(type)) }.getOrNull() ?: return false
        // 外层兜底只能包 Gson 原生基础 Adapter；用户自定义 Adapter 的异常必须交还调用方。
        return adapter.javaClass.name.startsWith("com.google.gson.internal.bind.TypeAdapters$")
    }

    private fun Throwable.hasNativeExpectedTokenCause(rootToken: JsonToken): Boolean {
        var current: Throwable? = this
        var seenJsonSyntaxException = false
        while (current != null) {
            if (current is JsonSyntaxException) {
                seenJsonSyntaxException = true
            }
            val message = current.message.orEmpty()
            if (
                (seenJsonSyntaxException || current is IllegalStateException) &&
                message.contains("Expected") &&
                message.contains("but was ${rootToken.name}")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun Throwable.hasNumberFormatCause(): Boolean {
        var current: Throwable? = this
        var seenJsonSyntaxException = false
        while (current != null) {
            if (current is JsonSyntaxException) {
                seenJsonSyntaxException = true
            }
            if (seenJsonSyntaxException && current is NumberFormatException) return true
            current = current.cause
        }
        return false
    }

    private fun <T> fallbackValue(type: Type, config: SafeParserConfig): T? {
        val rawType = TypeToken.get(type).rawType
        return FallbackValues.value(type, rawType, config.fallbackPolicy)
    }

    private fun dispatchRootPrimitiveFailure(
        type: Type,
        actualToken: JsonToken,
        error: RuntimeException,
        config: SafeParserConfig,
        rawJson: RawJsonContext.Snapshot?
    ) {
        config.dispatchTypeMismatch(
            TypeMismatchEvent(
                expectedType = type.toSafeTypeName(),
                actualToken = actualToken,
                path = "$",
                reason = error.message ?: error.javaClass.name,
                kind = ParseExceptionKind.OBJECT,
                rawJson = rawJson?.value,
                rawJsonTruncated = rawJson?.truncated == true
            )
        )
    }
}

private fun kotlinReflectCheck(): GsonSafeDiagnosticCheck {
    return if (runRecovering { Class.forName("kotlin.reflect.full.KClasses") }.isSuccess) {
        GsonSafeDiagnosticCheck(
            name = "kotlinReflect",
            severity = DiagnosticSeverity.OK,
            message = "kotlin-reflect is available."
        )
    } else {
        GsonSafeDiagnosticCheck(
            name = "kotlinReflect",
            severity = DiagnosticSeverity.WARNING,
            message = "kotlin-reflect is not available; Kotlin data class default value support can be limited."
        )
    }
}

/**
 * 给已有 GsonBuilder 接入 Safe Adapter。
 *
 * 这个入口会尽量读取 Builder 上已经配置的 InstanceCreator、ReflectionAccessFilter、复杂 Map key、
 * Unsafe 开关和 Object number 策略。安全关键字段读取失败时不注册 Safe Adapter，保留 Gson 原生行为；
 * 可选字段读取失败时继续注册，但对应 Builder 配置继承会降级。
 * 同一个 GsonBuilder 重复调用时会直接返回原 Builder，避免重复注册 Safe Adapter。
 *
 * @param config SafeParser 配置。它会和 GsonBuilder 上已有配置合并。
 * @return 原始 GsonBuilder，方便继续链式调用 `.create()`。
 */
fun GsonBuilder.enableSafeParser(
    config: SafeParserConfig = SafeParserConfig()
): GsonBuilder {
    if (hasSafeTypeAdapterFactory()) return this
    val snapshot = compatibilitySnapshot()
    if (snapshot.criticalFailures.isNotEmpty()) {
        // 如果安全关键字段读不到，就不要强行注册 Safe Adapter，否则可能比 Gson 原生更不稳定。
        val error = IllegalStateException(
            "Unable to inspect critical GsonBuilder internals, delegate to Gson default strategy."
        ).apply {
            snapshot.criticalFailures.forEach { failure -> addSuppressed(failure.asException()) }
        }
        config.dispatchAdapterCreationFailure(
            AdapterCreationFailureEvent(
                typeName = GsonBuilder::class.java.toSafeTypeName(),
                reason = error.message ?: error.javaClass.name,
                error = error
            )
        )
        return this
    }
    val objectToNumberStrategy = if (
        config.objectToNumberStrategy === GsonSafeAutoNumberStrategy &&
        snapshot.objectToNumberStrategy != null &&
        snapshot.objectToNumberStrategy !== defaultObjectToNumberStrategy()
    ) {
        // 调用方已经在 Builder 上指定数值策略时，以调用方配置为准，SafeParser 不抢策略所有权。
        snapshot.objectToNumberStrategy
    } else {
        config.objectToNumberStrategy
    }
    config.reflectionAccessFilters.forEach(::addReflectionAccessFilter)
    // safeConfig 是真正交给 SafeTypeAdapterFactory 的配置快照。
    // 这里把 Builder 上的配置合并进去，确保调用方通过 GsonBuilder 写的配置不会丢失。
    val safeConfig = config.copy(
        instanceCreators = snapshot.instanceCreators + config.instanceCreators,
        objectToNumberStrategy = objectToNumberStrategy,
        reflectionAccessFilters = config.reflectionAccessFilters.asReversed() + snapshot.reflectionFilters,
        complexMapKeySerialization = config.complexMapKeySerialization || snapshot.complexMapKeySerialization,
        useJdkUnsafe = if (
            config.requiredConstructorParameterPolicy == RequiredConstructorParameterPolicy.Strict
        ) {
            false
        } else {
            config.useJdkUnsafe && snapshot.useJdkUnsafe
        }
    )
    if (safeConfig.requiredConstructorParameterPolicy == RequiredConstructorParameterPolicy.Strict) {
        // Strict 优先级最高，不能让 Safe 构造层或 Gson delegate 通过 Unsafe 绕过构造校验。
        disableJdkUnsafe()
    }
    return setObjectToNumberStrategy(objectToNumberStrategy)
        .registerTypeAdapterFactory(SafeTypeAdapterFactory(safeConfig))
}

private fun GsonBuilder.registerSafeParserDirect(
    config: SafeParserConfig = SafeParserConfig()
): GsonBuilder {
    val safeConfig = config.copy(
        useJdkUnsafe = if (
            config.requiredConstructorParameterPolicy == RequiredConstructorParameterPolicy.Strict
        ) {
            false
        } else {
            config.useJdkUnsafe
        }
    )
    safeConfig.reflectionAccessFilters.forEach(::addReflectionAccessFilter)
    if (safeConfig.requiredConstructorParameterPolicy == RequiredConstructorParameterPolicy.Strict) {
        disableJdkUnsafe()
    }
    return setObjectToNumberStrategy(safeConfig.objectToNumberStrategy)
        .registerTypeAdapterFactory(SafeTypeAdapterFactory(safeConfig))
}

private fun GsonBuilder.hasSafeTypeAdapterFactory(): Boolean {
    val factories = runRecovering {
        snapshotField("factories") as? Collection<*>
    }.getOrNull()
    return factories?.any { factory -> factory is SafeTypeAdapterFactory } == true
}

private fun Gson.safeAdapterRegistrationCheck(): GsonSafeDiagnosticCheck {
    val factories = runRecovering {
        val field = Gson::class.java.getDeclaredField("factories")
        field.isAccessible = true
        field.get(this) as? Collection<*>
    }.getOrElse { error ->
        return GsonSafeDiagnosticCheck(
            name = "externalGsonSafeAdapter",
            severity = DiagnosticSeverity.WARNING,
            message = "Unable to inspect external Gson factories; field-level SafeAdapter registration is unknown: " +
                (error.message ?: error.javaClass.name)
        )
    }
    val hasSafeAdapter = factories?.any { factory -> factory is SafeTypeAdapterFactory } == true
    return if (hasSafeAdapter) {
        GsonSafeDiagnosticCheck(
            name = "externalGsonSafeAdapter",
            severity = DiagnosticSeverity.OK,
            message = "External Gson contains GsonSafeParser field-level SafeAdapter."
        )
    } else {
        GsonSafeDiagnosticCheck(
            name = "externalGsonSafeAdapter",
            severity = DiagnosticSeverity.WARNING,
            message = "External Gson does not contain GsonSafeParser field-level SafeAdapter; parsing keeps Gson native behavior."
        )
    }
}

/**
 * GsonBuilder 内部配置快照。
 *
 * @property instanceCreators Builder 上注册的 InstanceCreator。
 * @property objectToNumberStrategy Builder 上设置的 Object 数字策略。
 * @property reflectionFilters Builder 上设置的反射访问过滤器。
 * @property complexMapKeySerialization Builder 是否开启复杂 Map key 序列化。
 * @property useJdkUnsafe Builder 是否允许使用 JDK Unsafe。
 * @property failures 读取内部字段时遇到的异常列表。
 */
private data class GsonBuilderCompatibilitySnapshot(
    val instanceCreators: Map<Type, InstanceCreator<*>>,
    val objectToNumberStrategy: ToNumberStrategy?,
    val reflectionFilters: List<ReflectionAccessFilter>,
    val complexMapKeySerialization: Boolean,
    val useJdkUnsafe: Boolean,
    val failures: List<GsonBuilderCompatibilityFailure>
) {
    val criticalFailures: List<GsonBuilderCompatibilityFailure>
        get() = failures.filter { failure -> failure.field.critical }

    val safeAdapterRegistrationAvailable: Boolean
        get() = criticalFailures.isEmpty()

    fun summaryDiagnosticCheck(): GsonSafeDiagnosticCheck {
        val severity = when {
            criticalFailures.isNotEmpty() -> DiagnosticSeverity.ERROR
            failures.isNotEmpty() -> DiagnosticSeverity.WARNING
            else -> DiagnosticSeverity.OK
        }
        val message = when (severity) {
            DiagnosticSeverity.OK -> {
                "GsonBuilder critical internals are readable. Gson version: ${gsonRuntimeVersion()}."
            }
            DiagnosticSeverity.WARNING -> {
                "Gson version: ${gsonRuntimeVersion()}. Optional GsonBuilder internals are not fully readable: " +
                    failures.joinToString { failure -> failure.field.fieldName }
            }
            DiagnosticSeverity.ERROR -> {
                "Gson version: ${gsonRuntimeVersion()}. Critical GsonBuilder internals are not readable: " +
                    criticalFailures.joinToString { failure -> failure.field.fieldName }
            }
        }
        return GsonSafeDiagnosticCheck(
            name = "gsonBuilderCompatibility",
            severity = severity,
            message = message
        )
    }

    fun fieldDiagnosticChecks(): List<GsonSafeDiagnosticCheck> {
        return GsonBuilderCompatibilityField.values().map { field ->
            val failure = failures.firstOrNull { failure -> failure.field == field }
            val severity = when {
                failure == null -> DiagnosticSeverity.OK
                field.critical -> DiagnosticSeverity.ERROR
                else -> DiagnosticSeverity.WARNING
            }
            val role = if (field.critical) "critical" else "optional"
            val message = if (failure == null) {
                "GsonBuilder.${field.fieldName} $role compatibility field is readable."
            } else {
                "GsonBuilder.${field.fieldName} $role compatibility field is not readable: " +
                    (failure.error.message ?: failure.error.javaClass.name)
            }
            GsonSafeDiagnosticCheck(
                name = field.checkName,
                severity = severity,
                message = message
            )
        }
    }
}

private enum class GsonBuilderCompatibilityField(
    val fieldName: String,
    val checkName: String,
    val critical: Boolean
) {
    InstanceCreators(
        fieldName = "instanceCreators",
        checkName = "gsonBuilderInstanceCreatorsCompatibility",
        critical = false
    ),
    ObjectToNumberStrategy(
        fieldName = "objectToNumberStrategy",
        checkName = "gsonBuilderObjectToNumberStrategyCompatibility",
        critical = false
    ),
    ReflectionFilters(
        fieldName = "reflectionFilters",
        checkName = "gsonBuilderReflectionFiltersCompatibility",
        critical = true
    ),
    ComplexMapKeySerialization(
        fieldName = "complexMapKeySerialization",
        checkName = "gsonBuilderComplexMapKeySerializationCompatibility",
        critical = false
    ),
    UseJdkUnsafe(
        fieldName = "useJdkUnsafe",
        checkName = "gsonBuilderUseJdkUnsafeCompatibility",
        critical = true
    )
}

private data class GsonBuilderCompatibilityFailure(
    val field: GsonBuilderCompatibilityField,
    val error: Throwable
) {
    fun asException(): IllegalStateException {
        return IllegalStateException("Unable to read GsonBuilder.${field.fieldName}", error)
    }
}

/**
 * 读取 GsonBuilder 里 SafeParser 需要继承的内部配置。
 *
 * @return 读取到的配置快照；单个字段读取失败会记录到 failures。
 */
@Suppress("UNCHECKED_CAST")
private fun GsonBuilder.compatibilitySnapshot(): GsonBuilderCompatibilitySnapshot {
    // failures 用来收集反射读取失败的字段，最后统一交给 diagnostics 和回退逻辑判断。
    val failures = mutableListOf<GsonBuilderCompatibilityFailure>()
    fun field(field: GsonBuilderCompatibilityField): Any? {
        return runRecovering { snapshotField(field.fieldName) }
            .onFailure { error -> failures += GsonBuilderCompatibilityFailure(field, error) }
            .getOrNull()
    }

    return GsonBuilderCompatibilitySnapshot(
        instanceCreators = field(GsonBuilderCompatibilityField.InstanceCreators) as? Map<Type, InstanceCreator<*>> ?: emptyMap(),
        objectToNumberStrategy = field(GsonBuilderCompatibilityField.ObjectToNumberStrategy) as? ToNumberStrategy,
        reflectionFilters = (field(GsonBuilderCompatibilityField.ReflectionFilters) as? Collection<*>)
            ?.filterIsInstance<ReflectionAccessFilter>()
            .orEmpty(),
        complexMapKeySerialization = field(GsonBuilderCompatibilityField.ComplexMapKeySerialization) as? Boolean ?: false,
        useJdkUnsafe = field(GsonBuilderCompatibilityField.UseJdkUnsafe) as? Boolean ?: true,
        failures = failures
    )
}

/**
 * 读取 Gson 默认的 Object 数字策略。
 */
private fun defaultObjectToNumberStrategy(): ToNumberStrategy? {
    return runRecovering {
        GsonBuilder().snapshotField(GsonBuilderCompatibilityField.ObjectToNumberStrategy.fieldName) as? ToNumberStrategy
    }.getOrNull()
}

private fun gsonRuntimeVersion(): String {
    return Gson::class.java.`package`?.implementationVersion ?: "unknown"
}

/**
 * 反射读取 GsonBuilder 的单个字段。
 *
 * @param fieldName GsonBuilder 内部字段名。
 * @return 字段当前值。
 */
private fun GsonBuilder.snapshotField(fieldName: String): Any? {
    // GsonBuilder 没有公开所有内部配置读取入口，只能反射做快照；失败会进入 diagnostics 和回退链路。
    val field = GsonBuilder::class.java.getDeclaredField(fieldName)
    field.isAccessible = true
    return field.get(this)
}
