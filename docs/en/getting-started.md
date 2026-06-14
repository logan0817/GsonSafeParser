# Getting Started

[中文](../getting-started.md)

This document is for developers integrating GsonSafeParser for the first time.

It covers 5 things: installation, plain Gson usage, Retrofit usage, Kotlin APIs, and CI self-checks. For the full fallback scope, see the [Mismatch Capability Matrix](mismatch-capability-matrix.md).

## 1. Installation

The published artifacts are Android AARs compiled with JDK 17. Make sure your project uses JDK 17 or later.

Choose by scenario:

| Scenario | What to do |
| --- | --- |
| debug or non-minified builds | You can try zero config first. |
| release builds with R8 / ProGuard | Keep business model field names and constructors from [Android ProGuard](android-proguard.md). |
| legacy projects with scattered Beans | Start with broad package-level keep rules for bean, model, entity, response, and dto packages. |
| only adding `@SerializedName` | It fixes JSON field names only; it does not replace constructor or Kotlin Metadata keep rules. |

Zero config is only for debug or non-minified trials.

`android.enableR8.fullMode=false` is only a short-term legacy compatibility strategy. It does not replace business model keep rules.

Plain Gson usage:

Latest version: [![Maven Central: core](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-core?label=core)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-core)

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.3")
```

If the project uses Retrofit, only add:

Latest version: [![Maven Central: retrofit](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-retrofit?label=retrofit)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-retrofit)

```kotlin
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.3")
```

## 2. Plain Gson Integration

Dependency coordinates use `io.github.logan0817`, while code imports use `io.github.logan.gsonsafeparser`. This is the difference between the Maven Central namespace and the public Kotlin package name, and it does not change usage.

Minimal copyable example:

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.contractReport
import io.github.logan.gsonsafeparser.parseSafe

data class ApiResponse(
    val code: Int = 0,
    val data: User = User()
)

data class User(
    val id: Long = 0L,
    val name: String = "anonymous"
)

val json = """{"code":200,"data":[]}"""
val result = GsonSafeParser.parseSafe<ApiResponse>(json)

println(result.value)
println(result.events)
println(result.contractReport().toBackendMarkdown())
```

```kotlin
val gson = GsonSafeParser.create()
val response = gson.fromJson(json, ApiResponse::class.java)
```

`GsonSafeParser.create()` returns a Gson instance with Safe Adapters registered. Use it when you do not need a custom `GsonBuilder`.

The default entry does not read `GsonBuilder` internals, so it is the lower-risk choice when you do not need a custom GsonBuilder.

If you already own a `GsonBuilder`:

```kotlin
val gson = GsonBuilder()
    .serializeNulls()
    .enableSafeParser()
    .create()
```

This keeps your existing Gson options and registers field-level safe parsing on the same Builder.

`enableSafeParser()` registers safe parsing on the current `GsonBuilder` while preserving the Gson options you already configured.

The builder-first entry reads `GsonBuilder` internals only to inherit `InstanceCreator`, `ReflectionAccessFilter`, object number strategy, complex Map keys, and the Unsafe switch. If that inheritance cannot be inspected, `GsonSafeParser.diagnostics()` reports the exact field.

Repeated calls on the same `GsonBuilder` do not register duplicate Safe Adapters. Create a new `GsonBuilder` if a different config is needed.

## 3. Kotlin Convenience APIs

```kotlin
val value = GsonSafeParser.fromJsonSafe<ApiResponse>(json)
val result = GsonSafeParser.parseSafe<ApiResponse>(json)
```

`fromJsonSafe<T>()` is for cases that only need the parsed value. `parseSafe<T>()` also returns parse events for logging, monitoring, and API contract review.

Entry choice:

| Need | Recommended entry |
| --- | --- |
| Field-level fallback only | `GsonSafeParser.create()` or `GsonBuilder.enableSafeParser()`. |
| Parsed value plus event snapshot | `GsonSafeParser.parseSafe<T>()`. |
| Reuse the same config | `GsonSafeParser.parser(config)`. |
| Existing external Gson | Call `.enableSafeParser(config)` on the Builder first, then use `parserWithExternalGson(gson, config)`. |

Exception boundary:

| Problem | Default handling |
| --- | --- |
| Field-level unexpected JSON shape | Falls back for the current field, keeps the outer object parsing, and emits an event. |
| JSON syntax error | Still thrown. |
| Root-level parse failure | Still follows Gson boundaries. |
| Unsafe-to-isolate failure | `Error`, `ThreadDeath`, `LinkageError`, and `CancellationException` are still thrown. |

