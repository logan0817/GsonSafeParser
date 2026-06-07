package io.github.logan.gsonsafeparser.demo.cases

import android.content.res.ColorStateList
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import com.google.gson.ReflectionAccessFilter
import com.google.gson.ToNumberPolicy
import com.google.gson.TypeAdapter
import com.google.gson.annotations.Expose
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.Since
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.github.logan.gsonsafeparser.EmptyResponsePolicy
import io.github.logan.gsonsafeparser.EmptyResponseEvent
import io.github.logan.gsonsafeparser.FallbackPolicy
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.ObserverFailureEvent
import io.github.logan.gsonsafeparser.ParseExceptionKind
import io.github.logan.gsonsafeparser.PrimitiveParsingPolicy
import io.github.logan.gsonsafeparser.SafeObserverPolicy
import io.github.logan.gsonsafeparser.SafeParseResult
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.SafeParserEvent
import io.github.logan.gsonsafeparser.SafeParseDelegateToGson
import io.github.logan.gsonsafeparser.SafeParseSkip
import io.github.logan.gsonsafeparser.SafeReadPolicy
import io.github.logan.gsonsafeparser.SafeWritePolicy
import io.github.logan.gsonsafeparser.TypeMismatchEvent
import io.github.logan.gsonsafeparser.contractReport
import io.github.logan.gsonsafeparser.dispatchEvent
import io.github.logan.gsonsafeparser.enableSafeParser
import io.github.logan.gsonsafeparser.fromJsonSafe
import io.github.logan.gsonsafeparser.integrationCheck
import io.github.logan.gsonsafeparser.observerFailureReport
import io.github.logan.gsonsafeparser.parseSafe
import io.github.logan.gsonsafeparser.retrofit.GsonSafeConverterFactory
import io.github.logan.gsonsafeparser.demo.model.*
import io.github.logan.gsonsafeparser.demo.support.*
import okhttp3.RequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import java.lang.reflect.Type
import java.math.BigDecimal
import java.net.URI
import java.net.URL
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.BitSet
import java.util.Date
import java.util.EnumMap
import java.util.EnumSet
import java.util.SortedSet
import java.util.TreeSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentMap

/**
 * 固定 demo 用例分组。
 *
 * 这些函数保持为 package 内可见，统一由 DemoCaseRegistry 聚合。
 */
/**
 * `GsonSafeConverterFactory.create(config)` 空响应策略。
 */
internal fun retrofitEmptyResponseCase(): DemoCase = DemoCase(
    title = "Retrofit 空响应策略",
    capabilityIds = setOf("retrofit-empty-response"),
    category = "Retrofit",
    entryPoint = "GsonSafeConverterFactory.create(config)",
    description = "不依赖真实网络，用 ResponseBody 模拟 Retrofit 空响应，验证三种空响应策略。",
    defaultJson = "",
    expected = "默认策略返回 null，Null 返回 null，DelegateToGson 抛 EOF。"
) {
    val defaultEvents = mutableListOf<SafeParserEvent>()
    val defaultValue = convertWithRetrofit<EmptyApiResponse>(
        bodyText = "",
        config = SafeParserConfig(onEvent = defaultEvents::add)
    ).getOrThrow()
    val nullValue = convertWithRetrofit<EmptyApiResponse>(
        bodyText = "",
        config = SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.Null)
    ).getOrThrow()
    val delegateError = convertWithRetrofit<EmptyApiResponse>(
        bodyText = "",
        config = SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DelegateToGson)
    ).exceptionOrNull()
    val pass = defaultValue == null &&
        nullValue == null &&
        delegateError?.javaClass?.simpleName == "EOFException" &&
        defaultEvents.any { it is SafeParserEvent.EmptyResponse }
    DemoRunResult(
        pass = pass,
        actual = "Default=${pretty(defaultValue)}\nNull=$nullValue\nDelegateError=${delegateError?.javaClass?.name}",
        expected = "null / null / EOFException",
        events = defaultEvents.describeEvents(),
        contractReport = defaultEvents.toSafeResultReport()
    )
}

/**
 * Retrofit Unit 和 Void 空响应。
 */
internal fun retrofitUnitVoidEmptyResponseCase(): DemoCase = DemoCase(
    title = "Retrofit Unit/Void 空响应",
    category = "Retrofit",
    entryPoint = "GsonSafeConverterFactory DefaultValue empty body for Unit/Void",
    description = "接口声明 Unit 或 Void 时，空响应不能尝试构造业务对象，应该分别返回 Unit 和 null。",
    defaultJson = "",
    expected = "Unit 返回 kotlin.Unit，Void.TYPE 返回 null；Void.TYPE 会进入 SafeFactory 并产生 EmptyResponse 事件。"
) {
    val events = mutableListOf<SafeParserEvent>()
    val config = SafeParserConfig(onEvent = events::add)
    val unitValue = convertWithRetrofit("", Unit::class.java, config).getOrThrow()
    val voidValue = convertWithRetrofit("", Void.TYPE, config).getOrThrow()
    val emptyEvents = events.filterIsInstance<SafeParserEvent.EmptyResponse>()
    val pass = unitValue == Unit &&
        voidValue == null &&
        emptyEvents.isNotEmpty()
    DemoRunResult(
        pass = pass,
        actual = "Unit=$unitValue\nVoid=$voidValue\nemptyEvents=${emptyEvents.size}",
        expected = "Unit / null / 至少一个 EmptyResponse",
        events = events.describeEvents(),
        contractReport = events.toSafeResultReport()
    )
}

