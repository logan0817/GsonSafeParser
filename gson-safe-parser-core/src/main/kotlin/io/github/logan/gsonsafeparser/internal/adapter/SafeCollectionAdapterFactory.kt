package io.github.logan.gsonsafeparser.internal.adapter

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.internal.GsonTypes
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
import java.util.ArrayDeque
import java.util.Queue
import java.util.SortedSet
import java.util.TreeSet

/**
 * Collection 系列的安全 Adapter。
 *
 * 集合整体错形时返回空容器或 null；单个元素错形时只跳过当前元素，并继续读取后面的元素。
 * 这样能避免一个坏 item 让整个响应 Bean 解析失败。
 */
internal object SafeCollectionAdapterFactory {
    /**
     * 创建集合类型的 Safe Adapter。
     *
     * @param gson 当前 Gson，用来获取集合元素的 Adapter。
     * @param type 集合完整类型，例如 `List<User>`。
     * @param config SafeParser 配置。
     * @return 能读写该集合类型的 Adapter。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> create(gson: Gson, type: TypeToken<T>, config: SafeParserConfig): TypeAdapter<T> {
        // elementType 是集合元素类型，比如 List<User> 的元素类型就是 User。
        val elementType = collectionElementType(type.type)
        val elementTypeToken = TypeToken.get(elementType)
        // elementAdapter 负责读写单个元素，集合 Adapter 只负责数组结构和局部容错。
        val elementAdapter = gson.getAdapter(elementTypeToken) as TypeAdapter<Any?>
        val elementRawType = elementTypeToken.rawType
        val elementHandlesOwnShape =
            elementRawType.getAnnotation(JsonAdapter::class.java) != null ||
                elementRawType.delegatesPrimitiveInputShape(config) ||
                elementAdapter.handlesOwnInputShape()
        val elementAcceptsObject = TokenRules.accepts(elementType, elementRawType, JsonToken.BEGIN_OBJECT)
        val rawType = type.rawType

        return object : TypeAdapter<T>() {
            /**
             * 把集合写成 JSON 数组。
             *
             * @param out JSON 输出流。
             * @param value 要写出的集合值。
             */
            override fun write(out: JsonWriter, value: T?) {
                if (value == null) {
                    out.nullValue()
                    return
                }
                out.beginArray()
                (value as Collection<*>).forEach {
                    elementAdapter.writeRuntime(gson, elementType, out, it)
                }
                out.endArray()
            }

            /**
             * 从 JSON 数组读取集合。
             *
             * @param reader Gson Reader。
             * @return 构造后的集合；整体错形时按策略返回空集合或 null。
             */
            override fun read(reader: JsonReader): T {
                val token = reader.peek()
                val pathBeforeRead = reader.path
                if (token == JsonToken.NULL) {
                    reader.nextNull()
                    if (config.fallbackPolicy == FallbackPolicy.NullOnly) return null as T
                    return emptyCollection(type.type, rawType, config) as T
                }
                if (
                    token == JsonToken.BEGIN_OBJECT &&
                    ShapeCoercionReadContext.currentPolicy(config).supportsCollectionFromObject() &&
                    elementAcceptsObject
                ) {
                    return readSingleObjectAsCollection(
                        reader = reader,
                        token = token,
                        type = type,
                        elementAdapter = elementAdapter,
                        elementHandlesOwnShape = elementHandlesOwnShape,
                        rawType = rawType
                    )
                }
                if (token != JsonToken.BEGIN_ARRAY) {
                    notify(config, type, reader, token)
                    reader.skipValue()
                    if (config.fallbackPolicy == FallbackPolicy.NullOnly) return null as T
                    return emptyCollection(type.type, rawType, config) as T
                }

                // values 暂存已经成功解析的元素。失败元素不会进入这里。
                val values = mutableListOf<Any?>()
                reader.beginArray()
                while (reader.hasNext()) {
                    // itemToken 是当前元素的 JSON 形状，先判断形状可以避免错误元素破坏整个列表读取。
                    val itemToken = reader.peek()
                    if (!elementHandlesOwnShape && !TokenRules.accepts(elementType, elementRawType, itemToken)) {
                        // item 级错配只影响当前元素，集合本身和后续元素都应该继续保留。
                        notify(
                            config = config,
                            type = elementTypeToken,
                            reader = reader,
                            token = itemToken,
                            kind = ParseExceptionKind.LIST_ITEM
                        )
                        reader.skipValue()
                        continue
                    }
                    val pathBeforeRead = reader.path
                    runRecovering { elementAdapter.read(reader) }
                        .onSuccess { values += it }
                        .onFailure {
                            if (elementHandlesOwnShape) {
                                throw it.asCallerAdapterReadException()
                            }
                            // delegate 半路失败时先尽量把 reader 推过当前 item，再发事件，避免后续 item 被错位读取。
                            reader.skipUnreadValueIfPossible(pathBeforeRead)
                            notify(
                                config = config,
                                type = elementTypeToken,
                                reader = reader,
                                token = itemToken,
                                reason = it.message ?: it.javaClass.name,
                                kind = ParseExceptionKind.LIST_ITEM,
                                path = pathBeforeRead
                            )
                        }
                }
                reader.endArray()

                return materializeCollectionOrFallback(
                    reader = reader,
                    token = token,
                    type = type,
                    rawType = rawType,
                    pathBeforeRead = pathBeforeRead,
                    values = values
                )
            }

