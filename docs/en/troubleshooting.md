# Troubleshooting

[中文](../troubleshooting.md)

This document covers common integration issues in GsonSafeParser and the recommended handling.

## 1. Empty Retrofit Responses

An empty response means the body is actually empty. It does not include offline state, request cancellation, connection reset, or TLS failure.

Choose the policy by business semantics:

```kotlin
SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly) // Default: empty values only for Unit/Void.
SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DefaultValue) // Empty model responses return default objects.
SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.Null) // Empty response returns null directly.
SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DelegateToGson) // Empty response delegates to native Gson behavior.
```

| Policy | Empty model body | Empty `Unit` / `Void` body |
| --- | --- | --- |
| `DefaultValueForUnitOrVoidOnly` | Returns `null`. | Returns `Unit` / `null`. |
| `DefaultValue` | Returns a default object. | Returns `Unit` / `null`. |
| `Null` | Returns `null`. | Returns `null`. |
| `DelegateToGson` | Delegates to Gson and usually throws `EOFException`. | Returns `Unit` / `null`. |

Network failures do not use `emptyResponsePolicy`:

| Scenario | Handling |
| --- | --- |
| Offline state, request cancellation, connection reset, TLS failure | Returned to Retrofit / OkHttp error handling. |
| `EmptyResponse`, `RawJsonCaptureSkipped`, `TypeMismatch` | Not recorded for these failures. |
| App handling | Keep using the app's existing network error flow. |

## 2. Raw JSON Is Missing From Callbacks

Raw JSON capture is disabled by default to avoid extra memory cost for large responses. Enable it temporarily while troubleshooting:

```kotlin
SafeParserConfig( // Creates troubleshooting config.
    captureRawJsonInCallbacks = true, // Attaches raw JSON to callback events.
    maxRawJsonCaptureBytes = 1024 * 1024, // Limits raw JSON capture to 1 MiB.
    onTypeMismatch = { event -> // Receives field type mismatch events.
        println(event.rawJson) // Prints the raw JSON for this parse.
        println(event.rawJsonTruncated) // Prints whether raw JSON was truncated.
    } // Ends type mismatch callback.
) // Ends troubleshooting config.
```

Raw JSON capture depends on the scenario:

| Scenario | Result |
| --- | --- |
| Plain Gson parsing | Safely truncates by UTF-8 byte count without cutting Chinese text, emoji, or other multibyte characters. |
| Retrofit known-length body | Skips capture when `contentLength` exceeds the limit and records `skipReason=ContentLengthExceedsLimit`. |
| gzip, chunked, or missing `Content-Length` | Runs bounded probing first; captures when under the limit, otherwise records `skipReason=UnknownLengthExceedsLimit`. |

OkHttp may return `contentLength=-1` for gzip, chunked, or missing `Content-Length` responses.

## 3. Safe Adapter Creation Fails

When Safe Adapter creation fails, the library emits an event and then delegates back to native Gson adapters.

This is a fixed safety baseline and does not need an extra switch. It prevents the extension layer from becoming a new crash source during Adapter creation:

```kotlin
SafeParserConfig( // Creates config that observes Adapter creation failures.
    onAdapterCreationFailure = { event -> // Receives Safe Adapter creation failure events.
        println("${event.typeName}: ${event.reason}") // Prints failed type and reason.
    } // Ends Adapter creation failure callback.
) // Ends safe parsing config.
```

If the business wants to expose this strictly, collect events in tests and fail the test. Online traffic should keep the default "creation failure delegates back to Gson" baseline.

## 4. diagnostics Reports Unreadable GsonBuilder Fields

The default `GsonSafeParser.create(config)` entry does not read `GsonBuilder` internals. Only builder-first entries such as `.enableSafeParser(config)` and `GsonSafeConverterFactory.create(builder, config)` read internals to inherit caller-owned Builder configuration.

`diagnostics()` reports those fields separately:

| Result | Meaning | First check |
| --- | --- | --- |
| A `critical` field is unreadable | Builder-first entries cannot confirm reflection filters or the Unsafe switch, so they return to native Gson behavior. | Check whether AAR consumer ProGuard rules were merged, then confirm whether the Gson version was forced. |
| An `optional` field is unreadable | Field-level safe registration can continue, but the related Builder config inheritance degrades. | Check whether the app depends on that Builder setting; add version regression coverage if it does. |

If you do not need a custom `GsonBuilder`, prefer `GsonSafeParser.create(config)` or `GsonSafeConverterFactory.create(config)`.

## 5. Primitive Types Need SafeParser Lenient Parsing

Primitive types delegate to native Gson by default, staying close to Gson behavior. Enable SafeParser primitive parsing explicitly only when you need the previous lenient handling for string numbers, empty strings, and type mismatch defaults:

```kotlin
SafeParserConfig( // Creates config with lenient primitive parsing enabled.
    primitiveParsingPolicy = PrimitiveParsingPolicy.Safe // Uses SafeParser's local fallback for primitive mismatches.
) // Ends safe parsing config.
```

To keep low intervention, no extra config is required. You can also use:

```kotlin
SafeParserConfig.lowInterference() // Uses the low-interference preset.
```

## 6. Direct gson.fromJson And SafeParser Entries Report Different Exceptions

Use `parser.parseSafe(...)` or `parser.fromJson(...)` when you need SafeParser's top-level exception boundary. Direct `gson.fromJson(...)` keeps Gson's native top-level wrapping.

