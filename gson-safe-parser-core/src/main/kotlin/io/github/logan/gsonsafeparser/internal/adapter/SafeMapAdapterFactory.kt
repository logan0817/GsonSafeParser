package io.github.logan.gsonsafeparser.internal.adapter

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.internal.GsonTypes
import com.google.gson.internal.Streams
import com.google.gson.internal.bind.TypeAdapters
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import io.github.logan.gsonsafeparser.FallbackPolicy
import io.github.logan.gsonsafeparser.ParseExceptionKind
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.ShapeCoercionAction
import io.github.logan.gsonsafeparser.internal.TokenRules
import io.github.logan.gsonsafeparser.internal.asCallerAdapterReadException
import io.github.logan.gsonsafeparser.internal.objectcreation.SafeObjectConstructor
import io.github.logan.gsonsafeparser.internal.runRecovering
import java.lang.reflect.Type
import java.util.Collection

/**
 * Map 系列的安全 Adapter。
 *
 * 支持对象形式和数组 entry 形式读取。Map 整体错形时返回空 Map 或 null；
 * 单个 key/value 失败时跳过当前 entry，让后续 entry 继续解析。
 */
internal object SafeMapAdapterFactory {
    /**
     * 创建 Map 类型的 Safe Adapter。
     *
     * @param gson 当前 Gson，用来获取 key 和 value 的 Adapter。
     * @param type Map 完整类型，例如 `Map<Int, User>`。
     * @param config SafeParser 配置。
     * @return 能读写该 Map 类型的 Adapter。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> create(gson: Gson, type: TypeToken<T>, config: SafeParserConfig): TypeAdapter<T> {
        // keyValueTypes.first 是 key 类型，second 是 value 类型。后面读写都依赖这两个类型。
        val keyValueTypes = mapKeyValueTypes(type.type)
        val keyTypeToken = TypeToken.get(keyValueTypes.first)
        val keyRawType = keyTypeToken.rawType
        val valueTypeToken = TypeToken.get(keyValueTypes.second)
        val keyAdapter = keyAdapter(gson, keyValueTypes.first) as TypeAdapter<Any?>
        val keyHandlesOwnShape =
            keyRawType.getAnnotation(JsonAdapter::class.java) != null ||
                keyAdapter.handlesOwnInputShape()
        val valueAdapter = gson.getAdapter(valueTypeToken) as TypeAdapter<Any?>
        val valueRawType = valueTypeToken.rawType
        val valueHandlesOwnShape =
            valueRawType.getAnnotation(JsonAdapter::class.java) != null ||
                valueRawType.delegatesPrimitiveInputShape(config) ||
                valueAdapter.handlesOwnInputShape()
        val rawType = type.rawType
        val complexMapKeySerialization = config.complexMapKeySerialization

        return object : TypeAdapter<T>() {
            /**
             * 写出 Map。
             *
             * @param out JSON 输出流。
             * @param value 要写出的 Map。
             */
            override fun write(out: JsonWriter, value: T?) {
                if (value == null) {
                    out.nullValue()
                    return
                }
                // map 是业务传入的真实 Map，key/value 可能是任意类型，所以后续都要经过对应 Adapter。
                val map = value as Map<*, *>
                if (!complexMapKeySerialization) {
                    // 默认写出对象形式，但 key 仍先经过 keyAdapter，保证 enum @SerializedName 等规则不丢。
                    out.beginObject()
                    map.forEach { (key, mapValue) ->
                        out.name(safeKeyToString(key, keyAdapter))
                        valueAdapter.writeRuntime(gson, keyValueTypes.second, out, mapValue)
                    }
                    out.endObject()
                    return
                }

                val keys = map.keys.map { keyAdapter.toJsonTree(it) }
                val values = map.values.toList()
                val hasComplexKeys = keys.any { it.isJsonArray || it.isJsonObject }
                if (hasComplexKeys) {
                    // 复杂 key 只能用数组 entry 形式表达，否则对象 key 会被强制压成字符串。
                    out.beginArray()
                    keys.forEachIndexed { index, keyElement ->
                        out.beginArray()
                        Streams.write(keyElement, out)
                        valueAdapter.writeRuntime(gson, keyValueTypes.second, out, values[index])
                        out.endArray()
                    }
                    out.endArray()
                } else {
                    out.beginObject()
                    keys.forEachIndexed { index, keyElement ->
                        out.name(keyToString(keyElement))
                        valueAdapter.writeRuntime(gson, keyValueTypes.second, out, values[index])
                    }
                    out.endObject()
                }
            }

