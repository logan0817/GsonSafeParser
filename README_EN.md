# GsonSafeParser

[中文](README.md)

GsonSafeParser is a Kotlin-first Gson extension for Android projects. It is published as Android AARs.

It solves one common problem: when one backend field returns an unexpected JSON shape, native Gson may fail the whole bean.

GsonSafeParser tries to isolate that problem to the current field so the outer object can keep parsing.

It does not silently swallow bad payloads. The library records the field path, expected shape, actual shape, and fallback action.

Use that evidence to report contract issues and observe backend payload drift in production.

## Features

1. Field-level fallback: when an object, collection, map, or primitive field receives a mismatched JSON type, only the current field is handled defensively.
2. Native Gson adapter fallback by default: if a Safe Adapter cannot be created or a type cannot be handled confidently, SafeParser does not rewrite that type's read behavior.
3. Kotlin friendly: supports Kotlin data class defaults, reified APIs, `parseSafe<T>()`, and `fromJsonSafe<T>()`.
4. Retrofit integration: provides `GsonSafeConverterFactory` with empty response policies and raw JSON capture limits.
5. Explicit shape coercion: can read the first object from an array for object fields, or wrap a single object as a one-item collection or object array.
6. Contract evidence: records field path, expected shape, actual shape, and fallback action; it can also generate backend-facing Markdown reports.
7. Demo App: includes an Android Demo App for testing built-in cases and custom JSON on a real device.

## Default Behavior

The default config is meant for existing Gson projects. The library handles only field problems that can be safely isolated; primitives, root-level failures, and uncertain cases keep native Gson behavior.

### Default Config

| Config | Default |
| --- | --- |
| `fallbackPolicy` | `FallbackPolicy.NullOnly` |
| `primitiveParsingPolicy` | `PrimitiveParsingPolicy.DelegateToGson` |
| `emptyResponsePolicy` | `EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly` |
| `useJdkUnsafe` | `false` |
| `requiredConstructorParameterPolicy` | `RequiredConstructorParameterPolicy.GsonCompatible` |
| `mapItemKeyPolicy` | `MapItemKeyPolicy.Hash` |

### Optional Capability State

| Capability | Default state | How to enable |
| --- | --- | --- |
| JSON shape coercion | `ShapeCoercionPolicy.Disabled` | Call `withShapeCoercionPolicy(...)`, or annotate a field with `@SafeParseShapeCoercion`. |

### Constructor Policy

The default is `GsonCompatible + useJdkUnsafe = false`. It keeps Gson-compatible behavior while preventing SafeParser itself from bypassing constructors with Unsafe.

| Goal | Recommended config |
| --- | --- |
| Adopt the library in an existing project | Keep the default config. |
| Match native Gson Unsafe construction because the project depends on it | Use `GsonCompatible + useJdkUnsafe = true`. |
| Treat missing fields, `null`, or unknown enum values as API contract errors | Use `Strict + useJdkUnsafe = false`. |

See [Configuration](docs/en/configuration.md) for the full config reference.

### Fixed Boundaries

| Scenario | Unexpected JSON | Result |
| --- | --- | --- |
| Object field mismatch | `[]`, `""`, `1` | Returns `null` or keeps the constructed field default by default, while the outer object keeps parsing. |
| Root object mismatch | `[]`, `""`, `1` | Usually returns `null`; unrecoverable Gson exceptions are still thrown. |
| `String` field | `[]`, `{}` | Keeps the constructed field default when field reading fails; root values delegate to the native Gson adapter. |

`FallbackPolicy` (default: `FallbackPolicy.NullOnly`):

| Target type | Unexpected JSON | `FallbackPolicy.NullOnly` (default) | `FallbackPolicy.Default` |
| --- | --- | --- | --- |
| List / Set | `{}`, `""` | Returns `null`. | Returns an empty collection. |
| Map | `[]`, `""` | Returns `null`. | Returns an empty map. |

Note: fields with constructed defaults keep those defaults. Root values or fields without defaults still follow the table and return `null`.

