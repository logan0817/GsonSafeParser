package io.github.logan.gsonsafeparser

import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * 验证 Object/Any 数值策略。
 *
 * SafeParser 会把 `Any` 里的整数优先转成 Int，再转 Long，超过 Long 范围时保留 BigInteger，小数转 Double。
 * 这个策略影响日志、Map 和动态字段，不能被普通 Double 策略悄悄覆盖。
 */
class SafeParserObjectNumberStrategyTest {
    /** 测试模型：Any 字段和 Map<String, Any> 都要使用同一套数字策略。 */
    data class AnyResponse(
        val small: Any? = null,
        val large: Any? = null,
        val decimal: Any? = null,
        val values: Map<String, Any> = emptyMap()
    )

    /**
     * 测试方法说明：验证“object number strategy converts integers and decimals like SafeParser”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `object number strategy converts integers and decimals like SafeParser`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"small":1,"large":2147483648,"decimal":1.5,"values":{"count":2,"total":2147483649,"negative":-9999999999,"ratio":2.5}}""",
            AnyResponse::class.java
        )

        assertInstanceOf(Int::class.javaObjectType, result.small)
        assertEquals(1, result.small)
        assertInstanceOf(Long::class.javaObjectType, result.large)
        assertEquals(2147483648L, result.large)
        assertInstanceOf(Double::class.javaObjectType, result.decimal)
        assertEquals(1.5, result.decimal)
        assertInstanceOf(Int::class.javaObjectType, result.values["count"])
        assertEquals(2, result.values["count"])
        assertInstanceOf(Long::class.javaObjectType, result.values["total"])
        assertEquals(2147483649L, result.values["total"])
        assertInstanceOf(Long::class.javaObjectType, result.values["negative"])
        assertEquals(-9999999999L, result.values["negative"])
        assertInstanceOf(Double::class.javaObjectType, result.values["ratio"])
        assertEquals(2.5, result.values["ratio"])
    }

    /**
     * 测试方法说明：验证“negative integers below Int.MIN_VALUE stay Long”这个具体行为。
     * 这个用例专门防止 BigDecimal.toInt() 对超大负数截断溢出。
     */
    @Test
    fun `negative integers below int min value stay long`() {
        val gson = GsonSafeParser.create()

        val result = gson.fromJson("""{"large":-2147483649,"values":{"negative":-9999999999}}""", AnyResponse::class.java)

        assertInstanceOf(Long::class.javaObjectType, result.large)
        assertEquals(-2147483649L, result.large)
        assertInstanceOf(Long::class.javaObjectType, result.values["negative"])
        assertEquals(-9999999999L, result.values["negative"])
    }

    /**
     * 测试方法说明：验证“integers outside Long range stay BigInteger”这个具体行为。
     * 这个用例防止动态字段里的超大整数被 BigDecimal.toLong() 截断成错误 Long。
     */
    @Test
    fun `integers outside long range stay big integer`() {
        val gson = GsonSafeParser.create()

        val result = gson.fromJson(
            """{"large":9223372036854775808,"values":{"huge":9999999999999999999999999999}}""",
            AnyResponse::class.java
        )

        assertInstanceOf(BigInteger::class.java, result.large)
        assertEquals(BigInteger("9223372036854775808"), result.large)
        assertInstanceOf(BigInteger::class.java, result.values["huge"])
        assertEquals(BigInteger("9999999999999999999999999999"), result.values["huge"])
    }

    /**
     * 测试方法说明：验证“Long boundaries stay Long and negative overflow stays BigInteger”这个具体行为。
     * 这个用例锁住 Long 上下边界，避免后续边界比较写错导致 Any 数字类型漂移。
     */
    @Test
    fun `long boundaries stay long and negative overflow stays big integer`() {
        val gson = GsonSafeParser.create()

        val result = gson.fromJson(
            """{"large":9223372036854775807,"values":{"min":-9223372036854775808,"belowMin":-9223372036854775809}}""",
            AnyResponse::class.java
        )

        assertInstanceOf(Long::class.javaObjectType, result.large)
        assertEquals(Long.MAX_VALUE, result.large)
        assertInstanceOf(Long::class.javaObjectType, result.values["min"])
        assertEquals(Long.MIN_VALUE, result.values["min"])
        assertInstanceOf(BigInteger::class.java, result.values["belowMin"])
        assertEquals(BigInteger("-9223372036854775809"), result.values["belowMin"])
    }

    /**
     * 测试方法说明：验证“custom object number strategy can override default auto strategy”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `custom object number strategy can override default auto strategy`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(objectToNumberStrategy = ToNumberPolicy.LONG_OR_DOUBLE)
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("""{"small":1}""", AnyResponse::class.java)

        assertInstanceOf(Long::class.javaObjectType, result.small)
        assertEquals(1L, result.small)
    }

    /**
     * 测试方法说明：验证“root object number uses auto strategy”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `root object number uses auto strategy`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        val result: Any = gson.fromJson("1", Any::class.java)

        assertInstanceOf(Int::class.javaObjectType, result)
        assertEquals(1, result)
    }

    /**
     * 测试方法说明：验证“list object numbers use auto strategy”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `list object numbers use auto strategy`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        val result: List<Any> = gson.fromJson("[1,2147483648,1.5]", object : TypeToken<List<Any>>() {}.type)

        assertInstanceOf(Int::class.javaObjectType, result[0])
        assertEquals(1, result[0])
        assertInstanceOf(Long::class.javaObjectType, result[1])
        assertEquals(2147483648L, result[1])
        assertInstanceOf(Double::class.javaObjectType, result[2])
        assertEquals(1.5, result[2])
    }

    /**
     * 测试方法说明：验证“object decimal overflow stays finite and exact”这个具体行为。
     * 动态 Any 字段不能把超大 JSON 小数静默转成 Infinity。
     */
    @Test
    fun `object decimal overflow stays finite and exact`() {
        val gson = GsonSafeParser.create()
        val hugeDecimal = "1".repeat(400) + ".1"

        val result = gson.fromJson(
            """{"decimal":$hugeDecimal,"values":{"huge":$hugeDecimal}}""",
            AnyResponse::class.java
        )

        assertInstanceOf(BigDecimal::class.java, result.decimal)
        assertEquals(BigDecimal(hugeDecimal), result.decimal)
        assertInstanceOf(BigDecimal::class.java, result.values["huge"])
        assertEquals(BigDecimal(hugeDecimal), result.values["huge"])
    }
}
