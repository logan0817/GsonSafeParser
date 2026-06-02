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
 * 兼容回调仍然可用。
 */
internal fun callbackCompatibilityCase(): DemoCase = DemoCase(
    title = "兼容回调",
    category = "可观测性",
    entryPoint = "onTypeMismatch / onAdapterCreationFailure",
    description = "除了统一 onEvent，类型错配回调和 Adapter 创建失败回调也要继续收到事件。",
    defaultJson = """{"code":200,"data":[]}""",
    expected = "onTypeMismatch 收到 $.data，onAdapterCreationFailure 收到重复字段名失败。"
) { json ->
    val mismatches = mutableListOf<TypeMismatchEvent>()
    val adapterFailures = mutableListOf<io.github.logan.gsonsafeparser.AdapterCreationFailureEvent>()
    val gson = GsonSafeParser.create(
        SafeParserConfig(
            onTypeMismatch = mismatches::add,
            onAdapterCreationFailure = adapterFailures::add
        )
    )
    val value = gson.fromJson(json, ApiResponse::class.java)
    val adapterError = runCatching {
        gson.getAdapter(DuplicateNameResponse::class.java)
    }.exceptionOrNull()
    val pass = value == ApiResponse(code = 200) &&
        mismatches.any { event -> event.path == "$.data" } &&
        adapterFailures.isNotEmpty() &&
        adapterError is IllegalArgumentException
    val reportEvents = mismatches.map { event -> SafeParserEvent.TypeMismatch(event) } +
        adapterFailures.map { event -> SafeParserEvent.AdapterCreationFailure(event) }
    DemoRunResult(
        pass = pass,
        actual = """
            value=${pretty(value)}
            mismatchPaths=${mismatches.map { it.path }}
            adapterFailures=${adapterFailures.map { it.typeName }}
            adapterError=${adapterError?.javaClass?.name}
        """.trimIndent(),
        expected = "兼容回调都收到事件，解析主流程不被观察回调影响",
        contractReport = reportEvents.toSafeResultReport()
    )
}

/**
 * Safe Adapter 创建失败时回到 Gson 默认策略。
 */
internal fun adapterCreationFallbackCase(): DemoCase = DemoCase(
    title = "Adapter 创建失败回退 Gson",
    category = "安全回退",
    entryPoint = "onAdapterCreationFailure + Gson fallback",
    description = "重复 JSON 字段名会导致 Safe Adapter 创建失败；默认策略会先发事件，再交回 Gson 原生链路。",
    defaultJson = "{}",
    expected = "收到 AdapterCreationFailure 事件，同时 Gson 原生 Adapter 继续抛出重复字段异常。"
) {
    val events = mutableListOf<SafeParserEvent>()
    val gson = GsonSafeParser.create(SafeParserConfig(onEvent = events::add))
    val error = runCatching {
        gson.getAdapter(DuplicateNameResponse::class.java)
    }.exceptionOrNull()
    val pass = events.any { it is SafeParserEvent.AdapterCreationFailure } &&
        error is IllegalArgumentException
    DemoRunResult(
        pass = pass,
        actual = "异常类型=${error?.javaClass?.name}\n异常信息=${error?.message.orEmpty()}",
        expected = "AdapterCreationFailure 事件 + Gson 原生 IllegalArgumentException",
        events = events.describeEvents(),
        contractReport = events.toSafeResultReport()
    )
}

/**
 * @SerializedName 和字段级 @JsonAdapter。
 */
internal fun serializedNameAndJsonAdapterCase(): DemoCase = DemoCase(
    title = "SerializedName + JsonAdapter",
    category = "兼容能力",
    entryPoint = "@SerializedName(alternate) + @JsonAdapter(fieldAdapter)",
    description = "字段命名和字段级 Adapter 都属于 Gson 常用能力，Safe Reflective 不能破坏它们。",
    defaultJson = """{"user_name":"tom","score":"demo"}""",
    expected = "userName 通过 alternate 名称读取，score 由 JsonAdapter 转成字符串长度 4。"
) { json ->
    val value = GsonSafeParser.fromJsonSafe<NamedAdapterResponse>(json)
    val pass = value == NamedAdapterResponse(userName = "tom", score = ScoreValue(4))
    demoResult(pass, value, expected = "NamedAdapterResponse(userName=tom, score.count=4)")
}

/**
 * Android 平台类型默认跳过。
 */