`PrimitiveParsingPolicy` (default: `PrimitiveParsingPolicy.DelegateToGson`):

| Target type | Unexpected JSON | `PrimitiveParsingPolicy.DelegateToGson` (default) | `PrimitiveParsingPolicy.Safe` |
| --- | --- | --- | --- |
| Int / Long / Boolean | `{}`, `[]`, `""` | Delegates to the native Gson adapter. | Uses safe primitive defaults. |

`EmptyResponsePolicy` (default: `EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly`):

| Scenario | Response | `DefaultValueForUnitOrVoidOnly` (default) | `DefaultValue` | `Null` | `DelegateToGson` |
| --- | --- | --- | --- | --- | --- |
| Empty Retrofit `Unit` / `Void` body | Empty body | `Unit` returns `Unit`; `Void` returns `null`. | Returns each empty value. | Returns `null`. | Returns Retrofit empty values `Unit` / `null`; no Gson delegate is requested. |
| Empty Retrofit model body | Empty body | Returns `null`. | Returns a default object. | Returns `null`. | Usually ends with `EOFException`. |

## Installation

The published artifacts are Android AARs compiled with JDK 17. Make sure the business project uses JDK 17 or later.

Before production integration, read [Compatibility](docs/en/compatibility.md).

The current verified matrix is `minSdk 23`, `compileSdk 36`, `JDK 17`, `Kotlin 2.0.21`, `kotlin-reflect 2.0.21`, and `Gson 2.13.2`. The Retrofit module is verified with `Retrofit 2.8.1`.

Use the badge version below. If you use plain Gson or manage Gson yourself, depend on core only:

[![Maven Central: core](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-core?label=core)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-core)

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.3") // Adds the core defensive parsing library.
```

If you use Retrofit, depend on the retrofit module only; it already brings core transitively:

[![Maven Central: retrofit](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-retrofit?label=retrofit)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-retrofit)

```kotlin
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.3") // Adds the Retrofit converter integration and transitively includes core.
```

Extra Android release requirements:

| Scenario | What to do |
| --- | --- |
| debug or non-minified builds | You can try zero config first. |
| release builds with R8 / ProGuard | Add business model keep rules from [Android ProGuard](docs/en/android-proguard.md). |
| legacy projects with scattered models | Start with broad package-level keep rules for field names, constructors, and Kotlin defaults; narrow later. |
| only adding `@SerializedName` | It fixes JSON field names only; it does not replace Kotlin Metadata or constructor keep rules. |

## Quick Start

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser

data class ApiResponse(
    val code: Int = 0,
    val data: User? = null
)

data class User(
    val id: Long = 0L,
    val name: String = ""
)

val json = """{"code":200,"data":[]}"""
val gson = GsonSafeParser.create() // Creates a Gson instance with safe parsing enabled.
val response = gson.fromJson(json, ApiResponse::class.java) // Parses the API response through the safe Gson instance.
```

Native Gson throws when `data` expects an object but receives `[]`. GsonSafeParser falls back for the `data` field and continues parsing the outer `code`.

Fallback boundary:

| Problem | Default handling |
| --- | --- |
| Field-level unexpected JSON shape | Falls back only for the current field; the outer object keeps parsing. |
| JSON syntax error | Still thrown. It is not turned into a default value. |
| Root-level parse failure | Still follows Gson boundaries and cannot always be isolated at field level. |
| Retrofit network or transport read failure | Returns to Retrofit / OkHttp error handling, is not recorded as a field mismatch or empty response, and must not be hidden with `emptyResponsePolicy`. |
| Unsafe-to-isolate failure | `Error`, `ThreadDeath`, `LinkageError`, and `CancellationException` are still thrown. |

field-level adapter read failures emit events and keep the outer object parsing when the current field boundary can isolate them.

Entry choice:

