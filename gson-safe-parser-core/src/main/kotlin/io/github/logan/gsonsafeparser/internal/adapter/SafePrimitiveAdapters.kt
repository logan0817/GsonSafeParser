package io.github.logan.gsonsafeparser.internal.adapter

import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.TypeMismatchEvent
import io.github.logan.gsonsafeparser.dispatchTypeMismatch
import io.github.logan.gsonsafeparser.internal.FallbackValues
import io.github.logan.gsonsafeparser.internal.RawJsonContext
import io.github.logan.gsonsafeparser.internal.runRecovering
import io.github.logan.gsonsafeparser.internal.toSafeTypeName
import java.math.BigDecimal
import java.math.BigInteger

/**
 * 基础类型的安全 Adapter。
 *
 * 后端最常见的问题之一是把数字、布尔、字符串字段返回成对象或数组。
 * 这里在结构错形时只兜底当前字段，正常的标量兼容规则保持清晰且可预测。
 */
internal object SafePrimitiveAdapters {
    /**
     * 根标量兜底开关。
     *
     * Map key 会临时把字符串包装成一段独立 JSON 再交给 keyAdapter 读取，
     * 这类内部辅助解析不应该误触“根值兜底”，否则坏 key 会被错误转成 0 / false。
     */
    private val rootFallbackEnabled = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = true
    }

    /**
     * 为基础类型创建 Safe Adapter。
     *
     * @param type 目标类型，例如 String、Int、BigDecimal。
     * @param config SafeParser 配置。
     * @return 支持的基础类型返回 Adapter，不支持时返回 null 交给 Gson。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> create(type: TypeToken<T>, config: SafeParserConfig): TypeAdapter<T>? {
        // rawType 是实际的基础类型 Class。Kotlin 的 Int 到这里会表现成 java.lang.Integer 或 int。
        val rawType = type.rawType
        val adapter: TypeAdapter<*> = when {
            rawType == String::class.java -> stringAdapter(type, config)
            rawType == Boolean::class.javaObjectType || rawType == java.lang.Boolean.TYPE -> booleanAdapter(type, rawType, config)
            rawType == Int::class.javaObjectType || rawType == java.lang.Integer.TYPE -> numberAdapter(type, rawType, config) { it.toExactInt() }
            rawType == Long::class.javaObjectType || rawType == java.lang.Long.TYPE -> numberAdapter(type, rawType, config) { it.toExactLong() }
            rawType == Float::class.javaObjectType || rawType == java.lang.Float.TYPE -> numberAdapter(type, rawType, config) { it.toFloat() }
            rawType == Double::class.javaObjectType || rawType == java.lang.Double.TYPE -> numberAdapter(type, rawType, config) { it.toDouble() }
            rawType == Short::class.javaObjectType || rawType == java.lang.Short.TYPE -> numberAdapter(type, rawType, config) { it.toExactShort() }
            rawType == Byte::class.javaObjectType || rawType == java.lang.Byte.TYPE -> numberAdapter(type, rawType, config) { it.toExactByte() }
            rawType == BigDecimal::class.java -> numberAdapter(type, rawType, config) { it.toBigDecimalOrZero() }
            rawType == BigInteger::class.java -> numberAdapter(type, rawType, config) { it.toBigDecimalOrZero().toBigIntegerExact() }
            else -> return null
        }
        return adapter as TypeAdapter<T>
    }

    /**
     * 临时关闭根标量兜底。
     *
     * 只给内部辅助解析用，例如 Map key 的字符串转类型，不对外暴露成公开 API。
     */
    internal fun <T> withoutRootFallback(block: () -> T): T {
        val previous = rootFallbackEnabled.get()
        rootFallbackEnabled.set(false)
        return try {
            block()
        } finally {
            rootFallbackEnabled.set(previous)
        }
    }

    /**
     * String Adapter。
     *
     * @param type 目标类型，用于事件里的 expectedType。
     * @param config SafeParser 配置。
     * @return 能宽松读取字符串的 Adapter。
     */
    private fun stringAdapter(type: TypeToken<*>, config: SafeParserConfig): TypeAdapter<String?> {
        return object : TypeAdapter<String?>() {
            /**
             * 写出字符串值。
             *
             * @param out JSON 输出流。
             * @param value 当前字符串值。
             */
            override fun write(out: JsonWriter, value: String?) {
                out.value(value)
            }

            /**
             * 读取字符串值。
             *
             * @param reader Gson Reader。
             * @return 字符串、null，或错形后的 null 兜底。
             */
            override fun read(reader: JsonReader): String? {
                return when (val token = reader.peek()) {
                    JsonToken.NULL -> {
                        reader.nextNull()
                        null
                    }
                    // String 允许接收数字和布尔，避免后端把展示文案写成非字符串时直接失败。
                    JsonToken.STRING, JsonToken.NUMBER, JsonToken.BOOLEAN -> reader.nextString()
                    else -> {
                        notify(config, type, reader, token)
                        reader.skipValue()
                        null
                    }
                }
            }
        }
    }

    /**
     * Boolean Adapter。
     *
     * @param type 目标类型，用于事件里的 expectedType。
     * @param rawType Boolean 的原始 Class，用于取默认值。
     * @param config SafeParser 配置。
     * @return 能兼容 true/false、0/1 和字符串布尔值的 Adapter。
     */
    private fun booleanAdapter(type: TypeToken<*>, rawType: Class<*>, config: SafeParserConfig): TypeAdapter<Boolean?> {
        return object : TypeAdapter<Boolean?>() {
            /**
             * 写出布尔值。
             *
             * @param out JSON 输出流。
             * @param value 当前布尔值。
             */
            override fun write(out: JsonWriter, value: Boolean?) {
                if (value == null) out.nullValue() else out.value(value)
            }

            /**
             * 读取布尔值。
             *
             * @param reader Gson Reader。
             * @return 布尔值、null，或错形后的默认值。
             */
            override fun read(reader: JsonReader): Boolean? {
                return when (val token = reader.peek()) {
                    JsonToken.NULL -> {
                        reader.nextNull()
                        null
                    }
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    // 兼容常见的 0/1 布尔表示。
                    JsonToken.NUMBER -> {
                        val isRootValue = reader.path == "$" && rootFallbackEnabled.get() == true
                        val value = BigDecimal(reader.nextString())
                        when {
                            value.compareTo(BigDecimal.ZERO) == 0 -> false
                            value.compareTo(BigDecimal.ONE) == 0 -> true
                            else -> {
                                notify(config, type, reader, JsonToken.NUMBER, "Expected boolean number 0 or 1 but was $value")
                                structuralMismatchFallback(isRootValue, type, rawType, config)
                            }
                        }
                    }
                    JsonToken.STRING -> {
                        val value = reader.nextString()
                        when {
                            value.equals("true", ignoreCase = true) -> true
                            value.equals("false", ignoreCase = true) -> false
                            else -> rootPrimitiveFallbackOrThrow(
                                reader = reader,
                                type = type,
                                rawType = rawType,
                                config = config,
                                reason = "Expected boolean but was $value"
                            )
                        }
                    }
                    else -> {
                        val isRootValue = reader.path == "$" && rootFallbackEnabled.get() == true
                        notify(config, type, reader, token)
                        reader.skipValue()
                        structuralMismatchFallback(isRootValue, type, rawType, config)
                    }
                }
            }
        }
    }

    /**
     * 数字 Adapter。
     *
     * @param type 目标数字类型，用于事件里的 expectedType。
     * @param rawType 目标数字类型的原始 Class。
     * @param config SafeParser 配置。
     * @param parse 把字符串转成具体数字类型的函数。
     * @return 能读取 NUMBER 或 STRING token 的数字 Adapter。
     */
    private fun <N : Any> numberAdapter(
        type: TypeToken<*>,
        rawType: Class<*>,
        config: SafeParserConfig,
        parse: (String) -> N
    ): TypeAdapter<N?> {
        return object : TypeAdapter<N?>() {
            /**
             * 写出数字值。
             *
             * @param out JSON 输出流。
             * @param value 当前数字值。
             */
            override fun write(out: JsonWriter, value: N?) {
                if (value == null) out.nullValue() else out.value(value as Number)
            }

            /**
             * 读取数字值。
             *
             * @param reader Gson Reader。
             * @return 目标数字类型、null，或错形后的默认值。
             */
            override fun read(reader: JsonReader): N? {
                return when (val token = reader.peek()) {
                    JsonToken.NULL -> {
                        reader.nextNull()
                        null
                    }
                    JsonToken.NUMBER, JsonToken.STRING -> {
                        val value = reader.nextString()
                        if (value.isBlank()) {
                            // 空数字字符串按默认数字值处理，避免空串拖垮整棵 Bean。
                            FallbackValues.value(type.type, rawType, config.fallbackPolicy)
                        } else {
                            runRecovering { parse(value) }.getOrElse {
                                rootPrimitiveFallbackOrThrow(
                                    reader = reader,
                                    type = type,
                                    rawType = rawType,
                                    config = config,
                                    reason = it.message ?: it.javaClass.name,
                                    cause = it
                                )
                            }
                        }
                    }
                    else -> {
                        val isRootValue = reader.path == "$" && rootFallbackEnabled.get() == true
                        notify(config, type, reader, token)
                        reader.skipValue()
                        structuralMismatchFallback(isRootValue, type, rawType, config)
                    }
                }
            }
        }
    }

    /**
     * 把字符串转成 BigDecimal，空字符串返回 0。
     *
     * @return 当前字符串对应的 BigDecimal。
     */
    private fun String.toBigDecimalOrZero(): BigDecimal {
        return if (isBlank()) BigDecimal.ZERO else BigDecimal(this)
    }

    private fun String.toExactInt(): Int {
        val value = toExactBigInteger()
        require(value >= BigInteger.valueOf(Int.MIN_VALUE.toLong()) && value <= BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
            "Integer value is out of Int range"
        }
        return value.toInt()
    }

    private fun String.toExactLong(): Long {
        val value = toExactBigInteger()
        require(value >= BigInteger.valueOf(Long.MIN_VALUE) && value <= BigInteger.valueOf(Long.MAX_VALUE)) {
            "Integer value is out of Long range"
        }
        return value.toLong()
    }

    private fun String.toExactShort(): Short {
        val value = toExactBigInteger()
        require(value >= BigInteger.valueOf(Short.MIN_VALUE.toLong()) && value <= BigInteger.valueOf(Short.MAX_VALUE.toLong())) {
            "Integer value is out of Short range"
        }
        return value.toShort()
    }

    private fun String.toExactByte(): Byte {
        val value = toExactBigInteger()
        require(value >= BigInteger.valueOf(Byte.MIN_VALUE.toLong()) && value <= BigInteger.valueOf(Byte.MAX_VALUE.toLong())) {
            "Integer value is out of Byte range"
        }
        return value.toByte()
    }

    private fun String.toExactBigInteger(): BigInteger {
        return toBigDecimalOrZero().toBigIntegerExact()
    }

    /**
     * 发基础类型错配事件。
     *
     * @param config SafeParser 配置。
     * @param type 目标类型。
     * @param reader 当前 reader。
     * @param token 实际 token。
     * @param reason 错配原因。
     */
    private fun notify(
        config: SafeParserConfig,
        type: TypeToken<*>,
        reader: JsonReader,
        token: JsonToken,
        reason: String = "Unexpected JSON token"
    ) {
        val rawJson = RawJsonContext.current()
        config.dispatchTypeMismatch(
            TypeMismatchEvent(
                expectedType = type.type.toSafeTypeName(),
                actualToken = token,
                path = reader.path,
                reason = reason,
                fieldName = leafFieldNameFromPath(reader.path),
                rawJson = rawJson?.value,
                rawJsonTruncated = rawJson?.truncated == true
            )
        )
    }

    private fun <T> rootPrimitiveFallbackOrThrow(
        reader: JsonReader,
        type: TypeToken<*>,
        rawType: Class<*>,
        config: SafeParserConfig,
        reason: String,
        cause: Throwable? = null
    ): T? {
        if (reader.path != "$" || rootFallbackEnabled.get() != true) {
            throw IllegalArgumentException(reason, cause)
        }
        notify(config, type, reader, JsonToken.STRING, reason)
        return FallbackValues.value(type.type, rawType, config.fallbackPolicy)
    }

    private fun <T> structuralMismatchFallback(
        isRootValue: Boolean,
        type: TypeToken<*>,
        rawType: Class<*>,
        config: SafeParserConfig
    ): T? {
        return if (isRootValue) {
            FallbackValues.value(type.type, rawType, config.fallbackPolicy)
        } else {
            null
        }
    }
}