internal fun platformTypeSkipCase(): DemoCase = DemoCase(
    title = "Android 平台类型跳过",
    category = "安全回退",
    entryPoint = "skippedPlatformTypePrefixes = setOf(\"android.\")",
    description = "Android 平台对象内部字段复杂，默认跳过 Safe Reflective 绑定，避免反射系统类造成误伤。",
    defaultJson = """{"title":"remote","colorStateList":{"mChangingConfigurations":1}}""",
    expected = "title 正常解析，colorStateList 仍为 null。"
) { json ->
    val value = GsonSafeParser.fromJsonSafe<PlatformResponse>(json)
    val pass = value == PlatformResponse(title = "remote", colorStateList = null)
    demoResult(pass, value, expected = "PlatformResponse(title=remote, colorStateList=null)")
}

/**
 * org.json 类型桥接。
 */
internal fun orgJsonCase(): DemoCase = DemoCase(
    title = "JSONObject/JSONArray 适配",
    capabilityIds = setOf("org-json-mismatch"),
    category = "兼容能力",
    entryPoint = "SafeOrgJsonAdapters",
    description = "JSONObject 和 JSONArray 不能走普通字段反射，必须通过专用 Adapter 桥接。",
    defaultJson = """{"payload":{"id":1,"name":"Tom"},"items":[1,{"ok":true}]}""",
    expected = "payload.id=1，items.length=2。"
) { json ->
    val value = GsonSafeParser.fromJsonSafe<OrgJsonResponse>(json)
    val pass = value?.payload?.getInt("id") == 1 && value.items?.length() == 2
    DemoRunResult(
        pass = pass,
        actual = "payload=${value?.payload}\nitems=${value?.items}",
        expected = "JSONObject 和 JSONArray 正常读取"
    )
}

/**
 * Gson 内置类型交回原生 Adapter。
 */
internal fun gsonBuiltInTypesCase(): DemoCase = DemoCase(
    title = "Gson 内置类型交回原生",
    category = "安全回退",
    entryPoint = "GsonBuiltInTypes",
    description = "URL、URI、UUID、Date、BitSet、AtomicBoolean 这类 Gson 内置类型不走 Safe Reflective 反射。",
    defaultJson = "{}",
    expected = "内置类型字段能按 Gson 原生 Adapter 正常读回。"
) {
    val nativeGson = Gson()
    val bitSet = BitSet().apply {
        set(0)
        set(2)
    }
    val json = nativeGson.toJson(
        BuiltInTypesResponse(
            url = URL("https://example.com/path"),
            uri = URI("content://example/items/1"),
            uuid = UUID.fromString("00000000-0000-0000-0000-000000000123"),
            date = Date(0),
            bitSet = bitSet,
            atomicBoolean = AtomicBoolean(true)
        )
    )
    val value = GsonSafeParser.fromJsonSafe<BuiltInTypesResponse>(json)
    val pass = value?.url?.toExternalForm() == "https://example.com/path" &&
        value.uri == URI("content://example/items/1") &&
        value.uuid == UUID.fromString("00000000-0000-0000-0000-000000000123") &&
        value.date == Date(0) &&
        value.bitSet == bitSet &&
        value.atomicBoolean?.get() == true
    DemoRunResult(
        pass = pass,
        actual = "json=$json\nvalue=${pretty(value)}",
        expected = "Gson 内置 Adapter 正常生效"
    )
}

/**
 * `@SafeParseSkip`。
 */
internal fun annotationSkipCase(): DemoCase = DemoCase(
    title = "@SafeParseSkip 字段跳过",
    category = "注解",
    entryPoint = "@field:SafeParseSkip",
    description = "运行时状态、平台对象或高风险字段可以跳过 Safe Reflective 绑定。",
    defaultJson = """{"user":{"id":9,"name":"remote-user"},"title":"remote"}""",
    expected = "即使 user 收到正常对象，也会因为 @SafeParseSkip 保留默认值；title 正常解析为 remote。"
) { json ->
    val value = GsonSafeParser.fromJsonSafe<SkipFieldResponse>(json)
    val pass = value == SkipFieldResponse(user = User(), title = "remote")
    demoResult(pass, value, expected = "SkipFieldResponse(user=User(), title=remote)")
}

/**
 * `@SafeParseDelegateToGson`。
 */
internal fun annotationDelegateToGsonCase(): DemoCase = DemoCase(
    title = "@SafeParseDelegateToGson 交回 Gson",
    category = "注解",
    entryPoint = "@SafeParseDelegateToGson",
    description = "非常关键或已有自定义规则的类型可以明确不让 Safe Adapter 接管。",
    defaultJson = "[]",
    expected = "根对象传 [] 时交回 Gson 原生策略，抛出 JsonSyntaxException。"
) { json ->
    val error = runCatching {
        GsonSafeParser.fromJson(json, NativeOnly::class.java)
    }.exceptionOrNull()
    val pass = error is JsonSyntaxException
    DemoRunResult(
        pass = pass,
        actual = error?.javaClass?.name ?: "未抛异常",
        expected = "JsonSyntaxException"
    )
}
