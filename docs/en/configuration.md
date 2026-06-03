# Configuration

[中文](../configuration.md)

GsonSafeParser configuration has a simple goal: handle fields that can be safely isolated, and delegate anything uncertain back to native Gson adapters.

This document covers config fields, constructor policy, presets, events, contract reports, and annotations. For the full unexpected-shape scope, see the [Mismatch Capability Matrix](mismatch-capability-matrix.md).

## 1. Base Config

```kotlin
val config = SafeParserConfig( // Creates a complete safe parsing config.
    fallbackPolicy = FallbackPolicy.NullOnly, // Returns null or keeps constructed defaults for mismatched fields.
    emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly, // Returns empty values only for Unit/Void Retrofit bodies.
    primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson, // Delegates primitive values to native Gson adapters.
    complexMapKeySerialization = false, // Keeps complex Map key array-entry writing disabled by default.
    useJdkUnsafe = false, // SafeParser itself does not bypass constructors with Unsafe by default.
    skippedPlatformTypePrefixes = setOf("android."), // Skips Android platform types to avoid reflecting system objects; do not add business model package prefixes here.
    nullValuePolicy = NullValuePolicy.WriteExplicitNulls, // Writes explicit JSON null only to nullable fields.
    requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.GsonCompatible, // Keeps Gson-compatible behavior for missing non-null Kotlin constructor parameters.
    mapItemKeyPolicy = MapItemKeyPolicy.Hash, // Emits stable hashed map item keys in events.
    captureRawJsonInCallbacks = false, // Does not attach raw JSON to callbacks by default.
    maxRawJsonCaptureBytes = 1024 * 1024 // Limits raw JSON capture to 1 MiB.
) // Ends safe parsing config.
```

Config fields:

| Config | Default | When to change it |
| --- | --- | --- |
| `fallbackPolicy` | `NullOnly` | Change to `Default` only when collection or Map shape mismatches should return empty values. |
| `emptyResponsePolicy` | `DefaultValueForUnitOrVoidOnly` | Change when Retrofit empty bodies should return default objects, `null`, or native Gson behavior. |
| `primitiveParsingPolicy` | `DelegateToGson` | Change to `Safe` only when primitive values need lenient fallback. |
| `complexMapKeySerialization` | `false` | Enable only when Gson complex Map key array-entry format is needed. |
| `useJdkUnsafe` | `false` | Applies only in `GsonCompatible` mode and controls whether SafeParser itself may use Unsafe construction; `Strict` forces it off. |
| `skippedPlatformTypePrefixes` | `setOf("android.")` | Use for platform types only. Do not add business model package prefixes here. |
| `nullValuePolicy` | `WriteExplicitNulls` | Change when explicit backend `null` needs a different nullable-field write policy. |
| `requiredConstructorParameterPolicy` | `GsonCompatible` | Keep the default for existing Gson projects; switch to `Strict` only when missing non-null Kotlin constructor parameters should fail fast. |
| `mapItemKeyPolicy` | `Hash` | Change in debug when raw Map keys are needed for diagnosis. |
| `captureRawJsonInCallbacks` | `false` | Enable temporarily for troubleshooting. |
| `maxRawJsonCaptureBytes` | `1 MiB` | Tune when raw JSON capture needs a smaller or larger bound. |

Raw JSON capture rules:

1. Plain Gson parsing truncates safely by UTF-8 byte count.
2. Retrofit known-length bodies use `contentLength`.
3. gzip, chunked, or unknown-length bodies use bounded probing for unknown-length gzip or chunked bodies and capture only when the body stays within the limit.

`instanceCreators`, `reflectionAccessFilters`, and `skippedPlatformTypePrefixes` are snapshotted when `SafeParserConfig` is created.

Mutating caller-owned collections later does not affect an existing config.

`enableSafeParser()` is idempotent for the same `GsonBuilder`; repeated calls do not register duplicate Safe Adapters. Create a new `GsonBuilder` if a different config is needed.

## 2. Constructor Policy And Unsafe

These two config fields answer one question: when an object has no safe construction path, may the library bypass constructors with Unsafe?

Short version:

| Config combination | May SafeParser itself use Unsafe? | May the Gson fallback path use Unsafe? | Best for |
| --- | --- | --- | --- |
| `GsonCompatible + useJdkUnsafe = false` | No. | Keeps native Gson behavior. | Recommended default. Good for most legacy migrations while reducing extra construction risk from SafeParser itself. |
| `GsonCompatible + useJdkUnsafe = true` | Yes. | Keeps native Gson behavior. | Use only when the project needs behavior closest to native Gson construction. |
| `Strict + useJdkUnsafe = false` | No. | No. | New APIs or strict contracts where missing fields, `null`, wrong shapes, or unknown enum values should fail early. |
| `Strict + useJdkUnsafe = true` | No. | No. | Avoid writing this combination. `Strict` has the highest priority, so `useJdkUnsafe = true` is ignored. |

