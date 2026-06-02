package io.github.logan.gsonsafeparser

import com.google.gson.FakeNativeDelegateType
import com.google.gson.InstanceCreator
import com.google.gson.JsonIOException
import com.google.gson.ReflectionAccessFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 验证 ReflectionAccessFilter 相关行为。
 *
 * 这组用例保证 SafeObjectConstructor 和 SafeReflectiveAdapterFactory 尊重 Gson 的反射访问控制，
 * 尤其是 BLOCK_ALL、BLOCK_INACCESSIBLE 以及多个 filter 的优先级。
 */
class SafeParserReflectionAccessFilterTest {
    /** 测试模型：只有主构造参数，用来验证 BLOCK_INACCESSIBLE 不会强行打开私有构造。 */
    data class PrimaryConstructorOnly(val name: String)

    /** 测试模型：InstanceCreator 创建出来的对象，用来验证显式创建器优先级。 */
    data class CreatedByInstanceCreator(val name: String)

    /** 测试模型：包含私有字段，验证字段访问限制不会被 SafeParser 绕过。 */
    class PrivateFieldBean {
        /** 私有字段：BLOCK_INACCESSIBLE 下不能强行 setAccessible。 */
        private var secret: String = "local"

        /** 读取私有字段的公开方法，用于断言字段值是否被解析覆盖。 */
        fun secret(): String = secret
    }

    /** 测试模型：多个 ReflectionAccessFilter 按顺序匹配时使用。 */
    data class OrderedFilterBean(val name: String = "local")

    /** 测试过滤器：禁止访问不可见成员，但允许公开成员。 */
    private val blockInaccessible = ReflectionAccessFilter {
        ReflectionAccessFilter.FilterResult.BLOCK_INACCESSIBLE
    }

    /** 测试过滤器：完全禁止该类型的反射访问。 */
    private val blockAll = ReflectionAccessFilter {
        ReflectionAccessFilter.FilterResult.BLOCK_ALL
    }

    /**
     * 测试方法说明：验证“block inaccessible does not force private constructors accessible”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `block inaccessible does not force private constructors accessible`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(reflectionAccessFilters = listOf(blockInaccessible))
        )

        assertThrows(JsonIOException::class.java) {
            gson.fromJson("{}", PublicPrivateConstructor::class.java)
        }
    }

    /**
     * 测试方法说明：验证“block all does not use kotlin primary constructor fallback”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `block all does not use kotlin primary constructor fallback`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(reflectionAccessFilters = listOf(blockAll))
        )

        assertThrows(JsonIOException::class.java) {
            gson.fromJson("{}", PrimaryConstructorOnly::class.java)
        }
    }

    /**
     * 测试方法说明：验证“instance creator still wins when reflection access is blocked”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `instance creator still wins when reflection access is blocked`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                instanceCreators = mapOf(
                    CreatedByInstanceCreator::class.java to InstanceCreator { CreatedByInstanceCreator("creator") }
                ),
                reflectionAccessFilters = listOf(blockAll)
            )
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("{}", CreatedByInstanceCreator::class.java)

        assertEquals(CreatedByInstanceCreator("creator"), result)
    }

    /**
     * 测试方法说明：验证“reflection access filters are registered on gson builder delegate chain”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `reflection access filters are registered on gson builder delegate chain`() {
        val calls = AtomicInteger()
        val countingFilter = ReflectionAccessFilter { rawType ->
            if (rawType == FakeNativeDelegateType::class.java) {
                calls.incrementAndGet()
            }
            ReflectionAccessFilter.FilterResult.INDECISIVE
        }
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(reflectionAccessFilters = listOf(countingFilter))
        )

        runCatching {
            gson.fromJson("""{"value":7}""", FakeNativeDelegateType::class.java)
        }
        assertTrue(calls.get() > 0)
    }

    /**
     * 测试方法说明：验证“block inaccessible does not force private fields accessible”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `block inaccessible does not force private fields accessible`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(reflectionAccessFilters = listOf(blockInaccessible))
        )

        assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{"secret":"remote"}""", PrivateFieldBean::class.java)
        }
    }

    /**
     * 测试方法说明：验证“safe adapter uses gson reflection filter priority order”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `safe adapter uses gson reflection filter priority order`() {
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
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(reflectionAccessFilters = listOf(blockOrderedType, allowOrderedType))
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("""{"name":"remote"}""", OrderedFilterBean::class.java)

        assertEquals(OrderedFilterBean("remote"), result)
    }
}
