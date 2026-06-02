package io.github.logan.gsonsafeparser.internal.objectcreation

import com.google.gson.JsonIOException
import com.google.gson.ReflectionAccessFilter
import com.google.gson.internal.ReflectionAccessFilterHelper
import com.google.gson.internal.LinkedTreeMap
import com.google.gson.internal.UnsafeAllocator
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.internal.runRecovering
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.EnumMap
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Queue
import java.util.SortedMap
import java.util.SortedSet
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ConcurrentNavigableMap
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.javaType

/**
 * Safe Adapter 使用的对象构造器。
 *
 * 构造顺序遵循“显式配置优先、越不确定越靠后”的原则：显式 InstanceCreator 优先，其次特殊集合、Kotlin 主构造、
 * 无参构造、默认集合/Map 实现，最后才考虑 JDK Unsafe。反射被配置禁止时会及时回退或抛出。
 */
internal object SafeObjectConstructor {
    private val constructionStack = ThreadLocal<MutableList<Type>>()

    /**
     * 使用默认配置构造对象。
     *
     * @param rawType 要构造的原始 Class。
     * @return 构造出的对象；无法安全构造时返回 null。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> construct(rawType: Class<*>): T? {
        return construct(rawType, rawType, SafeParserConfig())
    }

    /**
     * 按完整 Type 和 rawType 构造对象。
     *
     * @param type 完整类型，可能包含泛型，例如 `List<User>`。
     * @param rawType 原始 Class，例如 `List::class.java`。
     * @param config SafeParser 配置。
     * @return 构造出的对象；无法安全构造时返回 null。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> construct(type: Type, rawType: Class<*>, config: SafeParserConfig): T? {
        // 用户显式注册的 InstanceCreator 是最明确的构造意图，必须优先于所有反射策略。
        constructFromInstanceCreator<T>(type, rawType, config)?.let { return it }
        return withConstructionGuard(type) {
            try {
                // filterResult 是 ReflectionAccessFilter 的最终结果，后面所有反射构造都必须尊重它。
                val filterResult = reflectionFilterResult(config, rawType)

                // value 是按不同类型分支得到的候选实例。基础类型和枚举不在这里构造。
                val value: Any? = when {
                    rawType == String::class.java -> null
                    rawType.isPrimitive -> null
                    rawType.isEnum -> null
                    rawType.isArray -> java.lang.reflect.Array.newInstance(rawType.componentType ?: Any::class.java, 0)
                    EnumSet::class.java.isAssignableFrom(rawType) -> enumSet(type)
                    rawType == EnumMap::class.java -> enumMap(type)
                    else -> constructObject(rawType, config, filterResult)
                        ?: defaultImplementation(type, rawType)
                        ?: unsafeConstruct(rawType, filterResult, config)
                }

                value as T?
            } catch (error: KotlinPrimaryConstructorFallbackException) {
                null
            }
        }
    }

    /**
     * 尝试使用用户显式注册的 InstanceCreator。
     *
     * @param type 完整目标类型。
     * @param rawType 原始 Class。
     * @param config SafeParser 配置。
     * @return InstanceCreator 创建出的对象；没有匹配项时返回 null。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> constructFromInstanceCreator(type: Type, rawType: Class<*>, config: SafeParserConfig): T? {
        config.instanceCreators[type]?.let { creator ->
            return creator.createInstance(type) as T?
        }
        config.instanceCreators[rawType]?.let { creator ->
            return creator.createInstance(type) as T?
        }
        return null
    }

    private fun constructObject(
        rawType: Class<*>,
        config: SafeParserConfig,
        filterResult: ReflectionAccessFilter.FilterResult
    ): Any? {
        if (filterResult == ReflectionAccessFilter.FilterResult.BLOCK_ALL) {
            // BLOCK_ALL 表示调用方明确不允许反射构造，不能偷偷走无参构造或 Kotlin 构造。
            throwBlocked(rawType, filterResult)
        }
        if (Modifier.isInterface(rawType.modifiers) || Modifier.isAbstract(rawType.modifiers)) {
            return null
        }
        return constructKotlin(rawType, config, filterResult)
            ?: constructNoArgs(rawType, filterResult)
    }

    /**
     * 尝试调用无参构造函数。
     *
     * @param rawType 目标 Class。
     * @param filterResult 反射访问限制结果。
     * @return 构造出的对象；没有无参构造时返回 null。
     */
    private fun constructNoArgs(
        rawType: Class<*>,
        filterResult: ReflectionAccessFilter.FilterResult
    ): Any? {
        val constructor = runRecovering { rawType.getDeclaredConstructor() }.getOrNull() ?: return null
        val canAccess = ReflectionAccessFilterHelper.canAccess(constructor, null) &&
            (filterResult != ReflectionAccessFilter.FilterResult.BLOCK_ALL ||
                Modifier.isPublic(constructor.modifiers))
        if (!canAccess && filterResult != ReflectionAccessFilter.FilterResult.ALLOW) {
            throwBlocked(rawType, filterResult)
        }
        if (filterResult == ReflectionAccessFilter.FilterResult.ALLOW) {
            // 只有 ALLOW 时才强行打开私有构造；其他 filter 结果必须尊重原始可见性。
            constructor.isAccessible = true
        }
        return runRecovering {
            constructor.newInstance()
        }.getOrNull()
    }

