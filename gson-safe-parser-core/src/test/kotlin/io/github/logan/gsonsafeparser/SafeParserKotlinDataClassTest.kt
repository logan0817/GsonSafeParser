package io.github.logan.gsonsafeparser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName

/**
 * 验证 Kotlin data class 默认值。
 *
 * Kotlin 构造参数和 Java 字段写入不完全一样，这里确保错形对象和缺字段都能保留 data class 默认值。
 */
class SafeParserKotlinDataClassTest {
    /** 测试模型：外层响应，`data` 默认对象用于验证 data class 默认值能保住。 */
    data class ApiResponse(val data: Profile = Profile())
    /** 测试模型：用户资料，两个默认字段分别覆盖数字和字符串场景。 */
    data class Profile(val id: Long = 0L, val name: String = "anonymous")
    /** 测试模型：枚举没有构造默认值时，外层对象仍应能解析成功。 */
    data class EnumPayload(val role: Role, val name: String = "local")
    /** 测试模型：包装含必填枚举的对象，用来验证嵌套构造占位不会泄露。 */
    data class NestedEnumPayload(val payload: EnumPayload, val traceId: String = "local")
    /** 测试模型：必填枚举被显式跳过时，不能把构造占位值泄露给业务。 */
    data class SkippedEnumPayload(@field:SafeParseSkip val role: Role, val name: String = "local")
    /** 测试模型：自引用必填参数用来验证构造兜底不会无限递归。 */
    data class RecursivePayload(val child: RecursivePayload)
    /** 测试模型：互相引用的必填参数，用来验证间接递归不会落到 Unsafe 兜底。 */
    data class IndirectRecursiveParent(val child: IndirectRecursiveChild)
    /** 测试模型：互相引用链路的另一端。 */
    data class IndirectRecursiveChild(val parent: IndirectRecursiveParent)
    /** 测试模型：主构造有业务校验，用来验证构造失败后不能绕过 constructor invariant。 */
    data class ValidatedConstructorPayload(val name: String) {
        init {
            require(name.isNotEmpty()) { "name must not be empty" }
        }
    }
    /** 测试模型：非空 String 没有默认值时，缺失或错形不能泄露构造占位值。 */
    data class RequiredStringPayload(val name: String)
    /** 测试模型：非空构造参数使用 @SerializedName 时，JSON 名称和必需参数校验都必须成立。 */
    data class RequiredSerializedNamePayload(@SerializedName("user_name") val name: String)
    /** 测试模型：非空基础类型没有默认值时，缺失或错形不能泄露构造占位值。 */
    data class RequiredPrimitivePayload(val count: Int, val enabled: Boolean)
    /** 测试模型：嵌套对象包含非空无默认字段时，父对象不能保留子对象占位值。 */
    data class NestedRequiredStringPayload(val payload: RequiredStringPayload, val traceId: String = "local")
    /** 测试模型：模拟业务接口外层 data 节点。 */
    data class FilterResponse(val data: FilterData = FilterData())
    /** 测试模型：模拟业务接口 attributes 节点。 */
    data class FilterData(val attributes: FiltersResource? = null)
    /** 测试模型：模拟后端只返回 groups，但旧业务模型还有 treatments/types 必填字段。 */
    data class FiltersResource(
        val treatments: List<String>,
        val types: List<String>,
        val groups: List<String> = emptyList()
    )
    /** 测试枚举：用于验证 Kotlin 枚举字段错形时不会拖垮整个对象。 */
    enum class Role {
        ADMIN,
        USER
    }

    private fun strictGson() = GsonSafeParser.create(
        SafeParserConfig(
            requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.Strict
        )
    )

