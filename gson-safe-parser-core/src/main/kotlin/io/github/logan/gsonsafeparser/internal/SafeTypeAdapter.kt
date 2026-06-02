package io.github.logan.gsonsafeparser.internal

import com.google.gson.JsonIOException
import com.google.gson.JsonParseException
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.TypeMismatchEvent
import io.github.logan.gsonsafeparser.dispatchTypeMismatch
import io.github.logan.gsonsafeparser.internal.adapter.leafFieldNameFromPath
import io.github.logan.gsonsafeparser.internal.throwIfFatal
import java.io.IOException
import java.lang.reflect.Type

/**
 * 非反射类型的兜底包装 Adapter。
 *
 * 它只在读取前做 token 检查和异常恢复，真正的读写仍交给 Gson 原来的 delegate。
 * 这样能覆盖枚举等边界类型，同时尽量不改变 Gson 已有行为。
 *
 * @param T 当前 Adapter 读写的目标类型。
 * @property type 完整目标类型，保留泛型信息。
 * @property rawType 目标类型的原始 Class，用来做 token 规则判断。
 * @property delegate Gson 原生 Adapter，正常读写都交给它。
 * @property config SafeParser 配置。
 */
internal class SafeTypeAdapter<T>(
    private val type: Type,
    private val rawType: Class<*>,
    private val delegate: TypeAdapter<T>,
    private val config: SafeParserConfig
) : TypeAdapter<T>() {
    /**
     * 序列化仍然交给原始 delegate。
     *
     * @param out Gson 写 JSON 的输出流。
     * @param value 要写出的业务值。
     */
    override fun write(out: JsonWriter, value: T?) {
        delegate.write(out, value)
    }

    /**
     * 读取 JSON，并在 token 明显错形或 delegate 抛出可恢复异常时返回安全默认值。
     *
     * @param reader Gson 当前 reader。
     * @return 读取到的值，或者当前类型的安全默认值。
     */
    override fun read(reader: JsonReader): T? {
        val token = reader.peek()
        val pathBeforeRead = reader.path
        // token 是读取前看到的 JSON 形状。先看形状，是为了在数组/对象错形时不让 delegate 直接抛异常。
        if (!TokenRules.accepts(type, rawType, token)) {
            notify(reader, token, "Unexpected JSON token")
            reader.skipValue()
            return FallbackValues.value(type, rawType, config.fallbackPolicy)
        }

        return try {
            delegate.read(reader)
        } catch (error: IllegalStateException) {
            error.throwIfFatal()
            recover(reader, error, token, pathBeforeRead)
        } catch (error: NumberFormatException) {
            error.throwIfFatal()
            recover(reader, error, token, pathBeforeRead)
        } catch (error: JsonParseException) {
            error.throwIfFatal()
            recover(reader, error, token, pathBeforeRead)
        } catch (error: IOException) {
            error.throwIfFatal()
            throw JsonIOException(error)
        }
    }

    /**
     * 从 delegate 抛出的可恢复异常里恢复。
     *
     * @param reader 当前 reader，可能已经被 delegate 消费了一部分。
     * @param error delegate 抛出的异常。
     * @return 当前类型的兜底值。
     */
    private fun recover(reader: JsonReader, error: RuntimeException, token: JsonToken, pathBeforeRead: String): T? {
        // delegate 可能已经消费了一部分 token，恢复时只尽量跳过当前值，失败也不能再扩大异常。
        notify(
            reader = reader,
            token = token,
            reason = error.message ?: error.javaClass.name,
            path = pathBeforeRead
        )
        runRecovering { reader.skipValue() }
        return FallbackValues.value(type, rawType, config.fallbackPolicy)
    }

    /**
     * 发出非反射 Adapter 的类型错配事件。
     *
     * @param reader 当前 reader，用来读取 path。
     * @param token 触发错配的 JSON token。
     * @param reason 简短原因。
     */
    private fun notify(reader: JsonReader, token: JsonToken, reason: String, path: String = reader.path) {
        // notify 只发事件，不抛异常；真正的兜底值由 read/recover 返回。
        config.dispatchTypeMismatch(
            TypeMismatchEvent(
                expectedType = type.toSafeTypeName(),
                actualToken = token,
                path = path,
                reason = reason,
                fieldName = leafFieldNameFromPath(path)
            )
        )
    }
}
