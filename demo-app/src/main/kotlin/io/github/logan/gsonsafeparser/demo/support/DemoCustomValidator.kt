package io.github.logan.gsonsafeparser.demo.support

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
import io.github.logan.gsonsafeparser.demo.model.AnyResponse
import io.github.logan.gsonsafeparser.demo.model.ApiResponse
import io.github.logan.gsonsafeparser.demo.model.CollectionResponse
import io.github.logan.gsonsafeparser.demo.model.EnumContainerResponse
import io.github.logan.gsonsafeparser.demo.model.MapEdgeResponse
import io.github.logan.gsonsafeparser.demo.model.MapResponse
import io.github.logan.gsonsafeparser.demo.model.NullableApiResponse
import io.github.logan.gsonsafeparser.demo.model.NullableCollectionMapResponse
import io.github.logan.gsonsafeparser.demo.model.OrgJsonResponse
import io.github.logan.gsonsafeparser.demo.model.PrimitiveResponse
import io.github.logan.gsonsafeparser.demo.model.User
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
 * 用户自定义 JSON 验证器。
 *
 * 固定 demo 用例更适合验证仓库功能是否完整；这里更适合用户把自己接口返回的 JSON 粘进来，
 * 再选择一个接近自己业务 Bean 的目标类型，看 SafeParser 和原生 Gson 分别会怎么处理。
 */
object DemoCustomValidator {
    /**
     * 用户 JSON 可以选择的验证入口。
     *
     * Core 入口适合普通 `GsonSafeParser.fromJson` 接入；Retrofit 入口会真正走 `GsonSafeConverterFactory`，
     * 适合验证项目通过 Retrofit 接口收到同样响应时是否符合预期。
     */
    val entries: List<DemoCustomEntry> = listOf(
        DemoCustomEntry(
            title = "Core fromJson",
            description = "直接调用 GsonSafeParser.fromJson，适合验证普通 Gson 接入。",
            safeParse = { json, type, config ->
                runCatching { GsonSafeParser.fromJson<Any?>(json, type, config) }
            }
        ),
        DemoCustomEntry(
            title = "Retrofit Converter",
            description = "通过 GsonSafeConverterFactory 模拟 Retrofit 响应体转换，适合验证 Retrofit 接入。",
            safeParse = { json, type, config ->
                convertWithRetrofit(bodyText = json, type = type, config = config)
            }
        )
    )

    /**
     * 页面可选择的目标类型。
     *
     * Android demo 不能在运行时根据用户输入临时生成任意业务类，所以这里提供一组常见接口结构。
     * 用户可以挑最接近自己接口的数据形状来验证：对象壳、可空对象壳、列表、集合/Map、基础类型和 Any。
     */
    val targets: List<DemoCustomTarget> = listOf(
        DemoCustomTarget(
            title = "ApiResponse<User>",
            description = "常见接口壳：code + 非空 data 对象，适合验证 data 返回 []、{}、null 等情况。",
            sampleJson = """{"code":200,"data":[]}""",
            type = ApiResponse::class.java
        ),
        DemoCustomTarget(
            title = "NullableApiResponse<User>",
            description = "data 可空的接口壳，适合验证 NullOnly 或低误伤策略下的表现。",
            sampleJson = """{"code":200,"data":[]}""",
            type = NullableApiResponse::class.java
        ),
        DemoCustomTarget(
            title = "List<User>",
            description = "根节点就是用户列表，适合验证坏 item 是否会被跳过。",
            sampleJson = """[{"id":1,"name":"A"},[],{"id":2,"name":"B"}]""",
            type = object : TypeToken<List<User>>() {}.type
        ),
        DemoCustomTarget(
            title = "CollectionResponse",
            description = "同时包含 List、Set、Map 和数字列表，适合验证集合整体错形和 item 跳过。",
            sampleJson = """{"users":{},"tags":{},"profile":true,"scores":[{},2.5]}""",
            type = CollectionResponse::class.java
        ),
        DemoCustomTarget(
            title = "NullableCollectionMapResponse",
            description = "集合和 Map 都允许为 null，适合验证 NullOnly 策略下是否比默认空容器更符合业务预期。",
            sampleJson = """{"users":{},"profile":false}""",
            type = NullableCollectionMapResponse::class.java
        ),
        DemoCustomTarget(
            title = "PrimitiveResponse",
            description = "包含 Int、Long、BigDecimal、Boolean 和 String，适合验证基础类型错形。",
            sampleJson = """{"count":{},"total":[],"price":{},"enabled":[],"title":{}}""",
            type = PrimitiveResponse::class.java
        ),
        DemoCustomTarget(
            title = "MapResponse",
            description = "包含对象字段和强类型 Map，适合验证 Map key/value 解析和局部跳过。",
            sampleJson = """{"code":200,"data":[],"profile":{"1":{"id":1,"name":"A"},"bad":[]}}""",
            type = MapResponse::class.java
        ),
        DemoCustomTarget(
            title = "MapEdgeResponse",
            description = "包含 Int key Map 和嵌套 Map，适合验证坏 key 跳过、mapItemKey 事件和数组 entry 形式。",
            sampleJson = """{"values":{"abc":"bad","1":"ok"},"nested":{"inner":[[2,"two"]]}}""",
            type = MapEdgeResponse::class.java
        ),
        DemoCustomTarget(
            title = "AnyResponse",
            description = "包含 Any 和 Map<String, Any>，适合观察 Object 数字策略和动态字段。",
            sampleJson = """{"small":1,"large":9007199254740993,"decimal":1.5,"values":{"ok":true}}""",
            type = AnyResponse::class.java
        ),
        DemoCustomTarget(
            title = "EnumContainerResponse",
            description = "同时包含 EnumSet 和 EnumMap，适合验证枚举容器是否能在真实 App 里正常构造。",
            sampleJson = """{"roles":["ADMIN","USER"],"labels":{"ADMIN":"owner"}}""",
            type = EnumContainerResponse::class.java
        ),
        DemoCustomTarget(
            title = "OrgJsonResponse",
            description = "包含 JSONObject 和 JSONArray，适合验证 org.json 类型适配。",
            sampleJson = """{"payload":{"name":"A"},"items":[1,2,3]}""",
            type = OrgJsonResponse::class.java
        )
    )

