package io.github.logan.gsonsafeparser.retrofit

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.logan.gsonsafeparser.EmptyResponsePolicy
import io.github.logan.gsonsafeparser.EmptyResponseEvent
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.GsonSafeParserLowLevelApi
import io.github.logan.gsonsafeparser.RawJsonCaptureSkippedEvent
import io.github.logan.gsonsafeparser.RawJsonCaptureSkipReason
import io.github.logan.gsonsafeparser.SafeParserEvent
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.dispatchEvent
import io.github.logan.gsonsafeparser.enableSafeParser
import io.github.logan.gsonsafeparser.internal.TransportIoContext
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Type
import java.net.ProtocolException
import java.net.SocketException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * Retrofit 接入入口。
 *
 * 这个工厂只在响应体进入 Gson 前后补充空响应策略、rawJson 捕获和事件派发。
 * 真正的序列化、反序列化仍交给 GsonConverterFactory，避免重新实现 Retrofit converter 协议。
 */
object GsonSafeConverterFactory {
    /**
     * 使用 GsonSafeParser 默认创建的 Gson。
     *
     * @param config SafeParser 配置，同时影响 core 解析和 Retrofit 空响应处理。
     * @return Retrofit 可直接注册的 Converter.Factory。
     */
    fun create(config: SafeParserConfig = SafeParserConfig()): Converter.Factory {
        return GsonSafeRetrofitConverterFactory(GsonSafeParser.create(config), config)
    }

    /**
     * 使用调用方的 GsonBuilder 创建字段级 Safe Gson。
     *
     * 这个入口会在 `.create()` 前注册 Safe Adapter，适合项目已有统一 GsonBuilder 配置，
     * 又希望 Retrofit 响应字段级错形由 SafeParser 隔离的场景。传入 Builder 会被追加 SafeParser 配置；
     * 如果不希望修改原 Builder，请先传入调用方自己复制出的 Builder。
     *
     * @param builder 调用方准备创建 Gson 的 Builder。
     * @param config SafeParser 配置，同时影响 core 解析和 Retrofit 空响应处理。
     * @return Retrofit 可直接注册的 Converter.Factory。
     */
    fun create(
        builder: GsonBuilder,
        config: SafeParserConfig = SafeParserConfig()
    ): Converter.Factory {
        return GsonSafeRetrofitConverterFactory(
            gson = builder.enableSafeParser(config).create(),
            config = config
        )
    }

    /**
     * 使用调用方已经配置好的 Gson。
     *
     * 这个入口适合项目里已经统一维护 GsonBuilder 的场景；空响应策略会使用默认配置。
     * 如果需要字段级安全解析，传入的 Gson 应该已经通过 `enableSafeParser(config)` 注册 Safe Adapter。
     *
     * @param gson 调用方已经创建好的 Gson。
     * @return Retrofit 可直接注册的 Converter.Factory。
     */
    fun create(gson: Gson): Converter.Factory {
        return create(gson, SafeParserConfig())
    }

    /**
     * 同时使用调用方 Gson 和 SafeParserConfig。
     *
     * 这个入口适合项目里已经统一维护 Gson，又希望 Retrofit 层继续使用
     * 空响应策略、rawJson 捕获和事件观察配置的场景。
     * 如果需要字段级安全解析，传入的 Gson 应该已经通过 `enableSafeParser(config)` 注册 Safe Adapter。
     *
     * @param gson 调用方已经创建好的 Gson。
     * @param config Retrofit 层额外需要的 SafeParser 配置。
     * @return Retrofit 可直接注册的 Converter.Factory。
     */
    fun create(gson: Gson, config: SafeParserConfig): Converter.Factory {
        return GsonSafeRetrofitConverterFactory(gson, config)
    }
}

/**
 * Retrofit converter 的实际实现。
 *
 * 它把大部分工作委托给 GsonConverterFactory，只在三个地方介入：
 * 空响应默认值、调试期 rawJson 捕获、rawJson 超限事件通知。
 *
 * @property gson 实际执行 JSON 转换的 Gson。
 * @property config SafeParser 配置，决定空响应策略和 rawJson 观测策略。
 */