`GsonCompatible` is the compatibility mode. Its goal is to avoid breaking projects that already depend on Gson's lenient behavior.

In this mode, `useJdkUnsafe` controls only SafeParser's own construction layer:

1. With `useJdkUnsafe = false`, SafeParser itself does not bypass constructors with Unsafe.
2. With `useJdkUnsafe = true`, if there is no usable constructor, default-value construction path, or `InstanceCreator`, SafeParser may create the object with Unsafe as the last fallback.
3. If SafeParser cannot handle the type safely, it still delegates back to native Gson adapters; that fallback path keeps Gson's own Unsafe setting.

`Strict` is the strict mode. Its goal is to treat missing required constructor parameters as API contract problems.

Once `Strict` is enabled, SafeParser closes both Unsafe paths:

1. SafeParser itself does not create objects with Unsafe.
2. The Gson delegate fallback path does not continue through Unsafe construction.
3. If `useJdkUnsafe = true` is passed together with `Strict`, `Strict` wins.

Recommended usage:

| Goal | Recommended config |
| --- | --- |
| Low-cost migration for an existing project, with minimal new parse failures | Keep the default: `GsonCompatible + useJdkUnsafe = false`. |
| Existing project clearly depends on Gson Unsafe construction and cannot update models immediately | Temporarily use `GsonCompatible + useJdkUnsafe = true`, then add business model keep rules and real JSON regression tests. |
| New API, strict contract, or early detection of backend missing fields | Use `Strict + useJdkUnsafe = false`. |

The risk of Unsafe is straightforward: it bypasses constructors and `init` code. The object may be created, but Kotlin defaults, non-null constraints, and constructor validation may not run. Because of that, `useJdkUnsafe = true` should be treated as a migration bridge for legacy behavior, not as the default for new projects.

## 3. Presets

```kotlin
val production = SafeParserConfig.production() // Creates the production default config.
val debug = SafeParserConfig.debug() // Creates an integration config with raw JSON capture enabled by default.
val lowInterference = SafeParserConfig.lowInterference() // Creates a conservative config closer to native Gson behavior.
```

Presets:

| Preset | Best for | Main behavior |
| --- | --- | --- |
| `production()` | Default production integration. | Contract-first reads, hashed Map item keys, event observation, and no full raw JSON in callbacks. |
| `debug()` | Integration testing and API troubleshooting. | Same read policy as production, bounded raw JSON capture, and plain-text Map item keys. |
| `lowInterference()` | Gradual rollout and low-interference adoption. | Whole-field, collection, and Map mismatches prefer `null`; primitives delegate to Gson; empty bodies return `null`. |

## 4. Layered Policies

```kotlin
val config = SafeParserConfig.fromPolicies( // Creates config from layered policies.
    readPolicy = SafeReadPolicy( // Configures JSON read behavior.
        fallbackPolicy = FallbackPolicy.NullOnly, // Returns null or keeps constructed defaults for mismatched fields.
        primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson, // Delegates primitive values to native Gson adapters.
        useJdkUnsafe = false // SafeParser itself does not bypass constructors with Unsafe.
    ), // Ends read policy.
    writePolicy = SafeWritePolicy( // Configures JSON write behavior.
        complexMapKeySerialization = false // Keeps complex Map key writing disabled by default.
    ), // Ends write policy.
    observerPolicy = SafeObserverPolicy( // Configures parse-event observation.
        onEvent = { event -> // Receives unified parse events.
            println(event) // Prints the event for logs or monitoring.
        } // Ends event callback.
    ) // Ends observer policy.
) // Ends layered config creation.
```

Layered policies are useful when a team wraps a shared config internally. They separate read behavior, write behavior, and observation so one constructor does not carry every concern.

## 5. Event Observation

```kotlin
val config = SafeParserConfig( // Creates safe parsing config with event callbacks.
    onEvent = { event -> // Receives unified parse events.
        println(event) // Prints the event object.
    }, // Ends unified event callback.
    onTypeMismatch = { event -> // Receives field type mismatch events.
        println("${event.path}: ${event.actualToken} -> ${event.expectedType}") // Prints path, actual token, and expected type.
    }, // Ends type mismatch callback.
    onAdapterCreationFailure = { event -> // Receives Safe Adapter creation failure events.
        println("${event.typeName}: ${event.reason}") // Prints failed type and reason.
    }, // Ends Adapter creation failure callback.
    onObserverFailure = { event -> // Receives failures thrown by business callbacks.
        println("${event.callbackName}: ${event.reason}") // Prints failed callback name and reason.
    } // Ends observer failure callback.
) // Ends event config.
```

1. `onEvent` is the unified event stream. It receives type mismatches, Adapter creation failures, empty responses, raw JSON capture skips, and other observable events.
2. `onTypeMismatch` is useful when the project only cares about field type mismatches.
3. `onAdapterCreationFailure` observes Safe Adapter creation failures. The default behavior still delegates back to native Gson adapters.
4. `onObserverFailure` observes exceptions thrown by business logging or analytics callbacks.

