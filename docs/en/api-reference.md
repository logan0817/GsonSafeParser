# API Reference

[中文](../api-reference.md)

This page helps adopters choose the right GsonSafeParser entry point and understand what falls back, what delegates to Gson, and what is rethrown.

## 1. Entry Points

Choose by what you already own: from scratch, use `GsonSafeParser.create(config)`; with an existing `GsonBuilder`, use `GsonBuilder.enableSafeParser(config)` or `GsonSafeParser.parser(builder, config)`; with an already-created `Gson`, first confirm Safe Adapter registration and then use `parserWithExternalGson(gson, config)`; for Retrofit, use `GsonSafeConverterFactory.create(...)`.

| API | Use Case | Returns Events | Reuses Gson | Boundary |
| --- | --- | --- | --- | --- |
| `GsonSafeParser.create(config)` | Create a Gson instance with Safe Adapter registration | No | The returned Gson is reusable | Field-level fallback works; direct `gson.fromJson(...)` keeps Gson root exception behavior |
| `GsonBuilder.enableSafeParser(config)` | Existing project-owned `GsonBuilder` | No | Caller owns Builder / Gson | Registers Safe Adapter before `create()` and preserves Builder config where possible |
| `GsonSafeParser.parser(config)` | Repository, data source, or batch parsing | `parseSafe` returns events | Yes | Creates one Gson internally |
| `GsonSafeParser.parser(builder, config)` | Reuse Builder config and Parser | `parseSafe` returns events | Yes | Recommended when the app owns a shared GsonBuilder |
| `GsonSafeParser.parserWithExternalGson(gson, config)` | Wrap an already-created Gson | `parseSafe` returns events | Yes | Does not auto-register Safe Adapter |
| `GsonSafeParser.fromJson(json, type, config)` | Low-frequency parsing or tests | No | No | Creates Gson from config per call |
| `GsonSafeParser.fromJson(gson, json, type, config)` | Parse with caller-provided Gson | No | Uses the provided Gson | Field-level fallback depends on whether that Gson was created with Safe Adapter registration |
| `GsonSafeParser.fromJsonSafe<T>(json, config)` | Kotlin caller, parsed value only | No | No | Reified Kotlin API |
| `GsonSafeParser.parseSafe<T>(json, config)` | Kotlin caller, value plus event snapshot | Yes | No | Good for logging, monitoring, and contract reports |
| `GsonSafeConverterFactory.create(...)` | Retrofit response conversion | Observed through config callbacks | Depends on creation path | Handles JSON conversion, empty body policy, and raw JSON observation; network failures stay with Retrofit / OkHttp |

## 2. Minimal Usage

### 2.1 Plain Gson

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser

data class ApiResponse(
    val code: Int = 0,
    val data: User = User()
)

data class User(
    val id: Long = 0L,
    val name: String = ""
)

val json = """{"code":200,"data":[]}"""
val gson = GsonSafeParser.create()
val response = gson.fromJson(json, ApiResponse::class.java)
```

When `data` expects an object but receives an array, GsonSafeParser falls back for the current field and keeps the outer object readable.

### 2.2 Value Plus Events

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.contractReport
import io.github.logan.gsonsafeparser.parseSafe

val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""")

println(result.value)
println(result.events)
println(result.contractReport().toBackendMarkdown())
```

`value` is the parsed value after fallback, `events` is the event snapshot for this parse, and `contractReport()` formats those events for client, backend, or CI review.

### 2.3 Reusable Parser

```kotlin
val parser = GsonSafeParser.parser(SafeParserConfig.production())

val value = parser.fromJsonSafe<ApiResponse>(json)
val result = parser.parseSafe<ApiResponse>(json)
```

Parser is suitable for DI, repositories, and data sources.

### 2.4 Retrofit

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create())
    .build()
```

If the project already owns a `GsonBuilder`, prefer the builder-first entry:

```kotlin
val config = SafeParserConfig.production()
val gsonBuilder = GsonBuilder().serializeNulls()

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(gsonBuilder, config))
    .build()
