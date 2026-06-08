package io.github.logan.gsonsafeparser

import java.math.BigDecimal
import java.math.BigInteger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 验证基础类型错形兜底。
 *
 * 数字、布尔、字符串字段是接口里最常见的基础字段，后端返回对象或数组时，
 * SafeParser 只能兜底当前字段，不能让整个 Bean 解析失败。
 */
class SafeParserPrimitiveMismatchTest {
    /** 测试模型：覆盖 Int、Long、BigDecimal、Boolean、String 五类常见基础字段。 */
    data class ApiResponse(
        val count: Int = 0,
        val total: Long = 0L,
        val price: BigDecimal = BigDecimal.ZERO,
        val enabled: Boolean = false,
        val title: String? = null
    )

    /** 测试模型：数字字符串和空字符串数字的兼容读取。 */
    data class NumericPayload(
        val count: Int = 1,
        val total: Long = 2L,
        val price: BigDecimal = BigDecimal("3.5")
    )

    /** 测试模型：Float 和 Double 默认值不能被非法字符串覆盖。 */
    data class FloatingDefaults(
        val ratio: Float = 1.5f,
        val amount: Double = 2.5
    )

    /** 测试模型：整数字段越界或收到小数时必须保留构造默认值。 */
    data class IntegerBoundaries(
        val count: Int = 7,
        val total: Long = 9L,
        val small: Short = 3,
        val byteValue: Byte = 4
    )

    /** 测试模型：基础类型字段遇到结构错形时应保留业务默认值。 */
    data class NonZeroPrimitiveDefaults(
        val count: Int = 7,
        val enabled: Boolean = true
    )

    /** 测试模型：布尔数字只接受明确的 0/1 表示。 */
    data class BooleanNumberDefaults(
        val enabled: Boolean = false
    )

    /** 测试模型：敏感字段被错配成 Boolean 时，事件原因不能带出原始值。 */
    data class SensitiveBooleanPayload(
        val token: Boolean = false
    )

    /** 测试模型：BigInteger 允许大整数，但不能把小数截断成整数。 */
    data class BigIntegerPayload(
        val value: BigInteger = BigInteger("99")
    )

    private val safePrimitiveConfig = SafeParserConfig(
        fallbackPolicy = FallbackPolicy.Default,
        primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
    )

    /**
     * 测试方法说明：验证“primitive fields use safe fallback when backend sends wrong structures”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `primitive fields use safe fallback when backend sends wrong structures`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"count":{},"total":[],"price":{},"enabled":[],"title":[]}""",
            ApiResponse::class.java
        )

        assertEquals(0, result.count)
        assertEquals(0L, result.total)
        assertEquals(BigDecimal.ZERO, result.price)
        assertEquals(false, result.enabled)
        assertNull(result.title)
    }

    /**
     * 测试方法说明：验证“primitive fields keep non zero defaults when backend sends wrong structures”这个具体行为。
     * 结构错形只应该影响当前 JSON 字段读取，不能把构造出的业务默认值覆盖成 0 或 false。
     */
    @Test
    fun `primitive fields keep non zero defaults when backend sends wrong structures`() {
        val result = GsonSafeParser.fromJsonSafe<NonZeroPrimitiveDefaults>("""{"count":{},"enabled":[]}""")

        assertEquals(NonZeroPrimitiveDefaults(), result)
    }

    /**
     * 测试方法说明：验证“boolean number coercion only accepts zero and one”这个具体行为。
     * 非 0/1 数字不能静默转 true，否则接口返回 2 或 -1 时会制造错误业务状态。
     */
    @Test
    fun `boolean number coercion only accepts zero and one`() {
        val result = GsonSafeParser.parseSafe<BooleanNumberDefaults>("""{"enabled":2}""", safePrimitiveConfig)

        assertEquals(BooleanNumberDefaults(), result.value)
        val event = result.events.single() as SafeParserEvent.TypeMismatch
        assertEquals("enabled", event.detail.fieldName)
        assertEquals(com.google.gson.stream.JsonToken.NUMBER, event.detail.actualToken)
    }

