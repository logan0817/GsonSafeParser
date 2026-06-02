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
 * `diagnostics()` + `integrationCheck()`。
 */
internal fun diagnosticsIntegrationCheckCase(): DemoCase = DemoCase(
    title = "diagnostics + integrationCheck",
    category = "接入自检",
    entryPoint = "GsonSafeParser.diagnostics(config) / GsonSafeParser.integrationCheck(config)",
    description = "不依赖业务接口，直接检查当前环境和 Safe Adapter 探针是否可用。",
    defaultJson = "{}",
    expected = "诊断没有阻断错误，集成检查确认失败回退 Gson 正常。"
) {
    val diagnostics = GsonSafeParser.diagnostics()
    val check = GsonSafeParser.integrationCheck()
    val pass = !diagnostics.hasErrors && !check.hasErrors && check.fallbackWorking
    DemoRunResult(
        pass = pass,
        actual = "诊断是否存在阻断错误=${diagnostics.hasErrors}\n失败回退 Gson 是否正常=${check.fallbackWorking}",
        expected = "无阻断错误，探针兜底成功",
        diagnostics = diagnostics.describe() + "\n\n" + check.describe()
    )
}

/**
 * `production()`、`debug()`、`lowInterference()` 三个预设。
 */
internal fun presetConfigCompareCase(): DemoCase = DemoCase(
    title = "配置预设对比",
    category = "配置",
    entryPoint = "SafeParserConfig.production() / debug() / lowInterference()",
    description = "同一份错形 JSON 下，对比线上、联调、低误伤三种常见接入策略。",
    defaultJson = """{"code":200,"data":[]}""",
    expected = "production/debug 保留默认对象；debug 有 rawJson；lowInterference 返回 null。"
) { json ->
    val debugEvents = mutableListOf<SafeParserEvent>()
    val production = GsonSafeParser.parseSafe<ApiResponse>(json, SafeParserConfig.production())
    val debugConfig = SafeParserConfig.debug(observerPolicy = SafeObserverPolicy(onEvent = debugEvents::add))
    val debug = GsonSafeParser.parseSafe<ApiResponse>(json, debugConfig)
    val low = GsonSafeParser.parseSafe<NullableApiResponse>(json, SafeParserConfig.lowInterference())
    val debugRawJson = debugEvents.firstNotNullOfOrNull { it.typeMismatchDetail()?.rawJson }
    val pass = production.value == ApiResponse(code = 200) &&
        debug.value == ApiResponse(code = 200) &&
        low.value == NullableApiResponse(code = 200, data = null) &&
        debugRawJson == json
    DemoRunResult(
        pass = pass,
        actual = """
            production=${pretty(production.value)}
            debug=${pretty(debug.value)}
            lowInterference=${pretty(low.value)}
            debugRawJson=$debugRawJson
        """.trimIndent(),
        expected = "production/debug 默认对象，lowInterference null，debug 捕获 rawJson",
        events = (production.events + debug.events + low.events).describeEvents(),
        contractReport = debug.contractReport().toMarkdown()
    )
}

/**
 * `SafeParserConfig.fromPolicies()` 分层策略入口。
 */
internal fun fromPoliciesCase(): DemoCase = DemoCase(
    title = "fromPolicies + 读策略/事件策略",
    category = "配置",
    entryPoint = "SafeParserConfig.fromPolicies(readPolicy, writePolicy, observerPolicy)",
    description = "读、写、观察策略分开配置；本用例重点验证读策略和事件策略，写策略由 Map 复杂 key 用例验证。",
    defaultJson = """{"code":200,"data":[],"profile":{"1":{"id":7,"name":"Tom"}}}""",
    expected = "data 保留默认对象，profile 的 Int key 正常解析，并能收到事件。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val config = SafeParserConfig.fromPolicies(
        readPolicy = SafeReadPolicy(fallbackPolicy = FallbackPolicy.Default),
        writePolicy = SafeWritePolicy(complexMapKeySerialization = false),
        observerPolicy = SafeObserverPolicy(onEvent = events::add)
    )
    val value = GsonSafeParser.parseSafe<MapResponse>(json, config)
    val pass = value.value?.data == User() &&
        value.value?.profile?.get(1) == User(7, "Tom") &&
        events.hasTypeMismatch(path = "$.data")
    demoResult(pass, value.value, events, value.contractReport().toMarkdown(), "data 默认对象，profile[1]=Tom")
}

