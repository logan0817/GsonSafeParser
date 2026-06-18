package io.github.logan.gsonsafeparser

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

class GsonSafeParserJsonConversionExampleTest {
    data class FullPayload(
        val id: Long = 0L,
        val name: String = "local",
        val active: Boolean = false,
        val score: BigDecimal = BigDecimal.ZERO,
        val role: Role = Role.MEMBER
    )

    data class FullEnvelope(
        val code: Int = 0,
        val data: FullPayload = FullPayload(),
        val users: List<FullPayload> = listOf(FullPayload(name = "local-list")),
        val userSet: Set<FullPayload> = setOf(FullPayload(name = "local-set")),
        val userMap: Map<String, FullPayload> = mapOf("local" to FullPayload(name = "local-map")),
        val tags: List<String> = listOf("local-tag"),
        val metadata: Map<String, Any?> = emptyMap()
    )

    data class NullableEnvelope(
        val title: String? = "local-title",
        val data: FullPayload? = FullPayload(name = "nullable-default"),
        val users: List<FullPayload>? = listOf(FullPayload(name = "nullable-list"))
    )

    data class PrimitiveEnvelope(
        val intValue: Int = 7,
        val longValue: Long = 8L,
        val doubleValue: Double = 9.5,
        val booleanValue: Boolean = true,
        val stringValue: String = "local",
        val bigInteger: BigInteger = BigInteger("123"),
        val bigDecimal: BigDecimal = BigDecimal("4.5")
    )

    data class ObjectFieldEnvelope(val data: FullPayload? = null)
    data class DefaultObjectFieldEnvelope(val data: FullPayload = FullPayload())
    data class ListEnvelope(val users: List<FullPayload> = emptyList())
    data class SetEnvelope(val users: Set<FullPayload> = emptySet())
    class ArrayEnvelope(val users: Array<FullPayload> = emptyArray())
    data class StringListEnvelope(val values: List<String> = emptyList())
    data class IntMapEnvelope(val values: Map<Int, String> = emptyMap())
    data class IntUserMapEnvelope(val values: Map<Int, FullPayload> = emptyMap())
    data class UserMapEnvelope(val values: Map<String, FullPayload> = emptyMap())
    data class NestedUserMapEnvelope(val values: Map<String, List<FullPayload>> = emptyMap())
    data class AdapterBackedMapEnvelope(val values: Map<String, AdapterBackedPayload> = emptyMap())
    data class RegisteredAdapterEnvelope(
        val payload: RegisteredAdapterPayload = RegisteredAdapterPayload("local-field"),
        val items: List<RegisteredAdapterPayload> = emptyList(),
        val values: Map<String, RegisteredAdapterPayload> = emptyMap()
    )
    data class HierarchyAdapterEnvelope(
        val payload: HierarchyPayload = HierarchyPayload("local-field"),
        val items: List<HierarchyPayload> = emptyList()
    )
    data class ComplexMapEnvelope(val values: Map<ComplexKey, String> = emptyMap())
    data class EnumEnvelope(val role: Role = Role.MEMBER, val nullableRole: Role? = Role.ADMIN)
    data class EnumCollectionEnvelope(
        val roles: List<Role> = emptyList(),
        val roleMap: Map<String, Role> = emptyMap(),
        val roleArray: Array<Role> = emptyArray()
    )
    data class OrgJsonEnvelope(val obj: JSONObject? = null, val arr: JSONArray? = null)
    data class JsonElementEnvelope(
        val tree: JsonElement? = null,
        val items: JsonElement? = null,
        val scalar: JsonElement? = null
    )

    data class AnnotatedObjectEnvelope(
        @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
        val data: FullPayload? = null
    )

    data class DisabledListEnvelope(
        @field:SafeParseDisableShapeCoercion
        val users: List<FullPayload> = emptyList()
    )

    data class SerializedNameEnvelope(
        @SerializedName("user_name")
        val userName: String = "local",
        val role: Role = Role.MEMBER
    )

    data class ComplexKey(val id: Long)

    @JsonAdapter(ArrayBackedPayloadAdapter::class)
    data class AdapterBackedPayload(val label: String)

    class ArrayBackedPayloadAdapter : TypeAdapter<AdapterBackedPayload>() {
        override fun write(out: JsonWriter, value: AdapterBackedPayload?) {
            out.value(value?.label)
        }

