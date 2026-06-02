package io.github.logan.gsonsafeparser

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 验证可复用 Parser API。
 *
 * 这组测试专门锁住高频解析场景：调用方先创建一次 Parser，后续每次解析都复用同一个 Gson 和同一份配置，
 * 避免为了便利入口反复创建 Gson。
 */
class SafeParserReusableParserTest {
    /** 测试模型：接口响应壳，data 字段用来触发 `Object` 字段错形兜底。 */
    data class ApiResponse(
        val code: Int = 0,
        val data: User = User()
    )

    /** 测试模型：业务用户对象，默认值用来判断 `Object` 字段错形后是否保留默认构造结果。 */
    data class User(
        val id: Long = 0L,
        val name: String = "anonymous"
    )

    /** 测试模型：验证调用方自定义 GsonBuilder 配置能被 Parser 包装入口保留。 */
    data class NamingResponse(
        val userName: String = "local"
    )

    /**
     * 测试方法说明：验证 `GsonSafeParser.parser(config)` 能复用配置和 Gson 实例完成多次解析。
     */
    @Test
    fun `parser created from config reuses safe gson for repeated parses`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(onEvent = events::add)

        val parser = GsonSafeParser.parser(config)
        val first = parser.fromJson("""{"code":200,"data":[]}""", ApiResponse::class.java)
        val second = parser.fromJson("""{"code":201,"data":[]}""", ApiResponse::class.java)