/**
 * Retrofit rawJson 捕获和跳过事件。
 */
internal fun retrofitRawJsonCase(): DemoCase = DemoCase(
    title = "Retrofit rawJson 捕获和跳过",
    capabilityIds = setOf("retrofit-raw-json-capture"),
    category = "Retrofit",
    entryPoint = "GsonSafeConverterFactory.create(debugConfig)",
    description = "响应体较小时捕获 rawJson，超过上限时发 RawJsonCaptureSkipped 并交回 converter。",
    defaultJson = """{"data":[]}""",
    expected = "小响应 TypeMismatch 带 rawJson；超限响应产生 RawJsonCaptureSkipped。"
) { json ->
    val rawEvents = mutableListOf<SafeParserEvent>()
    val rawConfig = SafeParserConfig.debug(observerPolicy = SafeObserverPolicy(onEvent = rawEvents::add))
    val rawValue = convertWithRetrofit<MismatchApiResponse>(json, rawConfig).getOrThrow()

    val skippedEvents = mutableListOf<SafeParserEvent>()
    val skippedConfig = SafeParserConfig.debug(
        observerPolicy = SafeObserverPolicy(onEvent = skippedEvents::add),
        maxRawJsonCaptureBytes = 4
    )
    val skippedValue = convertWithRetrofit<MismatchApiResponse>(json, skippedConfig).getOrThrow()

    val hasRawJson = rawEvents.firstNotNullOfOrNull { it.typeMismatchDetail()?.rawJson } == json
    val hasSkipped = skippedEvents.any { it is SafeParserEvent.RawJsonCaptureSkipped }
    val pass = rawValue == MismatchApiResponse() && skippedValue == MismatchApiResponse() && hasRawJson && hasSkipped
    DemoRunResult(
        pass = pass,
        actual = "rawValue=${pretty(rawValue)}\nskippedValue=${pretty(skippedValue)}",
        expected = "rawJson 捕获成功，超限时发跳过事件且继续解析",
        events = (rawEvents + skippedEvents).describeEvents(),
        contractReport = (rawEvents + skippedEvents).toSafeResultReport()
    )
}

/**
 * Retrofit rawJson 未知长度响应有界捕获。
 */
internal fun retrofitRawJsonUnknownLengthCase(): DemoCase = DemoCase(
    title = "Retrofit rawJson 未知长度有界捕获",
    category = "Retrofit",
    entryPoint = "GsonSafeConverterFactory.create(debugConfig)",
    description = "真实网络里可能遇到 chunked 或 gzip 这类未知长度响应；SafeParser 会先按上限探测，小响应仍能捕获 rawJson。",
    defaultJson = """{"data":[]}""",
    expected = "未知长度小响应的 TypeMismatch 带 rawJson，不产生 RawJsonCaptureSkipped。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val config = SafeParserConfig.debug(
        observerPolicy = SafeObserverPolicy(onEvent = events::add),
        maxRawJsonCaptureBytes = 64
    )
    val retrofit = Retrofit.Builder()
        .baseUrl("https://example.com/")
        .addConverterFactory(GsonSafeConverterFactory.create(config))
        .build()
    val converter = retrofit.nextResponseBodyConverter<MismatchApiResponse?>(
        null,
        typeOf<MismatchApiResponse>(),
        emptyArray()
    )
    val value = converter.convert(json.toUnknownLengthResponseBody())
    val rawJson = events.firstNotNullOfOrNull { it.typeMismatchDetail()?.rawJson }
    val skippedEvent = events.filterIsInstance<SafeParserEvent.RawJsonCaptureSkipped>().firstOrNull()
    val pass = value == MismatchApiResponse() && rawJson == json && skippedEvent == null
    DemoRunResult(
        pass = pass,
        actual = "value=${pretty(value)}\nrawJson=$rawJson\nskipped=${skippedEvent != null}",
        expected = "未知长度小响应 rawJson 捕获成功，转换继续返回安全默认值",
        events = events.describeEvents(),
        contractReport = events.toSafeResultReport()
    )
}

/**
 * Retrofit 事件观察者失败隔离。
 */
