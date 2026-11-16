package io.github.logan.gsonsafeparser.internal

import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.MalformedJsonException
import io.github.logan.gsonsafeparser.GsonSafeParserLowLevelApi
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.concurrent.CancellationException

/**
 * 只捕获可以由 SafeParser 局部隔离的异常。
 *
 * Kotlin 标准库的 `runCatching` 会捕获所有 `Throwable`。解析库不能吞掉 VM、类加载、
 * 线程终止、断言失败、取消信号和明确的 I/O 中断，否则会把不可恢复问题伪装成字段错形。
 *
 * @param allowMalformedJson 仅供诊断结果封装及观察回调使用；解析恢复链必须拒绝损坏文档。
 */
internal inline fun <T> runRecovering(
    allowMalformedJson: Boolean = false,
    block: () -> T
): Result<T> {
    return try {
        Result.success(block())
    } catch (error: Throwable) {
        error.throwIfFatal(allowMalformedJson)
        Result.failure(error)
    }
}

/**
 * 调用方自定义 Adapter 的读取失败不属于 SafeParser 可以局部伪装的错形。
 *
 * 用 JsonParseException 做边界标记，让外层 Safe Adapter 继续向外抛出，行为更接近 Gson 原生链路。
 */
internal class CallerAdapterReadException(error: Throwable) : JsonParseException(error)

internal fun Throwable.asCallerAdapterReadException(): CallerAdapterReadException {
    return this as? CallerAdapterReadException ?: CallerAdapterReadException(this)
}

/**
 * 遇到不应被库隔离的异常时直接外抛。
 *
 * 反射调用会把构造器或方法里的异常包进 `InvocationTargetException`，所以这里会检查
 * target exception 和 cause 链，避免不可恢复异常被包装后误判为普通反射失败。
 */
internal fun Throwable.throwIfFatal(allowMalformedJson: Boolean = false) {
    unrecoverableCauseOrNull(allowMalformedJson)?.let { unrecoverable ->
        throw unrecoverable
    }
}

private fun Throwable.unrecoverableCauseOrNull(allowMalformedJson: Boolean): Throwable? {
    val visited = Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>()
    var malformedDocument = false
    pending += this

    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current)) continue
        if (current is CallerAdapterReadException) return current
        if (current is JsonSyntaxException && current.message.orEmpty().startsWith("duplicate key: ")) {
            return current
        }
        if (current is Error || current is CancellationException) return current
        // 损坏或截断的文档没有可继续读取的可靠边界，重试 skipValue 可能一直停在同一位置。
        // 保留已存在的 Gson 包装异常，避免公开入口的异常类型随 catch 层级改变。
        if (current is MalformedJsonException || current is EOFException) malformedDocument = true
        if (current is IOException && current.isUnrecoverableTransportIo()) return current
        if (current is InvocationTargetException) {
            current.targetException?.let { pending += it }
        }
        current.cause?.let { pending += it }
    }
    return if (malformedDocument && !allowMalformedJson) this else null
}

@OptIn(GsonSafeParserLowLevelApi::class)
private fun IOException.isUnrecoverableTransportIo(): Boolean {
    if (this is InterruptedIOException) return true
    if (javaClass.name == "okhttp3.internal.http2.StreamResetException") return true
    return TransportIoContext.isMarked(this)
}
