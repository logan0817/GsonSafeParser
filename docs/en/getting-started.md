# Getting Started

[中文](../getting-started.md)

First run a field-mismatch example, then connect your application parsing. The examples below use **1.0.5**, available from Maven Central. Before upgrading, review behavior changes and migration guidance in the [release notes](release-notes-1.0.5.md).

## 1. Add a dependency

This library ships Android AARs. Use JDK 17 or later and `minSdk 23` or later. See [Compatibility](compatibility.md) for the verified versions. A plain JVM project cannot consume an Android AAR in the same way.

Open the root `settings.gradle.kts` and confirm that your existing dependency repositories include `mavenCentral()`. Keep existing configuration and add it only if missing:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Open the **application module** file, `app/build.gradle.kts`. Choose only one dependency below, then run Android Studio's **Sync Project with Gradle Files**.

For manual Gson calls:

```kotlin
dependencies {
    implementation("io.github.logan0817:gson-safe-parser-core:1.0.5")
}
```

For Retrofit:

```kotlin
dependencies {
    implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.5")
}
```

The Retrofit module already brings core transitively. Dependency coordinates use `io.github.logan0817`, while code imports use `io.github.logan.gsonsafeparser`.

## 2. Run a complete example

Copy this complete `.kt` file into your Android module's Kotlin source directory. Run `main()` from the IDE, or place its body in an existing debug entry point. Each later example is also a separate standalone file.

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

Expected output:

```text
ApiResponse(code=200, data=User(id=0, name=anonymous))
$.data
true
```

`data` expects an object but receives an array, so its constructed `User()` default is retained. The outer `code` is still 200. `parseSafe<T>()` returns both the value and this call's events. For a backend-facing report, call `result.contractReport().toBackendMarkdown()` inside the function.

If you only need the value, obtain a reusable Gson with `GsonSafeParser.create()` and call `gson.fromJson(json, ApiResponse::class.java)` as usual.

## 3. Reuse a parser and retain generic types

For repeated calls, create a Parser once as a repository or injected dependency member. Kotlin's `<List<User>>` retains its element type; do not replace it with `List::class.java`.

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.fromJsonSafe

data class User(val id: Long = 0L, val name: String = "")

fun main() {
    val config = SafeParserConfig.production()
    val parser = GsonSafeParser.parser(config)
    val users = parser.fromJsonSafe<List<User>>(
        """[{"id":1,"name":"remote"}]"""
    )
    println(users)
}
```

Expected output: `[User(id=1, name=remote)]`. For events, use `parser.parseSafe<ApiResponse>(json)` with the model from section 2.

## 4. Integrate an existing GsonBuilder or Gson

If you own a `GsonBuilder`, prefer `GsonSafeParser.parser(builder, config)`, which registers Safe Adapters before creating Gson. If the application already shares a Gson instance, add `.enableSafeParser(config)` where that instance is created, then pass it to `GsonSafeParser.parserWithExternalGson(gson, config)`:

```kotlin
import com.google.gson.GsonBuilder
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.enableSafeParser
import io.github.logan.gsonsafeparser.parseSafe

data class ApiResponse(val tags: List<String> = listOf("local"))

