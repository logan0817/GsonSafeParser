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
 * `GsonSafeParser.fromJson(json, Class, config)` 入口。
 */
internal fun gsonSafeParserFromJsonClassCase(): DemoCase = DemoCase(
    title = "fromJson(Class) + NullOnly",
    category = "公开 API",
    entryPoint = "GsonSafeParser.fromJson(json, NullableApiResponse::class.java, config)",
    description = "用 NullOnly 策略解析对象字段错形，验证公开 helper 能返回更接近 Gson 失败语义的 null。",
    defaultJson = """{"code":200,"data":[]}""",
    expected = "code=200，data=null，事件指向 $.data。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val config = SafeParserConfig(
        fallbackPolicy = FallbackPolicy.NullOnly,
        onEvent = events::add
    )
    val value = GsonSafeParser.fromJson(json, NullableApiResponse::class.java, config)
    val pass = value == NullableApiResponse(code = 200, data = null) &&
        events.hasTypeMismatch(path = "$.data")
    demoResult(pass, value, events, expected = "NullableApiResponse(code=200, data=null)")
}

/**
 * `GsonSafeParser.fromJson(json, Type, config)` 入口。
 */
internal fun gsonSafeParserFromJsonTypeCase(): DemoCase = DemoCase(
    title = "fromJson(Type) + 泛型 List",
    category = "公开 API",
    entryPoint = "GsonSafeParser.fromJson<List<User>>(json, type, config)",
    description = "根类型是泛型 List 时，坏 item 被跳过，后面的正常 item 继续解析。",
    defaultJson = """[{"id":1,"name":"A"},[],{"id":2,"name":"B"}]""",
    expected = "结果只保留 A 和 B 两个用户，[] 这个坏 item 产生 LIST_ITEM 事件。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val type = object : TypeToken<List<User>>() {}.type
    val value = GsonSafeParser.fromJson<List<User>>(json, type, SafeParserConfig(onEvent = events::add))
    val pass = value == listOf(User(1, "A"), User(2, "B")) &&
        events.any { event -> event.typeMismatchDetail()?.kind?.name == "LIST_ITEM" }
    demoResult(pass, value, events, expected = "[User(id=1,name=A), User(id=2,name=B)]")
}

/**
 * `GsonSafeParser.fromJson(gson, json, type, config)` 入口。
 */
internal fun gsonSafeParserFromJsonWithGsonCase(): DemoCase = DemoCase(
    title = "fromJson(gson) + rawJson 捕获",
    category = "公开 API",
    entryPoint = "GsonSafeParser.fromJson(gson, json, type, debugConfig)",
    description = "传入调用方已有 Gson，并开启 rawJson 捕获，验证错配事件能带上本次原始 JSON。",
    defaultJson = """{"code":200,"data":[]}""",
    expected = "解析成功，事件 rawJson 等于输入 JSON。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val config = SafeParserConfig.debug(observerPolicy = SafeObserverPolicy(onEvent = events::add))
    val gson = GsonSafeParser.create(config)
    val value = GsonSafeParser.fromJson<ApiResponse>(gson, json, ApiResponse::class.java, config)
    val rawJson = events.firstNotNullOfOrNull { it.typeMismatchDetail()?.rawJson }
    val pass = value == ApiResponse(code = 200) && rawJson == json
    demoResult(pass, value, events, expected = "事件 rawJson 应完整保留输入 JSON")
}

/**
 * `GsonSafeParser.parser(config)` 入口。
 */
internal fun gsonSafeParserReusableParserCase(): DemoCase = DemoCase(
    title = "parser(config) + 复用 Gson",
    category = "公开 API",
    entryPoint = "GsonSafeParser.parser(config)",
    description = "高频手动解析时先创建一次 Parser，再反复复用同一个 Gson，避免每次都新建 Gson。",
    defaultJson = """{"code":200,"data":[]}""",
    expected = "两次解析都成功，事件也都被正常记录。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val parser = GsonSafeParser.parser(
        SafeParserConfig(onEvent = events::add)
    )
    val first = parser.fromJson(json, ApiResponse::class.java)
    val second = parser.fromJson(json, ApiResponse::class.java)
    val pass = first == ApiResponse(code = 200) &&
        second == ApiResponse(code = 200) &&
        events.size == 2
    DemoRunResult(
        pass = pass,
        actual = """
            first=${pretty(first)}
            second=${pretty(second)}
            eventCount=${events.size}
        """.trimIndent(),
        expected = "两次解析都成功，每次解析各产生 1 条 $.data TypeMismatch 事件。",
        events = events.describeEvents(),
        contractReport = events.toSafeResultReport()
    )
}

