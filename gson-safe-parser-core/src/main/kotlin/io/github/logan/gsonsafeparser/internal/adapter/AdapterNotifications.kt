package io.github.logan.gsonsafeparser.internal.adapter

import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.logan.gsonsafeparser.ParseExceptionKind
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.TypeMismatchEvent
import io.github.logan.gsonsafeparser.MapItemKeyPolicy
import io.github.logan.gsonsafeparser.dispatchTypeMismatch
import io.github.logan.gsonsafeparser.internal.RawJsonContext
import io.github.logan.gsonsafeparser.internal.runRecovering
import io.github.logan.gsonsafeparser.internal.toSafeTypeName
import java.security.MessageDigest

/**
 * Adapter 内部统一发错配事件的入口。
 *
 * 不同 Adapter 会在 `Object` 字段、集合元素、Map item 等位置发现错形。
 * 统一从这里发事件，可以保证 path、fieldName、rawJson 截断标记的语义一致。
 *
 * @param config 当前解析配置，里面包含回调和 rawJson 开关。
 * @param type 期望读取的目标类型。
 * @param reader Gson 当前 reader，用来取 path。
 * @param token 实际遇到的 JSON token。
 * @param reason 错配原因。
 * @param kind 错配位置类型。
 * @param fieldName 已知字段名；传 null 时会从 path 推断。
 * @param mapItemKey Map item 失败时对应的 key。
 * @param path 已知触发错配的读取前路径；传 null 时使用 reader 当前路径。
 */
internal fun notify(
    config: SafeParserConfig,
    type: TypeToken<*>,
    reader: JsonReader,
    token: JsonToken,
    reason: String = "Unexpected JSON token",
    kind: ParseExceptionKind = ParseExceptionKind.OBJECT,
    fieldName: String? = null,
    mapItemKey: String? = null,
    path: String? = null
) {
    val rawJson = RawJsonContext.current()
    val eventPath = config.eventPath(path ?: reader.path, mapItemKey)
    // rawJson 从 ThreadLocal 取，只有通过 GsonSafeParser.fromJson/Retrofit 且配置开启时才有值。
    config.dispatchTypeMismatch(
        TypeMismatchEvent(
            expectedType = type.type.toSafeTypeName(),
            actualToken = token,
            path = eventPath,
            reason = reason,
            kind = kind,
            fieldName = fieldName ?: inferredFieldName(kind, eventPath),
            mapItemKey = config.eventMapItemKey(mapItemKey),
            rawJson = rawJson?.value,
            rawJsonTruncated = rawJson?.truncated == true
        )
    )
}

internal fun SafeParserConfig.eventPath(path: String, mapItemKey: String? = null): String {
    val key = mapItemKey?.takeIf { it.isNotBlank() }
    if (key != null) {
        val replacement = when (mapItemKeyPolicy) {
            MapItemKeyPolicy.PlainText -> key
            MapItemKeyPolicy.Hash -> "sha256:${key.sha256Hex()}"
            MapItemKeyPolicy.Omit -> if (key.isSensitiveEventPathValue()) MAP_ITEM_KEY_PLACEHOLDER else key
        }
        return path.replaceTrailingMapItemKey(key, replacement)
    }
    return path.redactSensitivePathSegments(mapItemKeyPolicy)
}

private fun SafeParserConfig.eventMapItemKey(key: String?): String? {
    if (key == null) return null
    return when (mapItemKeyPolicy) {
        MapItemKeyPolicy.PlainText -> key
        MapItemKeyPolicy.Hash -> "sha256:${key.sha256Hex()}"
        MapItemKeyPolicy.Omit -> null
    }
}

private fun String.replaceTrailingMapItemKey(originalKey: String, replacementKey: String): String {
    if (!endsWith(originalKey)) return this
    return dropLast(originalKey.length) + replacementKey
}

private fun String.redactSensitivePathSegments(policy: MapItemKeyPolicy): String {
    return sensitiveEventPathSegmentPatterns.fold(this) { sanitized, pattern ->
        pattern.replace(sanitized) { match ->
            match.value.redactedPathMatch(policy)
        }
    }
}

private fun String.redactedPathMatch(policy: MapItemKeyPolicy): String {
    val atIndex = indexOf('@')
    val pathPrefixEnd = if (atIndex > 0) {
        indexOf('.').takeIf { dotIndex -> dotIndex in 1 until atIndex }
    } else {
        null
    }
    if (pathPrefixEnd != null) {
        val prefix = substring(0, pathPrefixEnd + 1)
        val sensitivePart = substring(pathPrefixEnd + 1)
        return prefix + sensitivePart.redactedSensitiveValue(policy)
    }
    return redactedSensitiveValue(policy)
}

