package io.github.logan.gsonsafeparser

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.SortedSet
import java.util.TreeSet
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * 验证不容易归到单一模块的边界场景。
 *
 * 这里放的是 root 类型、数组、枚举、泛型和异常恢复等边界回归，
 * 用来防止局部修复时把外围行为悄悄改坏。
 */
class SafeParserEdgeCaseTest {
    /** 测试模型：具体集合实现，验证兜底后运行时类型仍然可赋值。 */
    data class ConcreteCollections(
        val arrayList: ArrayList<String> = arrayListOf(),
        val queue: ArrayDeque<String> = ArrayDeque(),
        val sortedSet: TreeSet<String> = TreeSet()
    )
    /** 测试模型：SortedSet 的元素不可比较时，容器物化失败不能拖垮整次解析。 */
    data class NonComparableSortedSetContainer(
        val values: SortedSet<NonComparableItem> = TreeSet()
    )
    /** 测试模型：没有实现 Comparable，用来触发 TreeSet.add 的边界。 */
    data class NonComparableItem(val id: Long = 0L)

    /** 测试模型：Int key 的 Map，用来覆盖非 String key 的读取。 */
    data class IntKeyMap(val scores: Map<Int, String> = emptyMap())
    /** 测试模型：集合和 Map 都允许为 null，用来验证 NullOnly 策略。 */
    data class NullableCollections(
        val users: List<String>?,
        val profile: Map<String, String>?
    )
    /** 测试模型：大数组解析，用来覆盖发布前压力边界。 */
    data class LargeArrayContainer(val values: List<Int> = emptyList())
    /** 测试模型：深层动态对象解析，用来覆盖递归边界。 */
    data class DeepDynamicContainer(val payload: Map<String, Any?> = emptyMap())
    /** 测试集合：自定义 List 子类，用来验证泛型从父类继承时仍能解析元素类型。 */
    class IntList : ArrayList<Int>()
    /** 测试 Map：自定义 Map 子类，用来验证继承泛型的 Map 构造。 */
    class StringIntMap : LinkedHashMap<String, Int>()
    /** 测试模型：同时包含自定义集合和自定义 Map。 */
    data class CustomContainers(
        val numbers: IntList = IntList(),
        val scores: StringIntMap = StringIntMap()
    )
    /** 测试模型：数组 entry 形式的 Map，value 读取失败时应只跳过当前 entry。 */
    data class ArrayEntryMapContainer(
        val values: Map<String, ThrowingItem> = emptyMap()
    )
    /** 测试模型：List item Adapter 抛出 fatal 时必须外抛。 */
    data class FatalListContainer(
        val values: List<FatalItem> = emptyList()
    )
    /** 测试模型：对象形式 Map value Adapter 抛出 fatal 时必须外抛。 */
    data class FatalObjectMapContainer(
        val values: Map<String, FatalItem> = emptyMap()
    )
    /** 测试模型：数组 entry 形式 Map value Adapter 抛出 fatal 时必须外抛。 */
    data class FatalArrayEntryValueMapContainer(
        val values: Map<String, FatalItem> = emptyMap()
    )
    /** 测试模型：对象形式 Map key Adapter 抛出 fatal 时必须外抛。 */
    data class FatalObjectMapKeyContainer(
        val values: Map<FatalKey, String> = emptyMap()
    )
    /** 测试模型：数组 entry 形式 Map key Adapter 抛出 fatal 时必须外抛。 */
    data class FatalArrayEntryKeyMapContainer(
        val values: Map<FatalKey, String> = emptyMap()
    )
    /** 测试模型：对象形式 Map，key 需要经过自定义 Adapter 读取。 */
    data class EscapedKeyMapContainer(
        val values: Map<EscapedKey, String> = emptyMap()
    )
    /** 测试模型：ConcurrentMap 不接受 null value，用来验证单个 entry 写入失败不会拖垮整次解析。 */
    data class ConcurrentNullableMapContainer(
        val values: ConcurrentMap<String, String?> = ConcurrentHashMap()
    )