/**
 * GsonBuilder 上已有字段命名、Expose 和版本过滤配置。
 */
internal fun builderFieldNamingExposeVersionCase(): DemoCase = DemoCase(
    title = "Builder FieldNamingPolicy/Expose/Version 透传",
    category = "配置",
    entryPoint = "GsonBuilder.setFieldNamingPolicy/excludeFieldsWithoutExposeAnnotation/setVersion + enableSafeParser",
    description = "项目里已有 GsonBuilder 配置时，SafeParser 必须继承这些规则，不能因为注册 Safe Adapter 就改变字段读写结果。",
    defaultJson = """{"user_name":"remote","visible":"remote-visible","hidden":"remote-hidden","supported":"remote-supported","future":"remote-future"}""",
    expected = "user_name 能写入 userName；hidden 和 future 保留本地默认值，写出时也被 GsonBuilder 规则排除。"
) { json ->
    // namingJson 只取字段命名策略需要的字段，避免 Expose 或 Version 示例互相干扰。
    val namingJson = """{"user_name":"remote"}"""
    val namingGson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .enableSafeParser()
        .create()
    val namingValue = namingGson.fromJson(namingJson, BuilderNamingResponse::class.java)

    // exposeGson 模拟老项目里常见的“只解析带 @Expose 的字段”配置。
    val exposeGson = GsonBuilder()
        .excludeFieldsWithoutExposeAnnotation()
        .enableSafeParser()
        .create()
    val exposeValue = exposeGson.fromJson(json, BuilderExposeResponse::class.java)
    val exposeWritten = exposeGson.toJson(exposeValue)

    // versionGson 模拟通过 @Since 灰度字段的接口模型。
    val versionGson = GsonBuilder()
        .setVersion(2.0)
        .enableSafeParser()
        .create()
    val versionValue = versionGson.fromJson(json, BuilderVersionedResponse::class.java)
    val versionWritten = versionGson.toJson(versionValue)

    val pass = namingValue == BuilderNamingResponse(userName = "remote") &&
        exposeValue == BuilderExposeResponse(visible = "remote-visible") &&
        exposeWritten == """{"visible":"remote-visible"}""" &&
        versionValue == BuilderVersionedResponse(supported = "remote-supported") &&
        versionWritten == """{"supported":"remote-supported"}"""
    DemoRunResult(
        pass = pass,
        actual = """
            naming=${pretty(namingValue)}
            expose=${pretty(exposeValue)}
            exposeWritten=$exposeWritten
            version=${pretty(versionValue)}
            versionWritten=$versionWritten
        """.trimIndent(),
        expected = "Builder 上的字段命名、Expose 和 Version 规则全部生效"
    )
}

/**
 * GsonBuilder 上注册的 InstanceCreator 和 Unsafe 开关。
 */
