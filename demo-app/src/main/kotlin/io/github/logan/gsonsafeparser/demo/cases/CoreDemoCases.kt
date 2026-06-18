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
import io.github.logan.gsonsafeparser.ShapeCoercionAction
import io.github.logan.gsonsafeparser.ShapeCoercionPolicy
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
 * `GsonSafeParser.create()` + Gson 实例 `fromJson(Class)`。
 */
internal fun objectFieldFallbackCase(): DemoCase = DemoCase(
    title = "create + 对象字段错形兜底",
    capabilityIds = setOf("object-field-mismatch", "kotlin-defaults"),
    category = "核心解析",
    entryPoint = "GsonSafeParser.create() -> gson.fromJson(json, ApiResponse::class.java)",
    description = "后端把对象字段 data 返回成 [] 时，外层 Bean 继续解析，data 保留默认对象。",
    defaultJson = """{"code":200,"data":[]}""",
    expected = "code=200，data=User(id=0,name=anonymous)，产生 $.data 类型错配事件。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val gson = GsonSafeParser.create(SafeParserConfig(onEvent = events::add))
    val value = gson.fromJson(json, ApiResponse::class.java)
    val pass = value == ApiResponse(code = 200) &&
            events.hasTypeMismatch(path = "$.data", token = "BEGIN_ARRAY")
    demoResult(pass, value, events, expected = "ApiResponse(code=200, data=User())")
}

/**
 * 基础类型错形和宽松转换。
 */
internal fun primitiveMismatchCase(): DemoCase = DemoCase(
    title = "基础类型错形兜底",
    capabilityIds = setOf(
        "integer-field-mismatch",
        "big-number-mismatch",
        "boolean-field-mismatch",
        "string-field-mismatch"
    ),
    category = "核心解析",
    entryPoint = "GsonSafeParser.parseSafe(config = PrimitiveParsingPolicy.Safe)",
    description = "显式开启 Safe 基础类型策略后，数字、布尔、字符串字段遇到数组或对象时只兜底当前字段，不让整棵 Bean 失败。",
    defaultJson = """{"count":{},"total":[],"price":{},"enabled":[],"title":[]}""",
    expected = "count=0,total=0,price=0,enabled=false,title=null，并产生多个错配事件。"
) { json ->
    val result = GsonSafeParser.parseSafe<PrimitiveResponse>(
        json = json,
        config = SafeParserConfig(primitiveParsingPolicy = PrimitiveParsingPolicy.Safe)
    )
    val expectedPaths = listOf("$.count", "$.total", "$.price", "$.enabled", "$.title")
    val pass = result.value == PrimitiveResponse() &&
            expectedPaths.all { path -> result.events.hasTypeMismatch(path = path) }
    demoResult(pass, result.value, result.events, result.contractReport().toMarkdown(), "PrimitiveResponse 默认值")
}

/**
 * 集合、Set、Map 整体错形和 item 级跳过。
 */
internal fun collectionMapMismatchCase(): DemoCase = DemoCase(
    title = "集合/Map 错形与 item 跳过",
    capabilityIds = setOf("collection-field-mismatch"),
    category = "核心解析",
    entryPoint = "SafeCollectionAdapterFactory / SafeMapAdapterFactory + PrimitiveParsingPolicy.Safe",
    description = "显式开启 Safe 基础类型策略后，集合和 Map 整体错形返回空容器，单个坏 item 只被跳过，后续 item 继续解析。",
    defaultJson = """{"users":{},"tags":{},"profile":true,"scores":[{},2.5]}""",
    expected = "users/tags/profile 为空，scores 只保留 2.5。"
) { json ->
    val result = GsonSafeParser.parseSafe<CollectionResponse>(
        json = json,
        config = SafeParserConfig(primitiveParsingPolicy = PrimitiveParsingPolicy.Safe)
    )
    val value = result.value
    val pass = value?.users == emptyList<User>() &&
            value.tags == emptySet<String>() &&
            value.profile == emptyMap<String, String>() &&
            value.scores == listOf(2.5) &&
            result.events.hasTypeMismatch(path = "$.users") &&
            result.events.hasTypeMismatch(path = "$.tags") &&
            result.events.hasTypeMismatch(path = "$.profile") &&
            result.events.any { event -> event.typeMismatchDetail()?.kind?.name == "LIST_ITEM" }
    demoResult(pass, value, result.events, result.contractReport().toMarkdown(), "scores=[2.5]，坏 item 被跳过")
}

