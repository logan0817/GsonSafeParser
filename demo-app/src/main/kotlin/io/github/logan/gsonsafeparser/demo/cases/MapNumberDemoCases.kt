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
 * Map 坏 key 归因和嵌套数组 entry。
 */
internal fun mapBadKeyNestedEntryCase(): DemoCase = DemoCase(
    title = "Map 坏 key 归因与嵌套 entry",
    capabilityIds = setOf("map-field-mismatch"),
    category = "Map",
    entryPoint = "SafeMapAdapterFactory key parsing / nested array entry",
    description = "非 String key 解析失败时只跳过坏 entry，并把脱敏后的 mapItemKey 带到事件里；嵌套 Map 仍支持 [[key,value]] entry 形式。",
    defaultJson = """{"values":{"abc":"bad","1":"ok"},"nested":{"inner":[[2,"two"]]}}""",
    expected = "values 只保留 1=ok，事件 mapItemKey 为 sha256 哈希；nested.inner[2]=two。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val value = GsonSafeParser.fromJson(
        json = json,
        type = MapEdgeResponse::class.java,
        config = SafeParserConfig(onEvent = events::add)
    )
    val badKeyEvent = events.firstNotNullOfOrNull { event ->
        event.typeMismatchDetail()?.takeIf { detail ->
            detail.kind == ParseExceptionKind.MAP_ITEM &&
                detail.mapItemKey?.startsWith("sha256:") == true &&
                detail.mapItemKey != "abc"
        }
    }
    val pass = value == MapEdgeResponse(
        values = mapOf(1 to "ok"),
        nested = mapOf("inner" to mapOf(2 to "two"))
    ) && badKeyEvent != null
    demoResult(pass, value, events, expected = "values={1=ok}，nested.inner[2]=two，mapItemKey=sha256:*")
}

/**
 * Map 数组 entry 形式和复杂 key 写出。
 */
internal fun mapEntryAndSerializationCase(): DemoCase = DemoCase(
    title = "Map entry 读取 + 复杂 key 写出",
    category = "Map",
    entryPoint = "SafeMapAdapterFactory read/write",
    description = "支持 [[key,value]] 形式，也能按配置写出复杂 Map key。",
    defaultJson = """{"items":[["a",{"id":1,"name":"A"}],["b",[]]]}""",
    expected = "items.a 保留，items.b 这个坏 entry 被跳过；复杂 key 开启后写成数组 entry。"
) { json ->
    val result = GsonSafeParser.parseSafe<ArrayEntryMapResponse>(json)
    val gson = GsonSafeParser.create(SafeParserConfig(complexMapKeySerialization = true))
    val written = gson.toJson(mapOf(ComplexKey("demo") to User(3, "C")))
    val pass = result.value?.items?.get("a") == User(1, "A") &&
        result.value?.items?.containsKey("b") == false &&
        written.startsWith("[[")
    DemoRunResult(
        pass = pass,
        actual = "parsed=${pretty(result.value)}\nserialized=$written",
        expected = "items.a=A，items.b 被跳过，复杂 key 序列化为数组 entry",
        events = result.events.describeEvents(),
        contractReport = result.contractReport().toMarkdown()
    )
}

/**
 * `GsonSafeAutoNumberStrategy`。
 */
internal fun objectNumberStrategyCase(): DemoCase = DemoCase(
    title = "Any/Object 数字策略",
    category = "兼容能力",
    entryPoint = "GsonSafeAutoNumberStrategy",
    description = "Any 字段里的整数优先 Int，超出正向 Int 范围用 Long，小数用 Double。",
    defaultJson = """{"small":1,"large":2147483648,"decimal":1.5,"values":{"count":2}}""",
    expected = "small 是 Int，large 是 Long，decimal 是 Double。"
) { json ->
    val value = GsonSafeParser.fromJsonSafe<AnyResponse>(json)
    val pass = value?.small is Int &&
        value.large is Long &&
        value.decimal is Double &&
        value.values["count"] is Int
    DemoRunResult(
        pass = pass,
        actual = "value=${pretty(value)}\ntypes=small:${value?.small?.javaClass?.name}, large:${value?.large?.javaClass?.name}, decimal:${value?.decimal?.javaClass?.name}",
        expected = "Int / Long / Double 类型按本库默认策略落地"
    )
}

/**
 * EnumSet 和 EnumMap 特殊容器构造。
 */
internal fun enumSetEnumMapCase(): DemoCase = DemoCase(
    title = "EnumSet/EnumMap 特殊容器",
    category = "兼容能力",
    entryPoint = "SafeObjectConstructor EnumSet / EnumMap",
    description = "EnumSet 和 EnumMap 需要真实枚举类型才能构造，不能退成普通 Set/Map。",
    defaultJson = """{"roles":["ADMIN","USER"],"labels":{"ADMIN":"owner"}}""",
    expected = "roles 是 EnumSet，labels 是 EnumMap，ADMIN 对应 owner。"
) { json ->
    val value = GsonSafeParser.fromJsonSafe<EnumContainerResponse>(json)
    val pass = value?.roles == EnumSet.of(DemoRole.ADMIN, DemoRole.USER) &&
        value.labels[DemoRole.ADMIN] == "owner" &&
        value.roles.javaClass.name.contains("EnumSet") &&
        value.labels.javaClass.name.contains("EnumMap")
    DemoRunResult(
        pass = pass,
        actual = "value=${pretty(value)}\nrolesType=${value?.roles?.javaClass?.name}\nlabelsType=${value?.labels?.javaClass?.name}",
        expected = "EnumSet + EnumMap"
    )
}