private class GsonSafeRetrofitConverterFactory(
    private val gson: Gson,
    private val config: SafeParserConfig
) : Converter.Factory() {
    // delegate 是 Retrofit 官方 Gson converter。除空响应和 rawJson 观测外，其他工作都交给它。
    private val delegate = GsonConverterFactory.create(gson)

    /**
     * 创建响应体 converter。
     *
     * @param type 接口方法声明的响应类型。
     * @param annotations Retrofit 传入的响应注解。
     * @param retrofit 当前 Retrofit 实例。
     * @return 包装后的响应 converter。
     */
    @OptIn(GsonSafeParserLowLevelApi::class)
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit
    ): Converter<ResponseBody, *>? {
        unitOrVoidResponseBodyConverter(type)?.let { converter ->
            return Converter<ResponseBody, Any?> { originalBody ->
                TransportIoContext.withTransportIoMarkers {
                    converter.convert(originalBody.markTransportIoFailures())
                }
            }
        }
        // converter 是原始 Gson converter；Safe 层处理不了的场景都要回到它。
        val converter = delegate.responseBodyConverter(type, annotations, retrofit)
            ?: return null
        return Converter<ResponseBody, Any?> { originalBody ->
            TransportIoContext.withTransportIoMarkers {
                val body = originalBody.markTransportIoFailures()
                if (body.isEmpty()) {
                    emptyResponseValue(type, body, converter)
                } else if (config.captureRawJsonInCallbacks) {
                    if (body.canCaptureRawJson()) {
                        // Retrofit 的 ResponseBody 只能读取一次，所以需要先读成字符串，再交给 GsonSafeParser 带上下文解析。
                        val rawJson = body.string()
                        GsonSafeParser.fromJson<Any?>(gson, rawJson, type, config)
                    } else {
                        // 响应体太大或长度未知时不强行读入内存，只发观测事件，然后回到原 converter。
                        val contentLength = body.contentLength()
                        config.dispatchEvent(
                            SafeParserEvent.RawJsonCaptureSkipped(
                                RawJsonCaptureSkippedEvent(
                                    typeName = type.toSafeTypeName(),
                                    contentLength = contentLength,
                                    maxBytes = config.maxRawJsonCaptureBytes,
                                    reason = rawJsonCaptureSkippedReason(contentLength).message
                                )
                            )
                        )
                        converter.convert(body)
                    }
                } else {
                    converter.convert(body)
                }
            }
        }
    }

    /**
     * Unit 和 Void 的响应体 converter。
     *
     * Gson 本身不会给 `void` 创建 Adapter，如果这里先向 GsonConverterFactory 要 delegate，
     * 空响应还没来得及进入兜底逻辑就会抛异常。Unit/Void 本身不需要 JSON 字段解析，
     * 所以这里直接做最小转换：空响应按 emptyResponsePolicy 派发事件并返回 Unit/null，
     * 非空响应也关闭 body 后返回对应空值，保持和 Retrofit 这类“无返回值接口”的使用习惯一致。
     *
     * @param type 接口方法声明的响应类型。
     * @return Unit/Void 专用 converter；其他类型返回 null，继续交给 Gson converter。
     */
    private fun unitOrVoidResponseBodyConverter(type: Type): Converter<ResponseBody, Any?>? {
        if (type != Unit::class.java && type != Void.TYPE && type != Void::class.java) {
            return null
        }
        return Converter<ResponseBody, Any?> { body ->
            if (body.isEmpty()) {
                emptyResponseValue(type, body, converter = null)
            } else {
                body.close()
                emptyValueForUnitOrVoid(type)
            }
        }
    }

    /**
     * 请求体 converter 不做额外处理。
     *
     * @param type 请求体类型。
     * @param parameterAnnotations 参数注解。
     * @param methodAnnotations 方法注解。
     * @param retrofit 当前 Retrofit 实例。
     * @return 原始 Gson 请求体 converter。
     */
    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<Annotation>,
        methodAnnotations: Array<Annotation>,
        retrofit: Retrofit
    ): Converter<*, RequestBody>? {
        return delegate.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)
    }

    /**
     * 字符串 converter 不做额外处理。
     *
     * @param type 字符串转换目标类型。
     * @param annotations Retrofit 注解。
     * @param retrofit 当前 Retrofit 实例。
     * @return 原始 Gson 字符串 converter。
     */
    override fun stringConverter(
        type: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit
    ): Converter<*, String>? {
        return delegate.stringConverter(type, annotations, retrofit)
    }

    /**
     * 处理空响应。
     *
     * @param type 接口方法声明的响应类型。
     * @param body Retrofit 响应体。
     * @param converter 原始 Gson converter，用于 DelegateToGson 策略。
     * @return 按配置得到的空响应结果。
     */
    @OptIn(GsonSafeParserLowLevelApi::class)
    private fun emptyResponseValue(
        type: Type,
        body: ResponseBody,
        converter: Converter<ResponseBody, *>?
    ): Any? {
        // 空响应也走统一事件流，方便接口层统计哪些接口没有返回 body。
        config.dispatchEvent(
            SafeParserEvent.EmptyResponse(
                EmptyResponseEvent(
                    typeName = type.toSafeTypeName(),
                    policy = config.emptyResponsePolicy
                )
            )
        )
        return when (config.emptyResponsePolicy) {
            EmptyResponsePolicy.DefaultValue -> {
                body.close()
                when (type) {
                    Unit::class.java -> Unit
                    Void.TYPE, Void::class.java -> null
                    // 用空对象触发 Gson 构造默认值，保持 Retrofit 调用方拿到的是业务默认模型。
                    else -> gson.fromJson<Any?>("{}", type)
                }
            }
            EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly -> {
                body.close()
                emptyValueForUnitOrVoid(type)
            }
            EmptyResponsePolicy.Null -> {
                body.close()
                null
            }
            EmptyResponsePolicy.DelegateToGson -> {
                // Unit/Void 没有 Gson delegate。这里返回 Retrofit 语义下的空值，避免为了“委托”制造新崩溃。
                converter?.convert(body) ?: run {
                    body.close()
                    emptyValueForUnitOrVoid(type)
                }
            }
        }
    }

    /**
     * Unit/Void 对应的空值。
     *
     * @param type Retrofit 方法声明的响应类型。
     * @return Unit 返回 `Unit`，Void 返回 `null`。
     */
    private fun emptyValueForUnitOrVoid(type: Type): Any? {
        return if (type == Unit::class.java) Unit else null
    }

    /**
     * 判断响应体是否为空。
     *
     * @return 没有任何字节可读时返回 true。
     */
    private fun ResponseBody.isEmpty(): Boolean {
        // request(1) 只探测是否至少有一个字节，不会把完整 body 读进内存。
        if (contentLength() == 0L) return true
        return runRecovering { !source().request(1) }.getOrDefault(false)
    }

    /**
     * 判断响应体是否允许被完整读入内存用于 rawJson 回调。
     *
     * @return 响应体已知长度或未知长度探测结果不超过配置上限时返回 true。
     */
    private fun ResponseBody.canCaptureRawJson(): Boolean {
        val length = contentLength()
        if (length >= 0) {
            return length <= config.maxRawJsonCaptureBytes
        }
        return isUnknownLengthWithinRawJsonCaptureLimit()
    }

    /**
     * 未知长度响应先用 peek 探测上限，不消费原始 body。
     *
     * gzip 透明解压后的 OkHttp body 常见 `contentLength=-1`。这里最多探测 `max + 1` 字节：
     * 没到上限就安全读取完整 body，超过上限就保留原始 body 交回 Retrofit delegate。
     */
    private fun ResponseBody.isUnknownLengthWithinRawJsonCaptureLimit(): Boolean {
        val maxBytes = config.maxRawJsonCaptureBytes
        if (maxBytes < 0) return false
        return runRecovering {
            !source().peek().request(maxBytes.toLong() + 1L)
        }.getOrDefault(false)
    }

    /**
     * rawJson 没有捕获时给出明确原因，方便 demo 和日志区分“大响应”和“未知长度响应”。
     *
     * @param contentLength OkHttp 暴露的响应体长度，未知时通常为 -1。
     * @return 面向日志和契约报告的英文原因。
     */
    private fun rawJsonCaptureSkippedReason(contentLength: Long): RawJsonCaptureSkipReason {
        return if (contentLength < 0) {
            RawJsonCaptureSkipReason.UnknownLengthExceedsLimit
        } else {
            RawJsonCaptureSkipReason.ContentLengthExceedsLimit
        }
    }
}

