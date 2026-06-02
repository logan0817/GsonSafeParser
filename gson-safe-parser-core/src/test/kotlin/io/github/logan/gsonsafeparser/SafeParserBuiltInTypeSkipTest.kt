package io.github.logan.gsonsafeparser

import com.google.gson.Gson
import android.content.res.ColorStateList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URL
import java.util.BitSet
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 验证 Gson 内置类型和 Android 平台类型的跳过策略。
 *
 * 这些类型不适合被 Safe Reflective 反射字段，否则很容易破坏 Gson 自己的稳定 Adapter，
 * 或者在 Android 平台类上遇到字段冲突、访问限制等问题。
 */
class SafeParserBuiltInTypeSkipTest {
    /** 测试模型：Gson 已有内置 Adapter 的类型集合，SafeParser 不应该反射它们。 */
    data class BuiltInResponse(
        val url: URL? = null,
        val uri: URI? = null,
        val uuid: UUID? = null,
        val date: Date? = null,
        val bitSet: BitSet? = null,
        val atomicBoolean: AtomicBoolean? = null,
        val character: Char? = null
    )

    /** 测试模型：Android 平台类型字段默认跳过 Safe Reflective 绑定。 */
    data class AndroidPlatformResponse(
        val title: String = "",
        val colorStateList: ColorStateList? = null
    )

    /**
     * 测试方法说明：验证“gson built in field types are delegated to native adapters”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `gson built in field types are delegated to native adapters`() {
        val nativeGson = Gson()
        val bitSet = BitSet().apply {
            set(0)
            set(2)
        }
        val json = nativeGson.toJson(
            BuiltInResponse(
                url = URL("https://example.com/path"),
                uri = URI("content://example/items/1"),
                uuid = UUID.fromString("00000000-0000-0000-0000-000000000123"),
                date = Date(0),
                bitSet = bitSet,
                atomicBoolean = AtomicBoolean(true),
                character = 'A'
            )
        )

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = GsonSafeParser.create().fromJson(json, BuiltInResponse::class.java)

        assertEquals("https://example.com/path", result.url?.toExternalForm())
        assertEquals(URI("content://example/items/1"), result.uri)
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000123"), result.uuid)
        assertEquals(Date(0), result.date)
        assertEquals(bitSet, result.bitSet)
        assertEquals(true, result.atomicBoolean?.get())
        assertEquals('A', result.character)
    }

    /**
     * 测试方法说明：验证“gson built in root types are delegated to native adapters”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `gson built in root types are delegated to native adapters`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        val url = gson.fromJson(""""https://example.com/path"""", URL::class.java)
        val uuid = gson.fromJson(""""00000000-0000-0000-0000-000000000123"""", UUID::class.java)
        val character = gson.fromJson(""""A"""", Char::class.javaObjectType)

        assertEquals("https://example.com/path", url.toExternalForm())
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000123"), uuid)
        assertEquals('A', character)
    }

    /**
     * 测试方法说明：验证“android platform field types are skipped during reflective binding”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `android platform field types are skipped during reflective binding`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson("""{"title":"ad","colorStateList":{}}""", AndroidPlatformResponse::class.java)

        assertEquals("ad", result.title)
        assertNull(result.colorStateList)
    }

    /**
     * 测试方法说明：验证“android platform field skip can be disabled to use Gson default behavior”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `android platform field skip can be disabled to use Gson default behavior`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(skippedPlatformTypePrefixes = emptySet())
        )

        assertThrows(RuntimeException::class.java) {
            gson.fromJson("""{"title":"ad","colorStateList":{}}""", AndroidPlatformResponse::class.java)
        }
    }
}
