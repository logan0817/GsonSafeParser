# 1.0.3 Release Notes

[中文](../release-notes-1.0.3.md)

`1.0.3` adds JSON shape coercion.

This feature handles backend responses where an object and an array occasionally drift into each other. It is not a default fallback and does not change previous default behavior.

The default policy remains `ShapeCoercionPolicy.Disabled`. Coercion runs only when callers use `withShapeCoercionPolicy(...)` or annotate a field.

## Changes

1. Adds `ShapeCoercionPolicy` for ordinary object fields returned as arrays.
2. Adds collection and object-array recovery from a single returned object.
3. Adds `@SafeParseShapeCoercion` for enabling a specific coercion policy on one field.
4. Adds `@SafeParseDisableShapeCoercion` for keeping one field on the original fallback behavior even when global coercion is enabled.
5. Adds `ShapeCoercion` events and reports `shapeCoercionAction`, field path, and discarded item count in contract reports and observer failure reports.
6. Root objects, root collections, root object arrays, maps, string re-parsing, numbers, booleans, transport failures, and fatal failures are not coerced.
7. Adds the CI OSV dependency vulnerability scan gate and pins the scanner action to a concrete valid version.
8. Keeps the Retrofit module on the `Retrofit 2.8.1` API while publishing the `OkHttp 4.12.0` and `Okio 3.6.0` network-stack safety baseline.
9. Redacts Maven Central deployment failure responses before logging and redacts Demo clipboard reports before copying them.
10. Adds the `maxRawJsonCaptureBytesTooLarge` diagnostic for unsafe raw JSON capture limits.

## Usage

Enable object and collection coercion globally:

```kotlin
val config = SafeParserConfig()
    .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
```

Enable object-from-array recovery for one field:

```kotlin
data class ApiResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val user: User?
)
```

Disable coercion for a high-risk field after global coercion is enabled:

```kotlin
data class ApiResponse(
    @field:SafeParseDisableShapeCoercion
    val error: ErrorBody?
)
```

## Behavior Boundaries

1. An object field receiving `[{"id":1}]` can read the first object as the field value.
2. An object field receiving `[]` does not create a fake object and returns to the original fallback behavior.
3. An object field receiving `[1]`, `["x"]`, or `[true]` does not force a scalar into an object. It emits `ShapeCoercion` and returns to the original fallback behavior.
4. A collection field receiving `{"id":1}` can read it as a collection with one item.
5. An object-array field receiving `{"id":1}` can read it as an array with one item.
6. Map fields are not coerced, so business dictionaries are not mistaken for lists.
7. Root objects, root collections, and root object arrays are not coerced, so the overall `Gson.fromJson(...)` failure semantics stay intact.

## Compatibility Boundaries

1. The feature is disabled by default. Without `withShapeCoercionPolicy(...)`, previous parsing results should not change.
2. Published artifacts remain Android AARs.
3. The verified matrix remains `minSdk 23`, `compileSdk 36`, `JDK 17`, `Kotlin 2.0.21`, `kotlin-reflect 2.0.21`, and `Gson 2.13.2`.
4. The Retrofit module is still verified with `Retrofit 2.8.1`, and it explicitly publishes `OkHttp 4.12.0` and `Okio 3.6.0` to prevent dependency resolution from falling back to Retrofit 2.8.1's old OkHttp / Okio transitive baseline.
5. Release builds with R8 / ProGuard still need business model field names, constructors, and Kotlin Metadata keep rules.
6. If your app already owns OkHttp or Okio, run `./gradlew dependencyInsight --dependency okhttp` and `./gradlew dependencyInsight --dependency okio` before publishing, then verify offline, cancellation, connection reset, TLS failure, and raw JSON capture regressions.

## Release Verification

Before publishing, this release should be checked with:

1. shape coercion disabled by default, enabled globally, enabled by field annotation, and disabled by field annotation.
2. object field recovery from the first array item, empty-array fallback, non-object first-item fallback, and discarded extra items.
3. List, Set, and object-array wrapping from a single object.
4. root objects, root collections, root object arrays, maps, primitive types, and string re-parsing remaining uncoerced.
5. `ShapeCoercion` events, contract reports, and observer failure reports.
6. `ThreadDeath`, `LinkageError`, `CancellationException`, and real transport I/O not being swallowed.
7. Retrofit builder-first entry using field-level Safe Adapters, while the plain-Gson entry does not secretly register field-level coercion.
8. core, retrofit, and demo debug unit tests.
9. demo release unit tests.
10. core, retrofit, and demo release lint.
11. demo debug and release APK builds.
12. `publishToMavenLocal`.
13. Maven local AAR, POM, sources, Dokka javadoc, and consumer ProGuard rule verification.
14. retrofit POM dependency checks for `okhttp 4.12.0` and `okio 3.6.0`.
15. OSV dependency vulnerability scan.
16. Maven Central deployment response redaction and Demo clipboard report redaction.
17. `maxRawJsonCaptureBytesTooLarge` diagnostics.
18. `releaseToMavenCentral --dry-run`.
19. `git diff --check`.