    /**
     * 测试方法说明：验证“primitive fields keep gson compatible scalar coercion”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `primitive fields keep gson compatible scalar coercion`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"count":"12","total":"99","price":"3.14","enabled":"true","title":88}""",
            ApiResponse::class.java
        )

        assertEquals(12, result.count)
        assertEquals(99L, result.total)
        assertEquals(BigDecimal("3.14"), result.price)
        assertEquals(true, result.enabled)
        assertEquals("88", result.title)
    }

    /**
     * 测试方法说明：验证“number adapters serialize numbers as json numbers”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `number adapters serialize numbers as json numbers`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        val json = gson.toJson(NumericPayload())

        assertEquals("""{"count":1,"total":2,"price":3.5}""", json)
    }

    /**
     * 测试方法说明：验证“invalid floating point strings do not overwrite field defaults”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `invalid floating point strings do not overwrite field defaults`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"ratio":"bad","amount":"bad"}""",
            FloatingDefaults::class.java
        )

        assertEquals(1.5f, result.ratio)
        assertEquals(2.5, result.amount)
    }

    /**
     * 测试方法说明：验证“floating point overflow keeps defaults and emits mismatch events”这个具体行为。
     * 过大的指数数值不能静默进入业务字段成为 Infinity。
     */
    @Test
    fun `floating point overflow keeps defaults and emits mismatch events`() {
        val result = GsonSafeParser.parseSafe<FloatingDefaults>(
            """{"ratio":1e400,"amount":1e400}""",
            safePrimitiveConfig
        )

        assertEquals(FloatingDefaults(), result.value)
        val details = result.events.map { event -> (event as SafeParserEvent.TypeMismatch).detail }
        assertEquals(setOf("ratio", "amount"), details.mapNotNull { it.fieldName }.toSet())
        assertTrue(details.all { detail -> detail.reason.contains("finite") })
    }

    /**
     * 测试方法说明：验证“primitive mismatch reasons do not expose raw sensitive scalar values”这个具体行为。
     * reason 会进入事件、契约报告和 demo 展示，所以不能包含 token 原文。
     */
    @Test
    fun `primitive mismatch reason does not expose raw sensitive scalar value`() {
        val result = GsonSafeParser.parseSafe<SensitiveBooleanPayload>(
            """{"token":"secret-token"}""",
            safePrimitiveConfig
        )

        val event = result.events.single() as SafeParserEvent.TypeMismatch
        val markdown = result.contractReport().toMarkdown()

        assertEquals("token", event.detail.fieldName)
        assertFalse(event.detail.reason.contains("secret-token"))
        assertFalse(event.detail.reason.contains("Expected boolean but was secret-token"))
        assertFalse(markdown.contains("secret-token"))
        assertTrue(event.detail.reason.contains("boolean"))
    }

    /**
     * 测试方法说明：验证“integer fields keep defaults on overflow and fractional numbers”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `integer fields keep defaults on overflow and fractional numbers`() {
        val result = GsonSafeParser.parseSafe<IntegerBoundaries>(
            """{"count":2147483648,"total":9223372036854775808,"small":32768,"byteValue":128}""",
            safePrimitiveConfig
        )

        assertEquals(IntegerBoundaries(), result.value)
        assertEquals(4, result.events.size)
        val details = result.events.map { event -> (event as SafeParserEvent.TypeMismatch).detail }
        assertEquals(setOf("count", "total", "small", "byteValue"), details.mapNotNull { it.fieldName }.toSet())
        assertTrue(details.all { event -> event.kind == ParseExceptionKind.OBJECT })
        assertTrue(details.any { event -> event.reason.contains("Int range") })
        assertTrue(details.any { event -> event.reason.contains("Long range") })
        assertTrue(details.any { event -> event.reason.contains("Short range") })
        assertTrue(details.any { event -> event.reason.contains("Byte range") })
    }

    /**
     * 测试方法说明：验证“integer fields reject fractional numbers instead of truncating”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `integer fields reject fractional numbers instead of truncating`() {
        val result = GsonSafeParser.parseSafe<IntegerBoundaries>("""{"count":1.9}""", safePrimitiveConfig)

        assertEquals(IntegerBoundaries(), result.value)
        assertEquals(1, result.events.size)
        val event = result.events.single() as SafeParserEvent.TypeMismatch
        assertEquals("count", event.detail.fieldName)
        assertTrue(event.detail.reason.contains("Rounding necessary"))
    }

    /**
     * 测试方法说明：验证“big integer keeps exact large integers and rejects fractional values”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `big integer keeps exact large integers and rejects fractional values`() {
        val parsed = GsonSafeParser.fromJsonSafe<BigIntegerPayload>(
            """{"value":9223372036854775808123456789}""",
            safePrimitiveConfig
        )
        val fallback = GsonSafeParser.parseSafe<BigIntegerPayload>("""{"value":1.9}""", safePrimitiveConfig)

        assertEquals(BigInteger("9223372036854775808123456789"), parsed?.value)
        assertEquals(BigIntegerPayload(), fallback.value)
        val event = fallback.events.single() as SafeParserEvent.TypeMismatch
        assertEquals("value", event.detail.fieldName)
        assertTrue(event.detail.reason.contains("Rounding necessary"))
    }

    /**
     * 测试方法说明：验证“primitive adapters can be delegated to Gson default strategy”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `primitive adapters can be delegated to Gson default strategy`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson)
        )

        assertThrows(RuntimeException::class.java) {
            gson.fromJson("""{}""", Int::class.java)
        }
    }
}
