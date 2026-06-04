package io.github.logan.gsonsafeparser.internal

import io.github.logan.gsonsafeparser.GsonSafeParserLowLevelApi
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 标记由外部传输流真实抛出的 I/O 异常。
 *
 * 普通业务 Adapter 抛出的 `IOException` 不能仅凭文案判断成网络失败。Retrofit 这类桥接层会在
 * `ResponseBody` 源真正抛出 `IOException` 时记录异常身份，core 只把被记录的异常视为传输失败。
 */
@GsonSafeParserLowLevelApi
object TransportIoContext {
    private val markedErrors = ThreadLocal<MutableSet<IOException>?>()

    fun <T> withTransportIoMarkers(block: () -> T): T {
        val previous = markedErrors.get()
        if (previous == null) {
            markedErrors.set(Collections.newSetFromMap(IdentityHashMap()))
        }
        return try {
            block()
        } finally {
            if (previous == null) {
                markedErrors.remove()
            } else {
                markedErrors.set(previous)
            }
        }
    }

    fun mark(error: IOException): IOException {
        markedErrors.get()?.add(error)
        return error
    }

    fun isMarked(error: IOException): Boolean {
        return markedErrors.get()?.contains(error) == true
    }
}