            /**
             * 读取 Map。
             *
             * @param reader Gson Reader。
             * @return 解析后的 Map；整体错形时按策略返回空 Map 或 null。
             */
            override fun read(reader: JsonReader): T {
                val token = reader.peek()
                if (token == JsonToken.NULL) {
                    reader.nextNull()
                    if (config.fallbackPolicy == FallbackPolicy.NullOnly) return null as T
                    return emptyMutableMapFor(type.type, rawType, config) as T
                }
                if (token != JsonToken.BEGIN_OBJECT && token != JsonToken.BEGIN_ARRAY) {
                    notify(config, type, reader, token)
                    reader.skipValue()
                    if (config.fallbackPolicy == FallbackPolicy.NullOnly) return null as T
                    return emptyMutableMapFor(type.type, rawType, config) as T
                }

                // values 是最终结果容器。每个 entry 成功后才写入，失败 entry 会被跳过。
                val values = emptyMutableMapFor(type.type, rawType, config)
                if (token == JsonToken.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val keyName = reader.nextName()
                        val key = parseKey(keyName, keyValueTypes.first, keyAdapter, keyHandlesOwnShape)
                        if (key is ParsedMapKey.Failure) {
                            // key 解析失败时跳过整个 entry，不能把错误的字符串 key 塞进强类型 Map。
                            notify(
                                config = config,
                                type = keyTypeToken,
                                reader = reader,
                                token = reader.peekSafe(),
                                reason = key.reason,
                                kind = ParseExceptionKind.MAP_ITEM,
                                mapItemKey = keyName
                            )
                            reader.skipValue()
                            continue
                        }
                        val valueToken = reader.peek()
                        val pathBeforeRead = reader.path
                        if (
                            !valueHandlesOwnShape &&
                            !TokenRules.accepts(keyValueTypes.second, valueRawType, valueToken) &&
                            !canCoerceMapValue(valueRawType, valueToken, config)
                        ) {
                            // value 错形只影响当前 entry，Map 里的其他 key 仍然可以继续解析。
                            notify(
                                config = config,
                                type = valueTypeToken,
                                reader = reader,
                                token = valueToken,
                                kind = ParseExceptionKind.MAP_ITEM,
                                mapItemKey = keyName
                            )
                            reader.skipValue()
                            continue
                        }
                        runRecovering {
                            when (
                                val value = readMapValue(
                                    reader = reader,
                                    valueType = valueTypeToken,
                                    valueAdapter = valueAdapter,
                                    valueToken = valueToken,
                                    valueRawType = valueRawType,
                                    valueHandlesOwnShape = valueHandlesOwnShape,
                                    pathBeforeRead = pathBeforeRead,
                                    config = config
                                )
                            ) {
                                is ParsedMapValue.Success -> putMapValue(
                                    values = values,
                                    key = (key as ParsedMapKey.Success).value,
                                    value = value.value
                                )
                                ParsedMapValue.Skipped -> Unit
                            }
                        }
                            .onFailure {
                                it.throwIfDuplicateMapKey()
                                if (valueHandlesOwnShape) {
                                    throw it.asCallerAdapterReadException()
                                }
                                // valueAdapter 可能已经消费了一半对象，先把 reader 修正到当前 entry 后面。
                                reader.skipUnreadValueIfPossible(pathBeforeRead)
                                notify(
                                    config = config,
                                    type = valueTypeToken,
                                    reader = reader,
                                    token = valueToken,
                                    reason = it.message ?: it.javaClass.name,
                                    kind = ParseExceptionKind.MAP_ITEM,
                                    mapItemKey = keyName,
                                    path = pathBeforeRead
                                )
                            }
                    }
                    reader.endObject()
                } else {
                    // Gson 复杂 Map key 会写成 [[key,value]]，读取时按 entry 粒度做容错。
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                            notify(
                                config = config,
                                type = type,
                                reader = reader,
                                token = reader.peekSafe(),
                                kind = ParseExceptionKind.MAP_ITEM,
                                mapItemKey = "null"
                            )
                            reader.skipValue()
                            continue
                        }
                        reader.beginArray()
                        // key 先声明在外面，是为了 value 失败时还能在事件里带上当前 key。
                        var key: Any? = null
                        // pathBeforeRead 记录读取 value 之前的位置，失败时用它恢复 reader。
                        var pathBeforeRead = reader.path
                        var failureToken = reader.peekSafe()
                        val keyResult = runRecovering { keyAdapter.read(reader) }
                        if (keyResult.isFailure) {
                            val error = keyResult.exceptionOrNull()
                                ?: IllegalStateException("Map entry key parsing failed")
                            if (keyHandlesOwnShape) {
                                throw error.asCallerAdapterReadException()
                            }
                            reader.skipUnreadValueIfPossible(pathBeforeRead)
                            notify(
                                config = config,
                                type = keyTypeToken,
                                reader = reader,
                                token = failureToken,
                                reason = error.message ?: error.javaClass.name,
                                kind = ParseExceptionKind.MAP_ITEM,
                                mapItemKey = null,
                                path = pathBeforeRead
                            )
                            while (runRecovering { reader.hasNext() }.getOrDefault(false)) {
                                reader.skipValue()
                            }
                            reader.endArray()
                            continue
                        }
                        key = keyResult.getOrThrow()
                        pathBeforeRead = reader.path
                        failureToken = reader.peekSafe()
                        runRecovering {
                            if (failureToken == JsonToken.END_ARRAY || failureToken == JsonToken.END_DOCUMENT) {
                                notify(
                                    config = config,
                                    type = valueTypeToken,
                                    reader = reader,
                                    token = failureToken,
                                    reason = "Map entry value is missing",
                                    kind = ParseExceptionKind.MAP_ITEM,
                                    mapItemKey = key?.toString() ?: "null",
                                    path = pathBeforeRead
                                )
                            } else if (
                                !valueHandlesOwnShape &&
                                !TokenRules.accepts(keyValueTypes.second, valueRawType, failureToken) &&
                                !canCoerceMapValue(valueRawType, failureToken, config)
                            ) {
                                notify(
                                    config = config,
                                    type = valueTypeToken,
                                    reader = reader,
                                    token = failureToken,
                                    kind = ParseExceptionKind.MAP_ITEM,
                                    mapItemKey = key?.toString() ?: "null"
                                )
                                reader.skipValue()
                            } else {
                                when (
                                    val value = readMapValue(
                                        reader = reader,
                                        valueType = valueTypeToken,
                                        valueAdapter = valueAdapter,
                                        valueToken = failureToken,
                                        valueRawType = valueRawType,
                                        valueHandlesOwnShape = valueHandlesOwnShape,
                                        pathBeforeRead = pathBeforeRead,
                                        config = config
                                    )
                                ) {
                                    is ParsedMapValue.Success -> putMapValue(
                                        values = values,
                                        key = key,
                                        value = value.value
                                    )
                                    ParsedMapValue.Skipped -> Unit
                                }
                            }
                        }.onFailure {
                            it.throwIfDuplicateMapKey()
                            if (valueHandlesOwnShape) {
                                throw it.asCallerAdapterReadException()
                            }
                            reader.skipUnreadValueIfPossible(pathBeforeRead)
                            notify(
                                config = config,
                                type = type,
                                reader = reader,
                                token = failureToken,
                                reason = it.message ?: it.javaClass.name,
                                kind = ParseExceptionKind.MAP_ITEM,
                                mapItemKey = key?.toString() ?: "null",
                                path = pathBeforeRead
                            )
                        }
                        while (runRecovering { reader.hasNext() }.getOrDefault(false)) {
                            reader.skipValue()
                        }
                        reader.endArray()
                    }
                    reader.endArray()
                }
                return values as T
            }
        }
    }

    private fun mapKeyValueTypes(type: Type): Pair<Type, Type> {
        val keyValueTypes = GsonTypes.getMapKeyAndValueTypes(type, GsonTypes.getRawType(type))
        return keyValueTypes[0] to keyValueTypes[1]
    }

    /**
     * 获取 Map key 的 Adapter。
     *
     * @param gson 当前 Gson。
     * @param keyType Map key 类型。
     * @return key 对应的 Adapter。
     */
    private fun keyAdapter(gson: Gson, keyType: Type): TypeAdapter<*> {
        // Gson 的 Map key 规则里 Boolean key 使用字符串 Adapter，避免 true/false 被当普通 JSON boolean。
        return if (keyType == Boolean::class.javaObjectType || keyType == java.lang.Boolean.TYPE) {
            TypeAdapters.BOOLEAN_AS_STRING
        } else {
            gson.getAdapter(TypeToken.get(keyType))
        }
    }

    /**
     * 把 JSON object member name 解析成声明的 Map key 类型。
     *
     * @param key JSON 对象里的字段名。
     * @param keyType Map 声明的 key 类型。
     * @param keyAdapter key 对应的 Gson Adapter。
     * @return 解析成功时返回 key 值，失败时返回失败原因。
     */
    private fun parseKey(
        key: String,
        keyType: Type,
        keyAdapter: TypeAdapter<Any?>,
        keyHandlesOwnShape: Boolean
    ): ParsedMapKey {
        if (keyType == String::class.java) return ParsedMapKey.Success(key)
        // 对象形式的 Map key 来自 JSON name，用 JsonPrimitive 交给 keyAdapter，避免手写 JSON 字符串转义出错。
        return runRecovering {
            ParsedMapKey.Success(
                SafePrimitiveAdapters.withoutRootFallback {
                    keyAdapter.fromJsonTree(JsonPrimitive(key))
                }
            )
        }.getOrElse {
            if (keyHandlesOwnShape) {
                throw it.asCallerAdapterReadException()
            }
            ParsedMapKey.Failure(it.message ?: it.javaClass.name)
        }
    }

    /**
     * Map value 本身不是字段，但它处在 Map 字段读取期间时可以继承字段级 shape coercion 策略。
     */
    private fun canCoerceMapValue(
        valueRawType: Class<*>,
        token: JsonToken,
        config: SafeParserConfig
    ): Boolean {
        val policy = ShapeCoercionReadContext.currentPolicy(config)
        return when {
            token == JsonToken.BEGIN_ARRAY &&
                policy.supportsObjectFromArray() &&
                TokenRules.isObjectLike(valueRawType) -> true
            token == JsonToken.BEGIN_OBJECT &&
                policy.supportsCollectionFromObject() &&
                (Collection::class.java.isAssignableFrom(valueRawType) || valueRawType.isArray) -> true
            else -> false
        }
    }

    private fun readMapValue(
        reader: JsonReader,
        valueType: TypeToken<*>,
        valueAdapter: TypeAdapter<Any?>,
        valueToken: JsonToken,
        valueRawType: Class<*>,
        valueHandlesOwnShape: Boolean,
        pathBeforeRead: String,
        config: SafeParserConfig
    ): ParsedMapValue {
        if (
            !valueHandlesOwnShape &&
            valueToken == JsonToken.BEGIN_ARRAY &&
            ShapeCoercionReadContext.currentPolicy(config).supportsObjectFromArray() &&
            TokenRules.isObjectLike(valueRawType)
        ) {
            return readObjectMapValueFromFirstArrayItem(
                reader = reader,
                valueType = valueType,
                valueAdapter = valueAdapter,
                valueToken = valueToken,
                pathBeforeRead = pathBeforeRead,
                config = config
            )
        }
        return ParsedMapValue.Success(valueAdapter.read(reader))
    }

    private fun readObjectMapValueFromFirstArrayItem(
        reader: JsonReader,
        valueType: TypeToken<*>,
        valueAdapter: TypeAdapter<Any?>,
        valueToken: JsonToken,
        pathBeforeRead: String,
        config: SafeParserConfig
    ): ParsedMapValue {
        reader.beginArray()
        if (!reader.hasNext()) {
            reader.endArray()
            dispatchShapeCoercion(
                config = config,
                type = valueType,
                reader = reader,
                token = valueToken,
                action = ShapeCoercionAction.EmptyArrayForObjectSkipped,
                path = pathBeforeRead
            )
            return ParsedMapValue.Skipped
        }

        val firstItemPath = reader.path
        val firstItemToken = reader.peek()
        if (firstItemToken != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            while (reader.hasNext()) {
                reader.skipValue()
            }
            reader.endArray()
            dispatchShapeCoercion(
                config = config,
                type = valueType,
                reader = reader,
                token = valueToken,
                action = ShapeCoercionAction.CoercionFailed,
                reason = "First array item is $firstItemToken, not an object value",
                path = pathBeforeRead
            )
            return ParsedMapValue.Skipped
        }

        val value = runRecovering { valueAdapter.read(reader) }
            .getOrElse { error ->
                reader.skipUnreadValueIfPossible(firstItemPath)
                while (runRecovering { reader.hasNext() }.getOrDefault(false)) {
                    reader.skipValue()
                }
                reader.endArray()
                dispatchShapeCoercion(
                    config = config,
                    type = valueType,
                    reader = reader,
                    token = valueToken,
                    action = ShapeCoercionAction.CoercionFailed,
                    reason = error.message ?: error.javaClass.name,
                    path = pathBeforeRead
                )
                return ParsedMapValue.Skipped
            }

        dispatchShapeCoercion(
            config = config,
            type = valueType,
            reader = reader,
            token = valueToken,
            action = ShapeCoercionAction.ObjectFromFirstArrayItem,
            path = pathBeforeRead
        )
        var discardedItemCount = 0
        while (reader.hasNext()) {
            reader.skipValue()
            discardedItemCount++
        }
        reader.endArray()
        if (discardedItemCount > 0) {
            dispatchShapeCoercion(
                config = config,
                type = valueType,
                reader = reader,
                token = valueToken,
                action = ShapeCoercionAction.ArrayExtraItemsSkipped,
                discardedItemCount = discardedItemCount,
                path = pathBeforeRead
            )
        }
        return ParsedMapValue.Success(value)
    }

    private fun putMapValue(values: MutableMap<Any?, Any?>, key: Any?, value: Any?) {
        val replaced = values.put(key, value)
        if (replaced != null) {
            throw JsonSyntaxException("duplicate key: $key")
        }
    }

    private fun Throwable.throwIfDuplicateMapKey() {
        if (this is JsonSyntaxException && message.orEmpty().startsWith("duplicate key: ")) {
            throw this
        }
    }

    /**
     * 把 JsonElement 形式的 key 转成 JSON object member name。
     *
     * @param keyElement keyAdapter 生成的 key 元素。
     * @return 能作为 JSON object member name 的字符串。
     */
    private fun keyToString(keyElement: JsonElement): String {
        if (keyElement.isJsonPrimitive) {
            val primitive: JsonPrimitive = keyElement.asJsonPrimitive
            return when {
                primitive.isNumber -> primitive.asNumber.toString()
                primitive.isBoolean -> primitive.asBoolean.toString()
                primitive.isString -> primitive.asString
                else -> throw AssertionError()
            }
        }
        if (keyElement.isJsonNull) return "null"
        throw AssertionError()
    }

    /**
     * 安全地把 Map key 转成字符串。
     *
     * @param key 原始 Map key。
     * @param keyAdapter key 对应的 Gson Adapter。
     * @return JSON object member name。
     */
    private fun safeKeyToString(key: Any?, keyAdapter: TypeAdapter<Any?>): String {
        return runRecovering {
            val keyElement = keyAdapter.toJsonTree(key)
            if (keyElement.isJsonObject || keyElement.isJsonArray) {
                // 未开启复杂 key 写出时，复杂 key 只能退回 toString，保持对象形式输出。
                key.toString()
            } else {
                keyToString(keyElement)
            }
        }.getOrElse {
            key.toString()
        }
    }

    /**
     * 创建可写入的 Map 容器。
     *
     * @param type 完整 Map 类型。
     * @param rawType Map 原始 Class。
     * @param config SafeParser 配置。
     * @return 可变 Map。构造失败时使用 linkedMapOf 兜底。
     */
    @Suppress("UNCHECKED_CAST")
    private fun emptyMutableMapFor(
        type: Type,
        rawType: Class<*>,
        config: SafeParserConfig
    ): MutableMap<Any?, Any?> {
        val constructed = SafeObjectConstructor.construct<Any>(type, rawType, config)
        return constructed as? MutableMap<Any?, Any?> ?: linkedMapOf()
    }

    /**
     * Map key 解析结果。
     *
     * 使用 sealed interface 是为了让读取逻辑必须显式处理成功和失败两种情况。
     */
    private sealed interface ParsedMapKey {
        /** key 解析成功，value 是真正要放进 Map 的 key。 */
        data class Success(val value: Any?) : ParsedMapKey
        /** key 解析失败，reason 会进入错配事件，帮助定位是哪个 key 不合法。 */
        data class Failure(val reason: String) : ParsedMapKey
    }

    private sealed interface ParsedMapValue {
        data class Success(val value: Any?) : ParsedMapValue
        data object Skipped : ParsedMapValue
    }
}