field-level adapter read failures emit events and keep the outer object parsing when the current field boundary can isolate them.

Direct `gson.fromJson(...)` calls keep Gson's native top-level exception wrapping. This preserves native Gson entry semantics.

```kotlin
val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""")

println(result.value)
println(result.events)
println(result.contractReport().toMarkdown())
println(result.contractReport().toBackendMarkdown())
```

These outputs show the parsed value, parse events, general Markdown report, and backend-facing contract report.

## 4. JSON Shape Coercion

Objects and arrays are not converted by default. This feature is enabled only through `withShapeCoercionPolicy(...)` or field annotations.

```kotlin
val config = SafeParserConfig()
    .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)

val gson = GsonSafeParser.create(config)
```

Field-level opt-in is usually better for production integration:

```kotlin
data class ApiResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val data: User? = null
)
```

Supported scope:

| Field type | Backend JSON | Behavior after enabling |
| --- | --- | --- |
| `data: User` | `"data":[{"id":1}]` | Reads the first object from the array. |
| `users: List<User>` | `"users":{"id":1}` | Wraps it as a one-item List. |
| `users: Array<User>` | `"users":{"id":1}` | Wraps it as a length-1 array. |

Root objects, root collections, root object arrays, maps, string re-parsing, numbers, and booleans are not coerced. Coercion failures emit `ShapeCoercion` events and return to the original fallback behavior.

## 5. Java Usage And Non-Reified APIs

Kotlin reified APIs are Kotlin-only. Java, reflected `Type`, and explicit type passing should use the `Class` or `Type` entries:

Java usage needs explicit type passing, and non-reified APIs are the right choice when you are not calling from Kotlin.

```java
SafeParserConfig config = new SafeParserConfig();
ApiResponse value = GsonSafeParser.INSTANCE.fromJson(json, ApiResponse.class, config);

GsonSafeParser.Parser parser = GsonSafeParser.INSTANCE.parser(config);
SafeParseResult<ApiResponse> result = parser.parseSafe(json, ApiResponse.class);
```

Use Gson `TypeToken` for generic types:

```java
Type listType = new TypeToken<List<ApiResponse>>() {}.getType();
SafeParseResult<List<ApiResponse>> result = parser.parseSafe(json, listType);
```

Java calls, reflected `Type`, and generic parsing must pass explicit types. Kotlin reified entries are Kotlin-only conveniences.

## 6. Reusable Parser

The convenience entry `GsonSafeParser.fromJson(json, type, config)` is useful for quick checks and low-frequency calls.

In repositories, data sources, or batch jobs that repeatedly parse with the same config, create a parser once and reuse it.

```kotlin
val config = SafeParserConfig.production()
val parser = GsonSafeParser.parser(config)

val first = parser.fromJson(json, ApiResponse::class.java)
val second = parser.fromJsonSafe<ApiResponse>(json)
val result = parser.parseSafe<ApiResponse>(json)
```

If the project already maintains a shared Gson instance, enable SafeParser on the Builder first and then wrap the Gson as a Parser:

```kotlin
val gson = GsonBuilder()
    .serializeNulls()
    .enableSafeParser(config)
    .create()

val parser = GsonSafeParser.parserWithExternalGson(gson, config)
```

`parserWithExternalGson(gson, config)` wraps an existing Gson only; it does not recreate, replace, or auto-register it.

Parser and Gson instances can be reused as singletons, DI objects, or repository members.

External Gson rules:

`parserWithExternalGson(gson, config)` does not automatically register Safe Adapter on an external Gson.

| Question | Answer |
| --- | --- |
| Does `parserWithExternalGson(gson, config)` auto-register Safe Adapters? | No. Call `.enableSafeParser(config)` on the same `GsonBuilder` before creating that Gson. |
| How do I check an external Gson? | Call `GsonSafeParser.diagnostics(gson)` and check whether the field-level Safe Adapter exists. |
| What does the config passed to `parserWithExternalGson(gson, config)` control? | Raw JSON capture, root primitive fallback, and the `parseSafe` event snapshot. |
| Field-level Adapter event callbacks | Owned by the config passed to `.enableSafeParser(...)` when the Gson was created. |
| Which thread runs callbacks? | The parsing caller thread. Caller-owned lists, log buffers, or metric collectors must be thread-safe under concurrency. |