/**
 * `GsonSafeParser.parser(config).parseSafe<T>()` 入口。
 */
internal fun gsonSafeParserReusableParserParseSafeCase(): DemoCase = DemoCase(
    title = "parser(config) + 契约报告",
    category = "公开 API",
    entryPoint = "GsonSafeParser.parser(config).parseSafe<ApiResponse>(json)",
    description = "复用 Parser 的同时拿到本次事件快照，适合高频解析后直接生成契约报告。",
    defaultJson = """{"code":200,"data":[]}""",
    expected = "解析成功，事件快照和外部事件都能看到 $.data。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val parser = GsonSafeParser.parser(
        SafeParserConfig(onEvent = events::add)
    )
    val result = parser.parseSafe<ApiResponse>(json)
    val report = result.contractReport()
    val pass = result.value == ApiResponse(code = 200) &&
        result.events.hasTypeMismatch(path = "$.data") &&
        events.hasTypeMismatch(path = "$.data") &&
        report.hasIssues
    demoResult(
        pass = pass,
        actual = result.value,
        events = result.events,
        contractReport = report.toMarkdown(),
        expected = "SafeParseResult 的 events 和外部 onEvent 都包含 $.data"
    )
}

/**
 * `GsonBuilder.enableSafeParser()` 扩展入口。
 */
internal fun enableSafeParserCase(): DemoCase = DemoCase(
    title = "enableSafeParser + 既有 GsonBuilder",
    category = "公开 API",
    entryPoint = "GsonBuilder().enableSafeParser(config).create()",
    description = "已有项目通常统一维护 GsonBuilder，这里验证直接在 Builder 上启用 SafeParser。",
    defaultJson = """{"code":200,"data":[]}""",
    expected = "解析结果与 create() 一致，说明 Builder 接入方式可用。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val gson = GsonBuilder()
        .enableSafeParser(SafeParserConfig(onEvent = events::add))
        .create()
    val value = gson.fromJson(json, ApiResponse::class.java)
    val pass = value == ApiResponse(code = 200) && events.hasTypeMismatch(path = "$.data")
    demoResult(pass, value, events, expected = "ApiResponse(code=200, data=User())")
}

/**
 * Kotlin `fromJsonSafe<T>()` 入口。
 */
internal fun kotlinFromJsonSafeCase(): DemoCase = DemoCase(
    title = "fromJsonSafe<T> + reified 兜底",
    category = "Kotlin API",
    entryPoint = "GsonSafeParser.fromJsonSafe<ApiResponse>(json)",
    description = "Kotlin 调用方不用手写 Class 或 TypeToken，也能在对象字段错形时保留默认值。",
    defaultJson = """{"code":201,"data":[]}""",
    expected = "code=201，data 回到默认 User。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val value = GsonSafeParser.fromJsonSafe<ApiResponse>(
        json = json,
        config = SafeParserConfig(onEvent = events::add)
    )
    val pass = value == ApiResponse(code = 201, data = User()) &&
        events.hasTypeMismatch(path = "$.data")
    demoResult(pass, value, events, expected = "ApiResponse(code=201, data=User())，事件流包含 $.data")
}

/**
 * `parseSafe<T>()` + `contractReport()`。
 */
internal fun parseSafeContractReportCase(): DemoCase = DemoCase(
    title = "parseSafe + 契约报告",
    category = "可观测性",
    entryPoint = "GsonSafeParser.parseSafe<ApiResponse>(json).contractReport()",
    description = "解析结果和事件快照一起返回，再把事件转成适合日志或 CI 查看的一份契约报告。",
    defaultJson = """{"code":200,"data":[]}""",
    expected = "解析结果成功，事件数量为 1，契约报告确认存在问题。"
) { json ->
    val result = GsonSafeParser.parseSafe<ApiResponse>(json)
    val report = result.contractReport()
    val pass = result.value == ApiResponse(code = 200) &&
        result.events.hasTypeMismatch(path = "$.data") &&
        report.hasIssues
    demoResult(
        pass = pass,
        actual = result.value,
        events = result.events,
        contractReport = report.toMarkdown(),
        expected = "SafeParseResult 的 value 是 ApiResponse，事件流包含 $.data"
    )
}