internal fun builderInstanceCreatorUnsafeCase(): DemoCase = DemoCase(
    title = "Builder InstanceCreator 与 Unsafe 透传",
    category = "配置",
    entryPoint = "GsonBuilder.registerTypeAdapter(InstanceCreator) / disableJdkUnsafe + enableSafeParser",
    description = "验证统一 GsonBuilder 里的对象创建器和 Unsafe 开关会进入 SafeObjectConstructor，不会只在原生 Gson 链路里生效。",
    defaultJson = """{"data":{},"unsafe":{"value":"remote"}}""",
    expected = "data.source 来自 Builder InstanceCreator；disableJdkUnsafe 后无无参构造类回到 JsonIOException。"
) {
    val creatorGson = GsonBuilder()
        .registerTypeAdapter(
            CreatedByInstanceCreator::class.java,
            InstanceCreator { CreatedByInstanceCreator("builder") }
        )
        .enableSafeParser()
        .create()
    val creatorValue = creatorGson.fromJson("""{"data":{}}""", BuilderCreatorResponse::class.java)

    val unsafeGson = GsonBuilder()
        .disableJdkUnsafe()
        .enableSafeParser()
        .create()
    val unsafeError = runCatching {
        unsafeGson.fromJson("""{"value":"remote"}""", OnlyParameterizedConstructor::class.java)
    }.exceptionOrNull()

    val pass = creatorValue == BuilderCreatorResponse(CreatedByInstanceCreator("builder")) &&
        unsafeError is JsonIOException
    DemoRunResult(
        pass = pass,
        actual = "creator=${pretty(creatorValue)}\nunsafeError=${unsafeError?.javaClass?.name}",
        expected = "Builder InstanceCreator 生效，Builder disableJdkUnsafe 生效"
    )
}

/**
 * 多个 ReflectionAccessFilter 的优先级。
 */
internal fun reflectionAccessFilterPriorityCase(): DemoCase = DemoCase(
    title = "ReflectionAccessFilter 优先级验证",
    category = "配置",
    entryPoint = "SafeParserConfig(reflectionAccessFilters = listOf(block, allow))",
    description = "GsonBuilder 会让后添加的过滤器优先匹配，SafeParser 合并配置后也要保持同样顺序。",
    defaultJson = """{"name":"remote"}""",
    expected = "先传 block 再传 allow，最终 allow 优先，OrderedFilterBean.name 能解析为 remote。"
) { json ->
    val blockOrderedType = ReflectionAccessFilter { rawType ->
        if (rawType == OrderedFilterBean::class.java) {
            ReflectionAccessFilter.FilterResult.BLOCK_ALL
        } else {
            ReflectionAccessFilter.FilterResult.INDECISIVE
        }
    }
    val allowOrderedType = ReflectionAccessFilter { rawType ->
        if (rawType == OrderedFilterBean::class.java) {
            ReflectionAccessFilter.FilterResult.ALLOW
        } else {
            ReflectionAccessFilter.FilterResult.INDECISIVE
        }
    }
    val value = GsonSafeParser.fromJson(
        json = json,
        type = OrderedFilterBean::class.java,
        config = SafeParserConfig(reflectionAccessFilters = listOf(blockOrderedType, allowOrderedType))
    )
    val pass = value == OrderedFilterBean("remote")
    demoResult(pass, value, expected = "OrderedFilterBean(name=remote)")
}

/**
 * 基础类型交回 Gson 原生策略。
 */
internal fun primitiveDelegateToGsonCase(): DemoCase = DemoCase(
    title = "PrimitiveParsingPolicy.DelegateToGson",
    category = "配置",
    entryPoint = "SafeParserConfig(primitiveParsingPolicy = DelegateToGson)",
    description = "基础类型可以不走 SafeParser 宽松解析，直接交回 Gson 原生 Adapter。",
    defaultJson = "{}",
    expected = "Safe 策略把根 Int 错形兜底为 0；DelegateToGson 策略抛出 Gson 原生异常。"
) { json ->
    val safeValue = GsonSafeParser.fromJson(
        json = json,
        type = Int::class.javaObjectType,
        config = SafeParserConfig(
            fallbackPolicy = FallbackPolicy.Default,
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
        )
    )
    val delegateError = runCatching {
        GsonSafeParser.fromJson(
            json = json,
            type = Int::class.javaObjectType,
            config = SafeParserConfig(primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson)
        )
    }.exceptionOrNull()
    val pass = safeValue == 0 && delegateError is JsonSyntaxException
    DemoRunResult(
        pass = pass,
        actual = "safeValue=$safeValue\ndelegateError=${delegateError?.javaClass?.name}",
        expected = "safeValue=0，delegateError=JsonSyntaxException"
    )
}