internal fun retrofitObserverFailureIsolationCase(): DemoCase = DemoCase(
    title = "Retrofit 观察者失败隔离",
    category = "Retrofit",
    entryPoint = "GsonSafeConverterFactory + onObserverFailure",
    description = "Retrofit 层派发 EmptyResponse 或 RawJsonCaptureSkipped 事件时，即使日志回调抛异常，也不能打断转换结果。",
    defaultJson = "",
    expected = "空响应按默认策略返回 null，onObserverFailure 收到 onEvent 失败记录。"
) {
    val observerFailures = mutableListOf<ObserverFailureEvent>()
    val value = convertWithRetrofit<EmptyApiResponse>(
        bodyText = "",
        config = SafeParserConfig(
            onEvent = {
                error("demo retrofit observer failed")
            },
            onObserverFailure = observerFailures::add
        )
    ).getOrThrow()
    val report = observerFailures.observerFailureReport()
    val pass = value == null &&
        report.hasFailures &&
        report.failuresByCallback.containsKey("onEvent") &&
        observerFailures.any { failure -> failure.sourceEvent is SafeParserEvent.EmptyResponse }
    DemoRunResult(
        pass = pass,
        actual = "value=${pretty(value)}\nfailures=${observerFailures.map { it.callbackName }}",
        expected = "转换成功，观察者失败被隔离",
        observerReport = report.toMarkdown()
    )
}

/**
 * Retrofit 请求体和字符串 Converter 委托。
 */
internal fun retrofitConverterDelegationCase(): DemoCase = DemoCase(
    title = "Retrofit 请求体与字符串 Converter 委托",
    category = "Retrofit",
    entryPoint = "requestBodyConverter / stringConverter delegate",
    description = "Safe Retrofit 工厂只增强响应体读取，请求体和字符串转换要原样交给 GsonConverterFactory，不新增解析规则。",
    defaultJson = """{"name":"demo"}""",
    expected = "requestBodyConverter 能写出 JSON；stringConverter 与 GsonConverterFactory 一样返回 null。"
) {
    val factory = GsonSafeConverterFactory.create()
    val retrofit = Retrofit.Builder()
        .baseUrl("https://example.com/")
        .addConverterFactory(factory)
        .build()
    @Suppress("UNCHECKED_CAST")
    val requestConverter = factory.requestBodyConverter(
        SimpleRequest::class.java,
        emptyArray(),
        emptyArray(),
        retrofit
    ) as? retrofit2.Converter<SimpleRequest, RequestBody>
    val requestBody = requestConverter?.convert(SimpleRequest("demo"))
    val buffer = Buffer()
    requestBody?.writeTo(buffer)
    val bodyText = buffer.readUtf8()
    val stringConverter = factory.stringConverter(SimpleRequest::class.java, emptyArray(), retrofit)
    val pass = bodyText == """{"name":"demo"}""" && stringConverter == null
    DemoRunResult(
        pass = pass,
        actual = "requestBody=$bodyText\nstringConverter=$stringConverter",
        expected = """requestBody={"name":"demo"}，stringConverter=null"""
    )
}

/**
 * `GsonSafeConverterFactory.create(gson)` 入口。
 */
internal fun retrofitCreateWithGsonCase(): DemoCase = DemoCase(
    title = "Retrofit create(gson)",
    category = "Retrofit",
    entryPoint = "GsonSafeConverterFactory.create(customGson)",
    description = "项目里已有统一 Gson 实例时，可以直接交给 Retrofit 扩展工厂；这条入口只复用传入的 Gson，不会给普通 Gson 自动补注册 Safe Adapter。",
    defaultJson = """{"data":[]}""",
    expected = "已注册 Safe Adapter 的自定义 Gson 能解析 Retrofit 响应，data 保留默认对象。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val gson = GsonSafeParser.create(SafeParserConfig(onEvent = events::add))
    val retrofit = Retrofit.Builder()
        .baseUrl("https://example.com/")
        .addConverterFactory(GsonSafeConverterFactory.create(gson))
        .build()
    val converter = retrofit.nextResponseBodyConverter<Any?>(null, MismatchApiResponse::class.java, emptyArray())
    val value = converter.convert(json.toResponseBody())
    val pass = value == MismatchApiResponse() &&
        events.hasTypeMismatch(path = "$.data")
    demoResult(pass, value, events, expected = "MismatchApiResponse(data=EmptyPayload())，事件流来自传入的 customGson")
}

/**
 * `GsonSafeConverterFactory.create(gson, config)` 入口。
 */
internal fun retrofitCreateWithGsonAndConfigCase(): DemoCase = DemoCase(
    title = "Retrofit create(gson, config)",
    category = "Retrofit",
    entryPoint = "GsonSafeConverterFactory.create(customGson, config)",
    description = "项目里既有统一 Gson，又想保留 Retrofit 层的空响应、rawJson 或事件策略时，使用这个重载入口。",
    defaultJson = "",
    expected = "已有 Gson 继续复用，Retrofit 空响应策略按 config 返回 null。"
) {
    val gson = GsonBuilder()
        .serializeNulls()
        .enableSafeParser()
        .create()
    val factory = GsonSafeConverterFactory.create(
        gson = gson,
        config = SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.Null)
    )
    val retrofit = Retrofit.Builder()
        .baseUrl("https://example.com/")
        .addConverterFactory(factory)
        .build()
    val converter = factory.responseBodyConverter(
        EmptyApiResponse::class.java,
        emptyArray(),
        retrofit
    )
    val value = converter?.convert("".toResponseBody())
    val pass = value == null
    DemoRunResult(
        pass = pass,
        actual = "value=$value",
        expected = "null"
    )
}