| What you need | Recommended entry |
| --- | --- |
| Field-level safe parsing only | `GsonSafeParser.create()` or `GsonBuilder.enableSafeParser()`. |
| Parsed value plus event snapshot | `GsonSafeParser.parseSafe<T>()` or `parser.parseSafe<T>()`. |
| Reuse one config frequently | `GsonSafeParser.parser(config)`. |
| Existing external Gson | Call `.enableSafeParser(config)` on the same `GsonBuilder` first, then use `parserWithExternalGson(gson, config)`. |
| Retrofit response conversion | `GsonSafeConverterFactory.create(...)`. |

Direct `gson.fromJson(...)` calls keep Gson's native top-level exception wrapping. This avoids replacing Gson itself or changing semantics callers may already depend on.

Kotlin convenience APIs:

```kotlin
val response = GsonSafeParser.fromJsonSafe<ApiResponse>(json) // Parses JSON and returns the target value directly.
val result = GsonSafeParser.parseSafe<ApiResponse>(json) // Parses JSON and returns the value plus parse events.

println(result.value)
println(result.events)
println(result.contractReport().toMarkdown())
println(result.contractReport().toBackendMarkdown())
println(result.contractReport().summary.warningCount)
println(result.contractReport().toStructuredRows().firstOrNull()?.stableKey)
```

If you parse the same API shape repeatedly, create one reusable Parser first:

```kotlin
val parser = GsonSafeParser.parser(config) // Creates one safe Parser and reuses the same Gson afterwards.
val value = parser.fromJsonSafe<ApiResponse>(json)
val result = parser.parseSafe<ApiResponse>(json)
```

## JSON Shape Coercion

By default, GsonSafeParser does not convert objects and arrays into each other. JSON shape coercion stays in the `ShapeCoercionPolicy.Disabled` state by default, so 1.0.3 keeps the same default parsing behavior as previous releases.

Use this feature only when a backend field is unstable and the business accepts an explicit recovery rule:

| Code field | Backend JSON | Behavior after enabling |
| --- | --- | --- |
| `data: User` | `"data":[{"id":1}]` | Reads the first object from the array into `data`. |
| `users: List<User>` | `"users":{"id":1}` | Wraps the object as a one-item List. |
| `users: Array<User>` | `"users":{"id":1}` | Wraps the object as a one-item array. |

Enable globally:

```kotlin
val config = SafeParserConfig()
    .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)

val gson = GsonSafeParser.create(config)
```

Enable for one field:

```kotlin
data class ApiResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val data: User? = null
)
```

Disable one strict-contract field when the global policy is enabled:

```kotlin
data class StrictEnvelope(
    @field:SafeParseDisableShapeCoercion
    val signedPayload: SignedPayload = SignedPayload()
)
```

If `errors: List<ApiError>` itself is an object/array drift field, do not disable it; use `CollectionFromSingleObject` or `ObjectAndCollection`.

The boundary is intentionally narrow: root objects, root collections, root object arrays, maps, string re-parsing, numbers, and booleans are not coerced. Empty arrays, a non-object first array item, or adapter failures during coercion emit `ShapeCoercion` events and return to the original fallback behavior. `Error`, `ThreadDeath`, `LinkageError`, `CancellationException`, and real transport I/O still escape.

## Retrofit Integration

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create()) // Registers the GsonSafeParser response converter.
    .build()
```

With custom empty response and observation policies:

```kotlin
val config = SafeParserConfig.production(
    observerPolicy = SafeObserverPolicy(
        onEvent = { event -> println(event) } // Receives and reports parse events.
    )
)

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(config)) // Registers the converter with custom config.
    .build()
```

If your project already owns a shared GsonBuilder, prefer the builder-first entry:

```kotlin
val config = SafeParserConfig.debug()

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(GsonBuilder().serializeNulls(), config))
    .build()
```

If your project already owns a created shared Gson instance and still needs Retrofit-level empty response, raw JSON, and observer policies, use:

```kotlin
val config = SafeParserConfig.debug() // Drives both the shared Safe Gson and the Retrofit-specific policies.
val gson = GsonBuilder()
    .serializeNulls()
    .enableSafeParser(config) // Registers the same SafeParserConfig on Gson.
    .create()

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(gson, config)) // Reuses the caller Gson and keeps Retrofit policies from config.
    .build()