`GsonSafeParser.create()` returns a Gson instance with field-level Safe Adapters registered. Field-level unexpected JSON shapes are still isolated.

The top-level entry is still Gson, so Gson may wrap adapter-thrown `CancellationException` and similar failures in `JsonSyntaxException`.

Recommended usage:

```kotlin
val parser = GsonSafeParser.parser(config) // Reuses one Parser in high-frequency parsing paths.
val value = parser.fromJson<ApiResponse>(json, ApiResponse::class.java) // Parses through the SafeParser entry.
val result = parser.parseSafe<ApiResponse>(json) // Also returns the event snapshot.
```

If your project already owns a Gson instance, enable Safe Adapter first and then wrap it:

```kotlin
val gson = GsonBuilder()
    .enableSafeParser(config)
    .create()
val parser = GsonSafeParser.parserWithExternalGson(gson, config)
```

Keeping direct `gson.fromJson(...)` on Gson's native top-level exception wrapping avoids replacing Gson itself and avoids changing semantics that callers may already rely on.

## 7. Android Platform Objects

The default config skips `android.*` fields to avoid platform-object reflection risk. Do not add business model package prefixes here, or matching business fields will be skipped:

```kotlin
SafeParserConfig(skippedPlatformTypePrefixes = setOf("android.")) // Skips Android platform types to avoid reflecting system objects; do not add business model package prefixes here.
```

If you change it to an empty set, related fields behave closer to native Gson, but platform-class reflection failures are more likely. Business model packages should be protected through ProGuard keep rules, not through skipped prefixes.

## 8. A Business Field Has Multiple Shapes

If the same field is an object on success but a string or array on failure, GsonSafeParser can avoid parsing crashes, but it cannot infer business semantics automatically. Recommended options:

1. Store the raw field as `JsonElement`, then branch in business code.
2. Write a custom `TypeAdapter` for that field.
3. Adjust the API model and use a wrapper that explicitly represents success and failure shapes.

## 9. Passing Through Undeclared Fields

Fields not declared by the Bean are not injected automatically. Recommended options:

1. Keep a `JsonObject raw` on the shared response wrapper.
2. Log raw responses through an OkHttp interceptor.
3. Explicitly declare fields that need to be passed through in the business model.

## 9. Gson version compatibility

The current published config depends on Gson `2.13.2`. Safe Adapter setup reflects part of `GsonBuilder` internals so it can inherit caller-owned Gson options. After forcing a Gson downgrade or upgrade, run:

```kotlin
val diagnostics = GsonSafeParser.diagnostics(SafeParserConfig.production()) // Checks whether GsonBuilder internals are still readable.
val check = GsonSafeParser.integrationCheck(SafeParserConfig.production()) // Runs built-in parse probes.
```

If `GsonSafeParser.diagnostics(config).safeAdapterAvailable` is `false`, GsonBuilder internals are not readable.

In that case, `enableSafeParser()` conservatively avoids registering Safe Adapter and emits an Adapter creation failure event during actual creation.

If `GsonSafeParser.diagnostics(gson)` reports `externalGsonSafeAdapter` as `WARNING`, the external Gson does not contain field-level Safe Adapter. Later parsing keeps native Gson behavior, and diagnostics itself does not emit events or auto-register adapters.

If `integrationCheck().hasErrors` is `true`, do not ship that dependency combination yet.

## 10. Release And Debug Behave Differently

Check these first:

1. Whether Kotlin Metadata is kept.
2. Whether model constructors were obfuscated or shrunk.
3. Whether business model field names were renamed to `a`, `b`, `c` by R8. Legacy models without `@SerializedName` should first keep field names through package-level keep rules.
4. Whether the `kotlin-reflect` version matches the Kotlin plugin version.
5. Whether release builds enable more aggressive R8 rules.

If `GsonSafeModelProbe` is already integrated and reports `modelFieldObfuscationSuspected`, `modelConstructorUnavailable`, or `modelProbeFailure`, first add broad keep rules for real model packages from [Android ProGuard](android-proguard.md).

Then compare the same JSON in debug and release builds.

## 11. Kotlin data class defaults fail after an AGP upgrade

The typical symptom is that debug builds work, but release builds on AGP 8.6+ or R8 full mode no longer preserve data class default values.

Another common signal is `GsonSafeModelProbe` reporting `modelConstructorUnavailable`. This is usually not a JSON field-name problem by itself; R8 may have shrunk business model constructors, Kotlin Metadata, or constructor parameter information.

Use this order:

1. First confirm that business model packages use the `-keep class ... { <fields>; public <init>(...); }` rule from [Android ProGuard](android-proguard.md).
2. Then confirm the release build is not relying on `@SerializedName` alone. `@SerializedName` does not replace constructor and Metadata keep rules; it only fixes JSON field names.
3. If the project cannot inventory every model package immediately, start with broad keep rules for bean, model, entity, response, dto packages to stabilize release behavior.
4. If the project must keep `android.enableR8.fullMode=true`, add `GsonSafeModelProbe`, debug/release comparison, and release-variant tests to release gates.
5. If the project chooses `android.enableR8.fullMode=false`, still keep business model rules. This switch cannot restore fields or constructors after they are obfuscated or shrunk.

See [Android ProGuard](android-proguard.md) for detailed configuration.