/**
 * `FallbackPolicy.NullOnly` 对集合和 Map 的影响。
 */
internal fun fallbackNullOnlyCollectionMapCase(): DemoCase = DemoCase(
    title = "FallbackPolicy.NullOnly 集合/Map",
    category = "配置",
    entryPoint = "SafeParserConfig(fallbackPolicy = NullOnly)",
    description = "集合或 Map 字段整体错形时，Default 策略给空容器，NullOnly 策略给 null。",
    defaultJson = """{"users":{},"profile":false}""",
    expected = "users=null，profile=null，并能看到两个错配事件。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val value = GsonSafeParser.fromJson(
        json = json,
        type = NullableCollectionMapResponse::class.java,
        config = SafeParserConfig(
            fallbackPolicy = FallbackPolicy.NullOnly,
            onEvent = events::add
        )
    )
    val pass = value == NullableCollectionMapResponse(users = null, profile = null) &&
        events.hasTypeMismatch(path = "$.users") &&
        events.hasTypeMismatch(path = "$.profile")
    demoResult(pass, value, events, expected = "NullableCollectionMapResponse(users=null, profile=null)")
}

/**
 * 调用方自定义 `Any/Object` 数字策略。
 */
internal fun customObjectNumberStrategyCase(): DemoCase = DemoCase(
    title = "objectToNumberStrategy 自定义",
    category = "配置",
    entryPoint = "SafeParserConfig(objectToNumberStrategy = ToNumberPolicy.LONG_OR_DOUBLE)",
    description = "默认策略会把 1 读成 Int；调用方可以改成 Gson 原生 LONG_OR_DOUBLE 策略。",
    defaultJson = """{"small":1,"values":{"count":2}}""",
    expected = "small 和 values.count 都是 Long。"
) { json ->
    val value = GsonSafeParser.fromJson(
        json = json,
        type = AnyResponse::class.java,
        config = SafeParserConfig(objectToNumberStrategy = ToNumberPolicy.LONG_OR_DOUBLE)
    )
    val pass = value?.small is Long && value.values["count"] is Long
    DemoRunResult(
        pass = pass,
        actual = "value=${pretty(value)}\ntypes=small:${value?.small?.javaClass?.name}, count:${value?.values?.get("count")?.javaClass?.name}",
        expected = "Long / Long"
    )
}

/**
 * InstanceCreator 显式对象创建器。
 */
internal fun instanceCreatorCase(): DemoCase = DemoCase(
    title = "InstanceCreator 对象构造",
    category = "兼容能力",
    entryPoint = "SafeParserConfig(instanceCreators = mapOf(type to InstanceCreator))",
    description = "调用方显式提供对象创建器时，SafeObjectConstructor 应优先使用它。",
    defaultJson = "{}",
    expected = "source 来自 InstanceCreator，值为 created-by-demo。"
) { json ->
    val targetType = InstanceCreated::class.java
    val value = GsonSafeParser.fromJson(
        json = json,
        type = targetType,
        config = SafeParserConfig(
            instanceCreators = mapOf(
                targetType to InstanceCreator { InstanceCreated("created-by-demo") }
            )
        )
    )
    val pass = value == InstanceCreated("created-by-demo")
    demoResult(pass, value, expected = "InstanceCreated(source=created-by-demo)")
}

/**
 * 反射访问限制和 Unsafe 关闭。
 */