```

Choose the entry by what you currently have:

| Current state | Recommended usage | Why |
| --- | --- | --- |
| You still have a `GsonBuilder` | `GsonSafeConverterFactory.create(builder, config)` | The factory registers Safe Adapters before `builder.create()`. |
| You already own a shared `Gson` | Call `.enableSafeParser(config)` on the `GsonBuilder` that creates it, then pass the final Gson to `create(gson, config)` | A created `Gson` has fixed configuration, and the library will not secretly mutate it. |
| You only call `create(gson, config)` | Reuses that Gson and applies Retrofit-level empty response, raw JSON, and event config | This does not automatically register Safe Adapter on the external Gson. |

Use `GsonSafeParser.diagnostics(gson)` if you are not sure whether an external Gson has field-level safe parsing enabled.

## Common Configuration

```kotlin
val config = SafeParserConfig(
    fallbackPolicy = FallbackPolicy.NullOnly, // Returns null or keeps constructed defaults for mismatched fields.
    emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly, // Returns empty values only for Unit/Void Retrofit bodies.
    primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson, // Delegates primitive values to native Gson adapters.
    skippedPlatformTypePrefixes = setOf("android."), // Skips Android platform types to avoid reflecting system objects; do not add business model package prefixes here.
    nullValuePolicy = NullValuePolicy.WriteExplicitNulls, // Writes explicit JSON null only to nullable fields.
    requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.GsonCompatible, // Keeps Gson-compatible behavior for missing non-null Kotlin constructor parameters.
    mapItemKeyPolicy = MapItemKeyPolicy.Hash, // Emits stable hashed map item keys in events.
    captureRawJsonInCallbacks = false, // Avoids attaching raw JSON to events in production.
    maxRawJsonCaptureBytes = 1024 * 1024, // Limits raw JSON capture to 1 MiB.
    onEvent = { event -> println(event) }, // Observes all unified safe parser events.
    onTypeMismatch = { event ->
        println("${event.path}: ${event.actualToken} -> ${event.expectedType}")
    } // Prints path, actual token, and expected type.
)
```

Preset configs:

```kotlin
val production = SafeParserConfig.production() // Production default config.
val debug = SafeParserConfig.debug() // Debug config with raw JSON capture enabled.
val lowInterference = SafeParserConfig.lowInterference() // Low-interference config closer to native Gson.
```

| Preset | Best for | Main behavior | Trade-off |
| --- | --- | --- | --- |
| `production()` | Default production traffic. | Observes events, hashes Map item keys, and does not attach full raw JSON. | Enough signals for operations with lower memory and privacy risk. |
| `debug()` | Integration testing and API troubleshooting. | Uses the production read policy but attaches bounded raw JSON. | Easier diagnosis, not recommended for long-term production use. |
| `lowInterference()` | Gradual rollout and low-interference adoption. | Whole-field, collection, and Map mismatches prefer `null`; primitives delegate to native Gson. | Closer to Gson, but fewer safe default values. |

## Annotations

```kotlin
@SafeParseDelegateToGson // Makes this type use native Gson directly.
class StrictModel

data class PageState(
    @field:SafeParseSkip // Tells Safe Reflective parsing to skip this field.
    val runtimeCache: Any? = null
)