    /** 测试模型：读取时可能半消费失败的 Map value。 */
    @JsonAdapter(HalfConsumingThrowingItemAdapter::class)
    data class ThrowingItem(val value: String = "local")
    /** 测试模型：读取时抛出 fatal 的集合或 Map value。 */
    @JsonAdapter(FatalItemAdapter::class)
    data class FatalItem(val value: String = "local")
    /** 测试模型：读取时抛出 fatal 的 Map key。 */
    @JsonAdapter(FatalKeyAdapter::class)
    data class FatalKey(val value: String = "local")
    /** 测试模型：用自定义 Adapter 暴露 Map key 重新包装 JSON 字符串的转义风险。 */
    @JsonAdapter(EscapedKeyAdapter::class)
    data class EscapedKey(val value: String)

    /**
     * 半消费后抛异常的测试 Adapter。
     *
     * 它会先读掉对象里的字段，再根据字段值决定是否抛异常，用来验证 reader 恢复逻辑。
     */
    class HalfConsumingThrowingItemAdapter : TypeAdapter<ThrowingItem>() {
        /** 写出普通对象结构。 */
        override fun write(out: JsonWriter, value: ThrowingItem?) {
            out.beginObject()
            out.name("value").value(value?.value)
            out.endObject()
        }

        /**
         * 读取对象并在 value 为 bad 时抛异常。
         *
         * @param reader Gson Reader。
         * @return 正常读取时返回 ThrowingItem。
         */
        override fun read(reader: JsonReader): ThrowingItem {
            reader.beginObject()
            val name = reader.nextName()
            val value = reader.nextString()
            if (name == "value" && value == "bad") {
                throw JsonParseException("failed after partially consuming object")
            }
            reader.endObject()
            return ThrowingItem(value)
        }
    }

    /** 测试 Adapter：读取 item 时抛出 fatal，验证集合和 Map item 不能吞掉 Error。 */
    class FatalItemAdapter : TypeAdapter<FatalItem>() {
        /** 写出普通对象结构。 */
        override fun write(out: JsonWriter, value: FatalItem?) {
            out.beginObject()
            out.name("value").value(value?.value)
            out.endObject()
        }

        /** 读取时抛出 AssertionError。 */
        override fun read(reader: JsonReader): FatalItem {
            throw AssertionError("fatal item adapter failure")
        }
    }

    /** 测试 Adapter：读取 key 时抛出 fatal，验证 Map key 不能吞掉 Error。 */
    class FatalKeyAdapter : TypeAdapter<FatalKey>() {
        /** 写出 key 字符串。 */
        override fun write(out: JsonWriter, value: FatalKey?) {
            out.value(value?.value)
        }

        /** 读取时抛出 AssertionError。 */
        override fun read(reader: JsonReader): FatalKey {
            throw AssertionError("fatal key adapter failure")
        }
    }

    /** 测试 Adapter：直接把 key 当字符串读写，方便确认 key 原值没有被破坏。 */
    class EscapedKeyAdapter : TypeAdapter<EscapedKey>() {
        /** 写出 key 原始字符串。 */
        override fun write(out: JsonWriter, value: EscapedKey?) {
            out.value(value?.value)
        }

        /** 读回 key 原始字符串。 */
        override fun read(reader: JsonReader): EscapedKey {
            return EscapedKey(reader.nextString())
        }
    }

    /** 测试 Adapter：根基础类型读取时抛出指定 RuntimeException。 */
    private class ThrowingRootIntAdapter(
        private val failure: RuntimeException
    ) : TypeAdapter<Int>() {
        /** 写出 Int 值，当前测试只关注读取分支。 */
        override fun write(out: JsonWriter, value: Int?) {
            if (value == null) out.nullValue() else out.value(value)
        }

        /** 读取时直接抛出构造时指定的异常。 */
        override fun read(reader: JsonReader): Int {
            throw failure
        }
    }

    /**
     * 测试方法说明：验证“concrete collection fields keep assignable runtime types”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `concrete collection fields keep assignable runtime types`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"arrayList":["a"],"queue":["b"],"sortedSet":["c"]}""",
            ConcreteCollections::class.java
        )

        assertEquals(arrayListOf("a"), result.arrayList)
        assertEquals(listOf("b"), result.queue.toList())
        assertEquals(TreeSet(listOf("c")), result.sortedSet)
    }

    /**
     * 测试方法说明：验证 SortedSet 元素不可比较时回到字段兜底，而不是让 TreeSet.add 抛出整次解析异常。
     */
    @Test
    fun `sorted set with non comparable items falls back without crashing response`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        val result = gson.fromJson(
            """{"values":[{"id":1},{"id":2}]}""",
            NonComparableSortedSetContainer::class.java
        )

