package io.github.logan.gsonsafeparser

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.SerializedName
import com.google.gson.annotations.JsonAdapter
import com.google.gson.reflect.TypeToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * 验证 Adapter 创建失败时的可观测回退。
 *
 * Safe Adapter 如果在创建阶段遇到字段冲突或反射限制，默认应该先发事件，
 * 再把解析权交回 Gson，不能把 GsonSafeParser 变成新的崩溃放大器。
 */
class SafeTypeAdapterFactoryFallbackTest {
    /** 测试模型：两个字段使用同一个 JSON 名称，用来触发 Safe Adapter 创建失败。 */
    data class DuplicateNamesForFallback(
        @SerializedName("same")
        val first: String = "",
        @SerializedName("same")
        val second: String = ""
    )
    /** 测试模型：字段 Adapter 创建期抛出 fatal 时必须外抛，不能发布可恢复创建失败事件。 */
    data class FatalAdapterCreationResponse(
        @JsonAdapter(FatalAdapterFactory::class)
        val value: FatalAdapterValue = FatalAdapterValue()
    )
    /** 测试模型：字段 Adapter 创建期 fatal 用的字段类型。 */
    data class FatalAdapterValue(val text: String = "local")

    /** 测试 AdapterFactory：创建字段 Adapter 时直接抛出 AssertionError。 */
    class FatalAdapterFactory : TypeAdapterFactory {
        /** 创建 Adapter 时抛出 fatal，模拟类加载或断言级失败。 */
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            throw AssertionError("fatal adapter creation failure")
        }
    }

    /**
     * 测试方法说明：验证“adapter creation failure is observable before delegating to Gson”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `adapter creation failure is observable before delegating to Gson`() {
        // events 用来收集回调事件，后面的断言会检查事件是否按预期产生。
        val events = mutableListOf<AdapterCreationFailureEvent>()
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(onAdapterCreationFailure = events::add)
        )

        assertThrows(IllegalArgumentException::class.java) {
            gson.getAdapter(DuplicateNamesForFallback::class.java)
        }

        assertEquals(DuplicateNamesForFallback::class.java.name, events.single().typeName)
    }

    /**
     * 测试方法说明：验证 Adapter 创建期 fatal 直接外抛，不进入 AdapterCreationFailure 回调。
     */
    @Test
    fun `adapter creation fatal failure is rethrown without fallback event`() {
        val events = mutableListOf<AdapterCreationFailureEvent>()
        val gson = GsonSafeParser.create(
            SafeParserConfig(onAdapterCreationFailure = events::add)
        )

        assertThrows(AssertionError::class.java) {
            gson.fromJson("""{"value":{"text":"remote"}}""", FatalAdapterCreationResponse::class.java)
        }

        assertTrue(events.isEmpty())
    }

}
