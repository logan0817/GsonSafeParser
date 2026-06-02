package io.github.logan.gsonsafeparser.internal

import io.github.logan.gsonsafeparser.FallbackPolicy
import java.lang.reflect.Type
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collection
import java.util.Map

/**
 * 字段错形时的默认值工厂。
 *
 * 这里只处理不会额外触发反射构造的轻量默认值，比如数字 0、空数组、空集合。
 * 复杂对象的默认构造由 SafeObjectConstructor 负责，避免这里悄悄扩大行为边界。
 */
internal object FallbackValues {
    /**
     * 根据目标类型返回最小安全值。
     *
     * @param type 完整目标类型，保留泛型信息，当前主要用于数组和集合判断。
     * @param rawType 目标类型的原始 Class，例如 `Int::class.javaObjectType`。
     * @param policy 当前兜底策略。如果是 `NullOnly`，无论什么类型都返回 null。
     * @return 能安全写回字段的默认值；没有合适默认值时返回 null。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> value(type: Type, rawType: Class<*>, policy: FallbackPolicy): T? {
        if (policy == FallbackPolicy.NullOnly) return null

        // 这些值只在当前字段无法按目标类型读取时使用，目的是保住外层 Bean 继续解析。
        // fallback 是 Any?，最后再做一次泛型转换，因为这里统一处理了基础类型、数组和集合。
        val fallback: Any? = when {
            rawType == java.lang.Boolean.TYPE || rawType == Boolean::class.javaObjectType -> false
            rawType == java.lang.Byte.TYPE || rawType == Byte::class.javaObjectType -> 0.toByte()
            rawType == java.lang.Short.TYPE || rawType == Short::class.javaObjectType -> 0.toShort()
            rawType == java.lang.Integer.TYPE || rawType == Int::class.javaObjectType -> 0
            rawType == java.lang.Long.TYPE || rawType == Long::class.javaObjectType -> 0L
            rawType == java.lang.Float.TYPE || rawType == Float::class.javaObjectType -> 0f
            rawType == java.lang.Double.TYPE || rawType == Double::class.javaObjectType -> 0.0
            rawType == java.lang.Character.TYPE || rawType == Char::class.javaObjectType -> '\u0000'
            rawType == BigDecimal::class.java -> BigDecimal.ZERO
            rawType == BigInteger::class.java -> BigInteger.ZERO
            rawType.isArray -> java.lang.reflect.Array.newInstance(rawType.componentType ?: Any::class.java, 0)
            Collection::class.java.isAssignableFrom(rawType) -> emptyList<Any>()
            Map::class.java.isAssignableFrom(rawType) -> emptyMap<Any, Any>()
            else -> null
        }

        return fallback as T?
    }
}
