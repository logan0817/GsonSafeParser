package io.github.logan.gsonsafeparser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * 验证集合错形兜底。
 *
 * 集合整体错形要返回空容器或 null，单个 item 错形只跳过当前 item。
 * 这类行为直接影响列表接口的可用性，不能让一个坏元素拖垮整棵 Bean。
 */
class SafeParserCollectionMismatchTest {
    /** 测试模型：同时覆盖 List、Set、Map 三类容器字段。 */
    data class ApiResponse(
        val users: List<User> = emptyList(),
        val tags: Set<String> = emptySet(),
        val profile: Map<String, String> = emptyMap()
    )

    /** 测试模型：列表里的用户对象，默认 id 用于判断 item 错形时是否被跳过。 */
    data class User(val id: Long = 0L)
    /** 测试模型：具体 ArrayList 字段，验证兜底值仍可赋给声明类型。 */
    data class ScoresResponse(val scores: ArrayList<Double> = arrayListOf(1.5))

    /**
     * 测试方法说明：验证“list field returns empty list when backend sends object”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `list field returns empty list when backend sends object`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"users":{},"tags":["a"],"profile":{"level":"vip"}}""",
            ApiResponse::class.java
        )

        assertEquals(emptyList<User>(), result.users)
        assertEquals(setOf("a"), result.tags)
        assertEquals("vip", result.profile["level"])
    }

    /**
     * 测试方法说明：验证“set field returns empty set when backend sends object”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `set field returns empty set when backend sends object`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"users":[{"id":1}],"tags":{},"profile":{"level":"vip"}}""",
            ApiResponse::class.java
        )

        assertEquals(1L, result.users.first().id)
        assertEquals(emptySet<String>(), result.tags)
    }

    /**
     * 测试方法说明：验证“map field returns empty map when backend sends array”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `map field returns empty map when backend sends array`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"users":[{"id":1}],"tags":["a"],"profile":[]}""",
            ApiResponse::class.java
        )

        assertEquals(1L, result.users.first().id)
        assertEquals(setOf("a"), result.tags)
        assertEquals(emptyMap<String, String>(), result.profile)
    }

    /**
     * 测试方法说明：验证“invalid collection item is skipped without failing the whole list”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `invalid collection item is skipped without failing the whole list`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(primitiveParsingPolicy = PrimitiveParsingPolicy.Safe)
        )
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"scores":["null",2.5]}""",
            ScoresResponse::class.java
        )

        assertEquals(arrayListOf(2.5), result.scores)
    }

    /**
     * 测试方法说明：验证“list field returns empty list when backend sends array string”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `list field returns empty list when backend sends array string`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"users":"[]","tags":["a"],"profile":{"level":"vip"}}""",
            ApiResponse::class.java
        )

        assertEquals(emptyList<User>(), result.users)
        assertEquals(setOf("a"), result.tags)
        assertEquals("vip", result.profile["level"])
    }
}