/**
 * 显式开启 JSON 形态转换后，对象字段可以从数组第 1 个对象恢复，集合字段可以把单对象包装成列表。
 */
internal fun shapeCoercionCase(): DemoCase = DemoCase(
    title = "JSON 形态转换显式开启",
    capabilityIds = setOf("shape-coercion"),
    category = "核心解析",
    entryPoint = "SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)",
    description = "后端把对象字段 data 返回成数组、集合字段 users 返回成对象时，默认不转换；显式开启后按策略恢复。",
    defaultJson = """{"code":200,"data":[{"id":9,"name":"Tom"},{"id":10,"name":"Jerry"}]}""",
    expected = "默认配置 data=null；显式开启后 data=User(id=9,name=Tom)，users 可包装成单元素列表，并产生 ShapeCoercion 事件。"
) { json ->
    val collectionJson = """{"users":{"id":12,"name":"Ana"}}"""
    val defaultValue = GsonSafeParser.fromJson(json, NullableUserEnvelope::class.java)
    val result = GsonSafeParser.parseSafe<NullableUserEnvelope>(
        json = json,
        config = SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
    )
    val collectionResult = GsonSafeParser.parseSafe<CollectionResponse>(
        json = collectionJson,
        config = SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
    )
    val allEvents = result.events + collectionResult.events
    val actions = allEvents.filterIsInstance<SafeParserEvent.ShapeCoercion>().map { event ->
        event.detail.action
    }
    val pass = defaultValue?.data == null &&
            result.value?.data == User(id = 9L, name = "Tom") &&
            collectionResult.value?.users == listOf(User(id = 12L, name = "Ana")) &&
            ShapeCoercionAction.ObjectFromFirstArrayItem in actions &&
            ShapeCoercionAction.CollectionFromSingleObject in actions &&
            ShapeCoercionAction.ArrayExtraItemsSkipped in actions
    DemoRunResult(
        pass = pass,
        actual = """
            default=${pretty(defaultValue)}
            enabled=${pretty(result.value)}
            collection=${pretty(collectionResult.value)}
        """.trimIndent(),
        expected = "默认关闭不转换；开启后对象字段取数组第 1 个对象，集合字段把单对象包装成列表",
        events = allEvents.describeEvents(),
        contractReport = SafeParseResult(Unit, allEvents).contractReport().toMarkdown(),
        previewOutput = "default.data=${defaultValue?.data}; enabled.data=${result.value?.data}; users=${collectionResult.value?.users}"
    )
}

/**
 * 根对象错形和 JSON null 字段边界。
 */
internal fun rootShapeAndNullFieldBoundaryCase(): DemoCase = DemoCase(
    title = "根对象错形与 null 字段边界",
    category = "核心解析",
    entryPoint = "SafeReflectiveAdapterFactory root mismatch / field null fallback",
    description = "根对象本身错形时返回 null；对象字段、集合字段、Map 字段收到 null 时保留或转换为安全默认值。",
    defaultJson = """{"title":null,"child":null,"users":null,"profile":null}""",
    expected = "根 Child 传 [] 返回 null；DefaultsResponse 内部字段保留构造默认值。"
) { json ->
    val events = mutableListOf<SafeParserEvent>()
    val gson = GsonSafeParser.create(SafeParserConfig(onEvent = events::add))
    val rootValue = gson.fromJson("[]", DefaultsChild::class.java)
    val fieldValue = gson.fromJson(json, DefaultsResponse::class.java)
    val pass = rootValue == null &&
            fieldValue == DefaultsResponse(
        title = "local",
        child = DefaultsChild("local-child"),
        users = listOf("local-user"),
        profile = mapOf("local" to "profile")
    ) &&
            events.hasTypeMismatch(path = "$")
    DemoRunResult(
        pass = pass,
        actual = "root=$rootValue\nfieldValue=${pretty(fieldValue)}",
        expected = "root=null，字段默认值不被 null 打散",
        events = events.describeEvents(),
        contractReport = events.toSafeResultReport()
    )
}

