package io.github.logan.gsonsafeparser.internal

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.JsonAdapter
import com.google.gson.reflect.TypeToken
import io.github.logan.gsonsafeparser.AdapterCreationFailureEvent
import io.github.logan.gsonsafeparser.PrimitiveParsingPolicy
import io.github.logan.gsonsafeparser.SafeParseDelegateToGson
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.dispatchAdapterCreationFailure
import io.github.logan.gsonsafeparser.internal.adapter.SafeArrayAdapterFactory
import io.github.logan.gsonsafeparser.internal.adapter.SafeCollectionAdapterFactory
import io.github.logan.gsonsafeparser.internal.adapter.SafeMapAdapterFactory
import io.github.logan.gsonsafeparser.internal.adapter.SafeOrgJsonAdapters
import io.github.logan.gsonsafeparser.internal.adapter.SafePrimitiveAdapters
import io.github.logan.gsonsafeparser.internal.adapter.SafeReflectiveAdapterFactory
import io.github.logan.gsonsafeparser.internal.toSafeTypeName
import io.github.logan.gsonsafeparser.internal.runRecovering
import java.util.Collection
import java.util.Map
import java.lang.reflect.Modifier

/**
 * Safe Adapter 的总分发入口。
 *
 * 它决定哪些类型由 GsonSafeParser 接管，哪些类型必须交回 Gson。
 * 这里的默认策略是保守的：自定义类级 @JsonAdapter、Gson 内置类型、抽象类型和接口都不抢解析权。
 */
internal class SafeTypeAdapterFactory(
    private val config: SafeParserConfig
) : TypeAdapterFactory {
    /**
     * 根据目标类型创建对应的 Safe Adapter。
     *
     * @param gson 当前 Gson 实例，用来获取 delegate adapter。
     * @param type 目标类型，包含 rawType 和泛型信息。
     * @return 返回 null 表示本库不接管，让 Gson 继续找后续工厂。
     */
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val rawType = type.rawType
        // rawType 是不带泛型的 Class，新手可以把它理解成“运行时能看到的目标类”。
        if (rawType.name.startsWith("com.google.gson.")) return null
        if (rawType.getAnnotation(SafeParseDelegateToGson::class.java) != null) return null

        // 基础类型是最容易被后端返回错形影响的地方，但也允许调用方选择完全交回 Gson。
        if (config.primitiveParsingPolicy == PrimitiveParsingPolicy.Safe) {
            SafePrimitiveAdapters.create<T>(type, config)?.let { return it }
        } else if (TokenRules.isString(rawType) || TokenRules.isBoolean(rawType) || TokenRules.isNumber(rawType)) {
            return null
        }
        if (GsonBuiltInTypes.contains(rawType)) return null
        SafeOrgJsonAdapters.create(gson, type, config)?.let { return it }

        if (rawType.getAnnotation(JsonAdapter::class.java) != null) {
            return null
        }

        // 集合、数组、Map、普通对象都可能在创建 Adapter 时遇到反射限制或字段冲突，所以统一走 createSafely。
        if (rawType.isArray && !rawType.componentType.isPrimitive) {
            return createSafely(config, type) {
                SafeArrayAdapterFactory.create(
                    gson = gson,
                    type = type,
                    config = config,
                    delegate = gson.getDelegateAdapter(this, type)
                )
            }
        }

        if (Collection::class.java.isAssignableFrom(rawType)) {
            return createSafely(config, type) { SafeCollectionAdapterFactory.create(gson, type, config) }
        }

        if (Map::class.java.isAssignableFrom(rawType)) {
            return createSafely(config, type) { SafeMapAdapterFactory.create(gson, type, config) }
        }

        if (TokenRules.isObjectLike(rawType)) {
            if (rawType.isInterface || Modifier.isAbstract(rawType.modifiers)) return null
            return createSafely(config, type) {
                SafeReflectiveAdapterFactory.create(
                    gson = gson,
                    type = type,
                    config = config,
                    delegateSkipPast = this
                )
            }
        }

        // delegate 是 Gson 后续工厂创建出来的原生 Adapter。SafeTypeAdapter 只在外面包一层形状检查。
        val delegate = gson.getDelegateAdapter(this, type)
        return SafeTypeAdapter(
            type = type.type,
            rawType = rawType,
            delegate = delegate,
            config = config
        )
    }

    /**
     * 安全创建高风险 Adapter。
     *
     * @param config SafeParser 配置。
     * @param type 当前目标类型。
     * @param block 真正创建 Adapter 的代码。
     * @return 创建成功时返回 Safe Adapter；失败且允许回退时返回 null。
     */
    private fun <T> createSafely(
        config: SafeParserConfig,
        type: TypeToken<T>,
        block: () -> TypeAdapter<T>
    ): TypeAdapter<T>? {
        return runRecovering(block).getOrElse { error ->
            // 创建期失败默认只发事件并返回 null，让 Gson 继续用自己的 Adapter，避免本库成为新的崩溃来源。
            config.dispatchAdapterCreationFailure(
                AdapterCreationFailureEvent(
                    typeName = type.type.toSafeTypeName(),
                    reason = error.message ?: error.javaClass.name,
                    error = error
                )
            )
            null
        }
    }
}
