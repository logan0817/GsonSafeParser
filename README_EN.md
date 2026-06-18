# GsonSafeParser

[中文](README.md)

[![Maven Central: core](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-core?label=core)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-core)
[![Maven Central: retrofit](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-retrofit?label=retrofit)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-retrofit)
[![CI](https://github.com/logan0817/GsonSafeParser/actions/workflows/ci.yml/badge.svg)](https://github.com/logan0817/GsonSafeParser/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

GsonSafeParser is a Kotlin-first Gson extension for Android projects. It is published as Android AARs.

It solves one common problem: when one backend field returns an unexpected JSON shape, native Gson may fail the whole bean.

GsonSafeParser tries to isolate that problem to the current field so the outer object can keep parsing.

It does not silently swallow bad payloads. The library records the field path, expected shape, actual shape, and fallback action.

Use that evidence to report contract issues and observe backend payload drift in production.

## 30-Second Setup

If you only want to verify field-level fallback, add the core dependency first:

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.4")
```

Then create Gson in business code:

```kotlin
val gson = GsonSafeParser.create()
val response = gson.fromJson(json, ApiResponse::class.java)
```

If you also need fallback events:

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.contractReport
import io.github.logan.gsonsafeparser.parseSafe

val result = GsonSafeParser.parseSafe<ApiResponse>(json)
println(result.value)
println(result.contractReport().toBackendMarkdown())
```

For Retrofit:

```kotlin
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.4")
```

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create())
    .build()
```

Recommended first read: 1. [Getting Started](docs/en/getting-started.md) 2. [API Reference](docs/en/api-reference.md) 3. [Mismatch Capability Matrix](docs/en/mismatch-capability-matrix.md) 4. [Android ProGuard](docs/en/android-proguard.md).

## Is This For You?

| Your Scenario | Fit | Recommended Entry |
| --- | --- | --- |
| Android Gson parsing fails because one field has the wrong JSON shape | Yes | `GsonSafeParser.create()` or `GsonBuilder.enableSafeParser()` |
| Retrofit responses sometimes contain field shape mismatches or empty bodies | Yes | `GsonSafeConverterFactory.create()` |
| You want to keep existing Gson config and only add field-level safe parsing | Yes | Call `.enableSafeParser(config)` on the same `GsonBuilder` |
| Pure JVM project without Android AAR consumption | Not yet | Current artifacts are Android AARs |
| You want JSON syntax errors, offline failures, or cancellations to become defaults | No | Those cases stay with Gson, Retrofit, or OkHttp |

## Features

1. Field-level fallback: object, collection, and map mismatches are isolated to the current field; primitive fields delegate to Gson by default and use safe primitive defaults only when `PrimitiveParsingPolicy.Safe` is enabled.
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
| `mapItemKeyPolicy` | `MapItemKeyPolicy.Omit` |

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
| Primitive / `String` field | `{}`, `[]` | Delegates to the native Gson adapter by default; read failures are thrown as native Gson exceptions and do not emit SafeParser events. |

`FallbackPolicy` (default: `FallbackPolicy.NullOnly`):

| Target type | Unexpected JSON | `FallbackPolicy.NullOnly` (default) | `FallbackPolicy.Default` |
| --- | --- | --- | --- |
| List / Set | `{}`, `""` | Returns `null`. | Returns an empty collection. |
| Map | `[]`, `""` | Returns `null`. | Returns an empty map. |

Note: fields with constructed defaults keep those defaults. Root values or fields without defaults still follow the table and return `null`.

`PrimitiveParsingPolicy` (default: `PrimitiveParsingPolicy.DelegateToGson`):

| Target type | Unexpected JSON | `PrimitiveParsingPolicy.DelegateToGson` (default) | `PrimitiveParsingPolicy.Safe` |
| --- | --- | --- | --- |
| Int / Long / Boolean / String | `{}`, `[]`, invalid strings | Delegates to the native Gson adapter and throws on failure. | Uses safe primitive defaults or keeps constructed defaults, and records events. |

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
implementation("io.github.logan0817:gson-safe-parser-core:1.0.4")
```

If you use Retrofit, depend on the retrofit module only; it already brings core transitively:

[![Maven Central: retrofit](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-retrofit?label=retrofit)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-retrofit)

```kotlin
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.4")
```

The Retrofit module keeps the `Retrofit 2.8.1` API baseline, and it also provides `OkHttp 4.12.0` and `Okio 3.6.0` as runtime network-stack safety baselines. This prevents Retrofit 2.8.1's old transitive dependencies from resolving back to OkHttp 3.14.x / Okio 1.x. If your app already owns the network stack, run `./gradlew dependencyInsight --dependency okhttp` and `./gradlew dependencyInsight --dependency okio` to confirm the final dependency resolution, then verify offline, cancellation, connection reset, TLS failure, and raw JSON capture regressions.

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
val gson = GsonSafeParser.create()
val response = gson.fromJson(json, ApiResponse::class.java)
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

SafeParser built-in adapters emit events and keep the outer object parsing when a field-level mismatch can be isolated. Types explicitly handled by `registerTypeAdapter(...)`, `registerTypeAdapterFactory(...)`, `registerTypeHierarchyAdapter(...)`, or `@JsonAdapter` keep the native Gson path first; exceptions thrown by those custom adapters are thrown outward instead of being disguised as field fallback.

Entry choice:

| What you need | Recommended entry |
| --- | --- |
| Field-level safe parsing only | `GsonSafeParser.create()` or `GsonBuilder.enableSafeParser()`. |
| Parsed value plus event snapshot | `GsonSafeParser.parseSafe<T>()` or `parser.parseSafe<T>()`. |
| Reuse one config frequently | `GsonSafeParser.parser(config)`. |
| Existing external Gson | Call `.enableSafeParser(config)` on the same `GsonBuilder` first, then use `parserWithExternalGson(gson, config)`. |
| Retrofit response conversion | `GsonSafeConverterFactory.create(...)`. |

The default `GsonSafeParser.create(config)` entry does not read `GsonBuilder` internals. Only builder-first entries read those internals, and only to inherit caller-provided `InstanceCreator`, `ReflectionAccessFilter`, object number strategy, complex Map key, and Unsafe settings. After forcing a different Gson version, run `GsonSafeParser.diagnostics()` first; it reports critical / optional compatibility per field.

Direct `gson.fromJson(...)` calls keep Gson's native top-level exception wrapping. This avoids replacing Gson itself or changing semantics callers may already depend on.

Kotlin convenience APIs:

```kotlin
val response = GsonSafeParser.fromJsonSafe<ApiResponse>(json)
val result = GsonSafeParser.parseSafe<ApiResponse>(json)

println(result.value)
println(result.events)
println(result.contractReport().toMarkdown())
println(result.contractReport().toBackendMarkdown())
println(result.contractReport().summary.warningCount)
println(result.contractReport().toStructuredRows().firstOrNull()?.stableKey)
```

`fromJsonSafe<T>()` returns the parsed value directly, while `parseSafe<T>()` also returns parse events for logs, metrics, and contract reports.

If you parse the same API shape repeatedly, create one reusable Parser first:

```kotlin
val parser = GsonSafeParser.parser(config)
val value = parser.fromJsonSafe<ApiResponse>(json)
val result = parser.parseSafe<ApiResponse>(json)
```

`parser(config)` creates one safe Parser and reuses the same Gson afterwards, which fits repositories, data sources, and batch parsing.

## JSON Shape Coercion

By default, GsonSafeParser does not convert objects and arrays into each other. JSON shape coercion stays in the `ShapeCoercionPolicy.Disabled` state by default, so 1.0.4 keeps the same default parsing behavior as previous releases.

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
    .addConverterFactory(GsonSafeConverterFactory.create())
    .build()
```

With custom empty response and observation policies:

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

`onEvent` receives the unified parse event stream and is the right place to connect logs, metrics, or contract reports.

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

This form reuses the existing Gson and keeps Retrofit-level empty response, raw JSON, and event policies from the same config.

Choose the entry by what you currently have:

| Current state | Recommended usage | Why |
| --- | --- | --- |
| You still have a `GsonBuilder` | `GsonSafeConverterFactory.create(builder, config)` | The factory registers Safe Adapters before `builder.create()`. |
| You already own a shared `Gson` | Call `.enableSafeParser(config)` on the `GsonBuilder` that creates it, then pass the final Gson to `create(gson, config)` | A created `Gson` has fixed configuration, and the library will not secretly mutate it. |
| You only call `create(gson, config)` | Reuses that Gson and applies Retrofit-level empty response, raw JSON, and event config | This does not automatically register Safe Adapter on the external Gson. |

Use `GsonSafeParser.diagnostics(gson)` if you are not sure whether an external Gson has field-level safe parsing enabled.

The default Retrofit `create(config)` entry also uses the lower-risk default path. Only `create(builder, config)` and `.enableSafeParser(config)` read Builder internals to inherit caller configuration.

## Common Configuration

The code below is copyable. The table after it explains what each option controls and when to change it.

```kotlin
val config = SafeParserConfig(
    fallbackPolicy = FallbackPolicy.NullOnly,
    emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly,
    primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson,
    skippedPlatformTypePrefixes = setOf("android."),
    nullValuePolicy = NullValuePolicy.WriteExplicitNulls,
    requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.GsonCompatible,
    mapItemKeyPolicy = MapItemKeyPolicy.Omit,
    captureRawJsonInCallbacks = false,
    maxRawJsonCaptureBytes = 1024 * 1024,
    onEvent = { event -> println(event) },
    onTypeMismatch = { event ->
        println("${event.path}: ${event.actualToken} -> ${event.expectedType}")
    }
)
```

Preset configs:

```kotlin
val production = SafeParserConfig.production()
val debug = SafeParserConfig.debug()
val lowInterference = SafeParserConfig.lowInterference()
```

| Preset | Best for | Main behavior | Trade-off |
| --- | --- | --- | --- |
| `production()` | Default production traffic. | Observes events, omits Map item keys by default, and does not attach full raw JSON. | Enough signals for operations with lower memory and privacy risk. |
| `debug()` | Integration testing and API troubleshooting. | Uses the production read policy but attaches bounded raw JSON. | Easier diagnosis, not recommended for long-term production use. |
| `lowInterference()` | Gradual rollout and low-interference adoption. | Whole-field, collection, and Map mismatches prefer `null`; primitives delegate to native Gson. | Closer to Gson, but fewer safe default values. |

## Annotations

```kotlin
@SafeParseDelegateToGson
class StrictModel

data class PageState(
    @field:SafeParseSkip
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
./gradlew :demo-app:assembleDebug
./gradlew :demo-app:installDebug
adb shell am start -n io.github.logan.gsonsafeparser.demo/.MainActivity
```

These commands build the debug Demo, install it on a connected device, and open the Demo main screen.

The Demo App supports built-in cases and custom JSON input. You can paste a real API response and compare GsonSafeParser with native Gson, including parsed output, event stream, and integration suggestions.

## Documentation

Read by scenario instead of reading every page from top to bottom.

| Scenario | Start Here | Then Read |
| --- | --- | --- |
| First adoption | [Getting Started](docs/en/getting-started.md) | [API Reference](docs/en/api-reference.md) |
| Understand fallback scope | [Mismatch Capability Matrix](docs/en/mismatch-capability-matrix.md) | [Configuration](docs/en/configuration.md) |
| Android release rollout | [Android ProGuard](docs/en/android-proguard.md) | [Compatibility](docs/en/compatibility.md) |
| Retrofit integration | Retrofit section in [Getting Started](docs/en/getting-started.md) | [Troubleshooting](docs/en/troubleshooting.md) |
| Device testing | [Demo App](docs/en/demo-app.md) | [Examples](examples/README_EN.md) |
| Contributing or reporting issues | [Contributing](CONTRIBUTING.md) | [Security Policy](SECURITY.md) |
| Release maintenance | [Release Checklist](docs/en/release-checklist.md) | [CHANGELOG](CHANGELOG.md) |

Full documentation index:

| Category | Documents |
| --- | --- |
| Start | [Getting Started](docs/en/getting-started.md), [API Reference](docs/en/api-reference.md), [Examples](examples/README_EN.md) |
| Reference | [Configuration](docs/en/configuration.md), [Mismatch Capability Matrix](docs/en/mismatch-capability-matrix.md), [Compatibility](docs/en/compatibility.md), [Troubleshooting](docs/en/troubleshooting.md) |
| Android | [Android ProGuard](docs/en/android-proguard.md), [Demo App](docs/en/demo-app.md) |
| Community | [Contributing](CONTRIBUTING.md), [Security Policy](SECURITY.md), [Code of Conduct](CODE_OF_CONDUCT.md) |
| Releases | [1.0.4 Release Notes](docs/en/release-notes-1.0.4.md), [1.0.3 Release Notes](docs/en/release-notes-1.0.3.md), [1.0.2 Release Notes](docs/en/release-notes-1.0.2.md), [1.0.1 Release Notes](docs/en/release-notes-1.0.1.md), [1.0.0 Release Notes](docs/en/release-notes-1.0.0.md) |

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

During design, problem-scenario review, README review, and issue self-checking, the project referred to the public [getActivity/GsonFactory](https://github.com/getActivity/GsonFactory) project. See [NOTICE](NOTICE) for license details and the original copyright notice.

## License

Apache License 2.0