private fun String.isSensitiveEventPathValue(): Boolean {
    return sensitiveEventPathSegmentPatterns.any { pattern -> pattern.containsMatchIn(this) }
}

private fun String.redactedSensitiveValue(policy: MapItemKeyPolicy): String {
    return when (policy) {
        MapItemKeyPolicy.PlainText -> this
        MapItemKeyPolicy.Hash -> "sha256:${sha256Hex()}"
        MapItemKeyPolicy.Omit -> MAP_ITEM_KEY_PLACEHOLDER
    }
}

private fun String.sha256Hex(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private const val MAP_ITEM_KEY_PLACEHOLDER = "[map-key]"

private val sensitiveEventPathSegmentPatterns = listOf(
    Regex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", RegexOption.IGNORE_CASE),
    Regex("(?i)\\b(?:token|password|passwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token|session[_-]?id|sid)\\b"),
    Regex("\\b\\+?\\d[\\d\\s().-]{7,}\\d\\b")
)

private fun inferredFieldName(kind: ParseExceptionKind, path: String): String? {
    // 普通对象错配取叶子字段；集合和 Map item 错配取顶层字段，方便知道是哪个列表或 Map 出问题。
    return if (kind == ParseExceptionKind.OBJECT) {
        leafFieldNameFromPath(path)
    } else {
        fieldNameFromPath(path)
    }
}

/**
 * 安全读取当前 token。
 *
 * reader 处于半消费状态时 `peek()` 也可能失败，这里返回 END_DOCUMENT 只是为了记录事件，不再扩大异常。
 *
 * @return 当前 token；读取失败时返回 END_DOCUMENT。
 */
internal fun JsonReader.peekSafe(): JsonToken {
    return runRecovering { peek() }.getOrDefault(JsonToken.END_DOCUMENT)
}

/**
 * 尽量把 reader 从半消费状态恢复到当前值之后。
 *
 * List item、Map item 读取失败时，Gson 的 delegate 可能已经读了一部分字段。
 * 这个函数只做有限次数尝试，避免因为错误 JSON 或 Adapter bug 陷入死循环。
 *
 * @param pathBeforeRead 读取某个 item/value 前的 path。传 null 时只跳过当前未读值。
 */
internal fun JsonReader.skipUnreadValueIfPossible(pathBeforeRead: String? = null) {
    if (pathBeforeRead != null) {
        // guard 是保护计数器。即使 reader.path 一直不变，也最多尝试 256 次，避免死循环。
        var guard = 0
        while (path.startsWith(pathBeforeRead) && guard < 256) {
            guard++
            val token = peekSafe()
            if (path == pathBeforeRead &&
                (token == JsonToken.NAME || token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY || token == JsonToken.END_DOCUMENT)
            ) {
                return
            }
            when (token) {
                JsonToken.NAME -> {
                    runRecovering { nextName() }.getOrNull() ?: return
                }
                JsonToken.END_OBJECT -> {
                    runRecovering { endObject() }.getOrNull() ?: return
                }
                JsonToken.END_ARRAY -> {
                    runRecovering { endArray() }.getOrNull() ?: return
                }
                JsonToken.END_DOCUMENT -> return
                else -> runRecovering { skipValue() }.getOrNull() ?: return
            }
        }
        return
    }

    val token = peekSafe()
    if (token == JsonToken.NAME || token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY || token == JsonToken.END_DOCUMENT) return
    runRecovering { skipValue() }
}

/**
 * 从 Gson path 中取顶层字段名。
 *
 * 例如 `$.users[0].id` 会得到 `users`，用于集合元素和 Map item 归因。
 *
 * @param path Gson Reader 的路径。
 * @return 顶层字段名；不是字段路径时返回 null。
 */
internal fun fieldNameFromPath(path: String): String? {
    if (!path.startsWith("$.")) return null
    return path
        .removePrefix("$.")
        .takeWhile { it != '.' && it != '[' }
        .takeIf { it.isNotEmpty() }
}

/**
 * 从 Gson path 中取叶子字段名。
 *
 * 例如 `$.data.profile` 会得到 `profile`，用于普通 `Object` 字段错配归因。
 *
 * @param path Gson Reader 的路径。
 * @return 最后一级字段名；不是字段路径时返回 null。
 */
internal fun leafFieldNameFromPath(path: String): String? {
    if (!path.startsWith("$.")) return null
    return path
        .removePrefix("$.")
        .split('.')
        .lastOrNull()
        ?.takeWhile { it != '[' }
        ?.takeIf { it.isNotEmpty() }
}
