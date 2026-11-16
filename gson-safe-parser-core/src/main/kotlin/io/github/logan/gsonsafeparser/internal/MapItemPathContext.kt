package io.github.logan.gsonsafeparser.internal

import com.google.gson.stream.JsonReader
import io.github.logan.gsonsafeparser.MapItemKeyPolicy
import io.github.logan.gsonsafeparser.ParseExceptionKind
import io.github.logan.gsonsafeparser.SafeParserEvent
import java.security.MessageDigest

/**
 * 记录正在读取的 Map entry，按真实 key 边界处理观察路径。
 *
 * Gson 的 path 不转义 key 中的点和括号，因此不能把 path 再按标点拆成字段名。
 * 保存 nextName 后的位置，才能区分业务 key、普通字段和嵌套 Map。
 */
internal object MapItemPathContext {
    private data class Entry(
        val reader: JsonReader,
        val path: String,
        val key: String
    ) {
        val start: Int = path.length - key.length

        fun contains(candidate: String): Boolean =
            candidate.startsWith(path) &&
                (candidate.length == path.length || candidate[path.length] == '.' || candidate[path.length] == '[')
    }

    private val current = ThreadLocal<List<Entry>?>()

    fun <T> withEntry(reader: JsonReader, key: String, block: () -> T): T {
        val previous = current.get()
        val parents = previous?.takeIf { it.lastOrNull()?.reader === reader }.orEmpty()
        current.set(parents + Entry(reader, reader.path, key))
        return try {
            block()
        } finally {
            restore(previous)
        }
    }

    /** 独立解析和观察者重入不能继承外层 reader 的 key。 */
    fun <T> withoutEntries(block: () -> T): T {
        val previous = current.get()
        current.remove()
        return try {
            block()
        } finally {
            restore(previous)
        }
    }

    fun redactPath(path: String, policy: MapItemKeyPolicy, mapItemKey: String? = null): String {
        if (policy == MapItemKeyPolicy.PlainText) return path
        val entries = current.get().orEmpty().filter { it.contains(path) }
        if (entries.isNotEmpty()) {
            // 从后往前替换，保留前面记录的原始字符下标；空 key 也必须占一个隐藏位置。
            return entries.asReversed().fold(path) { result, entry ->
                result.replaceRange(entry.start, entry.path.length, replacement(entry.key, policy))
            }
        }
        // 低层事件注入可能只带明确的 mapItemKey；仅替换完整尾部属性，不能误伤同后缀字段。
        if (mapItemKey != null && path.endsWith(".$mapItemKey")) {
            return path.dropLast(mapItemKey.length) + replacement(mapItemKey, policy)
        }
        return path
    }

    fun redact(event: SafeParserEvent, policy: MapItemKeyPolicy): SafeParserEvent {
        if (policy == MapItemKeyPolicy.PlainText) return event
        return when (event) {
            is SafeParserEvent.TypeMismatch -> event.copy(
                detail = event.detail.copy(
                    path = redactPath(event.detail.path, policy, event.detail.mapItemKey),
                    reason = redactReason(event.detail.reason, policy),
                    fieldName = redactFieldName(event.detail.path, event.detail.fieldName, event.detail.kind, policy),
                    mapItemKey = event.detail.mapItemKey?.let {
                        if (policy == MapItemKeyPolicy.Omit) null else replacement(it, policy)
                    }
                )
            )
            is SafeParserEvent.ShapeCoercion -> event.copy(
                detail = event.detail.copy(
                    path = redactPath(event.detail.path, policy),
                    reason = redactReason(event.detail.reason, policy),
                    fieldName = redactFieldName(event.detail.path, event.detail.fieldName, ParseExceptionKind.OBJECT, policy)
                )
            )
            else -> event
        }
    }

    private fun redactReason(reason: String, policy: MapItemKeyPolicy): String {
        val entries = current.get().orEmpty().sortedByDescending { it.path.length }
        if (entries.isEmpty()) return reason
        // 只匹配当前 entry 的完整 JSON 路径，不能把短 key 或空 key 当作任意子串替换。
        // 每个原始位置只处理一次，避免空父 key 的路径前缀再次匹配已经替换的输出。
        return buildString(reason.length) {
            var index = 0
            while (index < reason.length) {
                val entry = if (reason[index] == '$' &&
                    (index == 0 || !reason[index - 1].isLetterOrDigit())
                ) {
                    entries.firstOrNull { candidate ->
                        val end = index + candidate.path.length
                        reason.startsWith(candidate.path, index) &&
                            (end == reason.length || reason[end].let {
                                it == '.' || it == '[' || it.isWhitespace() || it in "\"',;:)}"
                            })
                    }
                } else {
                    null
                }
                if (entry == null) {
                    append(reason[index++])
                } else {
                    append(redactPath(entry.path, policy))
                    index += entry.path.length
                }
            }
        }
    }

    private fun redactFieldName(
        path: String,
        fieldName: String?,
        kind: ParseExceptionKind,
        policy: MapItemKeyPolicy
    ): String? {
        if (fieldName == null) return null
        val entries = current.get().orEmpty().filter { it.contains(path) }
        val entry = if (kind == ParseExceptionKind.OBJECT) {
            // value 或其数组元素的名称来自 key；真正的 value 子字段仍保留字段名。
            entries.lastOrNull {
                val suffix = path.substring(it.path.length)
                suffix.isEmpty() || (suffix.startsWith('[') && '.' !in suffix)
            }
        } else {
            // 集合/Map 错配用顶层名称归因。根 Map 的顶层名称就是业务 key。
            entries.firstOrNull { it.start == 2 }
        } ?: return fieldName
        return if (policy == MapItemKeyPolicy.Omit) null else replacement(entry.key, policy)
    }

    private fun replacement(key: String, policy: MapItemKeyPolicy): String = when (policy) {
        MapItemKeyPolicy.PlainText -> key
        MapItemKeyPolicy.Omit -> "[map-key]"
        MapItemKeyPolicy.Hash -> "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun restore(entries: List<Entry>?) {
        if (entries == null) current.remove() else current.set(entries)
    }
}
