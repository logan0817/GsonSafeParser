package io.github.logan.gsonsafeparser.retrofit

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.logan.gsonsafeparser.SafeParserConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Converter
import java.lang.reflect.Modifier

/**
 * Retrofit 模块公开 API 基线。
 *
 * 这组测试只锁住工厂入口的 ABI 和职责边界，具体响应转换行为由 GsonSafeConverterFactoryTest 覆盖。
 */
class GsonSafeConverterFactoryPublicApiTest {
    @Test
    fun `retrofit public factory keeps overload matrix and exposes retrofit factory type`() {
        val methods = GsonSafeConverterFactory::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) && method.name == "create" && !method.isSynthetic }
            .map { method ->
                method.returnType.name to method.parameterTypes.map { parameter -> parameter.name }
            }
            .toSet()

        assertTrue(signature(SafeParserConfig::class.java) in methods)
        assertTrue(
            signature(GsonBuilder::class.java, SafeParserConfig::class.java) in methods
        )
        assertTrue(signature(Gson::class.java) in methods)
        assertTrue(signature(Gson::class.java, SafeParserConfig::class.java) in methods)
    }

    private fun signature(vararg parameterTypes: Class<*>): Pair<String, List<String>> {
        return Converter.Factory::class.java.name to parameterTypes.map { parameter -> parameter.name }
    }
}
