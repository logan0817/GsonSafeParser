package io.github.logan.gsonsafeparser

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import com.google.gson.ReflectionAccessFilter
import com.google.gson.ToNumberStrategy
import com.google.gson.stream.JsonToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.lang.reflect.Type

/**
 * 公开 API 安全矩阵。
 *
 * 这里不重复字段错形细节，而是锁住公开项目最容易在发布后伤到用户的 API 面：
 * 入口签名、配置 ABI、事件脱敏报告和低层 opt-in 边界。
 */
class PublicApiSafetyMatrixTest {
    data class MatrixUser(val id: Long = 0L)
    data class MatrixResponse(val data: MatrixUser = MatrixUser())

    @Test
    fun `core public entrypoint methods keep stable overload matrix`() {
        val gsonSafeParserMethods = GsonSafeParser::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }
            .map { method ->
                method.name to method.parameterTypes.map { parameter -> parameter.name }
            }
            .toSet()

        assertTrue(signature("parser", SafeParserConfig::class.java) in gsonSafeParserMethods)
        assertTrue(signature("parser", GsonBuilder::class.java, SafeParserConfig::class.java) in gsonSafeParserMethods)
        assertTrue(signature("parserWithExternalGson", Gson::class.java, SafeParserConfig::class.java) in gsonSafeParserMethods)
        assertTrue(signature("create", SafeParserConfig::class.java) in gsonSafeParserMethods)
        assertTrue(signature("diagnostics", SafeParserConfig::class.java) in gsonSafeParserMethods)
        assertTrue(signature("diagnostics", Gson::class.java, SafeParserConfig::class.java) in gsonSafeParserMethods)
        assertTrue(signature("explainType", Type::class.java, SafeParserConfig::class.java) in gsonSafeParserMethods)
        assertTrue(signature("fromJson", String::class.java, Class::class.java, SafeParserConfig::class.java) in gsonSafeParserMethods)
        assertTrue(signature("fromJson", String::class.java, Type::class.java, SafeParserConfig::class.java) in gsonSafeParserMethods)
        assertTrue(
            signature("fromJson", Gson::class.java, String::class.java, Type::class.java, SafeParserConfig::class.java) in
                gsonSafeParserMethods
        )

