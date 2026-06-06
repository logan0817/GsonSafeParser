package io.github.logan.gsonsafeparser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import com.google.gson.reflect.TypeToken

/**
 * 验证显式 JSON 形态转换策略。
 *
 * 这组测试只覆盖调用方主动开启的行为。默认配置仍要保持 1.0.2 以来的错形兜底语义。
 */
class SafeParserShapeCoercionTest {
    data class UserResponse(val data: User? = null)
    data class DefaultUserResponse(val data: User = User())
    data class UserListResponse(val users: List<User> = emptyList())
    data class UserSetResponse(val users: Set<User> = emptySet())
    class UserArrayResponse(val users: Array<User> = emptyArray())
    data class UserMapResponse(val users: Map<String, User> = emptyMap())
    data class StringListResponse(val values: List<String> = emptyList())
    class IntArrayResponse(val values: Array<Int> = emptyArray())
    data class User(val id: Long = 0L, val name: String = "")
    data class ApiError(val code: Int = 0, val message: String = "")
    data class ErrorListResponse(val errors: List<ApiError> = emptyList())

    data class AnnotatedObjectResponse(
        @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        val data: User? = null
    )

    data class DisabledObjectResponse(
        @field:SafeParseDisableShapeCoercion
        val data: User? = null
    )

    data class AnnotatedArrayResponse(
        @field:SafeParseShapeCoercion(ShapeCoercionPolicy.CollectionFromSingleObject)
        val users: Array<User> = emptyArray()
    )

    data class AnnotatedErrorListResponse(
        @field:SafeParseShapeCoercion(ShapeCoercionPolicy.CollectionFromSingleObject)
        val errors: List<ApiError> = emptyList()
    )

    data class DisabledListResponse(
        @field:SafeParseDisableShapeCoercion
        val users: List<User> = emptyList()
    )

    class DisabledArrayResponse(
        @field:SafeParseDisableShapeCoercion
        val users: Array<User> = emptyArray()
    )

    @Test
    fun `shape coercion is disabled by default`() {
        val result = GsonSafeParser.fromJson(
            """{"data":[{"id":1,"name":"Tom"}]}""",
            UserResponse::class.java
        )

        assertNull(result?.data)
    }

    @Test
    fun `root object array keeps previous safe fallback when shape coercion is disabled`() {
        val gson = GsonSafeParser.create()

        val result = assertDoesNotThrow<Array<User>?> {
            gson.fromJson("""{"id":1}""", Array<User>::class.java)
        }

        assertNull(result)
    }

    @Test
    fun `root object array keeps previous safe fallback when shape coercion is enabled`() {
        val gson = GsonSafeParser.create(
            SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        )

        val result = assertDoesNotThrow<Array<User>?> {
            gson.fromJson("""{"id":1}""", Array<User>::class.java)
        }

        assertNull(result)
    }

    @Test
    fun `array field keeps previous fallback for object scalar null and missing shapes`() {
        val objectResult = GsonSafeParser.fromJson(
            """{"users":{"id":1}}""",
            UserArrayResponse::class.java
        )
        val scalarResult = GsonSafeParser.fromJson(
            """{"users":"[]"}""",
            UserArrayResponse::class.java
        )
        val nullResult = GsonSafeParser.fromJson(
            """{"users":null}""",
            UserArrayResponse::class.java
        )
        val missingResult = GsonSafeParser.fromJson(
            """{}""",
            UserArrayResponse::class.java
        )

        assertEquals(0, requireNotNull(objectResult).users.size)
        assertEquals(0, requireNotNull(scalarResult).users.size)
        assertEquals(0, requireNotNull(nullResult).users.size)
        assertEquals(0, requireNotNull(missingResult).users.size)
    }

