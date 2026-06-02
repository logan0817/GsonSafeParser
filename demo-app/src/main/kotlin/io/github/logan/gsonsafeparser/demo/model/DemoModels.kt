package io.github.logan.gsonsafeparser.demo.model

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

internal data class ApiResponse(val code: Int = 0, val data: User = User())
internal data class NullableApiResponse(val code: Int = 0, val data: User? = null)
internal data class User(val id: Long = 0L, val name: String = "anonymous")
internal data class PrimitiveResponse(
    val count: Int = 0,
    val total: Long = 0L,
    val price: BigDecimal = BigDecimal.ZERO,
    val enabled: Boolean = false,
    val title: String? = null
)
internal data class CollectionResponse(
    val users: List<User> = emptyList(),
    val tags: Set<String> = emptySet(),
    val profile: Map<String, String> = emptyMap(),
    val scores: List<Double> = emptyList()
)
internal data class NullableCollectionMapResponse(
    val users: List<String>? = null,
    val profile: Map<String, String>? = null
)
internal data class DefaultsResponse(
    val title: String = "local",
    val child: DefaultsChild = DefaultsChild("local-child"),
    val users: List<String> = listOf("local-user"),
    val profile: Map<String, String> = mapOf("local" to "profile")
)
internal data class DefaultsChild(val name: String = "child")
internal data class ScalarDefaultsResponse(
    val count: Int = 6,
    val enabled: Boolean = true,
    val intValue: Int = 9,
    val decimal: BigDecimal = BigDecimal("8.8")
)
internal data class MapResponse(
    val code: Int = 0,
    val data: User = User(),
    val profile: Map<Int, User> = emptyMap()
)
internal data class MapEdgeResponse(
    val values: Map<Int, String> = emptyMap(),
    val nested: Map<String, Map<Int, String>> = emptyMap()
)
internal data class ArrayEntryMapResponse(val items: Map<String, User> = emptyMap())
internal data class ComplexKey(val name: String)
internal data class ConcreteContainers(
    val arrayList: ArrayList<String> = arrayListOf(),
    val queue: ArrayDeque<String> = ArrayDeque(),
    val sortedSet: TreeSet<String> = TreeSet(),
    val numbers: IntList = IntList(),
    val scores: StringIntMap = StringIntMap()
)
internal class IntList : ArrayList<Int>()
internal class StringIntMap : LinkedHashMap<String, Int>()
internal data class AnyResponse(
    val small: Any? = null,
    val large: Any? = null,
    val decimal: Any? = null,
    val values: Map<String, Any> = emptyMap()
)
internal data class InstanceCreated(val source: String)
internal data class CreatedByInstanceCreator(val source: String)
internal data class BuilderCreatorResponse(val data: CreatedByInstanceCreator = CreatedByInstanceCreator("local"))
internal data class BuilderNamingResponse(val userName: String = "local")
internal data class BuilderExposeResponse(
    @field:Expose(deserialize = true, serialize = true)
    val visible: String = "local-visible",
    val hidden: String = "local-hidden"
)
internal data class BuilderVersionedResponse(
    @field:Since(2.0)
    val supported: String = "local-supported",
    @field:Since(3.0)
    val future: String = "local-future"
)
internal data class ReflectionBlockedResponse(val name: String = "local")
internal data class OrderedFilterBean(val name: String = "local")

/**
 * 只有带参构造的测试 Bean。
 *
 * Gson 默认可以借助 Unsafe 绕过构造方法创建对象；当 demo 显式关闭 Unsafe 时，
 * 这个类型就会稳定触发 JsonIOException，用来验证 SafeParser 是否尊重 GsonBuilder 的限制。
 */
internal class OnlyParameterizedConstructor {
    val value: String

    constructor(value: String) {
        this.value = value
    }
}

internal enum class DemoRole {
    ADMIN,
    USER
}
internal data class EnumContainerResponse(
    val roles: EnumSet<DemoRole> = EnumSet.noneOf(DemoRole::class.java),
    val labels: EnumMap<DemoRole, String> = EnumMap(DemoRole::class.java)
)
internal data class DuplicateNameResponse(
    @SerializedName("same")
    val first: String = "",
    @SerializedName("same")
    val second: String = ""
)
internal data class NamedAdapterResponse(
    @SerializedName(value = "name", alternate = ["user_name"])
    val userName: String = "local",
    @JsonAdapter(ScoreValueAdapter::class)
    val score: ScoreValue = ScoreValue()
)
internal data class ScoreValue(val count: Int = 0)
internal class ScoreValueAdapter : TypeAdapter<ScoreValue>() {
    /**
     * 写出数字分数。
     */
    override fun write(out: JsonWriter, value: ScoreValue?) {
        out.value(value?.count)
    }

    /**
     * 读取字符串并把字符串长度作为分数，方便页面直接看出字段级 Adapter 是否生效。
     */
    override fun read(reader: JsonReader): ScoreValue {
        return ScoreValue(reader.nextString().length)
    }
}
internal data class PlatformResponse(
    val title: String = "local",
    val colorStateList: ColorStateList? = null
)
internal data class OrgJsonResponse(val payload: JSONObject? = null, val items: JSONArray? = null)
internal data class BuiltInTypesResponse(
    val url: URL? = null,
    val uri: URI? = null,
    val uuid: UUID? = null,
    val date: Date? = null,
    val bitSet: BitSet? = null,
    val atomicBoolean: AtomicBoolean? = null
)
internal data class EmptyPayload(val name: String = "local")
internal data class EmptyApiResponse(val code: Int = 200, val data: EmptyPayload = EmptyPayload())
internal data class MismatchApiResponse(val data: EmptyPayload = EmptyPayload())
internal data class SimpleRequest(val name: String)
internal data class SkipFieldResponse(
    @field:SafeParseSkip
    val user: User = User(),
    val title: String = "local"
)

@SafeParseDelegateToGson
internal class NativeOnly {
    var name: String = "local"
}
