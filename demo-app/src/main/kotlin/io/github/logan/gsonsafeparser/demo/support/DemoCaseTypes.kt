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

internal const val DEFAULT_CONTRACT_REPORT_TEXT = "未生成契约报告"
internal const val DEFAULT_OBSERVER_REPORT_TEXT = "未生成观察者失败报告"
internal const val DEFAULT_DIAGNOSTICS_TEXT = "未运行诊断"

/**
 * demo 页面里的单个可运行用例。
 *
 * @property title 下拉列表和详情标题。
 * @property capabilityIds 本用例覆盖的稳定能力 ID，用来和文档矩阵对齐；不要用可展示标题当测试边界。
 * @property category 功能分类，方便用户理解这条用例属于核心解析、配置还是 Retrofit。
 * @property entryPoint 本用例覆盖的公开入口或核心能力。
 * @property description 给用户看的用例说明。
 * @property defaultJson 点击“恢复默认 JSON”时填回输入框的内容。
 * @property expected 人话版预期结果。
 * @property runner 真正执行解析的代码。
 */
data class DemoCase(
    val title: String,
    val capabilityIds: Set<String> = emptySet(),
    val category: String,
    val entryPoint: String,
    val description: String,
    val defaultJson: String,
    val expected: String,
    val runner: (String) -> DemoRunResult
) {
    /**
     * 安全运行用例。
     *
     * 即使用例代码抛异常，页面也会展示异常，而不是让 demo app 崩溃。
     */
    fun run(json: String): DemoRunResult {
        return runCatching { runner(json) }.getOrElse { error ->
            DemoRunResult(
                pass = false,
                actual = "运行异常：${error.javaClass.name}\n${error.message.orEmpty()}",
                expected = expected,
                error = error.stackTraceToString()
            )
        }
    }
}

/**
 * demo 用例运行结果。
 *
 * 页面会按这些字段分块展示，便于对照“输入 JSON、简洁预览、实际输出、预期、事件、报告和异常”。
 *
 * @property previewOutput 主页面上方的简洁结果预览，只保留最关键的解析结果，方便和输入 JSON 快速对照。
 */
data class DemoRunResult(
    val pass: Boolean,
    val actual: String,
    val expected: String,
    val events: String = "无事件",
    val contractReport: String = DEFAULT_CONTRACT_REPORT_TEXT,
    val observerReport: String = DEFAULT_OBSERVER_REPORT_TEXT,
    val diagnostics: String = DEFAULT_DIAGNOSTICS_TEXT,
    val error: String? = null,
    val previewOutput: String? = null
)
