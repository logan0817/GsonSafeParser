package io.github.logan.gsonsafeparser

import com.google.gson.InstanceCreator
import com.google.gson.GsonBuilder
import com.google.gson.JsonIOException
import com.google.gson.reflect.TypeToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Type
import java.util.EnumMap
import java.util.EnumSet
import java.util.SortedSet
import java.util.TreeSet
import java.util.concurrent.ConcurrentMap

/**
 * 验证 SafeParser 构造器链路行为。
 *
 * 这里覆盖 InstanceCreator、Builder 配置透传、EnumSet/EnumMap、默认集合 Map、
 * Kotlin 泛型默认值和 Unsafe 开关，防止 SafeObjectConstructor 的顺序被后续改乱。
 */
class SafeParserConstructorTest {
    /** 测试枚举：给 EnumSet 和 EnumMap 提供真实枚举类型。 */
    enum class Role {
        ADMIN,
        USER
    }

    /** 测试模型：响应外层，data 默认值用于确认错形时不被 InstanceCreator 覆盖。 */
    data class CreatorResponse(val data: CreatedByInstanceCreator = CreatedByInstanceCreator("local"))
    /** 测试模型：由 InstanceCreator 创建的对象，source 用来标记到底是哪条构造链路生效。 */
    data class CreatedByInstanceCreator(val source: String)
    /** 测试模型：验证 GsonBuilder 上注册的 InstanceCreator 也能被读取。 */
    data class BuilderCreatorResponse(val data: CreatedByInstanceCreator = CreatedByInstanceCreator("local"))
    /** 测试模型：无参构造抛出 fatal 时不能被构造链路吞掉。 */
    class FatalNoArgsConstructor {
        /** 构造期直接抛 fatal，模拟不可安全隔离的初始化失败。 */
        init {
            throw AssertionError("fatal no-args constructor failure")
        }

        /** 普通字段只用于触发反射字段收集。 */
        var value: String = "local"
    }
    /** 测试模型：同时覆盖 EnumSet 和 EnumMap 的默认构造。 */
    data class EnumContainers(
        val roles: EnumSet<Role> = EnumSet.noneOf(Role::class.java),
        val labels: EnumMap<Role, String> = EnumMap(Role::class.java)
    )

    /**
     * 测试方法说明：验证“config instance creator does not replace object field when json shape mismatches”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `config instance creator does not replace object field when json shape mismatches`() {
        val type: Type = CreatedByInstanceCreator::class.java
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                instanceCreators = mapOf(
                    type to InstanceCreator { CreatedByInstanceCreator("created") }
                )
            )
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("""{"data":[]}""", CreatorResponse::class.java)

        assertEquals(CreatedByInstanceCreator("local"), result.data)
    }

    /**
     * 测试方法说明：验证“gson builder instance creator is used by safe object constructor”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `gson builder instance creator is used by safe object constructor`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonBuilder()
            .registerTypeAdapter(
                CreatedByInstanceCreator::class.java,
                InstanceCreator { CreatedByInstanceCreator("builder") }
            )
            .enableSafeParser()
            .create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("""{"data":{}}""", BuilderCreatorResponse::class.java)

        assertEquals(CreatedByInstanceCreator("builder"), result.data)
    }

    /**
     * 测试方法说明：验证“gson builder disable unsafe is used by safe object constructor”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `gson builder disable unsafe is used by safe object constructor`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonBuilder()
            .disableJdkUnsafe()
            .enableSafeParser()
            .create()

        org.junit.jupiter.api.Assertions.assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{"value":"remote"}""", OnlyParameterizedConstructor::class.java)
        }
    }

    /**
     * 测试方法说明：验证默认兼容模式不会让无无参构造的普通 Gson 模型因为 SafeParser 接入而失败。
     */
    @Test
    fun `default config delegates constructor unavailable models to native gson`() {
        val gson = GsonSafeParser.create()

        val result = gson.fromJson("""{"value":"remote"}""", OnlyParameterizedConstructor::class.java)

        assertEquals("remote", result.value)
    }

    /**
     * 测试方法说明：验证 Strict 优先级高于 useJdkUnsafe，避免严格模式被 Gson delegate 的 Unsafe 绕过。
     */
    @Test
    fun `strict constructor policy disables native gson unsafe even when config allows unsafe`() {
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                useJdkUnsafe = true,
                requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.Strict
            )
        )

        assertThrows(JsonIOException::class.java) {
            gson.fromJson("""{"value":"remote"}""", OnlyParameterizedConstructor::class.java)
        }
    }

    /**
     * 测试方法说明：验证无参构造抛出 fatal 时直接外抛，不退到普通构造失败兜底。
     */
    @Test
    fun `no args constructor fatal failure is rethrown`() {
        val gson = GsonSafeParser.create()

        assertThrows(AssertionError::class.java) {
            gson.fromJson("""{"value":"remote"}""", FatalNoArgsConstructor::class.java)
        }
    }

    /**
     * 测试方法说明：验证“enum set keeps enum set runtime type and parses values”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `enum set keeps enum set runtime type and parses values`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"roles":["ADMIN","USER"],"labels":{"ADMIN":"owner"}}""",
            EnumContainers::class.java
        )

        assertEquals(EnumSet.of(Role.ADMIN, Role.USER), result.roles)
    }

    /**
     * 测试方法说明：验证“enum map keeps enum map runtime type and parses values”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `enum map keeps enum map runtime type and parses values`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"roles":["ADMIN"],"labels":{"ADMIN":"owner"}}""",
            EnumContainers::class.java
        )

        assertEquals("owner", result.labels[Role.ADMIN])
    }

    /**
     * 测试方法说明：验证“parameterized instance creator is not used when root object shape mismatches”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `parameterized instance creator is not used when root object shape mismatches`() {
        val listType = object : TypeToken<Box<String>>() {}.type
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                instanceCreators = mapOf(
                    listType to InstanceCreator<Box<String>> { Box("created") }
                )
            )
        )

        val result: Box<String>? = gson.fromJson("""[]""", object : TypeToken<Box<String>>() {}.type)

        assertNull(result)
    }

    /**
     * 测试方法说明：验证“default map constructors keep concurrent map contract”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `default map constructors keep concurrent map contract`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        val type = object : TypeToken<ConcurrentMap<String, Int>>() {}.type

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson<ConcurrentMap<String, Int>>("""{"one":1}""", type)

        assertEquals(1, result["one"])
    }

    /**
     * 测试方法说明：验证“default collection constructors prefer sorted set implementation”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `default collection constructors prefer sorted set implementation`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        val type = object : TypeToken<SortedSet<String>>() {}.type

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson<SortedSet<String>>("""["b","a"]""", type)

        assertEquals(TreeSet::class.java, result.javaClass)
        assertEquals(listOf("a", "b"), result.toList())
    }

    /** 测试模型：泛型容器，用来确认 Kotlin 泛型默认值构造不会丢类型。 */
    data class Box<T>(val value: T)
}
