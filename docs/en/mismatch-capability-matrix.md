# Mismatch Capability Matrix

[中文](../mismatch-capability-matrix.md)

This matrix answers one question: when the backend JSON shape does not match the Android model, what does GsonSafeParser preserve, what does it report, and what is still delegated to Gson or the caller.

## 1. Principles

1. Field-level problems affect only the current field when the outer object can still continue parsing.
2. Problems that cannot be isolated to a field are delegated back to native Gson adapters or thrown outward; syntax errors and unsafe-to-isolate failures such as `Error`, `ThreadDeath`, `LinkageError`, and `CancellationException` are not disguised as business defaults.
3. Observable mismatches enter `SafeParserEvent`, and `parseSafe<T>()` can turn them into `contractReport()` with JSON path, expected shape, actual shape, fallback action, client impact, and backend fix suggestion.

## 2. Default Capability Matrix

Out-of-the-box defaults and optional capability states:

| Item | Meaning |
| --- | --- |
| `fallbackPolicy = FallbackPolicy.NullOnly` | Unexpected field shapes prefer `null` or constructed defaults. |
| `primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson` | Primitive values delegate to native Gson adapters by default. |
| `emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly` | Empty Retrofit bodies return empty values only for `Unit` / `Void`. |
| `useJdkUnsafe = false` | In compatible mode, SafeParser itself does not use JDK Unsafe for object construction by default; after `Strict` is enabled, Unsafe is disabled for both SafeParser and the Gson fallback path. |
| `requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.GsonCompatible` | Missing non-null Kotlin constructor parameters keep Gson-compatible behavior; reference fields stay `null`, and primitives keep JVM defaults. |
| `mapItemKeyPolicy = MapItemKeyPolicy.Hash` | Map item events emit stable hashes by default. |
| JSON shape coercion | Disabled by default, with state `ShapeCoercionPolicy.Disabled`; enabled only by calling `withShapeCoercionPolicy(...)` or using a field annotation. |

The "Default handling" rows below describe these default states. Empty collections, empty maps, primitive safe values, and object-array shape coercion are used only when callers explicitly choose `FallbackPolicy.Default`, `PrimitiveParsingPolicy.Safe`, or `ShapeCoercionPolicy.*`.

Overview:

| Type | Default handling | Main boundary |
| --- | --- | --- |
| Object field | Falls back for the current field and keeps the outer object parsing. | Root object mismatches cannot always be isolated at field level. |
| Collection field | Whole-field mismatch returns `null` or keeps the constructed default. | One bad item inside the collection is skipped. |
| Map field | Whole-field mismatch returns `null` or keeps the constructed default. | `[]` may be valid Gson complex Map key array-entry input. |
| Object-array shape coercion | Disabled by default. | Enabled only for explicit field-level object-array drift recovery. |
| Primitive value | Delegates to native Gson adapters by default. | Safe primitive values require `PrimitiveParsingPolicy.Safe`. |
| Kotlin defaults | Missing fields and recoverable mismatches keep defaults when possible. | Non-null required parameters without defaults use Gson-compatible behavior by default; fail-fast requires explicit config. |
| Empty Retrofit body | `Unit` returns `Unit`; common other targets return `null`. | HTTP status and business error codes are outside this library. |

### 2.1 `data: User`
<!-- capability-id: object-field-mismatch -->

1. Backend returns: `[]`, `""`, `1`.
2. Default handling: reads the field value as `null`; reflective field reading does not overwrite an already constructed field default with `null`.
3. Evidence: `TypeMismatch`, `path=$.data`, `expectedJsonShape=JSON object`, `actualJsonShape=JSON array/string/number`.
4. Boundary: root object mismatch usually returns `null`; unrecoverable exceptions are still thrown.

### 2.2 `List<User>` / `Set<User>`
<!-- capability-id: collection-field-mismatch -->