@OptIn(GsonSafeParserLowLevelApi::class)
private fun ResponseBody.markTransportIoFailures(): ResponseBody {
    val upstream = this
    return object : ResponseBody() {
        private val markedSource: BufferedSource by lazy {
            upstream.source().markTransportIoFailures()
        }

        override fun contentLength(): Long = upstream.contentLength()

        override fun contentType() = upstream.contentType()

        override fun source(): BufferedSource = markedSource
    }
}

@OptIn(GsonSafeParserLowLevelApi::class)
private fun BufferedSource.markTransportIoFailures(): BufferedSource {
    val upstream = this
    return object : ForwardingSource(upstream) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            return markTransportIoFailure { super.read(sink, byteCount) }
        }

        override fun close() {
            markTransportIoFailure { super.close() }
        }
    }.buffer()
}

private fun InputStream.markTransportIoFailures(): InputStream {
    val upstream = this
    return object : InputStream() {
        override fun read(): Int {
            return markTransportIoFailure { upstream.read() }
        }

        override fun read(buffer: ByteArray, byteOffset: Int, byteCount: Int): Int {
            return markTransportIoFailure { upstream.read(buffer, byteOffset, byteCount) }
        }

        override fun available(): Int {
            return markTransportIoFailure { upstream.available() }
        }

        override fun close() {
            upstream.close()
        }
    }
}