## 7. Retrofit Integration

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create())
    .build()
```

Custom config:

```kotlin
val config = SafeParserConfig.production(
    observerPolicy = SafeObserverPolicy(
        onEvent = { event -> println(event) }
    )
)

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(config))
    .build()
```

`onEvent` receives the unified parse event stream and is typically connected to logs, metrics, or contract reports.

If the project already maintains a shared GsonBuilder, prefer the builder-first entry so the factory registers Safe Adapter before `.create()`:

```kotlin
val config = SafeParserConfig.debug()
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(GsonBuilder().serializeNulls(), config))
    .build()
```

If the project already maintains a created shared Gson instance and still needs Retrofit-level empty-response, raw JSON, and event policies:

```kotlin
val config = SafeParserConfig.debug()
val gson = GsonBuilder()
    .serializeNulls()
    .enableSafeParser(config)
    .create()

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(gson, config))
    .build()
```

This form reuses the existing Gson and keeps Retrofit-level empty response, raw JSON, and event policies.

Choose the entry by what you currently have:

| Current state | Recommended usage | Why |
| --- | --- | --- |
| You still have a `GsonBuilder` | `GsonSafeConverterFactory.create(builder, config)` | The factory registers Safe Adapters before `builder.create()`. |
| You already own a shared `Gson` | Call `.enableSafeParser(config)` on the `GsonBuilder` that creates it, then pass the final Gson to `create(gson, config)` | A created `Gson` has fixed configuration, and the library will not secretly mutate it. |
| You only call `create(gson, config)` | Reuses that Gson and applies Retrofit-level empty response, raw JSON, and event config | This does not automatically register Safe Adapter on the external Gson. |

## 8. CI Self-Check

```kotlin
val diagnostics = GsonSafeParser.diagnostics(SafeParserConfig.production())
val externalGsonDiagnostics = GsonSafeParser.diagnostics(gson)
val integrationCheck = GsonSafeParser.integrationCheck(SafeParserConfig.production())

integrationCheck.checks.forEach { item ->
    println("${item.severity}: ${item.name} - ${item.message}")
}

check(integrationCheck.hasErrors.not())
```

`diagnostics()` checks the environment and external Gson state; `integrationCheck()` runs built-in probes; the final `check(...)` turns blocking issues into test or CI failures.

`diagnostics()` checks Gson reflection compatibility and configuration risks, which is useful after forcing a different Gson version. It reports `GsonBuilder` internal compatibility per field: failed `critical` fields block builder-first safe registration, while failed `optional` fields only degrade inherited configuration.

`integrationCheck()` also runs built-in probes. It does not access the network, does not require an Android device, and does not parse business beans.

It is suitable for JVM unit tests that confirm Safe Adapter creation, event flow, and contract reports can work.

To put legacy release obfuscation risk into CI, pass a few key business model probes. Probe failures are converted into `checks`; they do not throw obfuscation-related exceptions out of the self-check call:

```kotlin
val modelProbe = GsonSafeModelProbe(
    name = "coreApiResponse",
    json = """{"code":200}""",
    type = ApiResponse::class.java,
    expectedFields = mapOf("code" to 200)
)

val releaseCheck = GsonSafeParser.integrationCheck(
    config = SafeParserConfig.production(),
    modelProbes = listOf(modelProbe)
)

check(releaseCheck.hasErrors.not())
```

`modelProbes` should cover only key response models. They detect whether release obfuscation still preserves field names and construction paths needed by real JSON.

Use 4 integration layers:

| Layer | What it checks |
| --- | --- |
| Layer 1 | `diagnostics()` checks library and Gson version compatibility. |
| Layer 2 | `integrationCheck()` checks built-in probes. |
| Layer 3 | `modelProbes` checks key business model release field names and constructors. |
| Layer 4 | The same real JSON is compared in debug and release builds. |

## 9. Suggested Integration Order

1. Start with `SafeParserConfig.debug()` in a test environment and inspect the event stream and contract reports.
2. For legacy Android projects, apply broad package-level keep rules from [Android ProGuard](android-proguard.md) first so release field names are stable.
3. Run business JSON regression tests for core APIs and confirm plain field-name models, `@SerializedName` models, defaults, and callback events match business expectations.
4. Switch to `SafeParserConfig.production()` before release to avoid long-term large raw JSON logging.
5. If the project is especially sensitive to behavior changes, start with `SafeParserConfig.lowInterference()` and observe real mismatches first.
