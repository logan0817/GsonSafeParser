package io.github.logan.gsonsafeparser.internal

import io.github.logan.gsonsafeparser.SafeParserConfig

/**
 * 本次解析的原始 JSON 上下文。
 *
 * rawJson 只用于观察事件，默认关闭。开启后也会按配置截断，避免把大响应体长期留在内存里。
 */
internal object RawJsonContext {
    /**
     * 捕获到的原始 JSON 快照。
     *
     * `truncated` 用来提醒日志侧：这里不是完整响应，不能拿它做重新解析。
     */
    data class Snapshot(
        val value: String?,
        val truncated: Boolean
    )

    /**
     * 当前线程正在解析的 rawJson。
     *
     * 使用 ThreadLocal 是为了避免多个接口并发解析时互相串数据。
     */
    private val current = ThreadLocal<Snapshot?>()

    /**
     * 读取当前线程正在解析的 rawJson 快照。
     *
     * @return 没有开启 rawJson 捕获时返回 null。
     */
    fun current(): Snapshot? = current.get()

    /**
     * 根据配置生成 rawJson 快照。
     *
     * @param json 本次解析的原始 JSON 字符串。
     * @param config SafeParser 配置。
     * @return 需要捕获时返回快照，否则返回 null。
     */
    fun snapshot(json: String, config: SafeParserConfig): Snapshot? {
        if (!config.captureRawJsonInCallbacks) return null
        val limit = config.maxRawJsonCaptureBytes.coerceAtLeast(0)
        return json.truncateUtf8(limit)
    }

    /**
     * 在当前线程临时绑定 rawJson。
     *
     * @param rawJson 本次解析要暴露给事件的 rawJson 快照。
     * @param block 真正执行 Gson 解析的代码块。
     * @return block 的执行结果。
     */
    fun <T> withRawJson(rawJson: Snapshot?, block: () -> T): T {
        val previous = current.get()
        current.set(rawJson)
        return try {
            block()
        } finally {
            // 解析可能嵌套调用，退出时必须恢复旧值，不能让上一层或下一次解析读到错误 rawJson。
            if (previous == null) {
                current.remove()
            } else {
                current.set(previous)
            }
        }
    }

    /**
     * 按 UTF-8 字节数截断，并保证不会切断多字节字符或 surrogate pair。
     */
    private fun String.truncateUtf8(maxBytes: Int): Snapshot {
        if (isEmpty()) return Snapshot(value = this, truncated = false)
        if (maxBytes <= 0) return Snapshot(value = "", truncated = true)
        var index = 0
        var usedBytes = 0
        while (index < length) {
            val codePoint = Character.codePointAt(this, index)
            val codePointBytes = codePoint.utf8ByteCount()
            if (usedBytes + codePointBytes > maxBytes) break
            usedBytes += codePointBytes
            index += Character.charCount(codePoint)
        }
        return Snapshot(
            value = substring(0, index),
            truncated = index < length
        )
    }

    private fun Int.utf8ByteCount(): Int {
        return when {
            this <= 0x7F -> 1
            this <= 0x7FF -> 2
            this <= 0xFFFF -> 3
            else -> 4
        }
    }
}