@OptIn(GsonSafeParserLowLevelApi::class)
private fun <T> markTransportIoFailure(block: () -> T): T {
    return try {
        block()
    } catch (error: IOException) {
        throw TransportIoContext.mark(error)
    }
}

/**
 * 把 Retrofit 的响应 Type 转成兼容低 Android 版本的类型名。
 *
 * 这里不能直接访问 `Type.typeName`，否则 Android minSdk 低于 28 的项目会看到 API 级别警告。
 */
private fun Type.toSafeTypeName(): String {
    return if (this is Class<*>) {
        name
    } else {
        toString()
    }
}

private inline fun <T> runRecovering(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (error: Throwable) {
        // Keep this module-local boundary aligned with core/internal/RecoverableErrors.kt.
        error.throwIfFatal()
        Result.failure(error)
    }
}

private fun Throwable.throwIfFatal() {
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
    if (TransportIoContext.isMarked(this)) return true
    if (
        this is EOFException ||
        this is InterruptedIOException ||
        this is ProtocolException ||
        this is SocketException ||
        this is SSLException
    ) {
        return true
    }
    if (javaClass.name.endsWith(".StreamResetException") || javaClass.simpleName == "StreamResetException") return true

    val normalizedMessage = message?.lowercase(Locale.US)?.trim() ?: return false
    return normalizedMessage == "canceled" ||
        normalizedMessage == "cancelled" ||
        normalizedMessage.contains("stream was reset") ||
        normalizedMessage.contains("stream reset") ||
        normalizedMessage.contains("connection reset") ||
        normalizedMessage.contains("broken pipe")
}