1. Backend returns: `{}`, `""`, `false`.
2. Default handling: whole-collection shape mismatches return `null`; reflective field reading does not overwrite an already constructed field default with `null`.
3. Evidence: `TypeMismatch`, field path, and fallback action for the collection field.
4. Boundary: one bad collection item is skipped without failing the whole collection.

### 2.3 Explicit JSON Shape Coercion
<!-- capability-id: shape-coercion -->

1. Default state: `ShapeCoercionPolicy.Disabled`; objects and arrays are not converted, preserving previous default behavior.
2. Object field: when `data: User` receives `"data":[{"id":1}]`, explicit `ObjectFromFirstArrayItem` reads the first object from the array.
3. Collection field: when `users: List<User>` or `users: Set<User>` receives `"users":{"id":1}`, explicit `CollectionFromSingleObject` wraps it as a one-item collection.
4. Object-array field: when `users: Array<User>` receives `"users":{"id":1}`, explicit `CollectionFromSingleObject` wraps it as a length-1 array.
5. Evidence: `ShapeCoercion`, with `path`, `fieldName`, `shapeCoercionAction`, and `discardedItemCount` in reports.
6. Boundary: root objects, root collections, root object arrays, maps, numbers, booleans, and strings are not coerced, and string content is not parsed again as JSON.
7. Failure handling: empty arrays, a non-object first array item, or adapter failures during coercion emit `ShapeCoercion` events and return to the original fallback behavior.
8. Unsafe-to-isolate failures: `Error`, `ThreadDeath`, `LinkageError`, `CancellationException`, and real transport I/O still escape instead of becoming coercion failure events.

### 2.4 `Map<String, User>`
<!-- capability-id: map-field-mismatch -->

1. Backend returns: `""`, `false`, or bad key/value inside array-entry form.
2. Default handling: whole-Map shape mismatches return `null`; reflective field reading does not overwrite an already constructed field default with `null`. Individual bad entries inside a Map are skipped.
3. Evidence: `TypeMismatch`, field path, and a `sha256:` hashed `mapItemKey` for map item mismatches by default.
4. Boundary: `[]` can be read as Gson's complex-map-key array-entry form; an empty array becomes an empty map and may not emit a mismatch event.

### 2.5 `Int` / `Long` / `Short` / `Byte`
<!-- capability-id: integer-field-mismatch -->

1. Backend returns: `{}`, `[]`, invalid string, out-of-range number, or fractional number for integer fields.
2. Default handling: field values delegate to native Gson adapters, and read failures keep the field default; root primitive values follow native Gson behavior.
3. Evidence: `TypeMismatch`, field name, path, range reason, or rounding reason.
4. Boundary: safe primitive values are used only with `PrimitiveParsingPolicy.Safe`.

### 2.6 `BigDecimal` / `BigInteger`
<!-- capability-id: big-number-mismatch -->

1. Backend returns: `{}`, `[]`, invalid string, or fractional number for `BigInteger`.
2. Default handling: field values delegate to native Gson adapters, and read failures keep the field default; root numeric values follow native Gson behavior.
3. Evidence: `TypeMismatch`, with a reason explaining why the value cannot be read as the target type.
4. Boundary: safe numeric defaults are used only with `PrimitiveParsingPolicy.Safe`; valid large integers keep exact precision and are not truncated.

### 2.7 `Boolean`
<!-- capability-id: boolean-field-mismatch -->

1. Backend returns: `{}`, `[]`, invalid string.
2. Default handling: field values delegate to native Gson adapters, and read failures keep the field default; root boolean values follow native Gson behavior.
3. Evidence: `TypeMismatch`, field path, and actual token.
4. Boundary: safe boolean values are used only with `PrimitiveParsingPolicy.Safe`; normal `"true"` / `"false"` values keep Gson-compatible parsing.

### 2.8 `String`
<!-- capability-id: string-field-mismatch -->

1. Backend returns: `{}`, `[]`.
2. Default handling: field values delegate to native Gson adapters, and read failures keep the field default; root string values follow native Gson behavior.
3. Evidence: `TypeMismatch`, `expectedJsonShape=JSON string`.
4. Boundary: number-to-string conversion remains Gson compatible.

