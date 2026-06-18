package io.github.logan.gsonsafeparser.internal.adapter

import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializer
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.internal.Excluder
import com.google.gson.internal.GsonTypes
import com.google.gson.internal.ReflectionAccessFilterHelper
import com.google.gson.internal.bind.TreeTypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import io.github.logan.gsonsafeparser.NullValuePolicy
import io.github.logan.gsonsafeparser.RequiredConstructorParameterPolicy
import io.github.logan.gsonsafeparser.ShapeCoercionAction
import io.github.logan.gsonsafeparser.ShapeCoercionPolicy
import io.github.logan.gsonsafeparser.SafeParseShapeCoercion
import io.github.logan.gsonsafeparser.SafeParseSkip
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.internal.GsonBuiltInTypes
import io.github.logan.gsonsafeparser.internal.TokenRules
import io.github.logan.gsonsafeparser.internal.asCallerAdapterReadException
import io.github.logan.gsonsafeparser.internal.runRecovering
import io.github.logan.gsonsafeparser.internal.objectcreation.SafeObjectConstructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.jvmErasure

/**
 * 普通业务对象的安全反射 Adapter。
 *
 * 它负责普通对象解析的核心能力：字段级兜底、默认值保留、字段命名策略、
 * `@SerializedName`、字段级 `@JsonAdapter`、Excluder 和 ReflectionAccessFilter。
 */
internal object SafeReflectiveAdapterFactory {
    private val fieldJsonAdapterDelegateFactory = object : TypeAdapterFactory {
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T> {
            throw AssertionError("Factory should not be used")
        }
    }

    /**
     * 创建普通业务对象的 Safe Reflective Adapter。
     *
     * @param gson 当前 Gson，用来获取字段 Adapter 和 Builder 配置。
     * @param type 目标业务类型，包含泛型信息。
     * @param config SafeParser 配置。
     * @return 能读写该业务对象的 Adapter。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> create(
        gson: Gson,
        type: TypeToken<T>,
        config: SafeParserConfig,
        delegateSkipPast: TypeAdapterFactory? = null
    ): TypeAdapter<T> {
        // rawType 是真正要反射的 Class，fields 是 JSON 字段名到字段绑定信息的索引。
        val rawType = type.rawType
        val constructorReadRequirements = if (hasConfiguredInstanceCreator(type, config)) {
            // 显式 InstanceCreator 是调用方给出的构造意图，不属于内部占位构造，不能再要求 JSON 必须覆盖构造参数。
            ConstructorReadRequirements.EMPTY
        } else {
            constructorReadRequirements(rawType)
        }
        val fields = collectFields(type, gson, config, constructorReadRequirements)
        if (
            config.requiredConstructorParameterPolicy == RequiredConstructorParameterPolicy.GsonCompatible &&
            constructorReadRequirements.hasUntrackedRequiredParameters(fields.values)
        ) {
            return delegateAdapter(gson, type, delegateSkipPast)
        }

        return object : TypeAdapter<T>(), ReflectiveRuntimeTypeAdapter {
            /**
             * 写出普通对象。
             *
             * @param out JSON 输出流。
             * @param value 要写出的对象。
             */
            override fun write(out: JsonWriter, value: T?) {
                if (value == null) {
                    out.nullValue()
                    return
                }

                out.beginObject()
                fields.values.distinctBy { it.field }.filter { it.serialized }.forEach { binding ->
                    val fieldValue = binding.field.get(value)
                    // 自引用字段直接跳过，避免序列化时无限递归。
                    if (fieldValue === value) return@forEach
                    out.name(binding.primaryName)
                    binding.adapter.writeRuntime(gson, binding.fieldType, out, fieldValue)
                }
                out.endObject()
            }

