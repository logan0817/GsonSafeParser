# Examples

[中文](README.md)

Each Kotlin example is a standalone `.kt` file containing imports, models, and `fun main()`. Copy one at a time. Run `main()` in the Android module, or move its body into an existing debug entry point. These examples do not add a Gradle module.

For repository and dependency placement, start with [Getting Started](../docs/en/getting-started.md#1-add-a-dependency): confirm `mavenCentral()` in `settings.gradle.kts`, choose either core or retrofit in `app/build.gradle.kts`, and sync Gradle. The Retrofit module already includes core.

These basic APIs target published **1.0.4**. New stability fixes in the working source are listed under [Unreleased](../CHANGELOG.md#unreleased); source test results do not establish that Maven 1.0.4 contains those fixes.

## 1. Choose an example

| Goal | Example |
| --- | --- |
| Retain outer fields when an object field has the wrong shape | [Plain Gson Minimal Example](#2-plain-gson-minimal-example) |
| Get the value, events, and problem path | [Events And Contract Report](#3-events-and-contract-report) |
| Configure the Retrofit factory | [Retrofit Minimal Example](#4-retrofit-minimal-example) |
| Enable object/array coercion on a known field | [JSON Shape Coercion](#5-json-shape-coercion) |
| Run built-in probes without network access | [CI Integration Self-Check](#6-ci-integration-self-check) |

## 2. Plain Gson Minimal Example

Requires only `io.github.logan0817:gson-safe-parser-core:1.0.4`:

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser

data class ApiResponse(val code: Int = 0, val data: User = User())
data class User(val id: Long = 0L, val name: String = "local")

fun main() {
    val json = """{"code":200,"data":[]}"""
    val gson = GsonSafeParser.create()
    val response = requireNotNull(gson.fromJson(json, ApiResponse::class.java))
    check(response.code == 200)
    check(response.data == User())
    println(response)
}
```

Expected output: `ApiResponse(code=200, data=User(id=0, name=local))`. When `data` expects an object but receives an array, its constructed default survives and the outer `code` remains 200.

For generic lists, use `parser.fromJsonSafe<List<User>>(json)` instead of `List::class.java`, which loses the element type. See the [complete parser and generic example](../docs/en/getting-started.md#3-reuse-a-parser-and-retain-generic-types).

## 3. Events And Contract Report

This example includes its own models and does not depend on the preceding file:

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParserEvent
import io.github.logan.gsonsafeparser.contractReport
import io.github.logan.gsonsafeparser.parseSafe

data class ApiResponse(val code: Int = 0, val data: User = User())
data class User(val id: Long = 0L, val name: String = "anonymous")

fun main() {
    val json = """{"code":200,"data":[]}"""
    val result = GsonSafeParser.parseSafe<ApiResponse>(json)
    val mismatch = result.events.single() as SafeParserEvent.TypeMismatch
    println(result.value)
    println(mismatch.detail.path)
    println(result.contractReport().hasIssues)
}
```

Expected lines: `ApiResponse(code=200, data=User(id=0, name=anonymous))`, `$.data`, and `true`. Call `result.contractReport().toBackendMarkdown()` inside the function for a backend-facing report. Generating the report does not parse JSON again.

Direct `gson.fromJson(...)` calls do not automatically attach raw JSON, even if capture is enabled in the config. Use a capture-enabled Parser, `parseSafe`, or Retrofit entry instead; see [Troubleshooting](../docs/en/troubleshooting.md).

## 4. Retrofit Minimal Example

Requires only `io.github.logan0817:gson-safe-parser-retrofit:1.0.4`. Replace an existing `GsonConverterFactory.create(...)` with the Safe factory; do not retain both Gson factories on the same Retrofit instance.

```kotlin
import io.github.logan.gsonsafeparser.retrofit.GsonSafeConverterFactory
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.GET

data class ApiResponse(val code: Int = 0)

interface ApiService {
    @GET("profile")
    fun profile(): Call<ApiResponse>
}

fun main() {
    val retrofit = Retrofit.Builder()
        .baseUrl("https://example.com/")
        .addConverterFactory(GsonSafeConverterFactory.create())
        .build()
    val service = retrofit.create(ApiService::class.java)
    println(service is ApiService)
}
```

Expected output: `true`. The example configures a URL and creates an interface proxy. It calls neither `execute()` nor `enqueue()`, so it sends no network request.

For an existing builder, use `GsonSafeConverterFactory.create(builder, config)`. For an existing Gson, use `GsonSafeConverterFactory.create(gson, config)` after registering `.enableSafeParser(config)` when creating that Gson. The latter overload does not add registration. See the [complete existing-Gson example](../docs/en/getting-started.md#4-integrate-an-existing-gsonbuilder-or-gson).

## 5. JSON Shape Coercion

Enable this only when the field's contract accepts reading the first object from an array. Default settings do not coerce objects and arrays:

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParseShapeCoercion
import io.github.logan.gsonsafeparser.ShapeCoercionPolicy
import io.github.logan.gsonsafeparser.parseSafe

data class User(val id: Long = 0L, val name: String = "")

data class FlexibleResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val data: User? = null
)

fun main() {
    val result = GsonSafeParser.parseSafe<FlexibleResponse>(
        """{"data":[{"id":1,"name":"remote"}]}"""
    )
    println(result.value?.data)
    println(result.events.size)
}
```

Expected output: `User(id=1, name=remote)` and `1`. The event records explicit shape coercion. Likewise, opt into `PrimitiveParsingPolicy.Safe` only when the application accepts scalar fallback; see [Configuration](../docs/en/configuration.md).

## 6. CI Integration Self-Check

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.integrationCheck

fun main() {
    val config = SafeParserConfig.production()
    val diagnostics = GsonSafeParser.diagnostics(config)
    check(diagnostics.hasErrors.not()) {
        diagnostics.checks.joinToString("\n")
    }
    val integrationCheck = GsonSafeParser.integrationCheck(config)
    check(integrationCheck.hasErrors.not()) {
        integrationCheck.checks.joinToString("\n")
    }
    println("Self-check passed")
}
```

A successful run prints `Self-check passed`. Default checks parse built-in probes only. Passing `modelProbes` also parses the named application models. Callbacks execute synchronously on the parsing thread; shared logging or event containers must be thread safe.

A JVM test or `testReleaseUnitTest` does not prove that R8 preserves your models. Follow [Android ProGuard](../docs/en/android-proguard.md), then run model probes with real application JSON inside an actual minified APK.

## 7. Continue Reading

| Topic | Document |
| --- | --- |
| Existing Builder / Gson and generic parsing | [Getting Started](../docs/en/getting-started.md) |
| API choices and explicit Java types | [API Reference](../docs/en/api-reference.md) |
| Field recovery, roots, and exception boundaries | [Mismatch Capability Matrix](../docs/en/mismatch-capability-matrix.md) |
| Retrofit empty bodies and raw JSON | [Troubleshooting](../docs/en/troubleshooting.md) |

Syntax errors, truncated JSON, caller-adapter exceptions, and Retrofit network failures still propagate. Recovery must not disguise them as successful application data.