```

## 3. Fallback, Delegation, And Rethrow Boundaries

| Scenario | Default Behavior | How To Read The Result |
| --- | --- | --- |
| Object field receives array, string, or number | Fallback for the current field; outer object continues parsing | `TypeMismatch` events include path, expected type, actual token, and fallback action |
| Collection field has the wrong top-level shape | `NullOnly` returns `null` or keeps the field default | A constructed default empty collection may make the final value look empty |
| One bad collection item | Skip that item and continue reading later items | Event kind is usually `LIST_ITEM` |
| Map has the wrong top-level shape | `NullOnly` returns `null` or keeps the field default | `FallbackPolicy.Default` is required for an actively created empty Map |
| One bad Map entry | Skip that entry and continue reading other entries | Map item keys are omitted by default |
| Primitive field mismatch | Delegates to Gson by default; read failures are thrown and do not emit SafeParser events | Safe primitive defaults and events apply only when `PrimitiveParsingPolicy.Safe` is enabled |
| Safe Adapter creation failure | Emits an event, then delegates to Gson | Prevents the extension layer from becoming a new crash source |
| Caller-registered `TypeAdapter`, `TypeAdapterFactory`, `registerTypeHierarchyAdapter(...)`, or `@JsonAdapter` matches | Uses the caller adapter first | Exceptions thrown by custom adapters are thrown outward, not disguised as field fallback |
| Class-level `@JsonAdapter` or `@SafeParseDelegateToGson` | Delegates to Gson | Use this for strict-contract or custom parsing types |
| JSON syntax error | Rethrown | Not converted into defaults |
| `Error`, `ThreadDeath`, `LinkageError`, `CancellationException` | Rethrown | VM, thread, class-loading, and cancellation signals are not swallowed |
| Retrofit offline, cancellation, connection reset, TLS failure | Stays with Retrofit / OkHttp handling | Not recorded as empty response or field mismatch |

## 4. API Details

### 4.1 `GsonSafeParser.create`

| Item | Description |
| --- | --- |
| Signature | `fun create(config: SafeParserConfig = SafeParserConfig()): Gson` |
| Purpose | Creates a Gson instance with Safe Adapter registration |
| Best for | Quick adoption without a custom `GsonBuilder` |
| Fallback | Handles field-level object, collection, Map, and object-array shape mismatch by config |
| Delegation | Safe Adapter creation failure, skipped types, Gson built-in types, and default primitives delegate to Gson |
| Risk | Direct `gson.fromJson(...)` keeps native Gson root-level exception wrapping |

### 4.2 `GsonBuilder.enableSafeParser`

| Item | Description |
| --- | --- |
| Signature | `fun GsonBuilder.enableSafeParser(config: SafeParserConfig = SafeParserConfig()): GsonBuilder` |
| Purpose | Registers Safe Adapter on an existing Builder |
| Best for | Projects with `serializeNulls()`, `registerTypeAdapter(...)`, `ReflectionAccessFilter`, or other Builder config |
| Fallback | Field-level Safe Adapter works after successful registration |
| Delegation | If critical GsonBuilder internals cannot be read, Safe Adapter registration is skipped and native Gson behavior is preserved |
| Verification | Use `GsonSafeParser.diagnostics()` |

### 4.3 `GsonSafeParser.parser`

| Item | Description |
| --- | --- |
| Signature | `parser(config)` or `parser(builder, config)` |
| Purpose | Creates a reusable Parser |
| Best for | High-frequency parsing, repositories, data sources, and batch jobs |
| Events | `parser.parseSafe(...)` returns the event snapshot for the current parse |
| Risk | Callbacks run synchronously on the parsing thread; caller-owned buffers or metric containers must be thread-safe |

### 4.4 `parserWithExternalGson`

| Item | Description |
| --- | --- |
| Signature | `parserWithExternalGson(gson, config)` |
| Purpose | Wraps an already-created Gson |
| Best for | Apps that already own a shared Gson singleton |
| Important boundary | It does not automatically register Safe Adapter |
| Correct setup | Call `.enableSafeParser(config)` on the same `GsonBuilder` before creating that Gson |
| Check | `GsonSafeParser.diagnostics(gson)` |

### 4.5 `parseSafe` And `fromJsonSafe`

| API | Return | Use Case |
| --- | --- | --- |
| `fromJsonSafe<T>(json)` | `T?` | Parsed value only |
| `parseSafe<T>(json)` | `SafeParseResult<T>` | Parsed value plus events and reports |
| `parser.fromJsonSafe<T>(json)` | `T?` | Reusable Parser, value only |
| `parser.parseSafe<T>(json)` | `SafeParseResult<T>` | Reusable Parser, value plus event snapshot |

## 5. Common Configuration

| Config | Default | Purpose |
| --- | --- | --- |
| `fallbackPolicy` | `NullOnly` | Fallback value strategy for field shape mismatch |
| `primitiveParsingPolicy` | `DelegateToGson` | Whether SafeParser handles primitive mismatch leniently |
| `emptyResponsePolicy` | `DefaultValueForUnitOrVoidOnly` | Retrofit empty body behavior |
| `useJdkUnsafe` | `false` | Whether SafeParser may use Unsafe object construction |
| `requiredConstructorParameterPolicy` | `GsonCompatible` | Kotlin required constructor parameter behavior |
| `mapItemKeyPolicy` | `Omit` | Whether Map item events expose keys |
| `captureRawJsonInCallbacks` | `false` | Whether mismatch events include raw JSON |
| `maxRawJsonCaptureBytes` | `1 MiB` | Raw JSON capture limit |

## 6. Observation And Reports

| Capability | API | Use |
| --- | --- | --- |
| Unified event stream | `SafeParserConfig(onEvent = { ... })` | Logs, metrics, and API drift observation |
| Type mismatch callback | `onTypeMismatch` | Compatibility callback for existing integrations that only observe field mismatch |
| Adapter creation failure callback | `onAdapterCreationFailure` | Observes which types delegated back to Gson |
| Observer failure callback | `onObserverFailure` | Prevents logging or analytics callback failures from breaking parsing |
| Contract report | `result.contractReport()` | Structured report for client, backend, or CI review |
| Environment diagnostics | `GsonSafeParser.diagnostics()` | Checks GsonBuilder internals, kotlin-reflect, and config risks |
| Integration self-check | `GsonSafeParser.integrationCheck()` | Verifies Safe Adapter, event, and report paths in CI |

## 7. Recommended Adoption Flow

1. Round 1: use `SafeParserConfig.debug()` in test builds and confirm events help diagnose API drift.
2. Round 2: configure business model keep rules for Android release builds with [Android ProGuard](android-proguard.md).
3. Round 3: run real JSON regression tests for key APIs.
4. Round 4: switch to `SafeParserConfig.production()` before release.
5. Round 5: use `SafeParserConfig.lowInterference()` for a conservative gray release if needed.