        assertEquals(ApiResponse(code = 200), first)
        assertEquals(ApiResponse(code = 201), second)
        assertEquals(2, events.size)
        assertTrue(events.all { event -> event is SafeParserEvent.TypeMismatch })
    }

    /**
     * 测试方法说明：验证 `GsonSafeParser.parserWithExternalGson(gson, config)` 不会丢掉调用方已有 GsonBuilder 配置。
     */
    @Test
    fun `parser can wrap caller gson without replacing it`() {
        val config = SafeParserConfig()
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .enableSafeParser(config)
            .create()

        val parser = GsonSafeParser.parserWithExternalGson(gson, config)
        val value = parser.fromJson("""{"user_name":"remote"}""", NamingResponse::class.java)

        assertSame(gson, parser.gson)
        assertSame(config, parser.config)
        assertEquals(NamingResponse(userName = "remote"), value)
    }

    /**
     * 测试方法说明：验证 builder-first 入口会在创建 Gson 前注册 Safe Adapter，避免外部 Gson 配置归属误用。
     */
    @Test
    fun `parser can create safe gson from caller builder`() {
        val events = mutableListOf<SafeParserEvent>()
        val config = SafeParserConfig(onEvent = events::add)
        val parser = GsonSafeParser.parser(
            builder = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES),
            config = config
        )

        val named = parser.fromJson("""{"user_name":"remote"}""", NamingResponse::class.java)
        val mismatch = parser.fromJson("""{"code":211,"data":[]}""", ApiResponse::class.java)

        assertEquals(NamingResponse(userName = "remote"), named)
        assertEquals(ApiResponse(code = 211), mismatch)
        assertSame(config, parser.config)
        assertEquals(1, events.size)
    }

    /**
     * 测试方法说明：验证外部 Gson 包装入口使用显式 API 名称，避免和 `parser(config)` 的配置所有权混淆。
     */
    @Test
    fun `external gson parser uses explicit external gson entry`() {
        val config = SafeParserConfig()
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .enableSafeParser(config)
            .create()

        val parser = GsonSafeParser.parserWithExternalGson(gson, config)
        val value = parser.fromJson("""{"user_name":"remote"}""", NamingResponse::class.java)

        assertSame(gson, parser.gson)
        assertSame(config, parser.config)
        assertEquals(NamingResponse(userName = "remote"), value)
    }

    /**
     * 测试方法说明：验证外部普通 Gson 不会被包装入口偷偷补注册 Safe Adapter。
     */
    @Test
    fun `external gson parser does not auto enable safe adapters`() {
        val parser = GsonSafeParser.parserWithExternalGson(
            gson = GsonBuilder().create(),
            config = SafeParserConfig()
        )

        assertThrows(JsonSyntaxException::class.java) {
            parser.fromJson("""{"code":203,"data":[]}""", ApiResponse::class.java)
        }
    }

    /**
     * 测试方法说明：验证可复用 Parser 也能使用 Kotlin reified 扩展。
     */
    @Test
    fun `parser supports kotlin reified from json helper`() {
        val events = mutableListOf<SafeParserEvent>()
        val parser = GsonSafeParser.parser(SafeParserConfig(onEvent = events::add))

        val value = parser.fromJsonSafe<ApiResponse>("""{"code":202,"data":[]}""")

        assertEquals(ApiResponse(code = 202), value)
        val event = events.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.data", event.detail.path)
    }

    /**
     * 测试方法说明：验证 `Parser.parseSafe(json, Class)` 能在复用 Parser 时返回事件快照。
     */
    @Test
    fun `parser parse safe returns event snapshot with reused gson`() {
        val parser = GsonSafeParser.parser()

        val result = parser.parseSafe("""{"code":204,"data":[]}""", ApiResponse::class.java)

        assertEquals(ApiResponse(code = 204), result.value)
        val event = result.events.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.data", event.detail.path)
        assertTrue(result.contractReport().hasIssues)
    }

    /**
     * 测试方法说明：验证 `Parser.parseSafe` 包装调用方已有 Gson 时也能收集 Safe Adapter 里的事件。
     */
    @Test
    fun `parser parse safe collects events from caller gson adapters`() {
        val externalEvents = mutableListOf<SafeParserEvent>()
        val configUsedByGson = SafeParserConfig(onEvent = externalEvents::add)
        val gson = GsonBuilder()
            .enableSafeParser(configUsedByGson)
            .create()
        val parser = GsonSafeParser.parserWithExternalGson(gson, SafeParserConfig())

        val result = parser.parseSafe<ApiResponse>("""{"code":205,"data":[]}""")

        assertEquals(ApiResponse(code = 205), result.value)
        val snapshotEvent = result.events.single() as SafeParserEvent.TypeMismatch
        val externalEvent = externalEvents.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.data", snapshotEvent.detail.path)
        assertEquals("$.data", externalEvent.detail.path)
    }

    /**
     * 测试方法说明：验证外部 Gson 字段级 Adapter 事件归属创建 Gson 时的配置，而不是包装层配置。
     */
    @Test
    fun `external gson field events belong to config used when gson was created`() {
        val eventsFromGsonConfig = mutableListOf<SafeParserEvent>()
        val eventsFromWrapperConfig = mutableListOf<SafeParserEvent>()
        val configUsedByGson = SafeParserConfig(onEvent = eventsFromGsonConfig::add)
        val wrapperConfig = SafeParserConfig(onEvent = eventsFromWrapperConfig::add)
        val gson = GsonBuilder()
            .enableSafeParser(configUsedByGson)
            .create()
        val parser = GsonSafeParser.parserWithExternalGson(gson, wrapperConfig)

        val result = parser.parseSafe<ApiResponse>("""{"code":206,"data":[]}""")

        assertEquals(ApiResponse(code = 206), result.value)
        assertEquals(1, result.events.size)
        assertEquals(1, eventsFromGsonConfig.size)
        assertTrue(eventsFromWrapperConfig.isEmpty())
    }

    /**
     * 测试方法说明：验证用户观察者抛异常时，事件桥仍然保留本次事件快照。
     */
    @Test
    fun `parser parse safe keeps snapshot when observer callback throws`() {
        val observerFailures = mutableListOf<ObserverFailureEvent>()
        val parser = GsonSafeParser.parser(
            SafeParserConfig(
                onEvent = { error("observer failed") },
                onObserverFailure = observerFailures::add
            )
        )

        val result = assertDoesNotThrow<SafeParseResult<ApiResponse>> {
            parser.parseSafe<ApiResponse>("""{"code":206,"data":[]}""")
        }

        assertEquals(ApiResponse(code = 206), result.value)
        assertEquals(1, result.events.size)
        assertEquals("onEvent", observerFailures.single().callbackName)
    }

    /**
     * 测试方法说明：验证 Parser 的 ThreadLocal 事件桥只负责结果快照，不会让同一个事件在快照里重复出现。
     */
    @Test
    fun `parser parse safe does not collect duplicate events from bridge and observers`() {
        val externalEvents = mutableListOf<SafeParserEvent>()
        val parser = GsonSafeParser.parser(SafeParserConfig(onEvent = externalEvents::add))

        val result = parser.parseSafe<ApiResponse>("""{"code":207,"data":[]}""")

        assertEquals(ApiResponse(code = 207), result.value)
        val snapshotEvent = result.events.single() as SafeParserEvent.TypeMismatch
        val externalEvent = externalEvents.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.data", snapshotEvent.detail.path)
        assertEquals("$.data", externalEvent.detail.path)
    }

    /**
     * 测试方法说明：验证顶层 parseSafe 仍然只通过临时 onEvent 包装收集一次事件，不会和 Parser 事件桥叠加。
     */
    @Test
    fun `top level parse safe does not collect duplicate events when external observer is configured`() {
        val externalEvents = mutableListOf<SafeParserEvent>()

        val result = GsonSafeParser.parseSafe<ApiResponse>(
            json = """{"code":208,"data":[]}""",
            config = SafeParserConfig(onEvent = externalEvents::add)
        )

        assertEquals(ApiResponse(code = 208), result.value)
        val snapshotEvent = result.events.single() as SafeParserEvent.TypeMismatch
        val externalEvent = externalEvents.single() as SafeParserEvent.TypeMismatch
        assertEquals("$.data", snapshotEvent.detail.path)
        assertEquals("$.data", externalEvent.detail.path)
    }

    /**
     * 测试方法说明：验证 parseSafe 的 ThreadLocal 事件桥会在解析结束后恢复，不污染下一次普通解析。
     */
    @Test
    fun `parser parse safe event bridge is restored after parse`() {
        val externalEvents = mutableListOf<SafeParserEvent>()
        val parser = GsonSafeParser.parser(SafeParserConfig(onEvent = externalEvents::add))

        val first = parser.parseSafe<ApiResponse>("""{"code":209,"data":[]}""")
        val second = parser.fromJson("""{"code":210,"data":[]}""", ApiResponse::class.java)

        assertEquals(ApiResponse(code = 209), first.value)
        assertEquals(ApiResponse(code = 210), second)
        assertEquals(1, first.events.size)
        assertEquals(2, externalEvents.size)
    }

    /**
     * 测试方法说明：验证共享 Parser 并发 parseSafe 时 ThreadLocal 事件和 rawJson 不串线程。
     */
    @Test
    fun `shared parser keeps parse safe snapshots isolated across threads`() {
        val externalEvents = Collections.synchronizedList(mutableListOf<SafeParserEvent>())
        val parser = GsonSafeParser.parser(
            SafeParserConfig(
                captureRawJsonInCallbacks = true,
                onEvent = externalEvents::add
            )
        )
        val executor = Executors.newFixedThreadPool(4)

        try {
            val results = (0 until 16).map { index ->
                executor.submit<Pair<Int, SafeParseResult<ApiResponse>>> {
                    val json = """{"code":$index,"data":[]}"""
                    index to parser.parseSafe<ApiResponse>(json)
                }
            }.map { future -> future.get(5, TimeUnit.SECONDS) }

            results.forEach { (index, result) ->
                assertEquals(ApiResponse(code = index), result.value)
                val event = result.events.single() as SafeParserEvent.TypeMismatch
                assertEquals("$.data", event.detail.path)
                assertTrue(event.detail.rawJson.orEmpty().contains(""""code":$index"""))
            }
            assertEquals(16, externalEvents.size)
        } finally {
            executor.shutdownNow()
        }
    }
}