    @Test
    fun `object field reads first object from array when enabled`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.ObjectFromFirstArrayItem
        )

        val result = GsonSafeParser.fromJson(
            """{"data":[{"id":3,"name":"Tom"}]}""",
            UserResponse::class.java,
            config
        )

        assertEquals(User(3L, "Tom"), result?.data)
    }

    @Test
    fun `object field keeps fallback behavior for empty array`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.ObjectFromFirstArrayItem
        )

        val nullableResult = GsonSafeParser.fromJson(
            """{"data":[]}""",
            UserResponse::class.java,
            config
        )
        val defaultResult = GsonSafeParser.fromJson(
            """{"data":[]}""",
            DefaultUserResponse::class.java,
            config
        )

        assertNull(nullableResult?.data)
        assertEquals(User(), defaultResult?.data)
    }

    @Test
    fun `object field uses first array item and skips extra items`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.ObjectFromFirstArrayItem
        )

        val result = GsonSafeParser.fromJson(
            """{"data":[{"id":1},{"id":2}]}""",
            UserResponse::class.java,
            config
        )

        assertEquals(1L, result?.data?.id)
    }

    @Test
    fun `field annotation enables object from array when global policy is disabled`() {
        val result = GsonSafeParser.fromJson(
            """{"data":[{"id":7}]}""",
            AnnotatedObjectResponse::class.java
        )

        assertEquals(7L, result?.data?.id)
    }

    @Test
    fun `disable annotation wins over global shape coercion policy`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.ObjectAndCollection
        )

        val result = GsonSafeParser.fromJson(
            """{"data":[{"id":7}]}""",
            DisabledObjectResponse::class.java,
            config
        )

        assertNull(result?.data)
    }

    @Test
    fun `list field wraps object as single item list when enabled`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.CollectionFromSingleObject
        )

        val result = GsonSafeParser.fromJson(
            """{"users":{"id":5}}""",
            UserListResponse::class.java,
            config
        )

        assertEquals(listOf(User(5L)), result?.users)
    }

    @Test
    fun `set field wraps object as single item set when enabled`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.CollectionFromSingleObject
        )

        val result = GsonSafeParser.fromJson(
            """{"users":{"id":5}}""",
            UserSetResponse::class.java,
            config
        )

        assertEquals(setOf(User(5L)), result?.users)
    }

    @Test
    fun `array field wraps object as single item array when enabled`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.CollectionFromSingleObject
        )

        val result = GsonSafeParser.fromJson(
            """{"users":{"id":8}}""",
            UserArrayResponse::class.java,
            config
        )

        assertEquals(1, result?.users?.size)
        assertEquals(User(8L), result?.users?.firstOrNull())
    }

    @Test
    fun `field annotation wraps object as single item array when global policy is disabled`() {
        val result = GsonSafeParser.fromJson(
            """{"users":{"id":11}}""",
            AnnotatedArrayResponse::class.java
        )

        assertEquals(1, result?.users?.size)
        assertEquals(User(11L), result?.users?.firstOrNull())
    }

    @Test
    fun `error list remains non null for array object null and missing shapes`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.ObjectAndCollection
        )
        val arrayResult = GsonSafeParser.fromJson(
            """{"errors":[{"code":401,"message":"expired"}]}""",
            ErrorListResponse::class.java,
            config
        )
        val objectResult = GsonSafeParser.fromJson(
            """{"errors":{"code":401,"message":"expired"}}""",
            ErrorListResponse::class.java,
            config
        )
        val nullResult = GsonSafeParser.fromJson(
            """{"errors":null}""",
            ErrorListResponse::class.java,
            config
        )
        val missingResult = GsonSafeParser.fromJson(
            """{}""",
            ErrorListResponse::class.java,
            config
        )

        assertEquals(ApiError(401, "expired"), requireNotNull(arrayResult).errors.firstOrNull())
        assertEquals(ApiError(401, "expired"), requireNotNull(objectResult).errors.firstOrNull())
        assertNull(requireNotNull(nullResult).errors.firstOrNull())
        assertNull(requireNotNull(missingResult).errors.firstOrNull())
    }

    @Test
    fun `field annotation can enable error list object wrapping without global policy`() {
        val result = GsonSafeParser.fromJson(
            """{"errors":{"code":403,"message":"token"}}""",
            AnnotatedErrorListResponse::class.java
        )

        assertEquals(ApiError(403, "token"), result?.errors?.firstOrNull())
    }

    @Test
    fun `disabled policy and annotation keep collection and array fallback behavior`() {
        val globalConfig = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.ObjectAndCollection
        )
        val disabledConfig = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.Disabled
        )

        val disabledList = GsonSafeParser.fromJson(
            """{"users":{"id":1}}""",
            DisabledListResponse::class.java,
            globalConfig
        )
        val disabledArray = GsonSafeParser.fromJson(
            """{"users":{"id":1}}""",
            DisabledArrayResponse::class.java,
            globalConfig
        )
        val globallyDisabledList = GsonSafeParser.fromJson(
            """{"users":{"id":1}}""",
            UserListResponse::class.java,
            disabledConfig
        )

        assertEquals(emptyList<User>(), disabledList?.users)
        assertEquals(0, disabledArray?.users?.size)
        assertEquals(emptyList<User>(), globallyDisabledList?.users)
    }

    @Test
    fun `scalar does not coerce into object or collection`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.ObjectAndCollection
        )

        val objectResult = GsonSafeParser.fromJson(
            """{"data":"{\"id\":1}"}""",
            UserResponse::class.java,
            config
        )
        val listResult = GsonSafeParser.fromJson(
            """{"users":"[]"}""",
            UserListResponse::class.java,
            config
        )

        assertNull(objectResult?.data)
        assertEquals(emptyList<User>(), listResult?.users)
    }

    @Test
    fun `root object does not read first item from array`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.ObjectAndCollection
        )

        val result = GsonSafeParser.fromJson(
            """[{"data":{"id":1}}]""",
            UserResponse::class.java,
            config
        )

        assertNull(result)
    }

    @Test
    fun `root list does not wrap single object`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.ObjectAndCollection
        )
        val listType = object : TypeToken<List<User>>() {}.type

        val result = GsonSafeParser.fromJson<List<User>>(
            """{"id":1}""",
            listType,
            config
        )

        assertNull(result)
    }

    @Test
    fun `map field does not wrap object as single entry`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(
            ShapeCoercionPolicy.ObjectAndCollection
        )

        val result = GsonSafeParser.fromJson(
            """{"users":[{"id":1}]}""",
            UserMapResponse::class.java,
            config
        )

        assertNotNull(result)
        assertEquals(emptyMap<String, User>(), result?.users)
    }

    @Test
    fun `collection shape coercion does not wrap object for scalar element types`() {
        val config = SafeParserConfig(
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
        ).withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)

        val listResult = GsonSafeParser.fromJson(
            """{"values":{"id":1}}""",
            StringListResponse::class.java,
            config
        )
        val arrayResult = GsonSafeParser.fromJson(
            """{"values":{"id":1}}""",
            IntArrayResponse::class.java,
            config
        )

        assertEquals(emptyList<String>(), listResult?.values)
        assertEquals(0, arrayResult?.values?.size)
    }
}