fun main() {
    val config = SafeParserConfig.production()
    val gson = GsonBuilder()
        .serializeNulls()
        .enableSafeParser(config)
        .create()
    val parser = GsonSafeParser.parserWithExternalGson(gson, config)
    val result = parser.parseSafe<ApiResponse>("""{"tags":{}}""")
    println(result.value)
}
```

Expected output: `ApiResponse(tags=[local])`. The wrapper does not automatically register Safe Adapter on an external Gson; passing a config after Gson creation cannot add registration. Field-level Adapter event callbacks belong to the config used to create Gson. Parser and Gson can be shared across threads. Callbacks run synchronously on the parsing thread, and shared callback containers must be thread safe.

## 5. Retrofit: replace the existing Gson converter

Replace an existing `.addConverterFactory(GsonConverterFactory.create(...))` line with the Safe factory below. **Do not register both Gson factories on the same Retrofit**: the first one can claim JSON conversion before the second runs.

This complete example creates Retrofit and an interface proxy without sending a request. Replace `baseUrl` with your service URL when integrating it.

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

Expected output: `true`. The example calls neither `execute()` nor `enqueue()`.

Choose the factory based on what you already own:

| Existing object | Factory |
| --- | --- |
| No custom Gson | `GsonSafeConverterFactory.create()` |
| A `GsonBuilder` still being configured | `GsonSafeConverterFactory.create(builder, config)` |
| A Gson created with `.enableSafeParser(config)` | `GsonSafeConverterFactory.create(gson, config)` |

The last overload does not register Safe Adapters on a plain external Gson. See [Troubleshooting](troubleshooting.md) for Retrofit Unit/Void, HTTP 204/205, and empty model responses.

## 6. Change fallback behavior only when needed

Start with defaults and actual application models, then choose a policy for a specific requirement:

| Requirement | Change |
| --- | --- |
| Invalid Int, Boolean, or String values may receive safe defaults | Explicitly enable `PrimitiveParsingPolicy.Safe` and review critical fields such as money and status. |
| A malformed whole collection should mean “no data” | Evaluate `FallbackPolicy.Default`; the default is `NullOnly`. |
| A specific backend field alternates between object and array | Enable field-level `@SafeParseShapeCoercion`; coercion is off by default. |
| Local troubleshooting needs the original JSON | Temporarily use `SafeParserConfig.debug()`; use `production()` for normal operation, with capture disabled. |
| Preserve existing Gson scalar behavior | Keep defaults, or read about `SafeParserConfig.lowInterference()`. |

With capture enabled, use `parseSafe`, Parser parsing methods, or the Retrofit factory to establish a per-call raw JSON context. Enabling capture on Gson's config and calling `gson.fromJson(...)` directly does not attach the original JSON. Capture also has a size limit; see [Troubleshooting](troubleshooting.md).

SafeParser built-in adapters recover isolated field mismatches and emit events. Primitives delegate to Gson by default. JSON syntax errors, truncated documents, caller-adapter exceptions, and unrecoverable failures propagate. Root-level behavior depends on type and policy; do not assume every failure produces a complete default object. See the [Mismatch Capability Matrix](mismatch-capability-matrix.md) and [Configuration](configuration.md).

## 7. Check integration and release obfuscation

The default self-check runs built-in probes without parsing application Beans. It needs neither network access nor an Android device:

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

A successful run prints `Self-check passed`. For an existing Gson, also use `GsonSafeParser.diagnostics(gson)` to confirm Safe Adapter registration. Supplying `modelProbes` also parses the application models named by those probes and checks expected fields; see the [API Reference](api-reference.md).

Zero config is only for debug or non-minified trials. For an R8 / ProGuard release, follow [Android ProGuard](android-proguard.md) to retain model fields, constructors, and necessary Kotlin Metadata. `@SerializedName` alone is not enough. `testReleaseUnitTest` does not run R8 on test models and is not an obfuscation check. Compare key application JSON in debug and an actual minified APK, and run model probes in that minified environment before release.

<details>
<summary>Custom adapters, Java usage, and internal compatibility</summary>

Types handled by `registerTypeAdapter(...)`, `registerTypeAdapterFactory(...)`, `registerTypeHierarchyAdapter(...)`, or `@JsonAdapter` retain caller-owned parsing rules, and exceptions from those adapters propagate. Direct `gson.fromJson(...)` calls retain Gson's outer exception wrapping.

The default entry does not read `GsonBuilder` internals. The builder-first entry reads `GsonBuilder` internals to inherit existing settings. After overriding the Gson version, inspect `diagnostics()`; see [Compatibility](compatibility.md) for details.

Java usage, reflective Type values, and non-reified APIs use an explicit `Class` or Gson `TypeToken`; see the [API Reference](api-reference.md).

</details>
