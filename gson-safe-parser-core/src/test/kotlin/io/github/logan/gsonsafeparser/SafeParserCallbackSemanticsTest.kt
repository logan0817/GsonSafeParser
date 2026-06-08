package io.github.logan.gsonsafeparser

import com.google.gson.stream.JsonToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验证 SafeParser 的异常回调语义。
 *
 * 重点不是只看有没有事件，而是要确认 `Object` 字段、List item、Map item 都能带上正确的分类、
 * 字段名、Map key 和 rawJson 信息，方便接入方把后端契约问题定位到具体字段。
 */
class SafeParserCallbackSemanticsTest {
    /** 测试模型：同时覆盖 `Object` 字段、List item 和 Map item 三种错配归因。 */
    data class Response(
        val child: Child = Child(),
        val users: List<Child> = emptyList(),
        val profile: Map<String, Child> = emptyMap()
    )

    /** 测试模型：一次响应里包含多个错配字段，用来验证事件不会互相覆盖。 */
    data class MultiErrorResponse(
        val child: ErrorChild = ErrorChild(),
        val log: Bean = Bean()
    )

    /** 测试模型：嵌套错误子对象，ext 字段会被构造成 Bean。 */
    data class ErrorChild(val ext: Bean = Bean())

    /** 测试模型：列表或 Map 里的普通子对象。 */
    data class Child(val id: Int = 0)
    /** 测试模型：默认对象，用来确认错形时仍保留本地默认值。 */
    data class Bean(val value: String = "local")
    /** 测试模型：基础类型错配回调用的响应。 */
    data class PrimitiveResponse(val count: Int = 1)

    /**
     * 测试方法说明：验证“field parse exception exposes object category and field name”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `field parse exception exposes object category and field name`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertEquals(Response(), gson.fromJson("""{"child":[]}""", Response::class.java))

        val event = events.single { it.fieldName == "child" }
        assertEquals(ParseExceptionKind.OBJECT, event.kind)
        assertEquals(JsonToken.BEGIN_ARRAY, event.actualToken)
    }

    /**
     * 测试方法说明：验证“list item parse exception exposes list item category and field name”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `list item parse exception exposes list item category and field name`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertEquals(Response(), gson.fromJson("""{"users":[[]]}""", Response::class.java))

        val event = events.single { it.kind == ParseExceptionKind.LIST_ITEM }
        assertEquals("users", event.fieldName)
        assertEquals(JsonToken.BEGIN_ARRAY, event.actualToken)
    }

    /**
     * 测试方法说明：验证“map item parse exception exposes map item category field name and explicit hashed key”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `map item parse exception exposes map item category field name and key`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                mapItemKeyPolicy = MapItemKeyPolicy.Hash,
                onTypeMismatch = events::add
            )
        )

        assertEquals(Response(), gson.fromJson("""{"profile":{"main":[]}}""", Response::class.java))

        val event = events.single { it.kind == ParseExceptionKind.MAP_ITEM }
        assertEquals("profile", event.fieldName)
        assertTrue(event.mapItemKey?.startsWith("sha256:") == true)
        assertTrue(event.mapItemKey != "main")
        assertEquals(JsonToken.BEGIN_ARRAY, event.actualToken)
        assertTrue(event.path.contains("profile"))
    }

    /**
     * 测试方法说明：验证“multiple object parse exceptions keep their own field names”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `multiple object parse exceptions keep their own field names`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertEquals(
            MultiErrorResponse(),
            gson.fromJson("""{"child":{"ext":[]},"log":[]}""", MultiErrorResponse::class.java)
        )

        val objectEvents = events.filter { it.kind == ParseExceptionKind.OBJECT }
        assertEquals(listOf("ext", "log"), objectEvents.map { it.fieldName })
    }

    /**
     * 测试方法说明：验证“parser helper attaches raw json to mismatch callback when configured”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `parser helper attaches raw json to mismatch callback when configured`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        val rawJson = """{"child":[]}"""

        GsonSafeParser.fromJson(
            json = rawJson,
            type = Response::class.java,
            config = SafeParserConfig(
                captureRawJsonInCallbacks = true,
                onTypeMismatch = events::add
            )
        )

        assertEquals(rawJson, events.single().rawJson)
    }

    /**
     * 测试方法说明：验证“primitive mismatch callback also attaches raw json when configured”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `primitive mismatch callback also attaches raw json when configured`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        val rawJson = """{"count":[]}"""

        GsonSafeParser.fromJson(
            json = rawJson,
            type = PrimitiveResponse::class.java,
            config = SafeParserConfig(
                captureRawJsonInCallbacks = true,
                onTypeMismatch = events::add
            )
        )

        assertEquals(rawJson, events.single().rawJson)
    }

    /**
     * 测试方法说明：验证“parser helper truncates raw json in callback when configured”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `parser helper truncates raw json in callback when configured`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<TypeMismatchEvent>()
        val rawJson = """{"count":[],"extra":"large"}"""

        GsonSafeParser.fromJson(
            json = rawJson,
            type = PrimitiveResponse::class.java,
            config = SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 10,
                onTypeMismatch = events::add
            )
        )

        assertEquals(rawJson.take(10), events.single().rawJson)
        assertTrue(events.single().rawJsonTruncated)
    }

    /**
     * 测试方法说明：验证普通 Gson 入口的 rawJson 上限按 UTF-8 字节计算，避免中文等非 ASCII 文本突破字节预算。
     */
    @Test
    fun `parser helper truncates raw json by utf8 byte limit without broken characters`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val rawJson = """{"count":[],"label":"中文"}"""

        GsonSafeParser.fromJson(
            json = rawJson,
            type = PrimitiveResponse::class.java,
            config = SafeParserConfig(
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = rawJson.indexOf("中") + 2,
                onTypeMismatch = events::add
            )
        )

        val event = events.single()
        assertEquals("{\"count\":[],\"label\":\"", event.rawJson)
        assertTrue(event.rawJsonTruncated)
    }
}