    /**
     * 测试方法说明：验证“kotlin data class uses default values when object payload is invalid”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `kotlin data class uses default values when object payload is invalid`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"data":[]}""",
            ApiResponse::class.java
        )

        assertEquals(Profile(), result.data)
    }

    /**
     * 测试方法说明：验证“missing kotlin data class fields keep default values”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `missing kotlin data class fields keep default values`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"data":{"id":8}}""",
            ApiResponse::class.java
        )

        assertEquals(Profile(id = 8L), result.data)
    }

    /**
     * 测试方法说明：验证“kotlin data class enum without constructor default does not fail parsing”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `kotlin data class enum without constructor default does not fail parsing`() {
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                fallbackPolicy = FallbackPolicy.Default,
                primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
                useJdkUnsafe = true
            )
        )
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"role":"ADMIN","name":"Tom"}""",
            EnumPayload::class.java
        )

        assertEquals(Role.ADMIN, result.role)
        assertEquals("Tom", result.name)
    }

    /**
     * 测试方法说明：验证“kotlin data class enum without constructor default parses valid json by default”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `kotlin data class enum without constructor default parses valid json by default`() {
        val gson = GsonSafeParser.create()

        val result = gson.fromJson(
            """{"role":"USER","name":"Tom"}""",
            EnumPayload::class.java
        )

        assertEquals(Role.USER, result.role)
        assertEquals("Tom", result.name)
    }

    /**
     * 测试方法说明：验证默认配置不会因为 Kotlin 非空必填构造参数缺字段而比 Gson 更容易失败。
     */
    @Test
    fun `missing required constructor parameter delegates to native gson by default`() {
        val gson = GsonSafeParser.create()

        val result = gson.fromJson("""{"name":"Tom"}""", EnumPayload::class.java)

        assertNull(result.role)
        assertEquals("Tom", result.name)
    }

    /**
     * 测试方法说明：复现真实业务日志里的 attributes groups 返回，确认缺 treatments/types 时默认保持 Gson 兼容。
     */
    @Test
    fun `nested required constructor fields keep gson compatibility by default`() {
        val gson = GsonSafeParser.create()

        val result = gson.fromJson(
            """{"data":{"attributes":{"groups":["faq"]}}}""",
            FilterResponse::class.java
        )

        assertEquals(listOf("faq"), result.data.attributes?.groups)
        assertNull(result.data.attributes?.treatments)
        assertNull(result.data.attributes?.types)
    }

    /**
     * 测试方法说明：验证非空 enum 没有默认值且 JSON 缺字段时，不能把构造占位值静默留给业务。
     */
    @Test
    fun `missing required enum constructor parameter does not keep placeholder enum`() {
        val gson = strictGson()

        val error = assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{"name":"Tom"}""", EnumPayload::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Required enum constructor parameter was not read from JSON"))
    }

    /**
     * 测试方法说明：验证非空 enum 没有默认值且字段错形时，不能把首枚举值当成兜底业务值。
     */
    @Test
    fun `wrong shaped required enum constructor parameter does not keep placeholder enum`() {
        val gson = strictGson()

        val error = assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{"role":[],"name":"Tom"}""", EnumPayload::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Required enum constructor parameter was not read from JSON"))
    }

    /**
     * 测试方法说明：验证非空 enum 没有默认值且 JSON 显式返回 null 时，不能把构造占位值留给业务。
     */
    @Test
    fun `null required enum constructor parameter does not keep placeholder enum`() {
        val gson = strictGson()

        val error = assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{"role":null,"name":"Tom"}""", EnumPayload::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Required enum constructor parameter was not read from JSON"))
    }

    /**
     * 测试方法说明：验证非空 enum 没有默认值且枚举值未知时，不能把首枚举值当成兜底业务值。
     */
    @Test
    fun `unknown required enum constructor parameter does not keep placeholder enum`() {
        val gson = strictGson()

        val error = assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{"role":"OWNER","name":"Tom"}""", EnumPayload::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Required enum constructor parameter was not read from JSON"))
    }

    /**
     * 测试方法说明：验证必填 enum 字段被 SafeParseSkip 跳过时，构造阶段的占位值不能进入业务对象。
     */
    @Test
    fun `skipped required enum constructor parameter does not keep placeholder enum`() {
        val gson = strictGson()

        val error = assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{"role":"USER","name":"Tom"}""", SkippedEnumPayload::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Required enum constructor parameter was not read from JSON"))
    }

    /**
     * 测试方法说明：验证嵌套对象读取失败时，父对象不能保留子对象里的 enum 构造占位值。
     */
    @Test
    fun `nested required enum constructor parameter does not leak placeholder through parent fallback`() {
        val gson = strictGson()

        val error = assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{"payload":{"name":"Tom"},"traceId":"remote"}""", NestedEnumPayload::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Required constructor parameter was not read from JSON"))
    }