            private fun readSingleObjectAsCollection(
                reader: JsonReader,
                token: JsonToken,
                type: TypeToken<T>,
                elementAdapter: TypeAdapter<Any?>,
                elementHandlesOwnShape: Boolean,
                rawType: Class<*>
            ): T {
                val pathBeforeRead = reader.path
                val value = runRecovering { elementAdapter.read(reader) }
                    .getOrElse { error ->
                        if (elementHandlesOwnShape) {
                            throw error.asCallerAdapterReadException()
                        }
                        reader.skipUnreadValueIfPossible(pathBeforeRead)
                        dispatchShapeCoercion(
                            config = config,
                            type = type,
                            reader = reader,
                            token = token,
                            action = ShapeCoercionAction.CoercionFailed,
                            reason = error.message ?: error.javaClass.name,
                            path = pathBeforeRead
                        )
                        return fallbackCollection(type, rawType) as T
                    }
                val collection = runRecovering {
                    collectionFor(type.type, rawType, config, listOf(value)) as T
                }.getOrElse { error ->
                    dispatchShapeCoercion(
                        config = config,
                        type = type,
                        reader = reader,
                        token = token,
                        action = ShapeCoercionAction.CoercionFailed,
                        reason = error.message ?: error.javaClass.name,
                        path = pathBeforeRead
                    )
                    return fallbackCollection(type, rawType) as T
                }
                dispatchShapeCoercion(
                    config = config,
                    type = type,
                    reader = reader,
                    token = token,
                    action = ShapeCoercionAction.CollectionFromSingleObject,
                    path = pathBeforeRead
                )
                return collection
            }

            private fun fallbackCollection(
                type: TypeToken<T>,
                rawType: Class<*>
            ): Collection<Any?>? {
                if (config.fallbackPolicy == FallbackPolicy.NullOnly) return null
                return emptyCollection(type.type, rawType, config)
            }

            private fun materializeCollectionOrFallback(
                reader: JsonReader,
                token: JsonToken,
                type: TypeToken<T>,
                rawType: Class<*>,
                pathBeforeRead: String,
                values: List<Any?>
            ): T {
                return runRecovering {
                    collectionFor(type.type, rawType, config, values) as T
                }.getOrElse { error ->
                    notify(
                        config = config,
                        type = type,
                        reader = reader,
                        token = token,
                        reason = error.message ?: error.javaClass.name,
                        path = pathBeforeRead
                    )
                    fallbackCollection(type, rawType) as T
                }
            }
        }
    }

    /**
     * 解析集合元素类型。
     *
     * @param type 完整集合类型，例如 `List<User>`。
     * @return 集合元素类型，例如 `User`。
     */
    private fun collectionElementType(type: Type): Type {
        return GsonTypes.getCollectionElementType(type, GsonTypes.getRawType(type))
    }

    /**
     * 构造一个空集合。
     *
     * @param type 完整集合类型。
     * @param rawType 集合原始 Class。
     * @param config SafeParser 配置，里面可能有 InstanceCreator。
     * @return 可作为兜底值的空集合。
     */
    private fun emptyCollection(type: Type, rawType: Class<*>, config: SafeParserConfig): Collection<Any?> {
        val constructed = SafeObjectConstructor.construct<Any>(type, rawType, config)
        // 如果声明类型能构造出具体集合，就沿用它；构造失败时用最接近语义的 JDK 默认实现兜底。
        val fallback: Collection<Any?> = if (Set::class.java.isAssignableFrom(rawType)) {
            java.util.LinkedHashSet<Any?>()
        } else if (Queue::class.java.isAssignableFrom(rawType)) {
            ArrayDeque()
        } else {
            java.util.ArrayList<Any?>()
        }
        return constructed as? Collection<Any?> ?: fallback
    }

    /**
     * 把已成功读取的元素放进目标集合容器。
     *
     * @param type 完整集合类型。
     * @param rawType 集合原始 Class。
     * @param config SafeParser 配置。
     * @param values 已成功读取的元素列表。
     * @return 最终返回给业务字段的集合。
     */
    private fun collectionFor(
        type: Type,
        rawType: Class<*>,
        config: SafeParserConfig,
        values: List<Any?>
    ): Collection<Any?> {
        val collection = emptyCollection(type, rawType, config)
        // 不同集合接口使用不同默认实现，保证返回值符合声明类型的运行时容器语义。
        return when (collection) {
            is MutableCollection<Any?> -> collection.apply { addAll(values) }
            else -> when {
                SortedSet::class.java.isAssignableFrom(rawType) -> TreeSet<Any?>().apply { addAll(values) }
                Set::class.java.isAssignableFrom(rawType) -> LinkedHashSet<Any?>().apply { addAll(values) }
                Queue::class.java.isAssignableFrom(rawType) -> ArrayDeque<Any?>().apply { addAll(values) }
                else -> ArrayList<Any?>().apply { addAll(values) }
            }
        }
    }
}
