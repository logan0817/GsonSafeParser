package io.github.logan.gsonsafeparser

import com.google.gson.reflect.TypeToken

/**
 * Kotlin 便捷解析结果。
 *
 * `value` 是 Gson 实际解析出的业务对象，`events` 是本次解析期间产生的 SafeParser 事件快照。
 *
 * @property value 解析得到的业务对象。可恢复错形会按配置返回兜底值或 null；不可恢复 Gson 异常会继续抛出。
 * @property events 本次解析捕获到的事件列表。列表是快照，外部修改不会影响解析过程。
 */
data class SafeParseResult<T>(
    val value: T?,
    val events: List<SafeParserEvent>
)

/**
 * reified 版本的安全解析入口。
 *
 * 调用方不用手写 TypeToken，泛型类型会在这里转换成 Gson 能识别的 Type。
 *
 * @param json 原始 JSON 字符串。
 * @param config SafeParser 配置。
 * @return 解析得到的对象。可恢复错形会按配置返回兜底值或 null；不可恢复 Gson 异常会继续抛出。
 */
inline fun <reified T> GsonSafeParser.fromJsonSafe(
    json: String,
    config: SafeParserConfig = SafeParserConfig()
): T? {
    return fromJson(json, object : TypeToken<T>() {}.type, config)
}

/**
 * reified 版本的可复用 Parser 解析入口。
 *
 * 这个入口适合高频场景：先创建一次 `GsonSafeParser.parser(...)`，后续反复调用这个方法，
 * 避免每次都新建 Gson。
 *
 * @param json 原始 JSON 字符串。
 * @return 解析得到的对象。可恢复错形会按配置返回兜底值或 null；不可恢复 Gson 异常会继续抛出。
 */
inline fun <reified T> GsonSafeParser.Parser.fromJsonSafe(
    json: String
): T? {
    return fromJson(json, object : TypeToken<T>() {}.type)
}

/**
 * reified 版本的可复用 Parser 契约结果入口。
 *
 * Parser 的 `parseSafe` 通过 ThreadLocal 事件桥收集事件，因为 Parser 可能包装一份已经创建好的 Gson，
 * 这份 Gson 内部的 Safe Adapter 已经持有旧配置，不能像顶层入口那样临时替换 `onEvent`。
 *
 * @param json 原始 JSON 字符串。
 * @return 同时包含业务对象和事件快照的结果。不可恢复 Gson 异常会继续抛出。
 */
inline fun <reified T> GsonSafeParser.Parser.parseSafe(
    json: String
): SafeParseResult<T> {
    return parseSafe(json, object : TypeToken<T>() {}.type)
}

/**
 * 解析并收集本次 SafeParser 事件。
 *
 * 这个方法会先把事件放入本地列表，再继续调用用户配置的 `onEvent`。
 * 即使用户回调失败，事件快照也能保留下来，方便生成契约报告。
 *
 * 顶层入口每次都会按传入配置创建本次解析用的 Gson，所以这里继续使用 `config.copy(onEvent = ...)` 收集事件。
 * 可复用 Parser 的 `parseSafe` 则使用 ThreadLocal 事件桥收集事件。两套机制服务的入口不同，后续如果要合并，
 * 必须先处理事件去重，否则同一个错形事件可能同时从配置包装和事件桥进入结果快照。
 *
 * @param json 原始 JSON 字符串。
 * @param config SafeParser 配置。
 * @return 同时包含业务对象和事件快照的结果。不可恢复 Gson 异常会继续抛出。
 */
inline fun <reified T> GsonSafeParser.parseSafe(
    json: String,
    config: SafeParserConfig = SafeParserConfig()
): SafeParseResult<T> {
    val events = mutableListOf<SafeParserEvent>()
    val observedConfig = config.copy(
        onEvent = { event ->
            // 先保存再通知外部，避免外部观察者异常导致本次事件丢失。
            events += event
            config.onEvent(event)
        }
    )
    val value = fromJson<T>(json, object : TypeToken<T>() {}.type, observedConfig)
    return SafeParseResult(value, events.toList())
}
