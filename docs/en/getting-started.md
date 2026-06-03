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

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.1") // Adds the core GsonSafeParser parsing library.
```

If the project uses Retrofit, only add:

```kotlin
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.1") // Adds the Retrofit converter integration and transitively includes core.
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
val gson = GsonSafeParser.create() // Creates a Gson instance with safe parsing enabled.
val response = gson.fromJson(json, ApiResponse::class.java) // Parses a business response with safe Gson.
```

If you already own a `GsonBuilder`:

```kotlin
val gson = GsonBuilder() // Creates a custom GsonBuilder.
    .serializeNulls() // Keeps the Gson serialization option you already need.
    .enableSafeParser() // Registers safe parsing on the current Builder.
    .create() // Creates the final Gson instance.
```

`enableSafeParser()` registers safe parsing on the current `GsonBuilder` while preserving the Gson options you already configured.

Repeated calls on the same `GsonBuilder` do not register duplicate Safe Adapters. Create a new `GsonBuilder` if a different config is needed.

## 3. Kotlin Convenience APIs

```kotlin
val value = GsonSafeParser.fromJsonSafe<ApiResponse>(json) // Parses and returns the business object.
val result = GsonSafeParser.parseSafe<ApiResponse>(json) // Parses and returns the value, events, and report entry.
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
val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""") // Parses JSON with an object-field mismatch.

println(result.value) // Prints the fallback business object.
println(result.events) // Prints events produced during parsing.
println(result.contractReport().toMarkdown()) // Prints the contract report in Markdown.
println(result.contractReport().toBackendMarkdown()) // Prints a backend-facing contract report for API fixes.
```

## 4. Java Usage And Non-Reified APIs

Kotlin reified APIs are Kotlin-only. Java, reflected `Type`, and explicit type passing should use the `Class` or `Type` entries:

```java
SafeParserConfig config = new SafeParserConfig(); // Java usage needs an explicit config object.
ApiResponse value = GsonSafeParser.INSTANCE.fromJson(json, ApiResponse.class, config); // Parses with a Java Class.

GsonSafeParser.Parser parser = GsonSafeParser.INSTANCE.parser(config); // Creates a reusable Parser.
SafeParseResult<ApiResponse> result = parser.parseSafe(json, ApiResponse.class); // Uses a non-reified entry and returns value plus events.
```

Use Gson `TypeToken` for generic types:

```java
Type listType = new TypeToken<List<ApiResponse>>() {}.getType(); // Keeps generic type information.
SafeParseResult<List<ApiResponse>> result = parser.parseSafe(json, listType); // Non-reified generic parsing.
```

## 5. Reusable Parser

The convenience entry `GsonSafeParser.fromJson(json, type, config)` is useful for quick checks and low-frequency calls.

In repositories, data sources, or batch jobs that repeatedly parse with the same config, create a parser once and reuse it.

```kotlin
val config = SafeParserConfig.production() // Creates the config reused in this business scenario.
val parser = GsonSafeParser.parser(config) // Creates a reusable Parser with one internal safe Gson.

val first = parser.fromJson(json, ApiResponse::class.java) // Parses once with the Parser's internal Gson.
val second = parser.fromJsonSafe<ApiResponse>(json) // Kotlin reified API, also using the same internal Gson.
val result = parser.parseSafe<ApiResponse>(json) // Reuses the Parser and returns events plus the report entry.
```

If the project already maintains a shared Gson instance, enable SafeParser on the Builder first and then wrap the Gson as a Parser:

```kotlin
val gson = GsonBuilder() // Creates the shared GsonBuilder owned by the project.
    .serializeNulls() // Keeps existing project Gson options.
    .enableSafeParser(config) // Registers SafeParser with the same config.
    .create() // Creates the shared Gson.

val parser = GsonSafeParser.parserWithExternalGson(gson, config) // Wraps existing Gson without recreating, replacing, or auto-registering it.
```

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

## 6. Retrofit Integration

```kotlin
val retrofit = Retrofit.Builder() // Creates a Retrofit builder.
    .baseUrl("https://example.com/") // Sets the API base URL.
    .addConverterFactory(GsonSafeConverterFactory.create()) // Registers the GsonSafeParser response converter.
    .build() // Builds Retrofit.
```

Custom config:

```kotlin
val config = SafeParserConfig.production( // Creates the recommended production config.
    observerPolicy = SafeObserverPolicy( // Configures parse-event observation.
        onEvent = { event -> // Receives unified parse events.
            println(event) // Prints the event for logs or monitoring.
        } // Ends event callback.
    ) // Ends observer policy.
) // Ends config creation.

val retrofit = Retrofit.Builder() // Creates a Retrofit builder.
    .baseUrl("https://example.com/") // Sets the API base URL.
    .addConverterFactory(GsonSafeConverterFactory.create(config)) // Registers the converter with custom config.
    .build() // Builds Retrofit.
```

If the project already maintains a shared GsonBuilder, prefer the builder-first entry so the factory registers Safe Adapter before `.create()`:

```kotlin
val config = SafeParserConfig.debug() // Controls SafeParser behavior for both Gson and Retrofit.
val retrofit = Retrofit.Builder() // Creates a Retrofit builder.
    .baseUrl("https://example.com/") // Sets the API base URL.
    .addConverterFactory(GsonSafeConverterFactory.create(GsonBuilder().serializeNulls(), config)) // Keeps Builder options and enables field-level Safe Adapter.
    .build() // Builds Retrofit.
```

If the project already maintains a created shared Gson instance and still needs Retrofit-level empty-response, raw JSON, and event policies:

```kotlin
val config = SafeParserConfig.debug() // Controls SafeParser behavior for both Gson and Retrofit.
val gson = GsonBuilder() // Creates the shared project GsonBuilder.
    .serializeNulls() // Keeps caller-owned Gson options.
    .enableSafeParser(config) // Registers the same SafeParserConfig on Gson.
    .create() // Creates the final Gson.

val retrofit = Retrofit.Builder() // Creates a Retrofit builder.
    .baseUrl("https://example.com/") // Sets the API base URL.
    .addConverterFactory(GsonSafeConverterFactory.create(gson, config)) // Reuses existing Gson and Retrofit-level SafeParserConfig.
    .build() // Builds Retrofit.
```

Choose the entry by what you currently have:

| Current state | Recommended usage | Why |
| --- | --- | --- |
| You still have a `GsonBuilder` | `GsonSafeConverterFactory.create(builder, config)` | The factory registers Safe Adapters before `builder.create()`. |
| You already own a shared `Gson` | Call `.enableSafeParser(config)` on the `GsonBuilder` that creates it, then pass the final Gson to `create(gson, config)` | A created `Gson` has fixed configuration, and the library will not secretly mutate it. |
| You only call `create(gson, config)` | Reuses that Gson and applies Retrofit-level empty response, raw JSON, and event config | This does not automatically register Safe Adapter on the external Gson. |

## 7. CI Self-Check

```kotlin
val diagnostics = GsonSafeParser.diagnostics(SafeParserConfig.production()) // Checks GsonBuilder compatibility and high-risk config.
val externalGsonDiagnostics = GsonSafeParser.diagnostics(gson) // Checks whether an external Gson has the field-level Safe Adapter.
val integrationCheck = GsonSafeParser.integrationCheck(SafeParserConfig.production()) // Runs the built-in integration self-check.

integrationCheck.checks.forEach { item -> // Iterates over every check result.
    println("${item.severity}: ${item.name} - ${item.message}") // Prints severity, name, and message.
} // Ends check result iteration.

check(integrationCheck.hasErrors.not()) // Fails the test or CI if blocking issues exist.
```

`diagnostics()` only checks Gson reflection compatibility and configuration risks, which is useful after forcing a different Gson version.

`integrationCheck()` also runs built-in probes. It does not access the network, does not require an Android device, and does not parse business beans.

It is suitable for JVM unit tests that confirm Safe Adapter creation, event flow, and contract reports can work.

To put legacy release obfuscation risk into CI, pass a few key business model probes. Probe failures are converted into `checks`; they do not throw obfuscation-related exceptions out of the self-check call:

```kotlin
val modelProbe = GsonSafeModelProbe( // Select one key response model as a release field-name probe.
    name = "coreApiResponse", // Use an API or model name for diagnosis.
    json = """{"code":200}""", // Minimal business JSON.
    type = ApiResponse::class.java, // Real business model type.
    expectedFields = mapOf("code" to 200) // Original field name and expected value.
) // Ends business model probe.

val releaseCheck = GsonSafeParser.integrationCheck( // Runs built-in checks plus business model probes.
    config = SafeParserConfig.production(), // Uses the production config.
    modelProbes = listOf(modelProbe) // Probe key models first; do not require full-project Bean coverage.
) // Ends release self-check.

check(releaseCheck.hasErrors.not()) // Fails CI if suspected model field obfuscation is reported.
```

Use 4 integration layers:

| Layer | What it checks |
| --- | --- |
| Layer 1 | `diagnostics()` checks library and Gson version compatibility. |
| Layer 2 | `integrationCheck()` checks built-in probes. |
| Layer 3 | `modelProbes` checks key business model release field names and constructors. |
| Layer 4 | The same real JSON is compared in debug and release builds. |

## 8. Suggested Integration Order

1. Start with `SafeParserConfig.debug()` in a test environment and inspect the event stream and contract reports.
2. For legacy Android projects, apply broad package-level keep rules from [Android ProGuard](android-proguard.md) first so release field names are stable.
3. Run business JSON regression tests for core APIs and confirm plain field-name models, `@SerializedName` models, defaults, and callback events match business expectations.
4. Switch to `SafeParserConfig.production()` before release to avoid long-term large raw JSON logging.
5. If the project is especially sensitive to behavior changes, start with `SafeParserConfig.lowInterference()` and observe real mismatches first.
