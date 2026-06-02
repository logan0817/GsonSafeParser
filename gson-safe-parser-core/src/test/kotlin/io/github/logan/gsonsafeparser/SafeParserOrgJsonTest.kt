package io.github.logan.gsonsafeparser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 验证 `JSONObject` 和 `JSONArray` 的专用适配。
 *
 * org.json 类型不能走普通反射字段，否则会把内部实现细节暴露给 Gson。
 * 这里确保对象、数组、错形和写出都通过 JsonElement 桥接保持稳定。
 */
class SafeParserOrgJsonTest {
    /** 测试模型：同时包含 JSONObject 和 JSONArray 字段，覆盖 org.json 的读写桥接。 */
    data class OrgJsonResponse(
        val payload: JSONObject? = null,
        val items: JSONArray? = null
    )

    /**
     * 测试方法说明：验证“json object and array fields parse into org json types”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `json object and array fields parse into org json types`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"payload":{"id":1,"name":"Tom"},"items":[1,{"ok":true}]}""",
            OrgJsonResponse::class.java
        )

        assertEquals(1, result.payload?.getInt("id"))
        assertEquals("Tom", result.payload?.getString("name"))
        assertEquals(2, result.items?.length())
        assertEquals(1, result.items?.getInt(0))
        assertEquals(true, result.items?.getJSONObject(1)?.getBoolean("ok"))
    }

    /**
     * 测试方法说明：验证“json object and array wrong shape returns null like SafeParser”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `json object and array wrong shape returns null like SafeParser`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"payload":[],"items":{}}""",
            OrgJsonResponse::class.java
        )

        assertNull(result.payload)
        assertNull(result.items)
    }

    /**
     * 测试方法说明：验证“org json wrong shape emits type mismatch events”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `org json wrong shape emits type mismatch events`() {
        val result = GsonSafeParser.parseSafe<OrgJsonResponse>(
            json = """{"payload":[],"items":{}}""",
            config = SafeParserConfig(captureRawJsonInCallbacks = true)
        )

        val mismatches = result.events
            .filterIsInstance<SafeParserEvent.TypeMismatch>()
            .map { event -> event.detail }

        assertNull(result.value?.payload)
        assertNull(result.value?.items)
        assertEquals(listOf("$.payload", "$.items"), mismatches.map { event -> event.path })
        assertEquals(listOf("payload", "items"), mismatches.map { event -> event.fieldName })
        assertEquals(listOf("org.json.JSONObject", "org.json.JSONArray"), mismatches.map { event -> event.expectedType })
        assertEquals(
            listOf(com.google.gson.stream.JsonToken.BEGIN_ARRAY, com.google.gson.stream.JsonToken.BEGIN_OBJECT),
            mismatches.map { event -> event.actualToken }
        )
        assertEquals(listOf(false, false), mismatches.map { event -> event.rawJsonTruncated })
        mismatches.forEach { event ->
            assertEquals("""{"payload":[],"items":{}}""", event.rawJson)
        }
    }

    /**
     * 测试方法说明：验证“org json null values do not emit type mismatch events”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `org json null values do not emit type mismatch events`() {
        val result = GsonSafeParser.parseSafe<OrgJsonResponse>(
            """{"payload":null,"items":null}"""
        )

        assertNull(result.value?.payload)
        assertNull(result.value?.items)
        assertEquals(emptyList<SafeParserEvent>(), result.events)
    }

    /**
     * 测试方法说明：验证根 JSONObject 和 JSONArray 错形时也产生 `$` 路径的 TypeMismatch。
     */
    @Test
    fun `root org json wrong shape emits root type mismatch events`() {
        val objectResult = GsonSafeParser.parseSafe<JSONObject>(
            json = """[]""",
            config = SafeParserConfig(captureRawJsonInCallbacks = true)
        )
        val arrayResult = GsonSafeParser.parseSafe<JSONArray>(
            json = """{}""",
            config = SafeParserConfig(captureRawJsonInCallbacks = true)
        )

        val objectEvent = objectResult.events.single() as SafeParserEvent.TypeMismatch
        val arrayEvent = arrayResult.events.single() as SafeParserEvent.TypeMismatch
        assertNull(objectResult.value)
        assertNull(arrayResult.value)
        assertEquals("$", objectEvent.detail.path)
        assertEquals("$", arrayEvent.detail.path)
        assertNull(objectEvent.detail.fieldName)
        assertNull(arrayEvent.detail.fieldName)
        assertEquals("org.json.JSONObject", objectEvent.detail.expectedType)
        assertEquals("org.json.JSONArray", arrayEvent.detail.expectedType)
        assertEquals(com.google.gson.stream.JsonToken.BEGIN_ARRAY, objectEvent.detail.actualToken)
        assertEquals(com.google.gson.stream.JsonToken.BEGIN_OBJECT, arrayEvent.detail.actualToken)
        assertEquals("""[]""", objectEvent.detail.rawJson)
        assertEquals("""{}""", arrayEvent.detail.rawJson)
    }

    /**
     * 测试方法说明：验证“org json types write as json structures”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `org json types write as json structures`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        val json = gson.toJson(
            OrgJsonResponse(
                payload = JSONObject("""{"id":1}"""),
                items = JSONArray("""[{"name":"Tom"}]""")
            )
        )

        val roundTrip = JSONObject(json)
        assertEquals(1, roundTrip.getJSONObject("payload").getInt("id"))
        assertEquals("Tom", roundTrip.getJSONArray("items").getJSONObject(0).getString("name"))
    }

    /**
     * 测试方法说明：验证“root org json types parse and write”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `root org json types parse and write`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        val jsonObject = gson.fromJson("""{"id":1}""", JSONObject::class.java)
        val jsonArray = gson.fromJson("""[{"id":2}]""", JSONArray::class.java)

        assertEquals(1, jsonObject.getInt("id"))
        assertEquals(2, jsonArray.getJSONObject(0).getInt("id"))
        assertEquals(1, JSONObject(gson.toJson(jsonObject)).getInt("id"))
        assertEquals(2, JSONArray(gson.toJson(jsonArray)).getJSONObject(0).getInt("id"))
    }

    /**
     * 测试方法说明：验证“org json null values stay null”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `org json null values stay null`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        assertNull(gson.fromJson("null", JSONObject::class.java))
        assertNull(gson.fromJson("null", JSONArray::class.java))
        assertEquals("null", gson.toJson(null as JSONObject?))
        assertEquals("null", gson.toJson(null as JSONArray?))
    }
}