    /**
     * 页面可选择的解析策略。
     *
     * 三个策略覆盖真实接入时最常用的判断：默认契约优先是否符合预期、低误伤是否更保守、调试时事件里能不能拿到 rawJson。
     */
    val policies: List<DemoCustomPolicy> = listOf(
        DemoCustomPolicy(
            title = "默认契约优先",
            description = "使用 SafeParser 1.0.0 默认配置，适合观察错配证据和默认返回边界。",
            configFactory = { events -> SafeParserConfig(onEvent = events::add) }
        ),
        DemoCustomPolicy(
            title = "低误伤 NullOnly",
            description = "字段错形尽量给 null，基础类型交回 Gson，更接近原生 Gson。",
            configFactory = { events ->
                SafeParserConfig.lowInterference(
                    observerPolicy = SafeObserverPolicy(onEvent = events::add)
                )
            }
        ),
        DemoCustomPolicy(
            title = "调试 rawJson",
            description = "开启 rawJson 捕获，适合联调时把后端原始返回和错配事件一起带出来。",
            configFactory = { events ->
                SafeParserConfig.debug(
                    observerPolicy = SafeObserverPolicy(onEvent = events::add)
                )
            }
        ),
        DemoCustomPolicy(
            title = "基础类型交回 Gson",
            description = "基础类型完全交回 Gson 原生 Adapter，适合验证低误伤接入时哪些异常会重新暴露出来。",
            configFactory = { events ->
                SafeParserConfig(
                    primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson,
                    onEvent = events::add
                )
            }
        ),
        DemoCustomPolicy(
            title = "Object 数字 Long/Double",
            description = "Any/Object 数字使用 Gson 的 LONG_OR_DOUBLE 策略，适合验证动态字段类型是否符合项目预期。",
            configFactory = { events ->
                SafeParserConfig(
                    objectToNumberStrategy = ToNumberPolicy.LONG_OR_DOUBLE,
                    onEvent = events::add
                )
            }
        ),
        DemoCustomPolicy(
            title = "rawJson 10 字节截断",
            description = "开启 rawJson 捕获但把上限降到 10 字节，适合验证日志截断和 rawJsonTruncated 标记。",
            configFactory = { events ->
                SafeParserConfig(
                    captureRawJsonInCallbacks = true,
                    maxRawJsonCaptureBytes = 10,
                    onEvent = events::add
                )
            }
        )
    )

    /**
     * 使用用户输入 JSON 做一次验证。
     *
     * @param json 用户在页面输入框里粘贴的 JSON。
     * @param targetIndex 目标类型下拉框位置。
     * @param policyIndex 解析策略下拉框位置。
     * @return 可直接渲染到 demo 页面上的结果。
     */
    fun validate(json: String, targetIndex: Int, policyIndex: Int, entryIndex: Int = 0): DemoRunResult {
        val target = targets.getOrElse(targetIndex) { targets.first() }
        val policy = policies.getOrElse(policyIndex) { policies.first() }
        val entry = entries.getOrElse(entryIndex) { entries.first() }
        val events = mutableListOf<SafeParserEvent>()
        val config = policy.configFactory(events)
        val safeResult = entry.safeParse(json, target.type, config)
        val nativeResult = runCatching {
            nativeGson.fromJson<Any?>(json, target.type)
        }
        val safeSuccess = safeResult.isSuccess
        val nativeSuccess = nativeResult.isSuccess
        val actual = buildActualText(entry, target, policy, safeResult, nativeResult, events)
        val previewOutput = safeResult.fold(
            onSuccess = ::pretty,
            onFailure = { error ->
                buildString {
                    appendLine("SafeParser 解析失败")
                    appendLine("异常类型：${error.javaClass.name}")
                    appendLine("异常信息：${error.message.orEmpty()}")
                }.trimEnd()
            }
        )
        val error = listOfNotNull(
            safeResult.exceptionOrNull()?.stackTraceToString()?.let { "SafeParser 异常：\n$it" },
            nativeResult.exceptionOrNull()?.stackTraceToString()?.let { "原生 Gson 异常：\n$it" }
        ).joinToString(separator = "\n\n").ifBlank { null }

        return DemoRunResult(
            pass = safeSuccess,
            actual = actual,
            expected = "SafeParser 解析成功代表这类 JSON 可以被当前配置兜底；如果原生 Gson 失败但 SafeParser 成功，说明仓库对该接口有实际接入价值。",
            events = events.describeEvents(),
            contractReport = SafeParseResult(safeResult.getOrNull(), events).contractReport().toDemoContractReport(),
            error = error,
            previewOutput = previewOutput
        )
    }

