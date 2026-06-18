package io.github.logan.gsonsafeparser.internal.adapter

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonWriter
import io.github.logan.gsonsafeparser.PrimitiveParsingPolicy
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.internal.TokenRules
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable

/**
 * 标记当前 Adapter 由 GsonSafeParser 内部创建。
 *
 * 这个接口用于运行时分类，不能依赖类名判断；release 混淆后接口关系仍然稳定。
 */
internal interface SafeRuntimeTypeAdapter

/**
 * 标记当前 Adapter 是 Safe Reflective Adapter。
 *
 * 序列化时需要知道声明类型和运行时类型哪个 Adapter 更应该被使用，避免子类反射 Adapter 覆盖显式声明的自定义 Adapter。
 */
internal interface ReflectiveRuntimeTypeAdapter : SafeRuntimeTypeAdapter

/**
 * 按 Gson 的运行时类型规则写出字段值。
 *
 * 如果运行时类型有非反射自定义 Adapter，优先使用它；如果声明类型已有自定义 Adapter，
 * 就不让运行时反射 Adapter 抢走写出逻辑。
 *
 * @param gson 当前 Gson，用来查运行时类型的 Adapter。
 * @param declaredType 字段或集合元素声明的类型。
 * @param out JSON 输出流。
 * @param value 要写出的实际值。
 */
@Suppress("UNCHECKED_CAST")
internal fun TypeAdapter<Any?>.writeRuntime(
    gson: Gson,
    declaredType: Type,
    out: JsonWriter,
    value: Any?
) {
    // runtimeType 是 value 实际运行时类型。比如字段声明为 Animal，实际对象可能是 Cat。
    val runtimeType = runtimeTypeIfMoreSpecific(declaredType, value)
    val adapter = if (runtimeType != declaredType) {
        // runtimeAdapter 是运行时类型对应的 Adapter，可能是用户自定义 Adapter，也可能是反射 Adapter。
        val runtimeAdapter = gson.getAdapter(TypeToken.get(runtimeType)) as TypeAdapter<Any?>
        when {
            !runtimeAdapter.isReflectiveAdapter() -> runtimeAdapter
            !isReflectiveAdapter() -> this
            else -> runtimeAdapter
        }
    } else {
        this
    }
    adapter.write(out, value)
}

/**
 * 判断 Adapter 是否是反射型 Adapter。
 *
 * @return `true` 表示它会按字段反射写出对象，通常不应该覆盖用户显式声明的自定义 Adapter。
 */
private fun TypeAdapter<*>.isReflectiveAdapter(): Boolean {
    if (this is ReflectiveRuntimeTypeAdapter) return true
    return javaClass.name.contains("ReflectiveTypeAdapterFactory")
}

/**
 * 判断当前 adapter 是否应保留自己的输入形状规则。
 *
 * GsonBuilder.registerTypeAdapter / registerTypeHierarchyAdapter 和 @JsonAdapter 都代表调用方已经显式声明
 * 了读取规则，SafeParser 不应在外层先做 token 形状过滤或 shape coercion。
 */
internal fun TypeAdapter<*>.handlesOwnInputShape(): Boolean {
    if (this is SafeRuntimeTypeAdapter) return false
    val name = javaClass.name
    return when {
        name.startsWith("com.google.gson.Gson\$") -> false
        name.startsWith("com.google.gson.internal.bind.TreeTypeAdapter") -> true
        gsonBuiltInAnonymousAdapterName.matches(name) -> false
        name.startsWith("com.google.gson.internal.bind.TypeAdapters\$EnumTypeAdapter") -> false
        name.startsWith("com.google.gson.internal.bind.EnumTypeAdapter") -> false
        name.startsWith("com.google.gson.internal.bind.NumberTypeAdapter") -> false
        name.startsWith("com.google.gson.internal.bind.ReflectiveTypeAdapterFactory") -> false
        name.startsWith("com.google.gson.internal.bind.CollectionTypeAdapterFactory") -> false
        name.startsWith("com.google.gson.internal.bind.MapTypeAdapterFactory") -> false
        name.startsWith("com.google.gson.internal.bind.ArrayTypeAdapter") -> false
        else -> true
    }
}

private val gsonBuiltInAnonymousAdapterName = Regex("""com\.google\.gson\.internal\.bind\.TypeAdapters\$\d+""")

internal fun Class<*>.delegatesPrimitiveInputShape(config: SafeParserConfig): Boolean {
    return config.primitiveParsingPolicy == PrimitiveParsingPolicy.DelegateToGson &&
        (TokenRules.isString(this) || TokenRules.isBoolean(this) || TokenRules.isNumber(this))
}

/**
 * 只有声明类型是 Class 或类型变量时才尝试替换为运行时类型。
 *
 * 已经是参数化类型时不替换，避免丢掉 `List<User>` 这类泛型信息。
 */
private fun runtimeTypeIfMoreSpecific(type: Type, value: Any?): Type {
    if (value != null && (type is Class<*> || type is TypeVariable<*>)) {
        return value.javaClass
    }
    return type
}