Ordinary exceptions thrown by callbacks do not interrupt parsing. GsonSafeParser isolates observer failures so logging-system issues do not affect API parsing.

Unsafe-to-isolate failures such as `Error`, `ThreadDeath`, `LinkageError`, and `CancellationException` are still thrown.

Event callbacks run synchronously on the parsing caller thread. If multiple threads share one Parser or Gson, caller-owned lists, log buffers, or metric collectors written by callbacks must be thread-safe.

`dispatchEvent` is a low-level event injection entry mainly used for cross-module bridging. Business code should usually not call it directly.

A manual call does not write into the current `parseSafe` event snapshot, and it does not mean a real parse happened.

## 6. Contract Report

```kotlin
val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""") // Parses JSON with an object-field mismatch.
val report = result.contractReport() // Converts parse events into a contract report.

if (report.hasIssues) { // Checks whether this parse found contract issues.
    println(report.toMarkdown()) // Prints the report in Markdown.
    println(report.toBackendMarkdown()) // Prints the backend-facing contract report for API fixes.
    println(report.summary.warningCount) // Prints warning count for CI or logging decisions.
    println(report.toStructuredRows().firstOrNull()?.stableKey) // Prints a stable fingerprint for online grouping.
    println(report.toStructuredRows().firstOrNull()?.fields?.get("captureSkipReason")) // Prints the raw JSON capture skip reason.
} // Ends issue report output.
```

The contract report only consumes events from the current parse. It does not parse JSON again and does not modify the parsed value. Use it for logs, CI reports, and API issue review.

It keeps field path, expected JSON shape, actual JSON shape, fallback action, client impact, backend fix suggestion, and `captureSkipReason`.

It does not print raw JSON bodies or Throwable objects.

Machine-side integrations should prefer `summary`, each issue's `stableKey`, and `toStructuredRows()` instead of parsing Markdown.

## 6. Observer Failure Report

```kotlin
val observerFailures = mutableListOf<ObserverFailureEvent>() // Creates a list for observer failure events.

val gson = GsonSafeParser.create( // Creates a Gson with observer failure collection.
    SafeParserConfig( // Creates safe parsing config.
        onObserverFailure = observerFailures::add // Adds observer failure events to the list.
    ) // Ends safe parsing config.
) // Ends Gson creation.

println(observerFailures.observerFailureReport().toMarkdown()) // Prints the redacted observer failure report.
```

The report redacts and summarizes failed callback names, source event types, field paths, and exception types. It does not directly output raw JSON or stack traces.

## 7. Annotations

```kotlin
@SafeParseDelegateToGson // Lets this type use native Gson Adapter directly.
class StrictModel // Defines a model that should stay strict under native Gson behavior.

data class PageState( // Defines a page model with runtime state.
    @field:SafeParseSkip // Tells Safe Reflective to skip this field.
    val runtimeCache: Any? = null // Stores runtime cache that should not be read from JSON.
) // Ends page model.
```

1. `@SafeParseDelegateToGson` is used on classes and makes that type use the native Gson Adapter directly.
2. `@SafeParseSkip` is used on fields and makes Safe Reflective skip that field. It is suitable for caches, runtime state, and platform objects.

## 8. Default Handling Summary

For the fuller scope covering objects, collections, maps, primitives, Kotlin defaults, `org.json`, Retrofit empty bodies, and raw JSON capture, see the [Mismatch Capability Matrix](mismatch-capability-matrix.md).

Out-of-the-box defaults:

| Config | Default |
| --- | --- |
| `fallbackPolicy` | `FallbackPolicy.NullOnly` |
| `primitiveParsingPolicy` | `PrimitiveParsingPolicy.DelegateToGson` |
| `emptyResponsePolicy` | `EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly` |
| `useJdkUnsafe` | `false` |
| `requiredConstructorParameterPolicy` | `RequiredConstructorParameterPolicy.GsonCompatible` |
| `mapItemKeyPolicy` | `MapItemKeyPolicy.Hash` |

Remember these defaults:

| Type | Default behavior |
| --- | --- |
| Object, collection, and Map field shape mismatch | Falls back for the current field and keeps the outer object parsing; `NullOnly` prefers `null` or constructed defaults. |
| Root object mismatch | Usually returns `null`; unrecoverable Gson exceptions are still thrown. |
| Missing non-null Kotlin constructor parameters | Keeps Gson-compatible behavior by default; reference fields stay `null`, and primitives keep JVM defaults. |
| Primitive shape mismatch | Delegates to native Gson adapters by default; `PrimitiveParsingPolicy.Safe` enables safe primitive values. |
| Empty Retrofit body | `Unit` returns `Unit`; `Void` and normal models return `null`. |
| Unsafe-to-isolate problems | JSON syntax errors, root failures, `Error`, `ThreadDeath`, `LinkageError`, and `CancellationException` are still thrown. |
