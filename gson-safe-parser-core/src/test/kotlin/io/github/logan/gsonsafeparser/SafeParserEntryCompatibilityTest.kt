package io.github.logan.gsonsafeparser

import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import com.google.gson.JsonIOException
import com.google.gson.ToNumberPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * 验证公开入口的长期兼容边界。
 *
 * 这里集中覆盖默认 `create(config)` 和 builder-first `.enableSafeParser(config)` 的配置继承差异。
 */
class SafeParserEntryCompatibilityTest {
    data class DirectEntryResponse(
        val data: CreatedByConfig = CreatedByConfig("local"),
        val payload: Any? = null,
        val values: Map<String, Any> = emptyMap()
    )

    data class BuilderEntryResponse(
        val data: CreatedByBuilder = CreatedByBuilder("local"),
        val payload: Any? = null,
        val values: Map<String, Any> = emptyMap()
    )

    data class CreatedByConfig(val source: String = "json")

    data class CreatedByBuilder(val source: String = "json")

    /**
     * 测试方法说明：验证默认入口 direct 注册时，仍尊重调用方传入 config 的 InstanceCreator 和 Object 数字策略。
     */
    @Test
    fun `default create entry keeps config instance creators and object number strategy`() {
        val gson = GsonSafeParser.create(
            SafeParserConfig(
                instanceCreators = mapOf(
                    CreatedByConfig::class.java to InstanceCreator { CreatedByConfig("config") }
                ),
                objectToNumberStrategy = ToNumberPolicy.LONG_OR_DOUBLE
            )
        )

        val result = gson.fromJson(
            """{"data":{},"payload":1,"values":{"count":2}}""",
            DirectEntryResponse::class.java
        )

        assertEquals(CreatedByConfig("config"), result.data)
        assertInstanceOf(Long::class.javaObjectType, result.payload)
        assertEquals(1L, result.payload)
        assertInstanceOf(Long::class.javaObjectType, result.values["count"])
        assertEquals(2L, result.values["count"])
    }

    /**
     * 测试方法说明：验证默认入口 direct 注册时，Strict 仍会关闭 Gson delegate 的 Unsafe 兜底。
     */
    @Test
    fun `default create entry keeps strict constructor policy over unsafe fallback`() {
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
     * 测试方法说明：验证 builder-first 入口仍继承用户放进 GsonBuilder 的配置。
     */
    @Test
    fun `builder first entry keeps builder instance creators and object number strategy`() {
        val gson = GsonBuilder()
            .registerTypeAdapter(
                CreatedByBuilder::class.java,
                InstanceCreator { CreatedByBuilder("builder") }
            )
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .enableSafeParser()
            .create()

        val result = gson.fromJson(
            """{"data":{},"payload":1,"values":{"count":2}}""",
            BuilderEntryResponse::class.java
        )

        assertEquals(CreatedByBuilder("builder"), result.data)
        assertInstanceOf(Long::class.javaObjectType, result.payload)
        assertEquals(1L, result.payload)
        assertInstanceOf(Long::class.javaObjectType, result.values["count"])
        assertEquals(2L, result.values["count"])
    }
}