            /**
             * 读取普通对象。
             *
             * @param reader Gson Reader。
             * @return 读取到的对象；顶层 `Object` 错形或无法安全构造时可能为 null。
             */
            override fun read(reader: JsonReader): T? {
                val token = reader.peek()
                val objectPathBeforeRead = reader.path
                if (token == JsonToken.NULL) {
                    reader.nextNull()
                    return null
                }
                if (token != JsonToken.BEGIN_OBJECT) {
                    // 顶层 `Object` 或 `Object` 字段错形时，当前反射 Adapter 只记录事件并返回 null，由外层决定是否保留默认值。
                    notify(config, type, reader, token)
                    reader.skipValue()
                    return null
                }

                // instance 是先构造出来的目标对象。后面字段读取成功才逐个写进去，失败字段保留默认值。
                val instance = runRecovering {
                    SafeObjectConstructor.construct<T>(type.type, rawType, config)
                }.getOrElse {
                    if (config.requiredConstructorParameterPolicy == RequiredConstructorParameterPolicy.Strict) {
                        throw it
                    }
                    return readWithDelegate(gson, type, reader, config, delegateSkipPast)
                } ?: return readWithDelegate(gson, type, reader, config, delegateSkipPast)
                val requiredConstructorBindings = fields.values
                    .filter { binding -> binding.requiresExplicitConstructorValue }
                    .distinctBy { binding -> binding.field }
                val requiredEnumBindings = requiredConstructorBindings
                    .filter { binding -> binding.requiresExplicitEnumValue }
                val requiredNestedConstructorBindings = requiredConstructorBindings
                    .filter { binding -> binding.requiresExplicitNestedConstructorValue }
                val untrackedRequiredConstructorParameters =
                    constructorReadRequirements.constructorParameterNames -
                        requiredConstructorBindings.map { binding -> binding.field.name }.toSet()
                val untrackedRequiredEnumParameters = constructorReadRequirements.enumParameterNames -
                    requiredEnumBindings.map { binding -> binding.field.name }.toSet()
                val untrackedRequiredNestedConstructorParameters =
                    constructorReadRequirements.nestedConstructorParameterNames -
                        requiredNestedConstructorBindings.map { binding -> binding.field.name }.toSet()
                val assignedRequiredConstructorFields = mutableSetOf<Field>()
                val assignedRequiredEnumFields = mutableSetOf<Field>()
                val assignedRequiredNestedConstructorFields = mutableSetOf<Field>()

                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    val binding = fields[name]
                    if (binding == null || !binding.deserialized) {
                        reader.skipValue()
                        continue
                    }
                    val pathBeforeRead = reader.path
                    val valueToken = reader.peek()
                    if (valueToken == JsonToken.NULL) {
                        reader.nextNull()
                        if (
                            config.nullValuePolicy == NullValuePolicy.WriteExplicitNulls &&
                            binding.acceptsNull
                        ) {
                            binding.field.set(instance, null)
                        }
                        continue
                    }
                    runRecovering {
                        val value = ShapeCoercionReadContext.withPolicy(binding.shapeCoercionPolicy) {
                            if (
                                !binding.adapterHandlesOwnShape &&
                                valueToken == JsonToken.BEGIN_ARRAY &&
                                binding.shapeCoercionPolicy.supportsObjectFromArray() &&
                                TokenRules.isObjectLike(TypeToken.get(binding.fieldType).rawType)
                            ) {
                                readObjectFromFirstArrayItem(
                                    reader = reader,
                                    binding = binding,
                                    valueToken = valueToken,
                                    pathBeforeRead = pathBeforeRead
                                )
                            } else {
                                binding.adapter.read(reader)
                            }
                        }
                        if (value != null) {
                            // null 不覆盖构造默认值，这是本库避免误伤本地默认值的关键行为。
                            binding.field.set(instance, value)
                            if (binding.requiresExplicitConstructorValue) {
                                assignedRequiredConstructorFields += binding.field
                            }
                            if (binding.requiresExplicitEnumValue) {
                                assignedRequiredEnumFields += binding.field
                            }
                            if (binding.requiresExplicitNestedConstructorValue) {
                                assignedRequiredNestedConstructorFields += binding.field
                            }
                        }
                    }
                        .onFailure {
                            if (binding.adapterHandlesOwnShape) {
                                if (objectPathBeforeRead == "$") {
                                    throw it
                                }
                                throw it.asCallerAdapterReadException()
                            }
                            reader.skipUnreadValueIfPossible(pathBeforeRead)
                            notify(
                                config = config,
                                type = TypeToken.get(binding.fieldType),
                                reader = reader,
                                token = valueToken,
                                reason = it.message ?: it.javaClass.name,
                                fieldName = binding.primaryName,
                                path = pathBeforeRead
                            )
                        }
                }
                reader.endObject()
                handleRequiredEnumValues(
                    config = config,
                    instance = instance as Any,
                    rawType = rawType,
                    requiredEnumBindings = requiredEnumBindings,
                    untrackedRequiredEnumParameters = untrackedRequiredEnumParameters,
                    assignedRequiredEnumFields = assignedRequiredEnumFields
                )
                handleRequiredNestedConstructorValues(
                    config = config,
                    instance = instance as Any,
                    rawType = rawType,
                    requiredNestedConstructorBindings = requiredNestedConstructorBindings,
                    untrackedRequiredNestedConstructorParameters = untrackedRequiredNestedConstructorParameters,
                    assignedRequiredNestedConstructorFields = assignedRequiredNestedConstructorFields
                )
                handleRequiredConstructorValues(
                    config = config,
                    instance = instance as Any,
                    rawType = rawType,
                    requiredConstructorBindings = requiredConstructorBindings,
                    untrackedRequiredConstructorParameters = untrackedRequiredConstructorParameters,
                    assignedRequiredConstructorFields = assignedRequiredConstructorFields
                )
                return instance
            }
        }
    }

    private fun <T> readWithDelegate(
        gson: Gson,
        type: TypeToken<T>,
        reader: JsonReader,
        config: SafeParserConfig,
        delegateSkipPast: TypeAdapterFactory?
    ): T? {
        // 构造实例失败时交回 Gson 默认 Adapter，保持“不能安全增强就回到 Gson”的底线。
        val pathBeforeRead = reader.path
        val token = reader.peekSafe()
        return runRecovering { gson.getDelegateAdapter(delegateSkipPast, type).read(reader) }
            .getOrElse { error ->
                notify(
                    config = config,
                    type = type,
                    reader = reader,
                    token = token,
                    reason = error.message ?: error.javaClass.name,
                    path = pathBeforeRead
                )
                if (pathBeforeRead == "$") {
                    throw error
                }
                runRecovering { reader.skipValue() }
                null
            }
    }

    private fun <T> delegateAdapter(
        gson: Gson,
        type: TypeToken<T>,
        delegateSkipPast: TypeAdapterFactory?
    ): TypeAdapter<T> {
        return if (delegateSkipPast != null) {
            gson.getDelegateAdapter(delegateSkipPast, type)
        } else {
            gson.getAdapter(type)
        }
    }

    /**
     * 收集当前类型及父类里的可读写字段。
     *
     * @param type 当前业务类型。
     * @param gson 当前 Gson，用来读取命名策略和 Excluder。
     * @param config SafeParser 配置。
     * @param constructorReadRequirements 需要由 JSON 显式读到的主构造参数集合。
     * @return JSON 字段名到字段绑定的映射。
     */
    private fun collectFields(
        type: TypeToken<*>,
        gson: Gson,
        config: SafeParserConfig,
        constructorReadRequirements: ConstructorReadRequirements
    ): Map<String, FieldBinding> {
        if (reflectionBlocked(type.rawType, config) && hasConfiguredInstanceCreator(type, config)) {
            // BLOCK_ALL 但有显式 InstanceCreator 时，只允许构造对象，不再反射字段。
            return emptyMap()
        }
        // fields 用 linkedMapOf 是为了保持字段收集顺序稳定，方便测试和问题排查。
        val fields = linkedMapOf<String, FieldBinding>()
        // currentType/current 会沿着父类链往上走，确保父类字段也能参与解析。
        var currentType = type
        var current: Class<*>? = type.rawType
        while (current != null && current != Any::class.java) {
            current.declaredFields
                .forEach { field ->
                    if (field.getAnnotation(SafeParseSkip::class.java) != null) {
                        return@forEach
                    }
                    if (GsonBuiltInTypes.isSkippedPlatformType(field.type, config.skippedPlatformTypePrefixes)) {
                        // Android 平台对象这类字段默认跳过，避免反射系统类内部字段导致崩溃。
                        return@forEach
                    }

                    // serialized/deserialized 分别表示这个字段是否参与写出和读取，受 Excluder、@Expose 等影响。
                    val serialized = includeField(gson, field, serialize = true)
                    val deserialized = includeField(gson, field, serialize = false)
                    if (!serialized && !deserialized) return@forEach

                    makeFieldAccessibleIfAllowed(field, current, config)
                    // fieldType 是解析后的真实字段类型，比如 T 会被替换成当前泛型里的实际类型。
                    val fieldType = GsonTypes.resolve(currentType.type, current, field.genericType)
                    val fieldTypeToken = TypeToken.get(fieldType)
                    val adapter = fieldAdapter(gson, field, fieldTypeToken, config)
                    val shapeCoercionPolicy = field.shapeCoercionPolicy(config, fieldType)
                    // 同一个字段可能把 @SerializedName 的主名也写进 alternate；这里先按字段自身去重，
                    // 避免误判为字段冲突。不同字段映射到同一个 JSON 名称仍会在下面 fail fast。
                    val names = namesFor(gson, field).distinct()
                    var previous: FieldBinding? = null
                    names.forEachIndexed { index, name ->
                        val requiresExplicitConstructorValue =
                            field.name in constructorReadRequirements.constructorParameterNames
                        val binding = FieldBinding(
                            field = field,
                            adapter = adapter,
                            adapterHandlesOwnShape = adapter.handlesOwnInputShape() ||
                                TypeToken.get(fieldType).rawType.delegatesPrimitiveInputShape(config),
                            fieldType = fieldType,
                            primaryName = name,
                            serialized = serialized && index == 0,
                            deserialized = deserialized,
                            acceptsNull = fieldAcceptsNull(field),
                            requiresExplicitConstructorValue = requiresExplicitConstructorValue,
                            requiresExplicitEnumValue = requiresExplicitConstructorValue &&
                                field.type.isEnum,
                            requiresExplicitNestedConstructorValue =
                                requiresExplicitConstructorValue &&
                                    field.name in constructorReadRequirements.nestedConstructorParameterNames,
                            shapeCoercionPolicy = shapeCoercionPolicy,
                            config = config
                        )
                        val replaced = fields.put(name, binding)
                        if (previous == null) previous = replaced
                    }
                    if (previous != null) {
                        // 多个字段映射到同一个 JSON 名称时必须 fail fast，避免 Bean 定义问题被静默吞掉。
                        throw IllegalArgumentException(
                            "${type.type} declares multiple JSON fields named ${previous?.primaryName}"
                        )
                    }
                }
            currentType = TypeToken.get(GsonTypes.resolve(currentType.type, current, current.genericSuperclass))
            current = current.superclass
        }
        return fields
    }

    private fun constructorReadRequirements(rawType: Class<*>): ConstructorReadRequirements {
        val constructor = runRecovering { rawType.kotlin.primaryConstructor }.getOrNull()
            ?: return ConstructorReadRequirements.EMPTY
        val constructorParameterNames = mutableSetOf<String>()
        val enumParameterNames = mutableSetOf<String>()
        val nestedConstructorParameterNames = mutableSetOf<String>()
        constructor.parameters
            .filterNot { parameter -> parameter.isOptional }
            .filterNot { parameter -> parameter.type.isMarkedNullable }
            .forEach { parameter ->
                val parameterName = parameter.name ?: return@forEach
                val parameterRawType = parameter.type.jvmErasure.java
                constructorParameterNames += parameterName
                if (parameterRawType.isEnum) {
                    enumParameterNames += parameterName
                } else if (hasRequiredConstructorFallback(parameterRawType, mutableSetOf(rawType))) {
                    nestedConstructorParameterNames += parameterName
                }
            }
        return ConstructorReadRequirements(
            constructorParameterNames = constructorParameterNames,
            enumParameterNames = enumParameterNames,
            nestedConstructorParameterNames = nestedConstructorParameterNames
        )
    }

    private fun hasRequiredConstructorFallback(rawType: Class<*>, visiting: MutableSet<Class<*>>): Boolean {
        if (!visiting.add(rawType)) return false
        return try {
            val constructor = runRecovering { rawType.kotlin.primaryConstructor }.getOrNull() ?: return false
            constructor.parameters
                .filterNot { parameter -> parameter.isOptional }
                .filterNot { parameter -> parameter.type.isMarkedNullable }
                .any { parameter ->
                    val parameterRawType = parameter.type.jvmErasure.java
                    parameterRawType.isEnum || hasRequiredConstructorFallback(parameterRawType, visiting)
                }
        } finally {
            visiting -= rawType
        }
    }

    private fun reflectionBlocked(rawType: Class<*>, config: SafeParserConfig): Boolean {
        return ReflectionAccessFilterHelper.getFilterResult(
            config.reflectionAccessFilters,
            rawType
        ) == com.google.gson.ReflectionAccessFilter.FilterResult.BLOCK_ALL
    }

    /**
     * 判断当前类型是否配置了 InstanceCreator。
     */
    private fun hasConfiguredInstanceCreator(type: TypeToken<*>, config: SafeParserConfig): Boolean {
        return config.instanceCreators.containsKey(type.type) ||
            config.instanceCreators.containsKey(type.rawType)
    }

    /**
     * 判断字段是否应该参与序列化或反序列化。
     *
     * @param gson 当前 Gson，用来读取 Excluder。
     * @param field Java 反射字段。
     * @param serialize `true` 表示判断写出，`false` 表示判断读取。
     * @return 字段是否应该参与当前方向的绑定。
     */
    @Suppress("DEPRECATION")
    private fun includeField(gson: Gson, field: Field, serialize: Boolean): Boolean {
        if (field.isSynthetic || Modifier.isStatic(field.modifiers) || Modifier.isTransient(field.modifiers)) {
            return false
        }
        val excluder = runRecovering { gson.excluder() }.getOrDefault(Excluder.DEFAULT)
        return !excluder.excludeClass(field.type, serialize) &&
            !excluder.excludeField(field, serialize)
    }

    private fun makeFieldAccessibleIfAllowed(
        field: Field,
        declaringType: Class<*>,
        config: SafeParserConfig
    ) {
        // filterResult 表示 Gson ReflectionAccessFilter 对这个声明类的最终判断。
        val filterResult = ReflectionAccessFilterHelper.getFilterResult(
            config.reflectionAccessFilters,
            declaringType
        )
        if (filterResult == com.google.gson.ReflectionAccessFilter.FilterResult.BLOCK_ALL) {
            throw JsonIOException(
                "Unable to use reflection on ${declaringType.name}; ReflectionAccessFilter result $filterResult."
            )
        }
        if (filterResult == com.google.gson.ReflectionAccessFilter.FilterResult.ALLOW) {
            // 只有明确 ALLOW 时才强开访问权限；BLOCK_INACCESSIBLE 需要尊重 Java 原本可见性。
            field.isAccessible = true
            return
        }
        if (!canAccessFieldWithoutForcing(field, declaringType)) {
            throw JsonIOException(
                "Unable to access field ${declaringType.name}.${field.name}; ReflectionAccessFilter result $filterResult."
            )
        }
    }

    private fun canAccessFieldWithoutForcing(field: Field, declaringType: Class<*>): Boolean {
        return Modifier.isPublic(field.modifiers) && Modifier.isPublic(declaringType.modifiers)
    }

    private fun fieldAcceptsNull(field: Field): Boolean {
        if (field.type.isPrimitive) return false
        val kotlinProperty = runRecovering {
            field.declaringClass.kotlin.memberProperties.firstOrNull { property ->
                property.javaField == field || property.name == field.name
            }
        }.getOrNull()
        return kotlinProperty?.returnType?.isMarkedNullable == true
    }

    private fun readObjectFromFirstArrayItem(
        reader: JsonReader,
        binding: FieldBinding,
        valueToken: JsonToken,
        pathBeforeRead: String
    ): Any? {
        val fieldTypeToken = TypeToken.get(binding.fieldType)
        reader.beginArray()
        if (!reader.hasNext()) {
            reader.endArray()
            dispatchShapeCoercion(
                config = binding.config,
                type = fieldTypeToken,
                reader = reader,
                token = valueToken,
                action = ShapeCoercionAction.EmptyArrayForObjectSkipped,
                fieldName = binding.primaryName,
                path = pathBeforeRead
            )
            return null
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
                config = binding.config,
                type = fieldTypeToken,
                reader = reader,
                token = valueToken,
                action = ShapeCoercionAction.CoercionFailed,
                fieldName = binding.primaryName,
                reason = "First array item is $firstItemToken, not an object value",
                path = pathBeforeRead
            )
            return null
        }
        val value = runRecovering { binding.adapter.read(reader) }
            .getOrElse { error ->
                reader.skipUnreadValueIfPossible(firstItemPath)
                while (runRecovering { reader.hasNext() }.getOrDefault(false)) {
                    reader.skipValue()
                }
                reader.endArray()
                dispatchShapeCoercion(
                    config = binding.config,
                    type = fieldTypeToken,
                    reader = reader,
                    token = valueToken,
                    action = ShapeCoercionAction.CoercionFailed,
                    fieldName = binding.primaryName,
                    reason = error.message ?: error.javaClass.name,
                    path = pathBeforeRead
                )
                return null
            }

        dispatchShapeCoercion(
            config = binding.config,
            type = fieldTypeToken,
            reader = reader,
            token = valueToken,
            action = ShapeCoercionAction.ObjectFromFirstArrayItem,
            fieldName = binding.primaryName,
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
                config = binding.config,
                type = fieldTypeToken,
                reader = reader,
                token = valueToken,
                action = ShapeCoercionAction.ArrayExtraItemsSkipped,
                fieldName = binding.primaryName,
                discardedItemCount = discardedItemCount,
                path = pathBeforeRead
            )
        }
        return value
    }

    private fun handleRequiredEnumValues(
        config: SafeParserConfig,
        instance: Any,
        rawType: Class<*>,
        requiredEnumBindings: List<FieldBinding>,
        untrackedRequiredEnumParameters: Set<String>,
        assignedRequiredEnumFields: Set<Field>
    ) {
        val missing = untrackedRequiredEnumParameters + requiredEnumBindings
            .filterNot { binding -> binding.field in assignedRequiredEnumFields }
            .map { binding -> binding.primaryName }
        if (missing.isEmpty()) return
        if (config.requiredConstructorParameterPolicy == RequiredConstructorParameterPolicy.GsonCompatible) {
            clearMissingReferenceFields(instance, requiredEnumBindings, assignedRequiredEnumFields)
            return
        }
        throw JsonIOException(
            "Required enum constructor parameter was not read from JSON for ${rawType.name}: " +
                missing.joinToString()
        )
    }

    private fun handleRequiredNestedConstructorValues(
        config: SafeParserConfig,
        instance: Any,
        rawType: Class<*>,
        requiredNestedConstructorBindings: List<FieldBinding>,
        untrackedRequiredNestedConstructorParameters: Set<String>,
        assignedRequiredNestedConstructorFields: Set<Field>
    ) {
        val missing = untrackedRequiredNestedConstructorParameters + requiredNestedConstructorBindings
            .filterNot { binding -> binding.field in assignedRequiredNestedConstructorFields }
            .map { binding -> binding.primaryName }
        if (missing.isEmpty()) return
        if (config.requiredConstructorParameterPolicy == RequiredConstructorParameterPolicy.GsonCompatible) {
            clearMissingReferenceFields(instance, requiredNestedConstructorBindings, assignedRequiredNestedConstructorFields)
            return
        }
        throw JsonIOException(
            "Required constructor parameter was not read from JSON for ${rawType.name}: " +
                missing.joinToString()
        )
    }

    private fun handleRequiredConstructorValues(
        config: SafeParserConfig,
        instance: Any,
        rawType: Class<*>,
        requiredConstructorBindings: List<FieldBinding>,
        untrackedRequiredConstructorParameters: Set<String>,
        assignedRequiredConstructorFields: Set<Field>
    ) {
        val missing = untrackedRequiredConstructorParameters + requiredConstructorBindings
            .filterNot { binding -> binding.field in assignedRequiredConstructorFields }
            .map { binding -> binding.primaryName }
        if (missing.isEmpty()) return
        if (config.requiredConstructorParameterPolicy == RequiredConstructorParameterPolicy.GsonCompatible) {
            clearMissingReferenceFields(instance, requiredConstructorBindings, assignedRequiredConstructorFields)
            return
        }
        throw JsonIOException(
            "Required constructor parameter was not read from JSON for ${rawType.name}: " +
                missing.joinToString()
        )
    }

    private fun clearMissingReferenceFields(
        instance: Any,
        requiredBindings: List<FieldBinding>,
        assignedFields: Set<Field>
    ) {
        requiredBindings
            .filterNot { binding -> binding.field in assignedFields }
            .map { binding -> binding.field }
            .distinct()
            .filterNot { field -> field.type.isPrimitive }
            .forEach { field -> runRecovering { field.set(instance, null) } }
    }

    /**
     * 获取字段自己的 Adapter。
     *
     * @param gson 当前 Gson。
     * @param field 当前字段。
     * @param type 字段解析后的真实类型。
     * @param config SafeParser 配置。
     * @return 字段读写 Adapter。
     */
    @Suppress("UNCHECKED_CAST")
    private fun fieldAdapter(
        gson: Gson,
        field: Field,
        type: TypeToken<*>,
        config: SafeParserConfig
    ): TypeAdapter<Any?> {
        val annotation = field.getAnnotation(JsonAdapter::class.java)
        if (annotation != null) {
            // 字段级 @JsonAdapter 优先于普通反射 Adapter，用来尊重业务显式声明的字段绑定行为。
            val adapterType = annotation.value.java
            val adapter = when {
                TypeAdapter::class.java.isAssignableFrom(adapterType) -> {
                    SafeObjectConstructor.construct<TypeAdapter<Any?>>(adapterType, adapterType, config)
                }
                TypeAdapterFactory::class.java.isAssignableFrom(adapterType) -> {
                    val factory = SafeObjectConstructor.construct<TypeAdapterFactory>(adapterType, adapterType, config)
                    factory?.create(gson, type) as TypeAdapter<Any?>?
                }
                JsonSerializer::class.java.isAssignableFrom(adapterType) ||
                    JsonDeserializer::class.java.isAssignableFrom(adapterType) -> {
                    val adapterInstance = SafeObjectConstructor.construct<Any>(adapterType, adapterType, config)
                    val serializer = adapterInstance as? JsonSerializer<Any?>
                    val deserializer = adapterInstance as? JsonDeserializer<Any?>
                    if (serializer == null && deserializer == null) {
                        null
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        TreeTypeAdapter(
                            serializer,
                            deserializer,
                            gson,
                            type as TypeToken<Any?>,
                            fieldJsonAdapterDelegateFactory,
                            annotation.nullSafe
                        )
                    }
                }
                else -> {
                    throw IllegalArgumentException(
                        "@JsonAdapter value must be TypeAdapter, TypeAdapterFactory, JsonSerializer or JsonDeserializer reference."
                    )
                }
            }
            if (adapter != null) {
                // 字段级适配器默认套 nullSafe，避免 null 值把字段读取链路打断。
                return if (annotation.nullSafe) adapter.nullSafe() else adapter
            }
        }
        return gson.getAdapter(type) as TypeAdapter<Any?>
    }

    private fun namesFor(gson: Gson, field: Field): List<String> {
        val serializedName = field.getAnnotation(SerializedName::class.java)
        if (serializedName != null) {
            // 主名和 alternate 都参与反序列化，只有主名用于序列化。
            return listOf(serializedName.value) + serializedName.alternate
        }
        val namingStrategy = runRecovering { gson.fieldNamingStrategy() }.getOrNull()
        return listOf(namingStrategy?.translateName(field) ?: field.name)
    }

    private data class FieldBinding(
        val field: Field,
        val adapter: TypeAdapter<Any?>,
        val adapterHandlesOwnShape: Boolean,
        val fieldType: java.lang.reflect.Type,
        val primaryName: String,
        val serialized: Boolean,
        val deserialized: Boolean,
        val acceptsNull: Boolean,
        val requiresExplicitConstructorValue: Boolean,
        val requiresExplicitEnumValue: Boolean,
        val requiresExplicitNestedConstructorValue: Boolean,
        val shapeCoercionPolicy: ShapeCoercionPolicy,
        val config: SafeParserConfig
    )

    private data class ConstructorReadRequirements(
        val constructorParameterNames: Set<String>,
        val enumParameterNames: Set<String>,
        val nestedConstructorParameterNames: Set<String>
    ) {
        fun hasUntrackedRequiredParameters(bindings: Collection<FieldBinding>): Boolean {
            val trackedFieldNames = bindings.map { binding -> binding.field.name }.toSet()
            return constructorParameterNames.any { name -> name !in trackedFieldNames } ||
                enumParameterNames.any { name -> name !in trackedFieldNames } ||
                nestedConstructorParameterNames.any { name -> name !in trackedFieldNames }
        }

        companion object {
            val EMPTY = ConstructorReadRequirements(
                constructorParameterNames = emptySet(),
                enumParameterNames = emptySet(),
                nestedConstructorParameterNames = emptySet()
            )
        }
    }
}
