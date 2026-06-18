package io.github.logan.gsonsafeparser.internal.adapter

import com.google.gson.JsonIOException
import com.google.gson.JsonParseException
import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import io.github.logan.gsonsafeparser.FallbackPolicy
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.ShapeCoercionAction
import io.github.logan.gsonsafeparser.internal.FallbackValues
import io.github.logan.gsonsafeparser.internal.TokenRules
import io.github.logan.gsonsafeparser.internal.asCallerAdapterReadException
import io.github.logan.gsonsafeparser.internal.runRecovering
import io.github.logan.gsonsafeparser.internal.throwIfFatal
import java.io.IOException
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Array
import java.lang.reflect.Type

/**
 * 对象数组的安全 Adapter。
 *
 * 默认仍按 JSON array 读取。只有字段级 shape coercion 策略允许时，才把单个 JSON object 包装成长度为 1 的数组。
 */
internal object SafeArrayAdapterFactory {
    /**
     * 创建数组类型 Adapter。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> create(
        gson: Gson,
        type: TypeToken<T>,
        config: SafeParserConfig,
        delegate: TypeAdapter<T>
    ): TypeAdapter<T> {
        val rawType = type.rawType
        val componentType = arrayComponentType(type.type, rawType)
        val componentTypeToken = TypeToken.get(componentType)
        val componentRawType = componentTypeToken.rawType
        val componentAdapter = gson.getAdapter(componentTypeToken) as TypeAdapter<Any?>
        val componentHandlesOwnShape = componentAdapter.handlesOwnInputShape() ||
            componentRawType.delegatesPrimitiveInputShape(config)
        val componentAcceptsObject = TokenRules.accepts(componentType, componentRawType, JsonToken.BEGIN_OBJECT)

        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T?) {
                delegate.write(out, value)
            }

            override fun read(reader: JsonReader): T? {
                val token = reader.peek()
                val pathBeforeRead = reader.path
                if (
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
                if (!TokenRules.accepts(type.type, rawType, token)) {
                    notify(config, type, reader, token, path = pathBeforeRead)
                    reader.skipValue()
                    return FallbackValues.value(type.type, rawType, config.fallbackPolicy)
                }
                return try {
                    delegate.read(reader)
                } catch (error: IllegalStateException) {
                    error.throwIfFatal()
                    if (componentHandlesOwnShape) throw error.asCallerAdapterReadException()
                    recover(reader, type, rawType, error, token, pathBeforeRead)
                } catch (error: NumberFormatException) {
                    error.throwIfFatal()
                    if (componentHandlesOwnShape) throw error.asCallerAdapterReadException()
                    recover(reader, type, rawType, error, token, pathBeforeRead)
                } catch (error: JsonParseException) {
                    error.throwIfFatal()
                    if (componentHandlesOwnShape) throw error.asCallerAdapterReadException()
                    recover(reader, type, rawType, error, token, pathBeforeRead)
                } catch (error: RuntimeException) {
                    error.throwIfFatal()
                    if (componentHandlesOwnShape) throw error.asCallerAdapterReadException()
                    recover(reader, type, rawType, error, token, pathBeforeRead)
                } catch (error: IOException) {
                    error.throwIfFatal()
                    if (componentHandlesOwnShape) throw error.asCallerAdapterReadException()
                    throw JsonIOException(error)
                }
            }

            private fun recover(
                reader: JsonReader,
                type: TypeToken<T>,
                rawType: Class<*>,
                error: RuntimeException,
                token: JsonToken,
                pathBeforeRead: String
            ): T? {
                reader.skipUnreadValueIfPossible(pathBeforeRead)
                notify(
                    config = config,
                    type = type,
                    reader = reader,
                    token = token,
                    reason = error.message ?: error.javaClass.name,
                    path = pathBeforeRead
                )
                return FallbackValues.value(type.type, rawType, config.fallbackPolicy)
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
                return arrayFor(rawType, componentType, listOf(value)) as T
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

    private fun arrayFor(rawType: Class<*>, componentType: Class<*>, values: List<Any?>): Any {
        val array = Array.newInstance(componentType, values.size)
        values.forEachIndexed { index, value ->
            Array.set(array, index, value)
        }
        return array
    }
}