data class FlexibleResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val data: User? = null,

    @field:SafeParseDisableShapeCoercion
    val signedPayload: SignedPayload = SignedPayload()
)
```

1. `@SafeParseDelegateToGson` is placed on a class to delegate that type to native Gson.
2. `@SafeParseSkip` is placed on a field to skip Safe Reflective read and write for that field.
3. `@SafeParseShapeCoercion` is placed on a field to allow the configured object-array coercion rule.
4. `@SafeParseDisableShapeCoercion` is placed on a field to keep original mismatch fallback even when the global policy is enabled.

## Demo App

The repository includes `demo-app` for testing the library on a real Android device:

```bash
./gradlew :demo-app:assembleDebug # Builds the debug Demo App.
./gradlew :demo-app:installDebug # Installs the debug Demo App on a connected device.
adb shell am start -n io.github.logan.gsonsafeparser.demo/.MainActivity # Starts the Demo App main screen.
```

The Demo App supports built-in cases and custom JSON input. You can paste a real API response and compare GsonSafeParser with native Gson, including parsed output, event stream, and integration suggestions.

## Documentation

Suggested reading order: start with [Getting Started](docs/en/getting-started.md), then read [Compatibility](docs/en/compatibility.md), [Configuration](docs/en/configuration.md), and the [Mismatch Capability Matrix](docs/en/mismatch-capability-matrix.md).

If you integrate into Android release builds, read [Android ProGuard](docs/en/android-proguard.md) next.

1. [Getting Started](docs/en/getting-started.md): installation, plain Gson usage, Retrofit integration, Kotlin APIs, and CI self-check.
2. [Mismatch Capability Matrix](docs/en/mismatch-capability-matrix.md): handling scope for objects, collections, maps, primitives, Kotlin defaults, Retrofit empty bodies, and raw JSON capture.
3. [Compatibility](docs/en/compatibility.md): Android, JDK, Kotlin, Gson, Retrofit, and R8 version boundaries.
4. [Configuration](docs/en/configuration.md): config fields, presets, event stream, annotations, and default behavior.
5. [Android ProGuard](docs/en/android-proguard.md): new project integration, legacy quick integration, R8 fullMode choice, and release validation.
6. [Demo App](docs/en/demo-app.md): device testing, screen overview, and custom JSON validation.
7. [Troubleshooting](docs/en/troubleshooting.md): empty responses, raw JSON, adapter creation failures, platform objects, and business schema issues.
8. [Release Checklist](docs/en/release-checklist.md): AAR, ProGuard, documentation version, and local Maven artifact checks before publishing 1.0.3.
9. [1.0.3 Release Notes](docs/en/release-notes-1.0.3.md): JSON shape coercion, event reporting, boundaries, and release verification notes.
10. [1.0.2 Release Notes](docs/en/release-notes-1.0.2.md): transport exception boundary fix, compatibility boundaries, and release verification notes.
11. [1.0.1 Release Notes](docs/en/release-notes-1.0.1.md): historical stabilization fixes, compatibility boundaries, and release verification notes.
12. [1.0.0 Release Notes](docs/en/release-notes-1.0.0.md): initial capabilities, compatibility boundaries, and release verification notes.

## Boundaries

GsonSafeParser is an enhancement layer for Gson, not a new JSON protocol interpreter.

It reduces parse failures and reports where a mismatch occurred, but it does not prove that the backend contract is correct.

Handling boundaries:

1. Field-level unexpected JSON shapes are handled by the library and recorded as events.
2. Problems that cannot be safely isolated are delegated to native Gson adapters or thrown outward.
3. Gson version differences, stripped runtime metadata, incomplete config, and Safe Adapter creation failures should not make the library a new crash source.
4. JSON syntax errors, root-level parse failures, and unsafe-to-isolate failures are not silently swallowed by `parseSafe`.

## Credits and Notice

GsonSafeParser is an independently maintained Kotlin open-source project.

The current codebase continues to evolve through maintainer-led, AI-assisted refactoring, with the final result reviewed, adjusted, and verified by the maintainer. The AI usage is disclosed separately below:

1. ChatGPT Codex: used for refactoring support, test hardening, documentation organization, and self-review.
2. DeepSeek DeepSeek-V4-Pro: used for refactoring assistance, documentation polishing, and issue cross-checking.

During design, problem-scenario review, README review, and issue self-checking, the project referred to the public [getActivity/GsonFactory](https://github.com/getActivity/GsonFactory) project. See [NOTICE](NOTICE) for license details, original copyright notice, and the fuller AI-transparency note.

## License

Apache License 2.0
