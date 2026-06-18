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
import io.github.logan.gsonsafeparser.GsonSafeParserLowLevelApi
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
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
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
 * rawJson 捕获截断标记。
 */
internal fun rawJsonTruncationCase(): DemoCase = DemoCase(
    title = "rawJson 截断标记",
    category = "可观测性",
    entryPoint = "SafeParserConfig(captureRawJsonInCallbacks = true, maxRawJsonCaptureBytes = 10, primitiveParsingPolicy = PrimitiveParsingPolicy.Safe)",
    description = "显式开启 Safe 基础类型策略和 rawJson 捕获后，超过上限的事件要带截断内容和“原始 JSON 已截断”标记。",
    defaultJson = """{"count":[],"extra":"large"}""",
    expected = "类型错配事件里的原始 JSON 只保留前 10 个字符，并标记已经截断。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val value = GsonSafeParser.fromJson(
        json = json,
        type = PrimitiveResponse::class.java,
        config = SafeParserConfig(
            captureRawJsonInCallbacks = true,
            maxRawJsonCaptureBytes = 10,
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
            onEvent = events::add
        )
    )
    val event = events.firstNotNullOfOrNull { item -> item.typeMismatchDetail() }
    val pass = value == PrimitiveResponse() &&
        event?.rawJson == json.take(10) &&
        event.rawJsonTruncated
    DemoRunResult(
        pass = pass,
        actual = "解析结果=${pretty(value)}\n捕获到的原始 JSON=${event?.rawJson}\n原始 JSON 是否截断=${event?.rawJsonTruncated}",
        expected = "捕获到的原始 JSON=${json.take(10)}，原始 JSON 是否截断=true",
        events = events.describeEvents(),
        contractReport = events.toSafeResultReport()
    )
}

/**
 * `observerFailureReport()`。
 */
internal fun observerFailureReportCase(): DemoCase = DemoCase(
    title = "观察者失败隔离与报告",
    category = "可观测性",
    entryPoint = "onObserverFailure + observerFailureReport()",
    description = "日志或埋点回调自己崩了，也不能打断解析；失败会进入独立报告。",
    defaultJson = """{"code":200,"data":[]}""",
    expected = "解析成功，同时 observerFailureReport 有 onEvent 失败记录。"
) { json ->
    val observerFailures = mutableListOf<ObserverFailureEvent>()
    val config = SafeParserConfig(
        onEvent = { error("demo observer failed") },
        onObserverFailure = observerFailures::add
    )
    val result = GsonSafeParser.parseSafe<ApiResponse>(json, config)
    val report = observerFailures.observerFailureReport()
    val pass = result.value == ApiResponse(code = 200) &&
        report.hasFailures &&
        report.failuresByCallback.containsKey("onEvent")
    DemoRunResult(
        pass = pass,
        actual = pretty(result.value),
        expected = "解析成功，观察者失败被隔离",
        events = result.events.describeEvents(),
        contractReport = result.contractReport().toMarkdown(),
        observerReport = report.toMarkdown()
    )
}

/**
 * `dispatchEvent()` 低层事件桥接入口。
 */
@OptIn(GsonSafeParserLowLevelApi::class)
internal fun dispatchEventCase(): DemoCase = DemoCase(
    title = "dispatchEvent 低层事件桥接",
    category = "可观测性",
    entryPoint = "SafeParserConfig.dispatchEvent(event)",
    description = "Retrofit 或扩展模块可以通过低层 Opt-in 入口复用统一事件流；普通业务优先用 onEvent 观察解析事件，手动 dispatch 不进入 parseSafe 快照。",
    defaultJson = "{}",
    expected = "onEvent 收到 EmptyResponse 事件。"
) {
    val events = mutableListOf<SafeParserEvent>()
    val config = SafeParserConfig(onEvent = events::add)
    config.dispatchEvent(
        SafeParserEvent.EmptyResponse(
            EmptyResponseEvent(typeName = "DemoResponse", policy = EmptyResponsePolicy.DefaultValue)
        )
    )
    val pass = events.singleOrNull() is SafeParserEvent.EmptyResponse
    DemoRunResult(
        pass = pass,
        actual = events.describeEvents(),
        expected = "收到 EmptyResponse 统一事件",
        events = events.describeEvents(),
        contractReport = events.toSafeResultReport()
    )
}