### 2.9 Kotlin data class defaults
<!-- capability-id: kotlin-defaults -->

1. Backend returns: missing field, field `null`, or unexpected field shape.
2. Default handling: missing fields keep constructed defaults; explicit `null` is written only to nullable fields; field shape mismatches keep constructed defaults after read failures.
3. Evidence: mismatch events and parsed value comparison can confirm this behavior.
4. Boundary: A non-null constructor parameter without a default uses Gson-compatible handling by default; missing reference fields stay `null`, and primitives keep JVM defaults.
5. Boundary: switch `requiredConstructorParameterPolicy` to `Strict` when missing fields, `null`, wrong shapes, or unknown enum values should be treated as API contract errors.
6. Boundary: strict mode throws `JsonIOException` and also prevents the Gson delegate from continuing through Unsafe construction; if `useJdkUnsafe = true` is passed together with `Strict`, `Strict` wins.

### 2.10 `JSONObject` / `JSONArray`
<!-- capability-id: org-json-mismatch -->

1. Backend returns: correct object or array shape.
2. Default handling: reads through dedicated `org.json` adapters.
3. Evidence: normal parsing does not emit mismatch events; `JSONObject` receiving an array or `JSONArray` receiving an object returns `null` and emits `TypeMismatch`, with field-level paths for model fields and `path=$` for root mismatches.
4. Boundary: the `org.json` bridge first reads into `JsonElement` and then converts through `JSONObject` / `JSONArray`, so large fields pay one extra string-conversion cost.

### 2.11 Retrofit empty body
<!-- capability-id: retrofit-empty-response -->

1. Backend returns: empty response body.
2. Default handling: `Unit` returns `Unit`, while `Void` and normal model responses return `null`; default objects or Gson delegation require an explicit `EmptyResponsePolicy` change.
3. Evidence: `EmptyResponse`, response type, and empty response policy.
4. Boundary: HTTP status and business error codes are outside this library.

### 2.12 Retrofit raw JSON capture
<!-- capability-id: retrofit-raw-json-capture -->

1. Backend returns: a body that is useful for diagnostics, or an explicitly enabled capture path sees an oversized or unknown-length body.
2. Default handling: does not capture raw JSON by default and does not emit `RawJsonCaptureSkipped`; the response continues through the normal converter path.
3. Evidence: After `captureRawJsonInCallbacks` is enabled explicitly, oversized bodies emit `RawJsonCaptureSkipped` with content length, max bytes, `skipReason`, and `captureSkipReason`.
4. Boundary: reports do not print the raw body by default; mismatch events carry bounded raw JSON only in debug config or after capture is enabled manually.

## 3. What Contract Reports Give Backend Owners

```kotlin
val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""")
println(result.contractReport().toBackendMarkdown())
```

The report turns "Android parsing failed" into a contract issue: `$.data` expected a JSON object but received a JSON array; GsonSafeParser skipped that field and preserved the outer object; the backend should return an object at `$.data` instead of an array.

## 4. Rollout Strategy

1. Use `SafeParserConfig.debug()` during integration to keep limited raw JSON and quickly locate backend payload drift.
2. Use `SafeParserConfig.lowInterference()` during rollout to observe events with smaller behavior changes.
3. Use `SafeParserConfig.production()` in production to disable raw JSON bodies and report only structured event and contract fields.

## 5. Out of Scope

1. JSON syntax errors are not field mismatches and are still thrown.
2. Root-level parse failures cannot always be isolated to a field and still follow Gson boundaries.
3. Business protocol errors, HTTP errors, signature failures, and semantic field errors are not judged by this library.
4. Ordinary exceptions thrown by custom adapters become field-level events only when the read boundary can isolate them to the current field; unsafe-to-isolate failures such as `Error`, `ThreadDeath`, `LinkageError`, and `CancellationException` are still thrown.