    private fun buildActualText(
        entry: DemoCustomEntry,
        target: DemoCustomTarget,
        policy: DemoCustomPolicy,
        safeResult: Result<Any?>,
        nativeResult: Result<Any?>,
        events: List<SafeParserEvent>
    ): String {
        val safeText = resultText("SafeParser", safeResult)
        val nativeText = resultText("原生 Gson", nativeResult)
        val advice = adviceText(safeResult.isSuccess, nativeResult.isSuccess, events)
        return buildString {
            appendSection("问题描述：", "这次验证会把同一份 JSON 分别交给 SafeParser 和原生 Gson，用来判断当前接口返回是否适合接入 GsonSafeParser。")
            appendSection(
                "当前选择：",
                buildString {
                    appendLine("验证入口：${entry.title}")
                    appendLine("入口说明：${entry.description}")
                    appendLine()
                    appendLine("目标类型：${target.title}")
                    appendLine("目标说明：${target.description}")
                    appendLine()
                    appendLine("解析策略：${policy.title}")
                    appendLine("策略说明：${policy.description}")
                }.trimEnd()
            )
            appendSection("SafeParser 结果：", safeText)
            appendSection("原生 Gson 对比：", nativeText)
            appendSection("接入建议：", advice)
        }.trimEnd()
    }

    private fun resultText(name: String, result: Result<Any?>): String {
        val value = result.getOrNull()
        val error = result.exceptionOrNull()
        return if (error == null) {
            "$name 解析成功\n格式化结果：\n${pretty(value)}"
        } else {
            "$name 解析失败\n异常类型：${error.javaClass.name}\n异常信息：${error.message.orEmpty()}"
        }
    }

    private fun adviceText(safeSuccess: Boolean, nativeSuccess: Boolean, events: List<SafeParserEvent>): String {
        return when {
            safeSuccess && !nativeSuccess -> "原生 Gson 会失败，SafeParser 可以继续解析；重点看事件流里的字段路径，确认兜底值是否符合业务预期。"
            safeSuccess && nativeSuccess && events.isNotEmpty() -> "两边都能返回结果，但 SafeParser 发现了契约问题；建议先在测试环境收集事件，再决定是否线上接入。"
            safeSuccess && nativeSuccess -> "当前 JSON 与目标类型匹配度较高，SafeParser 和原生 Gson 表现接近，可以继续用更异常的 JSON 做验证。"
            else -> "SafeParser 也未能解析成功，当前场景应回归 Gson 默认失败策略；建议修正后端契约，或为该字段提供自定义 TypeAdapter。"
        }
    }

    private val nativeGson: Gson = Gson()

    /**
     * 给自定义验证输出追加一个分区。
     *
     * 这里不用 Kotlin 三引号模板，因为 body 里经常带格式化 JSON。
     * 如果模板缩进和 JSON 缩进混在一起，复制结果时就会出现每行前面一大段空格。
     */
    private fun StringBuilder.appendSection(title: String, body: String) {
        if (isNotEmpty()) appendLine().appendLine()
        appendLine(title)
        appendLine(body.trimStart())
    }
}

/**
 * 用户 JSON 验证时可选择的执行入口。
 *
 * @property title 页面展示名称。
 * @property description 入口说明，帮助用户判断自己项目更接近哪种接入方式。
 * @property safeParse 真正执行 SafeParser 解析的函数。
 */
data class DemoCustomEntry(
    val title: String,
    val description: String,
    val safeParse: (String, Type, SafeParserConfig) -> Result<Any?>
)

/**
 * 用户 JSON 验证时可选择的目标类型。
 *
 * @property title 页面展示名称。
 * @property description 帮用户判断这个类型适合验证哪类接口。
 * @property sampleJson 该类型对应的示例 JSON，可用于后续扩展“一键填入示例”。
 * @property type Gson 实际解析时使用的目标 Type。
 */
data class DemoCustomTarget(
    val title: String,
    val description: String,
    val sampleJson: String,
    val type: Type
)

/**
 * 用户 JSON 验证时可选择的解析策略。
 *
 * @property title 页面展示名称。
 * @property description 策略说明。
 * @property configFactory 根据本次事件列表创建 SafeParser 配置。
 */
data class DemoCustomPolicy(
    val title: String,
    val description: String,
    val configFactory: (MutableList<SafeParserEvent>) -> SafeParserConfig
)