        assertEquals(emptyList<NonComparableItem>(), result.values.toList())
        val event = events.single()
        assertEquals(ParseExceptionKind.OBJECT, event.kind)
        assertEquals("$.values", event.path)
    }

    /**
     * 测试方法说明：验证根 SortedSet 元素不可比较时也按集合兜底处理，不能向公开 API 调用方抛 ClassCastException。
     */
    @Test
    fun `root sorted set with non comparable items falls back without class cast crash`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val type = object : TypeToken<SortedSet<NonComparableItem>>() {}.type

        val result = GsonSafeParser.fromJson<SortedSet<NonComparableItem>>(
            json = """[{"id":1},{"id":2}]""",
            type = type,
            config = SafeParserConfig(
                fallbackPolicy = FallbackPolicy.Default,
                onTypeMismatch = events::add
            )
        )

        assertEquals(emptyList<NonComparableItem>(), result?.toList())
        val event = events.single()
        assertEquals(ParseExceptionKind.OBJECT, event.kind)
        assertEquals("$", event.path)
    }

    /**
     * 测试方法说明：验证“map supports scalar non string keys”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `map supports scalar non string keys`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"scores":{"7":"vip"}}""",
            IntKeyMap::class.java
        )

        assertEquals("vip", result.scores[7])
    }

    /**
     * 测试方法说明：验证对象形式 Map 的非 String key 解析必须保留 JSON name 的真实字符串。
     */
    @Test
    fun `map object form preserves escaped key strings for custom key adapters`() {
        val gson = GsonSafeParser.create()

        val result = gson.fromJson(
            """{"values":{"a\\b":"slash","line\nkey":"newline","quote\"key":"quote"}}""",
            EscapedKeyMapContainer::class.java
        )

        assertEquals("slash", result.values[EscapedKey("""a\b""")])
        assertEquals("newline", result.values[EscapedKey("line\nkey")])
        assertEquals("quote", result.values[EscapedKey("quote\"key")])
    }

    @Test
    fun `map object form skips entry when target map rejects value write`() {
        val events = mutableListOf<TypeMismatchEvent>()

        val result = GsonSafeParser.fromJson(
            json = """{"values":{"bad":null,"ok":"next"}}""",
            type = ConcurrentNullableMapContainer::class.java,
            config = SafeParserConfig(
                mapItemKeyPolicy = MapItemKeyPolicy.PlainText,
                onTypeMismatch = events::add
            )
        )

        assertEquals(mapOf("ok" to "next"), result?.values)
        val event = events.single()
        assertEquals(ParseExceptionKind.MAP_ITEM, event.kind)
        assertEquals("$.values.bad", event.path)
        assertEquals("bad", event.mapItemKey)
    }

    /**
     * 测试方法说明：验证“null only policy applies to collection and map mismatch”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `null only policy applies to collection and map mismatch`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create(
            SafeParserConfig(fallbackPolicy = FallbackPolicy.NullOnly)
        )
        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"users":{},"profile":false}""",
            NullableCollections::class.java
        )

        assertNull(result.users)
        assertNull(result.profile)
    }

    /**
     * 测试方法说明：验证“custom collection and map subclasses resolve inherited generic arguments”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `custom collection and map subclasses resolve inherited generic arguments`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"numbers":["bad",1],"scores":{"ok":2,"bad":[]}}""",
            CustomContainers::class.java
        )

        assertEquals(listOf(1), result.numbers)
        assertEquals(2, result.scores["ok"])
        assertFalse(result.scores.containsKey("bad"))
    }

    /**
     * 测试方法说明：验证万级数组不会因为 Safe 集合适配器引入额外失败或丢元素。
     */
    @Test
    fun `large array parses without dropping valid items`() {
        val gson = GsonSafeParser.create()
        val json = (0 until 10_000).joinToString(
            prefix = """{"values":[""",
            separator = ",",
            postfix = "]}"
        )

        val result = gson.fromJson(json, LargeArrayContainer::class.java)

        assertEquals(10_000, result.values.size)
        assertEquals(0, result.values.first())
        assertEquals(9_999, result.values.last())
    }

    /**
     * 测试方法说明：验证深层嵌套对象仍交给 Gson/Safe Map 链路稳定读取，不污染 reader 状态。
     */
    @Test
    fun `deep nested dynamic object parses without stack or reader corruption`() {
        val gson = GsonSafeParser.create()
        val depth = 120
        val nestedOpen = "{" + (0 until depth).joinToString("") { """"level$it":{""" }
        val nestedClose = """"value":1""" + "}".repeat(depth + 1)
        val json = """{"payload":$nestedOpen$nestedClose}"""

        val result = gson.fromJson(json, DeepDynamicContainer::class.java)

        assertTrue(result.payload.isNotEmpty())
    }

    /**
     * 测试方法说明：验证“map array entry item failure does not break following entries”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `map array entry item failure does not break following entries`() {
        // gson 是本用例使用的解析器，默认情况下已经注册 Safe Adapter。
        val gson = GsonSafeParser.create()

        // result 是本次解析或转换得到的实际结果，后面的断言都围绕它展开。
        val result = gson.fromJson(
            """{"values":[["bad",{"value":"bad"}],["ok",{"value":"ok"}]]}""",
            ArrayEntryMapContainer::class.java
        )

        assertEquals("ok", result.values["ok"]?.value)
        assertFalse(result.values.containsKey("bad"))
    }

    /**
     * 测试方法说明：验证 List item Adapter 抛出 fatal 时直接外抛，不被当成单个坏 item。
     */
    @Test
    fun `list item fatal failure is rethrown`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(AssertionError::class.java) {
            gson.fromJson("""{"values":[{"value":"bad"}]}""", FatalListContainer::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证对象形式 Map value Adapter 抛出 fatal 时直接外抛。
     */
    @Test
    fun `map object value fatal failure is rethrown`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(AssertionError::class.java) {
            gson.fromJson("""{"values":{"bad":{"value":"bad"}}}""", FatalObjectMapContainer::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证数组 entry 形式 Map value Adapter 抛出 fatal 时直接外抛。
     */
    @Test
    fun `map array entry value fatal failure is rethrown`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(AssertionError::class.java) {
            gson.fromJson("""{"values":[["bad",{"value":"bad"}]]}""", FatalArrayEntryValueMapContainer::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证对象形式 Map key Adapter 抛出 fatal 时直接外抛。
     */
    @Test
    fun `map object key fatal failure is rethrown`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(AssertionError::class.java) {
            gson.fromJson("""{"values":{"bad":"value"}}""", FatalObjectMapKeyContainer::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证数组 entry 形式 Map key Adapter 抛出 fatal 时直接外抛。
     */
    @Test
    fun `map array entry key fatal failure is rethrown`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val gson = GsonSafeParser.create(SafeParserConfig(onTypeMismatch = events::add))

        assertThrows(AssertionError::class.java) {
            gson.fromJson("""{"values":[["bad","value"]]}""", FatalArrayEntryKeyMapContainer::class.java)
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证“root invalid number string falls back without throwing in safe mode”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `root invalid number string falls back without throwing in safe mode`() {
        val config = SafeParserConfig(
            fallbackPolicy = FallbackPolicy.Default,
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
        )
        // result 是本次解析得到的实际结果，后面的断言都围绕它展开。
        val result = GsonSafeParser.parseSafe<Int>("\"bad\"", config)

        assertEquals(0, result.value)
        assertEquals(1, result.events.size)
        assertEquals(
            SafeParserEvent.TypeMismatch::class.java,
            result.events.single()::class.java
        )
    }

    /**
     * 测试方法说明：验证“root invalid boolean string falls back without throwing in safe mode”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `root invalid boolean string falls back without throwing in safe mode`() {
        val config = SafeParserConfig(
            fallbackPolicy = FallbackPolicy.Default,
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
        )
        // result 是本次解析得到的实际结果，后面的断言都围绕它展开。
        val result = GsonSafeParser.parseSafe<Boolean>("\"not_boolean\"", config)

        assertEquals(false, result.value)
        assertEquals(1, result.events.size)
        assertEquals(
            SafeParserEvent.TypeMismatch::class.java,
            result.events.single()::class.java
        )
    }

    /**
     * 测试方法说明：验证根基础类型 Adapter 抛出取消信号时直接外抛，不被根兜底吞掉。
     */
    @Test
    fun `root primitive cancellation failure is rethrown in safe mode`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(
            fallbackPolicy = FallbackPolicy.Default,
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
            onTypeMismatch = events::add
        )
        val gson = rootIntGson(CancellationException("cancelled root primitive parse"))

        assertThrows(CancellationException::class.java) {
            GsonSafeParser.fromJson<Int>(
                gson = gson,
                json = "1",
                type = Int::class.java,
                config = config
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证根基础类型语法错误继续按 Gson 异常外抛，不被错形兜底掩盖。
     */
    @Test
    fun `root primitive syntax failure is rethrown in safe mode`() {
        val config = SafeParserConfig(
            fallbackPolicy = FallbackPolicy.Default,
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
        )

        assertThrows(JsonParseException::class.java) {
            GsonSafeParser.parseSafe<Int>("{", config)
        }
    }

    /**
     * 测试方法说明：验证外部 Gson 的根基础类型业务 Adapter 异常继续外抛。
     */
    @Test
    fun `root primitive delegated adapter failure is rethrown in safe mode`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(
            fallbackPolicy = FallbackPolicy.Default,
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
            onTypeMismatch = events::add
        )
        val gson = rootIntGson(IllegalArgumentException("business adapter failure"))

        assertThrows(IllegalArgumentException::class.java) {
            GsonSafeParser.fromJson<Int>(
                gson = gson,
                json = "1",
                type = Int::class.java,
                config = config
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证外部 Gson 的根基础类型业务 Adapter 收到结构错形时也继续外抛。
     */
    @Test
    fun `root primitive delegated adapter object failure is rethrown in safe mode`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(
            fallbackPolicy = FallbackPolicy.Default,
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
            onTypeMismatch = events::add
        )
        val gson = rootIntGson(
            JsonSyntaxException(
                IllegalStateException("Expected an int but was BEGIN_OBJECT at path $")
            )
        )

        assertThrows(JsonSyntaxException::class.java) {
            GsonSafeParser.fromJson<Int>(
                gson = gson,
                json = "{}",
                type = Int::class.java,
                config = config
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证业务 Adapter 包装成 Gson 风格数字异常时也继续外抛。
     */
    @Test
    fun `root primitive delegated adapter number format shaped failure is rethrown in safe mode`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(
            fallbackPolicy = FallbackPolicy.Default,
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
            onTypeMismatch = events::add
        )
        val gson = rootIntGson(
            JsonSyntaxException(NumberFormatException("For input string: \"bad\""))
        )

        assertThrows(JsonSyntaxException::class.java) {
            GsonSafeParser.fromJson<Int>(
                gson = gson,
                json = "\"bad\"",
                type = Int::class.java,
                config = config
            )
        }

        assertTrue(events.isEmpty())
    }

    /**
     * 测试方法说明：验证普通外部 Gson 的根基础类型结构错形仍可被 SafeParser 兜底。
     */
    @Test
    fun `root primitive object mismatch from plain external gson falls back in safe mode`() {
        val events = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(
            fallbackPolicy = FallbackPolicy.Default,
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe,
            onTypeMismatch = events::add
        )

        val result = GsonSafeParser.fromJson<Int>(
            gson = GsonBuilder().create(),
            json = "{}",
            type = Int::class.java,
            config = config
        )

        assertEquals(0, result)
        assertEquals(1, events.size)
    }

    private fun rootIntGson(failure: RuntimeException) = GsonBuilder()
        .registerTypeAdapter(Int::class.java, ThrowingRootIntAdapter(failure))
        .registerTypeAdapter(Int::class.javaObjectType, ThrowingRootIntAdapter(failure))
        .create()
}