        override fun read(reader: JsonReader): AdapterBackedPayload {
            return when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.skipValue()
                    }
                    reader.endArray()
                    AdapterBackedPayload("adapter-read-array")
                }
                JsonToken.BEGIN_OBJECT -> {
                    reader.skipValue()
                    AdapterBackedPayload("adapter-read-object")
                }
                else -> AdapterBackedPayload(reader.nextString())
            }
        }
    }

    data class RegisteredAdapterPayload(val label: String)

    class RegisteredPayloadAdapter : TypeAdapter<RegisteredAdapterPayload>() {
        override fun write(out: JsonWriter, value: RegisteredAdapterPayload?) {
            out.value(value?.label)
        }

        override fun read(reader: JsonReader): RegisteredAdapterPayload {
            return when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.skipValue()
                    }
                    reader.endArray()
                    RegisteredAdapterPayload("registered-read-array")
                }
                JsonToken.BEGIN_OBJECT -> {
                    reader.skipValue()
                    RegisteredAdapterPayload("registered-read-object")
                }
                else -> RegisteredAdapterPayload(reader.nextString())
            }
        }
    }

    open class HierarchyBase(open val label: String)
    data class HierarchyPayload(override val label: String = "local") : HierarchyBase(label)

    class HierarchyPayloadAdapter : TypeAdapter<HierarchyBase>() {
        override fun write(out: JsonWriter, value: HierarchyBase?) {
            out.value(value?.label)
        }

        override fun read(reader: JsonReader): HierarchyBase {
            return when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.skipValue()
                    }
                    reader.endArray()
                    HierarchyPayload("hierarchy-read-array")
                }
                JsonToken.BEGIN_OBJECT -> {
                    reader.skipValue()
                    HierarchyPayload("hierarchy-read-object")
                }
                else -> HierarchyPayload(reader.nextString())
            }
        }
    }

    class ExactContainerAdapter<T>(
        private val label: String,
        private val value: T
    ) : TypeAdapter<T>() {
        override fun write(out: JsonWriter, value: T?) {
            out.value(label)
        }

        override fun read(reader: JsonReader): T {
            reader.skipValue()
            return value
        }
    }

    enum class Role {
        @SerializedName("admin_user")
        ADMIN,
        MEMBER
    }

    @Test
    fun `example parses valid object collection map primitive enum and org json payloads`() {
        val result = GsonSafeParser.fromJsonSafe<FullEnvelope>(
            """
            {
              "code": 200,
              "data": {"id": 1, "name": "Ada", "active": true, "score": "98.5", "role": "admin_user"},
              "users": [{"id": 2, "name": "Ben"}],
              "userSet": [{"id": 3, "name": "Cat"}],
              "userMap": {"lead": {"id": 4, "name": "Dee"}},
              "tags": ["android", "gson"],
              "metadata": {"retries": 2, "ratio": 1.5, "nested": {"ok": true}}
            }
            """.trimIndent()
        )
        val orgJson = GsonSafeParser.fromJsonSafe<OrgJsonEnvelope>(
            """{"obj":{"name":"json"},"arr":[{"id":1},{"id":2}]}"""
        )

        requireNotNull(result)
        assertEquals(200, result.code)
        assertEquals(FullPayload(1L, "Ada", true, BigDecimal("98.5"), Role.ADMIN), result.data)
        assertEquals(listOf(FullPayload(2L, "Ben")), result.users)
        assertEquals(setOf(FullPayload(3L, "Cat")), result.userSet)
        assertEquals(FullPayload(4L, "Dee"), result.userMap["lead"])
        assertEquals(listOf("android", "gson"), result.tags)
        assertEquals(2, (result.metadata["retries"] as Number).toInt())
        assertEquals("json", orgJson?.obj?.getString("name"))
        assertEquals(2, orgJson?.arr?.length())
    }

    @Test
    fun `example delegates gson json element root and fields without safe shape filtering`() {
        val rootResult = GsonSafeParser.parseSafe<JsonElement>(
            """{"raw":[1,true,{"name":"Ada"}]}""",
            SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        )
        val fieldResult = GsonSafeParser.parseSafe<JsonElementEnvelope>(
            """{"tree":{"count":2},"items":[{"id":1},{"id":2}],"scalar":"plain"}""",
            SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        )

        assertTrue(rootResult.value?.asJsonObject?.getAsJsonArray("raw")?.get(1)?.asBoolean == true)
        assertEquals(2, fieldResult.value?.tree?.asJsonObject?.get("count")?.asInt)
        assertEquals(2, fieldResult.value?.items?.asJsonArray?.size())
        assertEquals("plain", fieldResult.value?.scalar?.asString)
        assertTrue(rootResult.events.isEmpty())
        assertTrue(fieldResult.events.isEmpty())
    }

    @Test
    fun `example parses root any object array and scalar values with configured object strategy`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        val objectResult = GsonSafeParser.parseSafe<Any>(
            """{"count":2,"nested":{"ok":true}}""",
            config
        )
        val arrayResult = GsonSafeParser.parseSafe<Any>(
            """["x",3,{"ok":true}]""",
            config
        )
        val stringResult = GsonSafeParser.parseSafe<Any>(""""plain"""", config)
        val booleanResult = GsonSafeParser.parseSafe<Any>("true", config)

        val objectMap = objectResult.value as Map<*, *>
        val nestedMap = objectMap["nested"] as Map<*, *>
        val arrayList = arrayResult.value as List<*>
        assertEquals(2, objectMap["count"])
        assertEquals(true, nestedMap["ok"])
        assertEquals(listOf("x", 3), arrayList.take(2))
        assertEquals(true, (arrayList[2] as Map<*, *>)["ok"])
        assertEquals("plain", stringResult.value)
        assertEquals(true, booleanResult.value)
        assertTrue(objectResult.events.isEmpty())
        assertTrue(arrayResult.events.isEmpty())
        assertTrue(stringResult.events.isEmpty())
        assertTrue(booleanResult.events.isEmpty())
    }

    @Test
    fun `example keeps outer object when field json shapes are wrong`() {
        val result = GsonSafeParser.parseSafe<FullEnvelope>(
            """
            {
              "code": 200,
              "data": [],
              "users": {},
              "userSet": false,
              "userMap": "",
              "tags": {"bad": true}
            }
            """.trimIndent()
        )

        assertEquals(200, result.value?.code)
        assertEquals(FullPayload(), result.value?.data)
        assertEquals(listOf(FullPayload(name = "local-list")), result.value?.users)
        assertEquals(setOf(FullPayload(name = "local-set")), result.value?.userSet)
        assertEquals(mapOf("local" to FullPayload(name = "local-map")), result.value?.userMap)
        assertEquals(listOf("local-tag"), result.value?.tags)
        assertTrue(result.events.filterIsInstance<SafeParserEvent.TypeMismatch>().size >= 5)
        assertTrue(result.events.filterIsInstance<SafeParserEvent.TypeMismatch>().any { it.detail.path == "$.data" })
        assertTrue(result.contractReport().hasIssues)
    }

    @Test
    fun `example distinguishes missing fields explicit null and keep default null policy`() {
        val missing = GsonSafeParser.fromJsonSafe<NullableEnvelope>("{}")
        val explicitNull = GsonSafeParser.fromJsonSafe<NullableEnvelope>(
            """{"title":null,"data":null,"users":null}"""
        )
        val keepDefaults = GsonSafeParser.fromJsonSafe<NullableEnvelope>(
            """{"title":null,"data":null,"users":null}""",
            SafeParserConfig(nullValuePolicy = NullValuePolicy.KeepDefaults)
        )

        assertEquals(NullableEnvelope(), missing)
        assertNull(explicitNull?.title)
        assertNull(explicitNull?.data)
        assertNull(explicitNull?.users)
        assertEquals(NullableEnvelope(), keepDefaults)
    }

    @Test
    fun `example covers primitive safe conversion and invalid primitive fallback`() {
        val config = SafeParserConfig(
            fallbackPolicy = FallbackPolicy.Default,
            primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
        )
        val scalarResult = GsonSafeParser.fromJsonSafe<PrimitiveEnvelope>(
            """
            {
              "intValue": "42",
              "longValue": "43",
              "doubleValue": "3.25",
              "booleanValue": 1,
              "stringValue": 99,
              "bigInteger": "999999999999999999999",
              "bigDecimal": "12345.6789"
            }
            """.trimIndent(),
            config
        )
        val invalidResult = GsonSafeParser.parseSafe<PrimitiveEnvelope>(
            """
            {
              "intValue": {},
              "longValue": [],
              "doubleValue": "not-a-number",
              "booleanValue": 2,
              "bigInteger": "3.14",
              "bigDecimal": {}
            }
            """.trimIndent(),
            config
        )

        assertEquals(42, scalarResult?.intValue)
        assertEquals(43L, scalarResult?.longValue)
        assertEquals(3.25, scalarResult?.doubleValue)
        assertEquals(true, scalarResult?.booleanValue)
        assertEquals("99", scalarResult?.stringValue)
        assertEquals(BigInteger("999999999999999999999"), scalarResult?.bigInteger)
        assertEquals(BigDecimal("12345.6789"), scalarResult?.bigDecimal)
        assertEquals(PrimitiveEnvelope(), invalidResult.value)
        assertTrue(invalidResult.events.filterIsInstance<SafeParserEvent.TypeMismatch>().size >= 6)
    }

    @Test
    fun `example covers map object form array entry form and bad entries`() {
        val objectForm = GsonSafeParser.parseSafe<IntMapEnvelope>(
            """{"values":{"1":"one","bad":"skip","2":"two"}}"""
        )
        val arrayEntryType = object : TypeToken<Map<Int, String>>() {}.type
        val arrayEntryForm = GsonSafeParser.fromJson<Map<Int, String>>(
            """[[1,"one"],[2,"two"]]""",
            arrayEntryType
        )
        val valueMismatch = GsonSafeParser.parseSafe<UserMapEnvelope>(
            """{"values":{"good":{"id":1,"name":"ok"},"bad":[],"later":{"id":2,"name":"next"}}}"""
        )

        assertEquals(mapOf(1 to "one", 2 to "two"), objectForm.value?.values)
        assertEquals(mapOf(1 to "one", 2 to "two"), arrayEntryForm)
        assertEquals(FullPayload(1L, "ok"), valueMismatch.value?.values?.get("good"))
        assertFalse(valueMismatch.value?.values?.containsKey("bad") == true)
        assertEquals(FullPayload(2L, "next"), valueMismatch.value?.values?.get("later"))
        assertTrue(objectForm.events.any { it is SafeParserEvent.TypeMismatch })
        assertTrue(valueMismatch.events.any { it is SafeParserEvent.TypeMismatch })
    }

    @Test
    fun `example covers root list set map object array and primitive array conversion`() {
        val userListType = object : TypeToken<List<FullPayload>>() {}.type
        val userSetType = object : TypeToken<Set<FullPayload>>() {}.type
        val userMapType = object : TypeToken<Map<String, FullPayload>>() {}.type
        val nullableUserListType = object : TypeToken<List<FullPayload?>>() {}.type
        val parser = GsonSafeParser.parser()
        val listResult = parser.parseSafe<List<FullPayload>>(
            """[{"id":1,"name":"Ada"},[],{"id":2,"name":"Ben"}]""",
            userListType
        )
        val setResult = GsonSafeParser.fromJson<Set<FullPayload>>(
            """[{"id":3,"name":"Cat"},{"id":3,"name":"Cat"}]""",
            userSetType
        )
        val mapResult = parser.parseSafe<Map<String, FullPayload>>(
            """{"lead":{"id":4,"name":"Dee"},"bad":[],"next":{"id":5,"name":"Eli"}}""",
            userMapType
        )
        val objectArrayResult = GsonSafeParser.fromJson(
            """[{"id":6,"name":"Fox"},{}]""",
            Array<FullPayload>::class.java
        )
        val intArrayResult = GsonSafeParser.fromJson(
            """[1,2,3]""",
            IntArray::class.java
        )
        val nullableList = GsonSafeParser.fromJson<List<FullPayload?>>(
            """[{"id":7,"name":"Gia"},null]""",
            nullableUserListType
        )

        assertEquals(listOf(FullPayload(1L, "Ada"), FullPayload(2L, "Ben")), listResult.value)
        assertEquals(setOf(FullPayload(3L, "Cat")), setResult)
        assertEquals(FullPayload(4L, "Dee"), mapResult.value?.get("lead"))
        assertFalse(mapResult.value?.containsKey("bad") == true)
        assertEquals(FullPayload(5L, "Eli"), mapResult.value?.get("next"))
        assertEquals(listOf(FullPayload(6L, "Fox"), FullPayload()), objectArrayResult?.toList())
        assertEquals(listOf(1, 2, 3), intArrayResult?.toList())
        assertEquals(listOf(FullPayload(7L, "Gia"), null), nullableList)
        assertTrue(listResult.events.any { it is SafeParserEvent.TypeMismatch })
        assertTrue(mapResult.events.any { it is SafeParserEvent.TypeMismatch })
    }

    @Test
    fun `example covers root container wrong shape null and raw json callback boundaries`() {
        val userListType = object : TypeToken<List<FullPayload>>() {}.type
        val userMapType = object : TypeToken<Map<String, FullPayload>>() {}.type
        val mismatches = mutableListOf<TypeMismatchEvent>()
        val config = SafeParserConfig(
            captureRawJsonInCallbacks = true,
            onTypeMismatch = mismatches::add
        )
        val parser = GsonSafeParser.parser(config)
        val wrongRootList = parser.parseSafe<List<FullPayload>>(
            """{"id":1}""",
            userListType
        )
        val wrongRootMap = parser.parseSafe<Map<String, FullPayload>>(
            """[{"id":1}]""",
            userMapType
        )
        val nullRootMap = GsonSafeParser.fromJson<Map<String, FullPayload>>(
            "null",
            userMapType,
            config
        )
        val scalarRoot = GsonSafeParser.parseSafe<Int>(
            "{}",
            SafeParserConfig(primitiveParsingPolicy = PrimitiveParsingPolicy.Safe)
        )

        assertNull(wrongRootList.value)
        assertEquals(emptyMap<String, FullPayload>(), wrongRootMap.value)
        assertNull(nullRootMap)
        assertNull(scalarRoot.value)
        assertTrue(mismatches.any { it.path == "$" && it.rawJson == """{"id":1}""" })
        assertTrue(wrongRootMap.events.any { it is SafeParserEvent.TypeMismatch })
        assertTrue(scalarRoot.events.any { it is SafeParserEvent.TypeMismatch })
    }

    @Test
    fun `example covers enum root fields lists maps arrays and unknown values`() {
        val roleType = object : TypeToken<Role>() {}.type
        val roleMapType = object : TypeToken<Map<String, Role>>() {}.type
        val parser = GsonSafeParser.parser()
        val rootRole = GsonSafeParser.fromJson<Role>(""""admin_user"""", roleType)
        val unknownRootRole = parser.parseSafe<Role>(""""OWNER"""", roleType)
        val fieldRoles = GsonSafeParser.parseSafe<EnumEnvelope>(
            """{"role":"admin_user","nullableRole":null}"""
        )
        val unknownFields = GsonSafeParser.parseSafe<EnumEnvelope>(
            """{"role":"OWNER","nullableRole":"OWNER"}"""
        )
        val collections = GsonSafeParser.parseSafe<EnumCollectionEnvelope>(
            """{"roles":["admin_user","OWNER","MEMBER"],"roleMap":{"ok":"MEMBER","bad":"OWNER"},"roleArray":["MEMBER","OWNER"]}"""
        )
        val rootMap = parser.parseSafe<Map<String, Role>>(
            """{"ok":"admin_user","bad":"OWNER","next":"MEMBER"}""",
            roleMapType
        )

        assertEquals(Role.ADMIN, rootRole)
        assertNull(unknownRootRole.value)
        assertEquals(Role.ADMIN, fieldRoles.value?.role)
        assertNull(fieldRoles.value?.nullableRole)
        assertEquals(EnumEnvelope(), unknownFields.value)
        assertEquals(listOf(Role.ADMIN, null, Role.MEMBER), collections.value?.roles)
        assertEquals(mapOf("ok" to Role.MEMBER, "bad" to null), collections.value?.roleMap)
        assertEquals(listOf(Role.MEMBER, null), collections.value?.roleArray?.toList())
        assertEquals(mapOf("ok" to Role.ADMIN, "bad" to null, "next" to Role.MEMBER), rootMap.value)
        assertTrue(unknownRootRole.events.isEmpty())
        assertTrue(unknownFields.events.isEmpty())
        assertTrue(collections.events.isEmpty())
        assertTrue(rootMap.events.isEmpty())
    }

    @Test
    fun `example covers shape coercion inside map values and nested list values`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        val mapValueFromArray = GsonSafeParser.parseSafe<UserMapEnvelope>(
            """{"values":{"lead":[{"id":12,"name":"first"},{"id":13,"name":"skip"}]}}""",
            config
        )
        val nestedListFromObject = GsonSafeParser.parseSafe<NestedUserMapEnvelope>(
            """{"values":{"team":{"id":14,"name":"solo"},"bad":"oops"}}""",
            config
        )

        assertEquals(FullPayload(12L, "first"), mapValueFromArray.value?.values?.get("lead"))
        assertEquals(listOf(FullPayload(14L, "solo")), nestedListFromObject.value?.values?.get("team"))
        assertFalse(nestedListFromObject.value?.values?.containsKey("bad") == true)
        assertTrue(mapValueFromArray.events.any {
            it is SafeParserEvent.ShapeCoercion &&
                it.detail.path == "$.values.lead" &&
                it.detail.action == ShapeCoercionAction.ObjectFromFirstArrayItem
        })
        assertTrue(nestedListFromObject.events.any {
            it is SafeParserEvent.ShapeCoercion &&
                it.detail.path == "$.values.team" &&
                it.detail.action == ShapeCoercionAction.CollectionFromSingleObject
        })
    }

    @Test
    fun `example covers map value shape coercion array entry form and failed items`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        val objectForm = GsonSafeParser.parseSafe<UserMapEnvelope>(
            """
            {
              "values": {
                "good": [{"id":15,"name":"object-form"}],
                "empty": [],
                "bad": [1],
                "plain": {"id":18,"name":"plain-object"}
              }
            }
            """.trimIndent(),
            config
        )
        val arrayEntryForm = GsonSafeParser.parseSafe<UserMapEnvelope>(
            """
            {
              "values": [
                ["entry", [{"id":16,"name":"array-entry"}]],
                ["empty", []],
                ["bad", [false]],
                ["plain", {"id":17,"name":"entry-plain"}]
              ]
            }
            """.trimIndent(),
            config
        )

        assertEquals(FullPayload(15L, "object-form"), objectForm.value?.values?.get("good"))
        assertEquals(FullPayload(18L, "plain-object"), objectForm.value?.values?.get("plain"))
        assertFalse(objectForm.value?.values?.containsKey("empty") == true)
        assertFalse(objectForm.value?.values?.containsKey("bad") == true)
        assertEquals(FullPayload(16L, "array-entry"), arrayEntryForm.value?.values?.get("entry"))
        assertEquals(FullPayload(17L, "entry-plain"), arrayEntryForm.value?.values?.get("plain"))
        assertFalse(arrayEntryForm.value?.values?.containsKey("empty") == true)
        assertFalse(arrayEntryForm.value?.values?.containsKey("bad") == true)
        assertTrue(objectForm.events.any {
            it is SafeParserEvent.ShapeCoercion &&
                it.detail.path == "$.values.good" &&
                it.detail.action == ShapeCoercionAction.ObjectFromFirstArrayItem
        })
        assertTrue(objectForm.events.any {
            it is SafeParserEvent.ShapeCoercion &&
                it.detail.path == "$.values.empty" &&
                it.detail.action == ShapeCoercionAction.EmptyArrayForObjectSkipped
        })
        assertTrue(objectForm.events.any {
            it is SafeParserEvent.ShapeCoercion &&
                it.detail.path == "$.values.bad" &&
                it.detail.action == ShapeCoercionAction.CoercionFailed
        })
        assertTrue(arrayEntryForm.events.any {
            it is SafeParserEvent.ShapeCoercion &&
                it.detail.action == ShapeCoercionAction.ObjectFromFirstArrayItem
        })
    }

    @Test
    fun `example covers non string map keys with shape coerced object values`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        val objectForm = GsonSafeParser.parseSafe<IntUserMapEnvelope>(
            """
            {
              "values": {
                "1": [{"id":21,"name":"object-form"}],
                "bad": [{"id":99,"name":"bad-key"}],
                "2": {"id":22,"name":"plain-object"}
              }
            }
            """.trimIndent(),
            config
        )
        val arrayEntryForm = GsonSafeParser.parseSafe<IntUserMapEnvelope>(
            """
            {
              "values": [
                [3, [{"id":23,"name":"array-entry"}]],
                ["bad", [{"id":98,"name":"bad-key"}]],
                [4, []],
                [5, {"id":25,"name":"entry-plain"}]
              ]
            }
            """.trimIndent(),
            config
        )

        assertEquals(FullPayload(21L, "object-form"), objectForm.value?.values?.get(1))
        assertEquals(FullPayload(22L, "plain-object"), objectForm.value?.values?.get(2))
        assertFalse(objectForm.value?.values?.containsKey(0) == true)
        assertEquals(FullPayload(23L, "array-entry"), arrayEntryForm.value?.values?.get(3))
        assertFalse(arrayEntryForm.value?.values?.containsKey(4) == true)
        assertEquals(FullPayload(25L, "entry-plain"), arrayEntryForm.value?.values?.get(5))
        assertTrue(objectForm.events.any {
            it is SafeParserEvent.ShapeCoercion &&
                it.detail.path == "$.values.1" &&
                it.detail.action == ShapeCoercionAction.ObjectFromFirstArrayItem
        })
        assertTrue(arrayEntryForm.events.any {
            it is SafeParserEvent.ShapeCoercion &&
                it.detail.action == ShapeCoercionAction.EmptyArrayForObjectSkipped
        })
        assertTrue(objectForm.events.any { it is SafeParserEvent.TypeMismatch })
        assertTrue(arrayEntryForm.events.any { it is SafeParserEvent.TypeMismatch })
    }

    @Test
    fun `example keeps json adapter map values outside shape coercion`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)

        val result = GsonSafeParser.parseSafe<AdapterBackedMapEnvelope>(
            """{"values":{"custom":[{"label":"remote"}]}}""",
            config
        )

        assertEquals(AdapterBackedPayload("adapter-read-array"), result.value?.values?.get("custom"))
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `example keeps gson builder registered adapters on root fields collections and maps`() {
        val gson = GsonBuilder()
            .registerTypeAdapter(RegisteredAdapterPayload::class.java, RegisteredPayloadAdapter())
            .enableSafeParser(SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection))
            .create()

        val root = gson.fromJson(
            """[{"label":"root"}]""",
            RegisteredAdapterPayload::class.java
        )
        val envelope = gson.fromJson(
            """
            {
              "payload": [{"label":"field"}],
              "items": [[{"label":"list"}]],
              "values": {"entry": [{"label":"map"}]}
            }
            """.trimIndent(),
            RegisteredAdapterEnvelope::class.java
        )

        assertEquals(RegisteredAdapterPayload("registered-read-array"), root)
        assertEquals(RegisteredAdapterPayload("registered-read-array"), envelope.payload)
        assertEquals(listOf(RegisteredAdapterPayload("registered-read-array")), envelope.items)
        assertEquals(RegisteredAdapterPayload("registered-read-array"), envelope.values["entry"])
    }

    @Test
    fun `example keeps gson builder last exact adapter registration priority`() {
        val gson = GsonBuilder()
            .registerTypeAdapter(
                RegisteredAdapterPayload::class.java,
                ExactContainerAdapter("first", RegisteredAdapterPayload("first"))
            )
            .registerTypeAdapter(
                RegisteredAdapterPayload::class.java,
                ExactContainerAdapter("second", RegisteredAdapterPayload("second"))
            )
            .enableSafeParser(SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection))
            .create()

        val result = gson.fromJson(
            """[{"label":"root"}]""",
            RegisteredAdapterPayload::class.java
        )

        assertEquals(RegisteredAdapterPayload("second"), result)
    }

    @Test
    fun `example keeps gson hierarchy adapters for fields and collection elements`() {
        val gson = GsonBuilder()
            .registerTypeHierarchyAdapter(HierarchyBase::class.java, HierarchyPayloadAdapter())
            .enableSafeParser(SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection))
            .create()

        val result = gson.fromJson(
            """
            {
              "payload": [{"label":"field"}],
              "items": [[{"label":"list"}]]
            }
            """.trimIndent(),
            HierarchyAdapterEnvelope::class.java
        )

        assertEquals(HierarchyPayload("hierarchy-read-array"), result.payload)
        assertEquals(listOf(HierarchyPayload("hierarchy-read-array")), result.items)
    }

    @Test
    fun `example keeps gson builder last hierarchy adapter registration priority`() {
        val gson = GsonBuilder()
            .registerTypeHierarchyAdapter(
                HierarchyBase::class.java,
                ExactContainerAdapter("first", HierarchyPayload("first"))
            )
            .registerTypeHierarchyAdapter(
                HierarchyBase::class.java,
                ExactContainerAdapter("second", HierarchyPayload("second"))
            )
            .enableSafeParser(SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection))
            .create()

        val result = gson.fromJson(
            """[{"label":"root"}]""",
            HierarchyPayload::class.java
        )

        assertEquals(HierarchyPayload("second"), result)
    }

    @Test
    fun `example keeps exact list and map adapters outside safe container adapters`() {
        val listType = object : TypeToken<List<FullPayload>>() {}.type
        val mapType = object : TypeToken<Map<String, FullPayload>>() {}.type
        val gson = GsonBuilder()
            .registerTypeAdapter(
                listType,
                ExactContainerAdapter("list", listOf(FullPayload(31L, "exact-list")))
            )
            .registerTypeAdapter(
                mapType,
                ExactContainerAdapter("map", mapOf("exact" to FullPayload(32L, "exact-map")))
            )
            .enableSafeParser(SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection))
            .create()

        val list = gson.fromJson<List<FullPayload>>(
            """{"not":"a-list"}""",
            listType
        )
        val map = gson.fromJson<Map<String, FullPayload>>(
            """[{"not":"a-map"}]""",
            mapType
        )

        assertEquals(listOf(FullPayload(31L, "exact-list")), list)
        assertEquals(mapOf("exact" to FullPayload(32L, "exact-map")), map)
        assertEquals(""""list"""", gson.toJson(list, listType))
        assertEquals(""""map"""", gson.toJson(map, mapType))
    }

    @Test
    fun `example delegates opaque custom type adapter factory when it matches target type`() {
        var createCalls = 0
        val factory = object : TypeAdapterFactory {
            @Suppress("UNCHECKED_CAST")
            override fun <T> create(gson: com.google.gson.Gson, type: TypeToken<T>): TypeAdapter<T>? {
                if (type.rawType != RegisteredAdapterPayload::class.java) return null
                createCalls++
                return RegisteredPayloadAdapter() as TypeAdapter<T>
            }
        }
        val gson = GsonBuilder()
            .registerTypeAdapterFactory(factory)
            .enableSafeParser(SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection))
            .create()

        val result = gson.fromJson(
            """[{"label":"root"}]""",
            RegisteredAdapterPayload::class.java
        )

        assertEquals(RegisteredAdapterPayload("registered-read-array"), result)
        assertEquals(1, createCalls)
    }

    @Test
    fun `example probes unmatched custom type adapter factory before safe fallback`() {
        val seenTypes = mutableListOf<String>()
        val factory = object : TypeAdapterFactory {
            override fun <T> create(gson: com.google.gson.Gson, type: TypeToken<T>): TypeAdapter<T>? {
                seenTypes += type.rawType.name
                return null
            }
        }
        val gson = GsonBuilder()
            .registerTypeAdapterFactory(factory)
            .enableSafeParser(SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection))
            .create()

        val result = gson.fromJson(
            """{"data":[{"id":33,"name":"safe"}]}""",
            ObjectFieldEnvelope::class.java
        )

        assertEquals(FullPayload(33L, "safe"), result.data)
        assertTrue(seenTypes.contains(ObjectFieldEnvelope::class.java.name))
        assertTrue(seenTypes.contains(FullPayload::class.java.name))
    }

    @Test
    fun `example covers explicit shape coercion global annotation and disable override`() {
        val globalConfig = SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        val objectFromArray = GsonSafeParser.parseSafe<ObjectFieldEnvelope>(
            """{"data":[{"id":5,"name":"array-first"},{"id":6}]}""",
            globalConfig
        )
        val listFromObject = GsonSafeParser.parseSafe<ListEnvelope>(
            """{"users":{"id":7,"name":"single"}}""",
            globalConfig
        )
        val setFromObject = GsonSafeParser.fromJsonSafe<SetEnvelope>(
            """{"users":{"id":8,"name":"single-set"}}""",
            globalConfig
        )
        val arrayFromObject = GsonSafeParser.fromJsonSafe<ArrayEnvelope>(
            """{"users":{"id":9,"name":"single-array"}}""",
            globalConfig
        )
        val annotationOnly = GsonSafeParser.fromJsonSafe<AnnotatedObjectEnvelope>(
            """{"data":[{"id":10,"name":"annotation"}]}"""
        )
        val disabled = GsonSafeParser.fromJsonSafe<DisabledListEnvelope>(
            """{"users":{"id":11,"name":"disabled"}}""",
            globalConfig
        )

        assertEquals(FullPayload(5L, "array-first"), objectFromArray.value?.data)
        assertEquals(listOf(FullPayload(7L, "single")), listFromObject.value?.users)
        assertEquals(setOf(FullPayload(8L, "single-set")), setFromObject?.users)
        assertEquals(listOf(FullPayload(9L, "single-array")), arrayFromObject?.users?.toList())
        assertEquals(FullPayload(10L, "annotation"), annotationOnly?.data)
        assertEquals(emptyList<FullPayload>(), disabled?.users)
        val skippedExtraItems = objectFromArray.events
            .filterIsInstance<SafeParserEvent.ShapeCoercion>()
            .single { it.detail.action == ShapeCoercionAction.ArrayExtraItemsSkipped }
        assertEquals(1, skippedExtraItems.detail.discardedItemCount)
        assertTrue(listFromObject.events.any { it is SafeParserEvent.ShapeCoercion })
    }

    @Test
    fun `example keeps shape coercion out of roots maps and scalar element collections`() {
        val config = SafeParserConfig().withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
        val rootObject = GsonSafeParser.fromJson(
            """[{"id":1}]""",
            FullPayload::class.java,
            config
        )
        val rootList = GsonSafeParser.fromJson<List<FullPayload>>(
            """{"id":1}""",
            object : TypeToken<List<FullPayload>>() {}.type,
            config
        )
        val mapResult = GsonSafeParser.fromJsonSafe<UserMapEnvelope>(
            """{"values":{"id":1,"name":"not-a-map-entry"}}""",
            config
        )
        val stringList = GsonSafeParser.fromJsonSafe<StringListEnvelope>(
            """{"values":{"id":1}}""",
            config
        )

        assertNull(rootObject)
        assertNull(rootList)
        assertEquals(emptyMap<String, FullPayload>(), mapResult?.values)
        assertEquals(emptyList<String>(), stringList?.values)
    }

    @Test
    fun `example covers serialized names enums parser reuse and contract report`() {
        val parser = GsonSafeParser.parser()
        val valid = parser.fromJsonSafe<SerializedNameEnvelope>(
            """{"user_name":"Ada","role":"admin_user"}"""
        )
        val mismatch = parser.parseSafe<DefaultObjectFieldEnvelope>(
            """{"data":[]}"""
        )
        val issue = mismatch.contractReport().issues.single()

        assertEquals(SerializedNameEnvelope("Ada", Role.ADMIN), valid)
        assertEquals(FullPayload(), mismatch.value?.data)
        assertEquals("$.data", issue.path)
        assertEquals(ParseExceptionKind.OBJECT, issue.kind)
    }

    @Test
    fun `example covers json serialization and complex map key serialization`() {
        val gson = GsonSafeParser.create()
        val complexKeyGson = GsonBuilder()
            .enableSafeParser(
                SafeParserConfig.fromPolicies(
                    writePolicy = SafeWritePolicy(complexMapKeySerialization = true)
                )
            )
            .create()
        val normalJson = gson.toJson(SerializedNameEnvelope("Ada", Role.ADMIN))
        val complexJson = complexKeyGson.toJson(
            ComplexMapEnvelope(mapOf(ComplexKey(1L) to "one"))
        )

        assertTrue(normalJson.contains(""""user_name":"Ada""""))
        assertTrue(normalJson.contains(""""role":"admin_user""""))
        assertTrue(complexJson.contains("[[{\"id\":1},\"one\"]]"))
    }
}
