package io.github.logan.gsonsafeparser.internal.adapter

import com.google.gson.reflect.TypeToken
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.logan.gsonsafeparser.SafeParseDisableShapeCoercion
import io.github.logan.gsonsafeparser.SafeParseDelegateToGson
import io.github.logan.gsonsafeparser.SafeParseShapeCoercion
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.ShapeCoercionAction
import io.github.logan.gsonsafeparser.ShapeCoercionEvent
import io.github.logan.gsonsafeparser.ShapeCoercionPolicy
import io.github.logan.gsonsafeparser.dispatchShapeCoercion
import io.github.logan.gsonsafeparser.internal.toSafeTypeName
import java.lang.reflect.Field
import java.lang.reflect.Type

/**
 * 字段级 shape coercion 运行期上下文。
 *
 * 集合和数组 Adapter 不知道自己正在读取哪个字段，所以由反射 Adapter 在字段读取期间临时注入策略。
 */
internal object ShapeCoercionReadContext {
    private val currentPolicy = ThreadLocal<ShapeCoercionPolicy?>()

    /**
     * 在当前线程临时使用字段级策略读取一个字段。
     */
    fun <T> withPolicy(policy: ShapeCoercionPolicy, block: () -> T): T {
        val previous = currentPolicy.get()
        currentPolicy.set(policy)
        return try {
            block()
        } finally {
            if (previous == null) {
                currentPolicy.remove()
            } else {
                currentPolicy.set(previous)
            }
        }
    }

    /**
     * 返回当前字段策略；没有字段上下文时固定关闭，避免根级对象或根级集合被全局策略误转换。
     */
    fun currentPolicy(config: SafeParserConfig): ShapeCoercionPolicy {
        return currentPolicy.get() ?: ShapeCoercionPolicy.Disabled
    }
}

/**
 * 计算字段最终使用的 shape coercion 策略。
 */
internal fun Field.shapeCoercionPolicy(config: SafeParserConfig, resolvedFieldType: Type): ShapeCoercionPolicy {
    if (getAnnotation(SafeParseDisableShapeCoercion::class.java) != null) {
        return ShapeCoercionPolicy.Disabled
    }
    val resolvedRawType = TypeToken.get(resolvedFieldType).rawType
    if (resolvedRawType.getAnnotation(SafeParseDelegateToGson::class.java) != null) {
        return ShapeCoercionPolicy.Disabled
    }
    val annotation = getAnnotation(SafeParseShapeCoercion::class.java)
    if (annotation != null) {
        return annotation.policy
    }
    if (resolvedRawType.getAnnotation(JsonAdapter::class.java) != null) {
        return ShapeCoercionPolicy.Disabled
    }
    return config.shapeCoercionPolicy
}

/**
 * 是否允许对象字段从数组第一个元素恢复。
 */
internal fun ShapeCoercionPolicy.supportsObjectFromArray(): Boolean {
    return this == ShapeCoercionPolicy.ObjectFromFirstArrayItem ||
        this == ShapeCoercionPolicy.ObjectAndCollection
}

/**
 * 是否允许集合或数组字段从单个对象恢复。
 */
internal fun ShapeCoercionPolicy.supportsCollectionFromObject(): Boolean {
    return this == ShapeCoercionPolicy.CollectionFromSingleObject ||
        this == ShapeCoercionPolicy.ObjectAndCollection
}

/**
 * 派发 shape coercion 事件，保持 path 和字段名归因一致。
 */
internal fun dispatchShapeCoercion(
    config: SafeParserConfig,
    type: TypeToken<*>,
    reader: JsonReader,
    token: JsonToken,
    action: ShapeCoercionAction,
    fieldName: String? = null,
    discardedItemCount: Int = 0,
    reason: String = "JSON shape coerced by explicit policy",
    path: String? = null
) {
    val eventPath = path ?: reader.path
    config.dispatchShapeCoercion(
        ShapeCoercionEvent(
            expectedType = type.type.toSafeTypeName(),
            actualToken = token,
            path = eventPath,
            action = action,
            fieldName = fieldName ?: leafFieldNameFromPath(eventPath),
            discardedItemCount = discardedItemCount,
            reason = reason
        )
    )
}