internal fun reflectionAccessAndUnsafeCase(): DemoCase = DemoCase(
    title = "ReflectionAccessFilter + useJdkUnsafe",
    category = "安全回退",
    entryPoint = "reflectionAccessFilters / useJdkUnsafe=false",
    description = "验证用户禁止反射或关闭 Unsafe 时，SafeParser 不会强行绕过限制，而是回到 Gson 异常语义。",
    defaultJson = """{"value":"remote"}""",
    expected = "BLOCK_ALL 和 useJdkUnsafe=false 都得到 JsonIOException。"
) { json ->
    val blockAllFilter = ReflectionAccessFilter { rawType ->
        if (rawType == ReflectionBlockedResponse::class.java) {
            ReflectionAccessFilter.FilterResult.BLOCK_ALL
        } else {
            ReflectionAccessFilter.FilterResult.INDECISIVE
        }
    }
    val reflectionError = runCatching {
        GsonSafeParser.fromJson(
            json = "{}",
            type = ReflectionBlockedResponse::class.java,
            config = SafeParserConfig(reflectionAccessFilters = listOf(blockAllFilter))
        )
    }.exceptionOrNull()
    val unsafeError = runCatching {
        GsonSafeParser.fromJson(
            json = json,
            type = OnlyParameterizedConstructor::class.java,
            config = SafeParserConfig(useJdkUnsafe = false)
        )
    }.exceptionOrNull()
    val pass = reflectionError is JsonIOException && unsafeError is JsonIOException
    DemoRunResult(
        pass = pass,
        actual = "reflectionError=${reflectionError?.javaClass?.name}\nunsafeError=${unsafeError?.javaClass?.name}",
        expected = "两个场景都回到 Gson 的 JsonIOException"
    )
}

/**
 * diagnostics 高风险配置告警。
 */
internal fun diagnosticsRiskWarningCase(): DemoCase = DemoCase(
    title = "diagnostics 高风险配置告警",
    category = "接入自检",
    entryPoint = "GsonSafeParser.diagnostics(riskyConfig)",
    description = "用户关闭平台类型跳过或把 rawJson 上限设成 0 时，诊断要提示风险，但不能改变解析行为。",
    defaultJson = "{}",
    expected = "出现 skippedPlatformTypePrefixes、maxRawJsonCaptureBytes 两个 WARNING，但没有阻断错误。"
) {
    val diagnostics = GsonSafeParser.diagnostics(
        SafeParserConfig(
            skippedPlatformTypePrefixes = emptySet(),
            captureRawJsonInCallbacks = true,
            maxRawJsonCaptureBytes = 0
        )
    )
    val warnings = diagnostics.checks
        .filter { check -> check.severity.name == "WARNING" }
        .map { check -> check.name }
    val pass = "skippedPlatformTypePrefixes" in warnings &&
        "maxRawJsonCaptureBytes" in warnings &&
        !diagnostics.hasErrors
    DemoRunResult(
        pass = pass,
        actual = "告警检查项=$warnings\n是否存在阻断错误=${diagnostics.hasErrors}",
        expected = "高风险配置只告警，不阻断默认回退策略",
        diagnostics = diagnostics.describe()
    )
}

/**
 * integrationCheck 平台类型跳过配置告警。
 */
internal fun integrationCheckPlatformWarningCase(): DemoCase = DemoCase(
    title = "integrationCheck 平台类型告警",
    category = "接入自检",
    entryPoint = "GsonSafeParser.integrationCheck(platformRiskConfig)",
    description = "关闭平台类型跳过时仍要完成内置探针，并把反射平台对象的风险以 WARNING 展示给接入方。",
    defaultJson = "{}",
    expected = "探针仍成功，失败回退 Gson 正常，同时包含平台类型配置 warning。"
) {
    val check = GsonSafeParser.integrationCheck(
        SafeParserConfig(
            skippedPlatformTypePrefixes = emptySet()
        )
    )
    val warnings = check.checks
        .filter { item -> item.severity.name == "WARNING" }
        .map { item -> item.name }
    val pass = check.probeParsed &&
        check.fallbackWorking &&
        !check.hasErrors &&
        "skippedPlatformTypePrefixes" in warnings
    DemoRunResult(
        pass = pass,
        actual = "探针 JSON 是否解析成功=${check.probeParsed}\n失败回退 Gson 是否正常=${check.fallbackWorking}\n告警检查项=$warnings",
        expected = "平台类型跳过配置可被探针识别并给出 warning",
        diagnostics = check.describe(),
        contractReport = check.contractReport.toMarkdown()
    )
}
