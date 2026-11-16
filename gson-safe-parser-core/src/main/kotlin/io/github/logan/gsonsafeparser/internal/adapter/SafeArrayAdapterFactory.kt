package io.github.logan.gsonsafeparser.internal.adapter

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import io.github.logan.gsonsafeparser.FallbackPolicy
import io.github.logan.gsonsafeparser.ParseExceptionKind
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.ShapeCoercionAction
import io.github.logan.gsonsafeparser.internal.FallbackValues
import io.github.logan.gsonsafeparser.internal.TokenRules
import io.github.logan.gsonsafeparser.internal.asCallerAdapterReadException
import io.github.logan.gsonsafeparser.internal.runRecovering
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Array
import java.lang.reflect.Type

/**
 * 数组的安全 Adapter。
 *
 * 每个元素独立读取，错形或可恢复值错误只跳过当前元素。原始类型数组不接收 null。
 * 只有字段级 shape coercion 策略允许时，才把单个 JSON object 包装成长度为 1 的对象数组。
 */
internal object SafeArrayAdapterFactory {
    /**
     * 创建数组类型 Adapter。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> create(
        gson: Gson,
        type: TypeToken<T>,
        config: SafeParserConfig
    ): TypeAdapter<T> {
        val rawType = type.rawType
        val componentType = arrayComponentType(type.type, rawType)
        val componentTypeToken = TypeToken.get(componentType)
        val componentRawType = componentTypeToken.rawType
        val componentAdapter = gson.getAdapter(componentTypeToken) as TypeAdapter<Any?>
        val componentHandlesOwnShape = componentRawType.getAnnotation(JsonAdapter::class.java) != null ||
            componentAdapter.handlesOwnInputShape() ||
            componentRawType.delegatesPrimitiveInputShape(config)
        val componentAcceptsObject = TokenRules.accepts(componentType, componentRawType, JsonToken.BEGIN_OBJECT)

        return object : TypeAdapter<T>(), SafeRuntimeTypeAdapter {
            override fun write(out: JsonWriter, value: T?) {
                if (value == null) {
                    out.nullValue()
                    return
                }
                out.beginArray()
                for (index in 0 until Array.getLength(value)) {
                    componentAdapter.writeRuntime(gson, componentType, out, Array.get(value, index))
                }
                out.endArray()
            }

            override fun read(reader: JsonReader): T? {
                val token = reader.peek()
                val pathBeforeRead = reader.path
                if (
                    !componentRawType.isPrimitive &&
                    token == JsonToken.BEGIN_OBJECT &&
                    ShapeCoercionReadContext.currentPolicy(config).supportsCollectionFromObject() &&
                    componentAcceptsObject
                ) {
                    return readSingleObjectAsArray(
                        reader = reader,
                        token = token,
                        type = type,
                        rawType = rawType,
                        componentType = componentRawType,
                        componentAdapter = componentAdapter
                    )
                }
                if (token == JsonToken.NULL) {
                    reader.nextNull()
                    return null
                }
                if (!TokenRules.accepts(type.type, rawType, token)) {
                    notify(config, type, reader, token, path = pathBeforeRead)
                    reader.skipValue()
                    return FallbackValues.value(type.type, rawType, config.fallbackPolicy)
                }
                val values = mutableListOf<Any?>()
                reader.beginArray()
                while (reader.hasNext()) {
                    val itemToken = reader.peek()
                    val itemPath = reader.path
                    if (!componentHandlesOwnShape && !TokenRules.accepts(componentType, componentRawType, itemToken)) {
                        notify(
                            config = config,
                            type = componentTypeToken,
                            reader = reader,
                            token = itemToken,
                            kind = ParseExceptionKind.LIST_ITEM,
                            path = itemPath
                        )
                        reader.skipValue()
                        continue
                    }
                    runRecovering { componentAdapter.read(reader) }
                        .onSuccess { value ->
                            if (value != null || !componentRawType.isPrimitive) {
                                values += value
                            } else if (itemToken == JsonToken.NULL) {
                                notify(
                                    config = config,
                                    type = componentTypeToken,
                                    reader = reader,
                                    token = itemToken,
                                    kind = ParseExceptionKind.LIST_ITEM,
                                    path = itemPath
                                )
                            }
                        }
                        .onFailure { error ->
                            if (componentHandlesOwnShape) {
                                throw error.asCallerAdapterReadException()
                            }
                            reader.skipUnreadValueIfPossible(itemPath)
                            notify(
                                config = config,
                                type = componentTypeToken,
                                reader = reader,
                                token = itemToken,
                                reason = error.message ?: error.javaClass.name,
                                kind = ParseExceptionKind.LIST_ITEM,
                                path = itemPath
                            )
                        }
                }
                reader.endArray()
                return arrayFor(componentRawType, values) as T
            }

            private fun readSingleObjectAsArray(
                reader: JsonReader,
                token: JsonToken,
                type: TypeToken<T>,
                rawType: Class<*>,
                componentType: Class<*>,
                componentAdapter: TypeAdapter<Any?>
            ): T? {
                val pathBeforeRead = reader.path
                val value = runRecovering { componentAdapter.read(reader) }
                    .getOrElse { error ->
                        if (componentHandlesOwnShape) {
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
                        return fallbackArray(rawType)
                    }
                dispatchShapeCoercion(
                    config = config,
                    type = type,
                    reader = reader,
                    token = token,
                    action = ShapeCoercionAction.ArrayFromSingleObject,
                    path = pathBeforeRead
                )
                return arrayFor(componentType, listOf(value)) as T
            }

            private fun fallbackArray(rawType: Class<*>): T? {
                if (config.fallbackPolicy == FallbackPolicy.NullOnly) return null
                return Array.newInstance(rawType.componentType ?: Any::class.java, 0) as T
            }
        }
    }

    private fun arrayComponentType(type: Type, rawType: Class<*>): Type {
        return when (type) {
            is GenericArrayType -> type.genericComponentType
            else -> rawType.componentType ?: Any::class.java
        }
    }

    private fun arrayFor(componentType: Class<*>, values: List<Any?>): Any {
        val array = Array.newInstance(componentType, values.size)
        values.forEachIndexed { index, value ->
            Array.set(array, index, value)
        }
        return array
    }
}
