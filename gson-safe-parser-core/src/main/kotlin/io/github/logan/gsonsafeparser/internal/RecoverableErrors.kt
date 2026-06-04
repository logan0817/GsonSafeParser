package io.github.logan.gsonsafeparser.internal

import io.github.logan.gsonsafeparser.GsonSafeParserLowLevelApi
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
 */
internal inline fun <T> runRecovering(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (error: Throwable) {
        error.throwIfFatal()
        Result.failure(error)
    }
}

/**
 * 遇到不应被库隔离的异常时直接外抛。
 *
 * 反射调用会把构造器或方法里的异常包进 `InvocationTargetException`，所以这里会检查
 * target exception 和 cause 链，避免不可恢复异常被包装后误判为普通反射失败。
 */
internal fun Throwable.throwIfFatal() {
    unrecoverableCauseOrNull()?.let { unrecoverable ->
        throw unrecoverable
    }
}

private fun Throwable.unrecoverableCauseOrNull(): Throwable? {
    val visited = Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>()
    pending += this

    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current)) continue
        if (current is Error || current is CancellationException) return current
        if (current is IOException && current.isUnrecoverableTransportIo()) return current
        if (current is InvocationTargetException) {
            current.targetException?.let { pending += it }
        }
        current.cause?.let { pending += it }
    }
    return null
}

@OptIn(GsonSafeParserLowLevelApi::class)
private fun IOException.isUnrecoverableTransportIo(): Boolean {
    if (this is InterruptedIOException) return true
    if (javaClass.name == "okhttp3.internal.http2.StreamResetException") return true
    return TransportIoContext.isMarked(this)
}
