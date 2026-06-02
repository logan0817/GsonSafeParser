package io.github.logan.gsonsafeparser.internal

import com.google.gson.JsonElement
import com.google.gson.stream.JsonToken
import java.lang.reflect.Type
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collection
import java.util.Map

/**
 * 目标类型和 JSON token 的第一层匹配规则。
 *
 * 这里只做非常粗的形状判断，真正的值转换仍交给对应 Adapter。
 * 这样可以在结构明显错形时尽早局部兜底，同时不把 Gson 的细节规则全部重写一遍。
 */
internal object TokenRules {
    /**
     * 判断某个 JSON token 是否适合交给目标类型的 Adapter 读取。
     *
     * @param type 完整目标类型。当前保留这个参数，是为了后续需要泛型规则时不用改方法签名。
     * @param rawType 目标类型的原始 Class。
     * @param token 当前 JSON token。
     * @return `true` 表示形状大体匹配，可以继续交给 delegate；`false` 表示应该先兜底。
     */
    fun accepts(type: Type, rawType: Class<*>, token: JsonToken): Boolean {
        if (token == JsonToken.NULL) return true
        if (JsonElement::class.java.isAssignableFrom(rawType)) return true

        return when {
            rawType.isArray -> token == JsonToken.BEGIN_ARRAY
            Collection::class.java.isAssignableFrom(rawType) -> token == JsonToken.BEGIN_ARRAY
            Map::class.java.isAssignableFrom(rawType) -> token == JsonToken.BEGIN_OBJECT || token == JsonToken.BEGIN_ARRAY
            isString(rawType) -> isScalar(token)
            isBoolean(rawType) -> isScalar(token)
            isNumber(rawType) -> token == JsonToken.NUMBER || token == JsonToken.STRING
            rawType.isEnum -> token == JsonToken.STRING || token == JsonToken.NUMBER
            isObjectLike(rawType) -> token == JsonToken.BEGIN_OBJECT
            else -> true
        }
    }

    /**
     * 判断目标类型是否像普通业务对象。
     *
     * @param rawType 目标类型的原始 Class。
     * @return `true` 表示可以尝试走 SafeReflectiveAdapterFactory。
     */
    fun isObjectLike(rawType: Class<*>): Boolean {
        // Any/Object 不算普通业务对象，它应该走 Gson 的 Object Adapter 和数值策略。
        return !rawType.isPrimitive &&
            !rawType.isArray &&
            !rawType.isEnum &&
            !Collection::class.java.isAssignableFrom(rawType) &&
            !Map::class.java.isAssignableFrom(rawType) &&
            !isString(rawType) &&
            !isBoolean(rawType) &&
            !isNumber(rawType) &&
            !GsonBuiltInTypes.contains(rawType) &&
            !JsonElement::class.java.isAssignableFrom(rawType) &&
            rawType != Any::class.java
    }

    /**
     * 判断目标类型是不是字符串。
     *
     * @param rawType 目标类型的原始 Class。
     * @return `true` 表示可以按字符串标量规则读取。
     */
    fun isString(rawType: Class<*>): Boolean = rawType == String::class.java

    /**
     * 判断目标类型是不是 Boolean 或 boolean。
     */
    fun isBoolean(rawType: Class<*>): Boolean {
        return rawType == Boolean::class.javaObjectType || rawType == java.lang.Boolean.TYPE
    }

    /**
     * 判断目标类型是不是常见数字类型。
     */
    fun isNumber(rawType: Class<*>): Boolean {
        return Number::class.java.isAssignableFrom(rawType) ||
            rawType == java.lang.Byte.TYPE ||
            rawType == java.lang.Short.TYPE ||
            rawType == java.lang.Integer.TYPE ||
            rawType == java.lang.Long.TYPE ||
            rawType == java.lang.Float.TYPE ||
            rawType == java.lang.Double.TYPE ||
            rawType == BigDecimal::class.java ||
            rawType == BigInteger::class.java
    }

    /**
     * 判断 token 是否是字符串、数字或布尔这类标量值。
     *
     * @param token 当前 JSON token。
     * @return `true` 表示它可以被字符串或布尔的宽松规则处理。
     */
    private fun isScalar(token: JsonToken): Boolean {
        return token == JsonToken.STRING || token == JsonToken.NUMBER || token == JsonToken.BOOLEAN
    }
}