    /**
     * 尝试调用 Kotlin 主构造。
     *
     * @param rawType 目标 Class。
     * @param config SafeParser 配置。
     * @param filterResult 反射访问限制结果。
     * @return 构造出的对象；不是 Kotlin data class 或构造失败时返回 null。
     */
    private fun constructKotlin(
        rawType: Class<*>,
        config: SafeParserConfig,
        filterResult: ReflectionAccessFilter.FilterResult
    ): Any? {
        if (filterResult == ReflectionAccessFilter.FilterResult.BLOCK_ALL) return null
        return runRecovering {
            val kClass: KClass<*> = rawType.kotlin
            val constructor = kClass.primaryConstructor ?: return@runRecovering null
            if (filterResult == ReflectionAccessFilter.FilterResult.ALLOW) {
                constructor.isAccessible = true
            }
            // args 是传给 callBy 的参数表。optional 参数不传，让 Kotlin 自己使用默认值。
            val args = constructor.parameters
                .filterNot(KParameter::isOptional)
                .associateWith { parameter -> fallbackFor(parameter, config) }
            // callBy 会保留 Kotlin 默认参数，这样缺字段或错形字段不会覆盖 data class 里的默认值。
            constructor.callBy(args)
        }.getOrElse { error ->
            if (error.hasRecursiveConstructorFallback()) {
                throw JsonIOException(error.message, error)
            }
            throw KotlinPrimaryConstructorFallbackException(rawType, error)
        }
    }

    private fun reflectionFilterResult(
        config: SafeParserConfig,
        rawType: Class<*>
    ): ReflectionAccessFilter.FilterResult {
        if (config.reflectionAccessFilters.isEmpty()) return ReflectionAccessFilter.FilterResult.ALLOW
        return ReflectionAccessFilterHelper.getFilterResult(config.reflectionAccessFilters, rawType)
    }

    /**
     * 抛出反射被禁止的异常。
     *
     * @param rawType 当前要构造的类型。
     * @param filterResult 禁止反射的 filter 结果。
     */
    private fun throwBlocked(
        rawType: Class<*>,
        filterResult: ReflectionAccessFilter.FilterResult
    ): Nothing {
        throw JsonIOException(
            "Unable to create instance of $rawType; ReflectionAccessFilter result $filterResult " +
                "does not permit using reflection for this type."
        )
    }

    private fun fallbackFor(parameter: KParameter, config: SafeParserConfig): Any? {
        if (parameter.type.isMarkedNullable) return null
        val erasedClass = parameter.type.jvmErasure
        if (erasedClass.java.isEnum) {
            return erasedClass.java.enumConstants?.firstOrNull()
        }

        // 非空构造参数没有默认值时，给一个最小安全值，保证对象能先构造出来。
        return when (erasedClass) {
            Boolean::class -> false
            Byte::class -> 0.toByte()
            Short::class -> 0.toShort()
            Int::class -> 0
            Long::class -> 0L
            Float::class -> 0f
            Double::class -> 0.0
            Char::class -> '\u0000'
            String::class -> ""
            List::class, Collection::class -> emptyList<Any>()
            Set::class -> emptySet<Any>()
            Map::class -> emptyMap<Any, Any>()
            else -> {
                // parameterType 保留 Kotlin 参数里的泛型信息，避免构造嵌套泛型默认值时只剩 raw class。
                val parameterType = parameter.type.javaType
                val parameterRawType = runRecovering {
                    com.google.gson.reflect.TypeToken.get(parameterType).rawType
                }.getOrDefault(parameter.type.jvmErasure.java)
                construct(parameterType, parameterRawType, config)
            }
        }
    }

    private fun <T> withConstructionGuard(type: Type, block: () -> T): T {
        val existingStack = constructionStack.get()
        val stack = existingStack ?: mutableListOf<Type>().also(constructionStack::set)
        if (stack.any { activeType -> activeType == type }) {
            throw RecursiveConstructorFallbackException(type)
        }
        stack += type
        return try {
            block()
        } finally {
            stack.removeAt(stack.lastIndex)
            if (existingStack == null) {
                constructionStack.remove()
            }
        }
    }