    /**
     * 测试方法说明：验证非空 String 没有默认值且 JSON 缺字段时，不能把空字符串占位值留给业务。
     */
    @Test
    fun `missing required string constructor parameter does not keep placeholder string`() {
        val gson = strictGson()

        val error = assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{}""", RequiredStringPayload::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Required constructor parameter was not read from JSON"))
    }

    /**
     * 测试方法说明：验证非空 String 没有默认值且字段错形时，不能把空字符串占位值留给业务。
     */
    @Test
    fun `wrong shaped required string constructor parameter does not keep placeholder string`() {
        val gson = strictGson()

        val error = assertThrows(JsonSyntaxException::class.java) {
            gson.fromJson("""{"name":[]}""", RequiredStringPayload::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Expected a string but was BEGIN_ARRAY"))
    }

    /**
     * 测试方法说明：验证非空基础类型没有默认值且 JSON 缺字段时，不能把 0 或 false 占位值留给业务。
     */
    @Test
    fun `missing required primitive constructor parameters do not keep placeholder values`() {
        val gson = strictGson()

        val error = assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{}""", RequiredPrimitivePayload::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Required constructor parameter was not read from JSON"))
    }

    /**
     * 测试方法说明：验证合法 JSON 仍能写入非空无默认构造参数，不影响正常模型解析。
     */
    @Test
    fun `required constructor parameters parse valid json`() {
        val gson = GsonSafeParser.create()

        val stringResult = gson.fromJson("""{"name":"remote"}""", RequiredStringPayload::class.java)
        val serializedNameResult = gson.fromJson(
            """{"user_name":"remote"}""",
            RequiredSerializedNamePayload::class.java
        )
        val primitiveResult = gson.fromJson(
            """{"count":7,"enabled":true}""",
            RequiredPrimitivePayload::class.java
        )

        assertEquals(RequiredStringPayload("remote"), stringResult)
        assertEquals(RequiredSerializedNamePayload("remote"), serializedNameResult)
        assertEquals(RequiredPrimitivePayload(count = 7, enabled = true), primitiveResult)
    }

    /**
     * 测试方法说明：验证嵌套对象读取失败时，父对象不能保留子对象里的构造占位值。
     */
    @Test
    fun `nested required constructor parameter does not leak placeholder object`() {
        val gson = strictGson()

        val error = assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{"payload":{},"traceId":"remote"}""", NestedRequiredStringPayload::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Required constructor parameter was not read from JSON"))
    }

    /**
     * 测试方法说明：验证“recursive required constructor parameter fails without stack overflow”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `recursive required constructor parameter fails without stack overflow`() {
        val gson = strictGson()

        val error = assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{}""", RecursivePayload::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Recursive constructor fallback"))
        assertEquals(ApiResponse(Profile(id = 8L)), gson.fromJson("""{"data":{"id":8}}""", ApiResponse::class.java))
    }

    /**
     * 测试方法说明：验证“indirect recursive constructor parameter does not fall back to unsafe”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `indirect recursive constructor parameter does not fall back to unsafe`() {
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                useJdkUnsafe = true,
                requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.Strict
            )
        )

        val error = assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{}""", IndirectRecursiveParent::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Recursive constructor fallback"))
    }

    /**
     * 测试方法说明：验证普通 Kotlin 主构造占位值失败时，合法 JSON 仍能交回 Gson 路径继续解析。
     */
    @Test
    fun `ordinary kotlin primary constructor placeholder failure delegates to gson for valid json`() {
        val gson = GsonSafeParser.create(SafeParserConfig(useJdkUnsafe = true))

        val result = gson.fromJson("""{"name":"remote"}""", ValidatedConstructorPayload::class.java)

        assertEquals(ValidatedConstructorPayload("remote"), result)
    }

    /**
     * 测试方法说明：验证默认禁用 JDK Unsafe 时，构造失败回退不能借 Gson delegate 绕过主构造校验。
     */
    @Test
    fun `ordinary kotlin primary constructor placeholder failure does not delegate through unsafe by default`() {
        val gson = strictGson()

        val error = assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{"name":"remote"}""", ValidatedConstructorPayload::class.java)
        }

        assertTrue(error.message.orEmpty().contains("Unable to create instance"))
    }
}