        val parserMethods = GsonSafeParser.Parser::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) }
            .map { method ->
                method.name to method.parameterTypes.map { parameter -> parameter.name }
            }
            .toSet()

        assertTrue(signature("fromJson", String::class.java, Class::class.java) in parserMethods)
        assertTrue(signature("fromJson", String::class.java, Type::class.java) in parserMethods)
        assertTrue(signature("parseSafe", String::class.java, Class::class.java) in parserMethods)
        assertTrue(signature("parseSafe", String::class.java, Type::class.java) in parserMethods)
    }

    @Test
    fun `config and observer constructors keep public parameter order`() {
        val function1Type = kotlin.jvm.functions.Function1::class.java
        val configConstructorTypes = listOf(
            FallbackPolicy::class.java,
            EmptyResponsePolicy::class.java,
            java.util.Map::class.java,
            ToNumberStrategy::class.java,
            PrimitiveParsingPolicy::class.java,
            java.util.List::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            java.util.Set::class.java,
            NullValuePolicy::class.java,
            RequiredConstructorParameterPolicy::class.java,
            MapItemKeyPolicy::class.java,
            Boolean::class.javaPrimitiveType,
            Integer::class.javaPrimitiveType,
            function1Type,
            function1Type,
            function1Type,
            function1Type
        )
        val observerConstructorTypes = listOf(
            Boolean::class.javaPrimitiveType,
            Integer::class.javaPrimitiveType,
            MapItemKeyPolicy::class.java,
            function1Type,
            function1Type,
            function1Type,
            function1Type
        )

        assertTrue(
            SafeParserConfig::class.java.constructors.any { constructor ->
                constructor.parameterTypes.toList() == configConstructorTypes
            }
        )
        assertTrue(
            SafeObserverPolicy::class.java.constructors.any { constructor ->
                constructor.parameterTypes.toList() == observerConstructorTypes
            }
        )
        assertFalse(SafeParserConfig::class.isData)
        assertFalse(SafeObserverPolicy::class.isData)
    }

    @Test
    fun `policy value sets stay explicit for public release compatibility`() {
        assertEquals(listOf("Default", "NullOnly"), FallbackPolicy.values().map { it.name })
        assertEquals(
            listOf("DefaultValue", "DefaultValueForUnitOrVoidOnly", "Null", "DelegateToGson"),
            EmptyResponsePolicy.values().map { it.name }
        )
        assertEquals(listOf("Safe", "DelegateToGson"), PrimitiveParsingPolicy.values().map { it.name })
        assertEquals(
            listOf(
                "Disabled",
                "ObjectFromFirstArrayItem",
                "CollectionFromSingleObject",
                "ObjectAndCollection"
            ),
            ShapeCoercionPolicy.values().map { it.name }
        )
        assertEquals(listOf("WriteExplicitNulls", "KeepDefaults"), NullValuePolicy.values().map { it.name })
        assertEquals(
            listOf("GsonCompatible", "Strict"),
            RequiredConstructorParameterPolicy.values().map { it.name }
        )
        assertEquals(listOf("PlainText", "Hash", "Omit"), MapItemKeyPolicy.values().map { it.name })
        assertEquals(
            listOf(
                "ObjectFromFirstArrayItem",
                "CollectionFromSingleObject",
                "ArrayFromSingleObject",
                "EmptyArrayForObjectSkipped",
                "ArrayExtraItemsSkipped",
                "CoercionFailed"
            ),
            ShapeCoercionAction.values().map { it.name }
        )
        assertEquals(
            listOf("TypeMismatch", "AdapterCreationFailure", "EmptyResponse", "RawJsonCaptureSkipped"),
            SafeParseContractIssueCategory.values().map { it.name }
        )
        assertEquals(listOf("Info", "Warning"), SafeParseContractIssueSeverity.values().map { it.name })
        assertEquals(
            listOf("TypeMismatch", "AdapterCreationFailure", "EmptyResponse", "RawJsonCaptureSkipped", "Unknown"),
            SafeParserEventCategory.values().map { it.name }
        )
    }

    @Test
    fun `copy and policy factories keep shape coercion and mutable inputs isolated`() {
        val creator = InstanceCreator<MatrixUser> { MatrixUser(7L) }
        val instanceCreators = linkedMapOf<Type, InstanceCreator<*>>(MatrixUser::class.java to creator)
        val filters = mutableListOf(
            ReflectionAccessFilter { ReflectionAccessFilter.FilterResult.INDECISIVE }
        )
        val skippedPrefixes = mutableSetOf("android.")
        val config = SafeParserConfig.fromPolicies(
            readPolicy = SafeReadPolicy(
                reflectionAccessFilters = filters,
                skippedPlatformTypePrefixes = skippedPrefixes
            ),
            observerPolicy = SafeObserverPolicy(maxRawJsonCaptureBytes = 128),
            instanceCreators = instanceCreators
        ).withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        val copied = config.copy(captureRawJsonInCallbacks = true)

        instanceCreators.clear()
        filters.clear()
        skippedPrefixes.clear()

        assertEquals(ShapeCoercionPolicy.ObjectFromFirstArrayItem, copied.shapeCoercionPolicy)
        assertEquals(setOf(MatrixUser::class.java), copied.instanceCreators.keys)
        assertEquals(1, copied.reflectionAccessFilters.size)
        assertEquals(setOf("android."), copied.skippedPlatformTypePrefixes)
        assertTrue(copied.captureRawJsonInCallbacks)
        assertEquals(128, copied.maxRawJsonCaptureBytes)
    }

    @Test
    fun `reports keep raw json and throwable details out of public output`() {
        val rawJson = """{"token":"secret","password":"hidden"}"""
        val result = SafeParseResult(
            value = MatrixResponse(),
            events = listOf(
                SafeParserEvent.TypeMismatch(
                    TypeMismatchEvent(
                        expectedType = MatrixUser::class.java.name,
                        actualToken = JsonToken.BEGIN_ARRAY,
                        path = "$.data",
                        reason = "backend returned an array",
                        fieldName = "data",
                        rawJson = rawJson,
                        rawJsonTruncated = true
                    )
                ),
                SafeParserEvent.AdapterCreationFailure(
                    AdapterCreationFailureEvent(
                        typeName = "com.example.SecretModel",
                        reason = "adapter creation failed",
                        error = IllegalStateException("token=secret")
                    )
                )
            )
        )
        val observerReport = listOf(
            ObserverFailureEvent(
                callbackName = "onEvent",
                eventName = "TypeMismatch",
                sourceEvent = result.events.first(),
                reason = "logger leaked token=secret",
                error = IllegalStateException("logger leaked token=secret")
            )
        ).observerFailureReport()

        val contractReport = result.contractReport()
        val markdown = contractReport.toMarkdown()
        val backendMarkdown = contractReport.toBackendMarkdown()
        val rows = contractReport.toStructuredRows()
        val observerMarkdown = observerReport.toMarkdown()

        assertTrue(contractReport.hasIssues)
        assertFalse(markdown.contains(rawJson))
        assertFalse(markdown.contains("secret"))
        assertFalse(markdown.contains("password"))
        assertFalse(backendMarkdown.contains(rawJson))
        assertFalse(backendMarkdown.contains("secret"))
        assertFalse(backendMarkdown.contains("password"))
        assertFalse(observerMarkdown.contains("secret"))
        assertTrue(rows.none { row -> row.fields.values.any { value -> value.contains("password") } })
        assertTrue(rows.first().fields.getValue("rawJsonTruncated") == "true")
    }

    private fun signature(name: String, vararg parameterTypes: Class<*>): Pair<String, List<String>> {
        return name to parameterTypes.map { parameter -> parameter.name }
    }
}