/**
 * 标量字符串兼容和非法值兜底。
 */
internal fun scalarStringFallbackCase(): DemoCase = DemoCase(
    title = "标量字符串兼容与非法值兜底",
    category = "核心解析",
    entryPoint = "SafePrimitiveAdapters scalar coercion",
    description = "非法数字/布尔字符串不覆盖对象构造默认值；空数字字符串使用 0 兜底。",
    defaultJson = """{"count":"abc","enabled":"not_boolean","intValue":"","decimal":""}""",
    expected = "count=6、enabled=true 保留默认值；intValue=0、decimal=0。"
) { json ->
    val result = GsonSafeParser.parseSafe<ScalarDefaultsResponse>(
        json,
        SafeParserConfig(
            fallbackPolicy = FallbackPolicy.Default,
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
        )
    )
    val pass = result.value == ScalarDefaultsResponse(
        count = 6,
        enabled = true,
        intValue = 0,
        decimal = BigDecimal.ZERO
    )
    demoResult(pass, result.value, result.events, result.contractReport().toMarkdown(), "非法值保留默认，空数字转 0")
}

/**
 * 集合和 Map 的运行时类型契约。
 */
internal fun containerRuntimeContractCase(): DemoCase = DemoCase(
    title = "集合 Map 运行时类型契约",
    category = "核心解析",
    entryPoint = "SafeObjectConstructor collection/map default implementations",
    description = "显式开启 Safe 基础类型策略后，验证具体集合、自定义集合、ConcurrentMap 和 SortedSet 都保持可赋值的运行时类型，避免 demo 只测 List/Map 接口。",
    defaultJson = """{"arrayList":["a"],"queue":["b"],"sortedSet":["c"],"numbers":["bad",1],"scores":{"ok":2,"bad":[]}}""",
    expected = "具体容器能正常赋值；自定义容器跳过坏 item；ConcurrentMap 和 SortedSet 根类型也能构造。"
) { json ->
    val concrete = GsonSafeParser.fromJsonSafe<ConcreteContainers>(
        json = json,
        config = SafeParserConfig(primitiveParsingPolicy = PrimitiveParsingPolicy.Safe)
    )
    val concurrentType = object : TypeToken<ConcurrentMap<String, Int>>() {}.type
    val concurrent = GsonSafeParser.fromJson<ConcurrentMap<String, Int>>("""{"one":1}""", concurrentType)
    val sortedType = object : TypeToken<SortedSet<String>>() {}.type
    val sorted = GsonSafeParser.fromJson<SortedSet<String>>("""["b","a"]""", sortedType)
    val pass = concrete?.arrayList == arrayListOf("a") &&
            concrete.queue.toList() == listOf("b") &&
            concrete.sortedSet == TreeSet(listOf("c")) &&
            concrete.numbers == listOf(1) &&
            concrete.scores["ok"] == 2 &&
            !concrete.scores.containsKey("bad") &&
            concurrent?.get("one") == 1 &&
            sorted?.javaClass == TreeSet::class.java &&
            sorted.toList() == listOf("a", "b")
    DemoRunResult(
        pass = pass,
        actual = """
            concrete=${pretty(concrete)}
            arrayListType=${concrete?.arrayList?.javaClass?.name}
            queueType=${concrete?.queue?.javaClass?.name}
            sortedSetType=${concrete?.sortedSet?.javaClass?.name}
            concurrentType=${concurrent?.javaClass?.name}
            sortedRootType=${sorted?.javaClass?.name}
        """.trimIndent(),
        expected = "容器运行时类型保持可赋值，坏 item 不影响后续数据"
    )
}
