# Examples

[中文](README.md)

This directory contains minimal copyable examples. They are documentation snippets instead of a new Gradle submodule, so the current Android AAR publishing structure stays unchanged.

## Example Dependencies

Plain Gson examples only need core:

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.4")
```

Retrofit examples only need retrofit, which brings core transitively:

```kotlin
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.4")
```

## 1. Choose An Example By Goal

| Goal | Example | What You Will See |
| --- | --- | --- |
| Verify field-level fallback only | [Plain Gson Minimal Example](#2-plain-gson-minimal-example) | The outer object keeps parsing when `data` receives an array |
| Get parse events with the value | [Events And Contract Report](#3-events-and-contract-report) | `parseSafe<T>()`, `events`, and `contractReport()` |
| Integrate Retrofit | [Retrofit Minimal Example](#4-retrofit-minimal-example) | `GsonSafeConverterFactory.create()` |
| Explicit object-array coercion | [JSON Shape Coercion](#5-json-shape-coercion) | `@SafeParseShapeCoercion` |
| Add CI self-checks | [CI Integration Self-Check](#6-ci-integration-self-check) | `diagnostics()` and `integrationCheck()` |

## 2. Plain Gson Minimal Example

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser

data class ApiResponse(
    val code: Int = 0,
    val data: User = User()
)

data class User(
    val id: Long = 0L,
    val name: String = "local"
)

val json = """{"code":200,"data":[]}"""
val gson = GsonSafeParser.create()
val response = gson.fromJson(json, ApiResponse::class.java)

check(response.code == 200)
check(response.data == User())
```

This example verifies one boundary: `data` expects a JSON object but receives a JSON array. GsonSafeParser falls back only for the `data` field, and the outer `code` value keeps parsing.

## 3. Events And Contract Report

The snippet below reuses the `ApiResponse` / `User` models from section 2. Copy those models first when pasting this snippet alone.

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.contractReport
import io.github.logan.gsonsafeparser.parseSafe

val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""")

println(result.value)
println(result.events)
println(result.contractReport().toBackendMarkdown())
```

This example verifies that the parsed value, parse events, and backend-facing contract report come from the same parse result. `contractReport()` does not parse JSON again and does not mutate the business value.

## 4. Retrofit Minimal Example

```kotlin
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.retrofit.GsonSafeConverterFactory
import com.google.gson.GsonBuilder
import retrofit2.Retrofit

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create())
    .build()
```

If the project already owns a shared `GsonBuilder`:

```kotlin
val config = SafeParserConfig.production()
val builder = GsonBuilder().serializeNulls()

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(builder, config))
    .build()
```

This example verifies that Retrofit response bodies use Gson with Safe Adapter registration. Offline state, cancellation, connection reset, and TLS failure still return to Retrofit / OkHttp; they are not treated as empty responses or field mismatches.

## 5. JSON Shape Coercion

The snippet below reuses the `User` model from section 2. Copy that model first when pasting this snippet alone.

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParseShapeCoercion
import io.github.logan.gsonsafeparser.ShapeCoercionPolicy
import io.github.logan.gsonsafeparser.parseSafe

data class FlexibleResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val data: User? = null
)

val result = GsonSafeParser.parseSafe<FlexibleResponse>(
    """{"data":[{"id":1,"name":"remote"}]}"""
)

println(result.value?.data)
println(result.events)
```

This example verifies that an object field can explicitly read the first object from an array. This capability is disabled by default and should only be used on fields where the business contract accepts that recovery rule.

## 6. CI Integration Self-Check

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParserConfig

val diagnostics = GsonSafeParser.diagnostics(SafeParserConfig.production())
check(diagnostics.hasErrors.not()) {
    diagnostics.checks.joinToString(separator = "\n")
}

val integrationCheck = GsonSafeParser.integrationCheck(SafeParserConfig.production())
check(integrationCheck.hasErrors.not()) {
    integrationCheck.checks.joinToString(separator = "\n")
}
```

This example verifies whether the current Gson version, kotlin-reflect setup, Safe Adapter registration, event stream, and contract report path are ready for adoption. It does not access the network and does not require an Android device.

## 7. Continue Reading

| Topic | Document |
| --- | --- |
| Choose an API entry point | [API Reference](../docs/en/api-reference.md) |
| Full fallback scope | [Mismatch Capability Matrix](../docs/en/mismatch-capability-matrix.md) |
| Android release obfuscation | [Android ProGuard](../docs/en/android-proguard.md) |
| Retrofit empty responses and raw JSON | [Troubleshooting](../docs/en/troubleshooting.md) |