    private fun defaultImplementation(type: Type, rawType: Class<*>): Any? {
        if (Collection::class.java.isAssignableFrom(rawType)) {
            // 按接口语义选择默认集合实现，避免把 Set/Queue 都退成 List。
            return when {
                SortedSet::class.java.isAssignableFrom(rawType) -> TreeSet<Any>()
                Set::class.java.isAssignableFrom(rawType) -> LinkedHashSet<Any>()
                Queue::class.java.isAssignableFrom(rawType) -> ArrayDeque<Any>()
                else -> ArrayList<Any>()
            }
        }

        if (Map::class.java.isAssignableFrom(rawType)) {
            // String key 使用 LinkedTreeMap 对齐 Gson，非 String key 使用 LinkedHashMap 保留插入顺序。
            return when {
                ConcurrentNavigableMap::class.java.isAssignableFrom(rawType) -> ConcurrentSkipListMap<Any, Any>()
                ConcurrentMap::class.java.isAssignableFrom(rawType) -> ConcurrentHashMap<Any, Any>()
                SortedMap::class.java.isAssignableFrom(rawType) -> TreeMap<Any, Any>()
                hasNonStringMapKey(type) -> LinkedHashMap<Any, Any>()
                else -> LinkedTreeMap<Any, Any>()
            }
        }

        return null
    }

    /**
     * 判断 Map key 是否不是 String。
     *
     * @param type 完整 Map 类型。
     * @return 非 String key 返回 true，无法判断时按 false 处理。
     */
    private fun hasNonStringMapKey(type: Type): Boolean {
        val keyType = (type as? ParameterizedType)?.actualTypeArguments?.firstOrNull() ?: return false
        val keyRawType = runRecovering {
            com.google.gson.reflect.TypeToken.get(keyType).rawType
        }.getOrNull() ?: return false
        return !String::class.java.isAssignableFrom(keyRawType)
    }

    private fun unsafeConstruct(
        rawType: Class<*>,
        filterResult: ReflectionAccessFilter.FilterResult,
        config: SafeParserConfig
    ): Any? {
        if (!config.useJdkUnsafe) {
            throw JsonIOException(
                "Unable to create instance of $rawType; usage of JDK Unsafe is disabled."
            )
        }
        // Unsafe 是最后兜底，只在反射明确允许时使用；它会绕过构造函数，所以不能提前尝试。
        if (filterResult != ReflectionAccessFilter.FilterResult.ALLOW) {
            return null
        }
        if (Modifier.isInterface(rawType.modifiers) || Modifier.isAbstract(rawType.modifiers)) {
            return null
        }
        return runRecovering { UnsafeAllocator.INSTANCE.newInstance(rawType) }.getOrNull()
    }

    /**
     * 构造 EnumSet。
     *
     * @param type EnumSet 的完整类型，例如 `EnumSet<Role>`。
     * @return 空 EnumSet；类型非法时抛 JsonIOException。
     */
    private fun enumSet(type: Type): EnumSet<*>? {
        // EnumSet/EnumMap 必须拿到真实枚举类型，否则运行时容器没有办法安全创建。
        val enumClass = firstTypeArgumentClass(type)
            ?: throw JsonIOException("Invalid EnumSet type: $type")
        if (!enumClass.isEnum) throw JsonIOException("Invalid EnumSet type: $type")
        return runRecovering {
            EnumSet::class.java
                .getMethod("noneOf", Class::class.java)
                .invoke(null, enumClass) as EnumSet<*>
        }.getOrNull()
    }

    /**
     * 构造 EnumMap。
     *
     * @param type EnumMap 的完整类型，例如 `EnumMap<Role, String>`。
     * @return 空 EnumMap；类型非法时抛 JsonIOException。
     */
    private fun enumMap(type: Type): EnumMap<*, *>? {
        val enumClass = firstTypeArgumentClass(type)
            ?: throw JsonIOException("Invalid EnumMap type: $type")
        if (!enumClass.isEnum) throw JsonIOException("Invalid EnumMap type: $type")
        return runRecovering {
            EnumMap::class.java
                .getConstructor(Class::class.java)
                .newInstance(enumClass) as EnumMap<*, *>
        }.getOrNull()
    }

    /**
     * 取第一个泛型参数对应的 Class。
     *
     * @param type 参数化类型。
     * @return 第一个泛型参数是普通 Class 时返回它，否则返回 null。
     */
    private fun firstTypeArgumentClass(type: Type): Class<*>? {
        return (type as? ParameterizedType)?.actualTypeArguments?.firstOrNull() as? Class<*>
    }

    private fun Throwable.hasRecursiveConstructorFallback(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is RecursiveConstructorFallbackException) return true
            current = current.cause
        }
        return false
    }

    private class RecursiveConstructorFallbackException(type: Type) : RuntimeException(
        "Recursive constructor fallback detected for $type."
    )

    private class KotlinPrimaryConstructorFallbackException(type: Type, cause: Throwable) : RuntimeException(
        "Kotlin primary constructor failed for $type.",
        cause
    )
}
