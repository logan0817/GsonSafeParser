package io.github.logan.gsonsafeparser.internal.adapter

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.internal.runRecovering
import org.json.JSONArray
import org.json.JSONObject

/**
 * org.json 类型的桥接 Adapter。
 *
 * Gson 默认不认识 `JSONObject/JSONArray` 的内部结构。这里先借用 JsonElement 读写，
 * 再转换成 org.json 对象，遇到形状不匹配时返回 null，避免反射到 org.json 的内部字段。
 */
internal object SafeOrgJsonAdapters {
    /**
     * 根据目标类型创建 org.json Adapter。
     *
     * @param gson 当前 Gson，用来获取 JsonElement Adapter。
     * @param type 目标类型。
     * @param config SafeParser 配置，用于错形事件分发。
     * @return 只在目标类型是 JSONObject 或 JSONArray 时返回 Adapter。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> create(gson: Gson, type: TypeToken<T>, config: SafeParserConfig): TypeAdapter<T>? {
        val adapter = when (type.rawType) {
            JSONObject::class.java -> jsonObjectAdapter(gson, type, config)
            JSONArray::class.java -> jsonArrayAdapter(gson, type, config)
            else -> return null
        }
        return adapter as TypeAdapter<T>
    }

    /**
     * JSONObject Adapter。
     *
     * @param gson 当前 Gson。
     * @param type 当前目标类型，用于事件里的 expectedType。
     * @param config SafeParser 配置。
     * @return JSONObject 的读写 Adapter。
     */
    private fun jsonObjectAdapter(
        gson: Gson,
        type: TypeToken<*>,
        config: SafeParserConfig
    ): TypeAdapter<JSONObject?> {
        val proxy = gson.getAdapter(JsonElement::class.java)
        return object : TypeAdapter<JSONObject?>() {
            override fun write(out: JsonWriter, value: JSONObject?) {
                if (value == null) {
                    out.nullValue()
                    return
                }
                proxy.write(out, proxy.fromJson(value.toString()))
            }

            override fun read(reader: JsonReader): JSONObject? {
                val token = reader.peek()
                val pathBeforeRead = reader.path
                val element = proxy.read(reader)
                if (element.isJsonNull) return null
                if (!element.isJsonObject) {
                    notify(config, type, reader, token, path = pathBeforeRead)
                    return null
                }
                // 通过字符串转换能保留 org.json 自己的结构语义，也避免直接依赖内部实现。
                return runRecovering { JSONObject(element.toString()) }.getOrNull()
            }
        }
    }

    /**
     * JSONArray Adapter。
     *
     * @param gson 当前 Gson。
     * @param type 当前目标类型，用于事件里的 expectedType。
     * @param config SafeParser 配置。
     * @return JSONArray 的读写 Adapter。
     */
    private fun jsonArrayAdapter(
        gson: Gson,
        type: TypeToken<*>,
        config: SafeParserConfig
    ): TypeAdapter<JSONArray?> {
        val proxy = gson.getAdapter(JsonElement::class.java)
        return object : TypeAdapter<JSONArray?>() {
            override fun write(out: JsonWriter, value: JSONArray?) {
                if (value == null) {
                    out.nullValue()
                    return
                }
                proxy.write(out, proxy.fromJson(value.toString()))
            }

            override fun read(reader: JsonReader): JSONArray? {
                val token = reader.peek()
                val pathBeforeRead = reader.path
                val element = proxy.read(reader)
                if (element.isJsonNull) return null
                if (!element.isJsonArray) {
                    notify(config, type, reader, token, path = pathBeforeRead)
                    return null
                }
                return runRecovering { JSONArray(element.toString()) }.getOrNull()
            }
        }
    }
}
