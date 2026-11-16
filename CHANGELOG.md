# Changelog

All notable changes are summarized here. Detailed bilingual release notes stay under `docs/`.

## Unreleased

### 1.0.5 release candidate

The current source and local artifacts target **1.0.5**, which has not been published to Maven Central. Public installation examples continue to use **1.0.4** until publication is confirmed.

1. Preserves field-level `@JsonAdapter` serialization when a value has a scalar or custom runtime adapter, keeping masking and protocol conversion intact.
2. Honors explicitly included `transient` fields through the caller's Gson exclusion rules. Static fields remain excluded by SafeParser.
3. Closes Retrofit response bodies when probing, observation, or conversion fails, including Unit/Void responses.
4. Keeps Retrofit raw JSON capture on the official converter path, preserving configured strictness, full-consumption checks, charset, and BOM handling.
5. Adds an opt-in low-level `withRawJsonContext` bridge for third-party converters; context remains bounded and is restored after nested calls or failures.
6. Fixes safe Boolean-to-String conversion and preserves the actual numeric token in root mismatch events.
7. Skips blank numeric Map keys instead of turning them into `0` or `null`, for both object and array-entry Map encodings.
8. Applies Gson 2.13.2's number length and scale limits to safe decimal/integer/Boolean conversion and the default dynamic-number strategy, avoiding excessive BigInteger expansion.
9. Rejects malformed escapes and truncated documents instead of swallowing syntax errors or repeatedly retrying an unreadable array element.
10. Preserves following fields after a consumed invalid character; isolates rejected collection elements and bad object/primitive array elements without discarding valid neighbors.
11. Honors explicit-null reads by caller adapters, and preserves runtime subtype fields when only a deserializer is registered or values are stored in arrays.
12. Applies Map key privacy policies to nested paths, inferred field names and shape-coercion events, including numeric-key failure reasons.
13. Isolates Retrofit raw JSON contexts when capture is disabled or skipped, and tests actual service calls rather than only invoking converters directly.
14. Clarifies that Retrofit's built-in Unit/Void conversion and HTTP 204/205 handling take precedence over SafeParser empty-response policies.
15. Isolates raw JSON, Map paths, and event collectors while invoking observers, including observer-failure callbacks; independent nested parses no longer leak outer bodies or pollute outer result snapshots.
16. Skips malformed array-entry Map keys without manufacturing null keys, and isolates container insertion failures separately from caller-adapter read failures.
17. Probes actual response bytes even when a Retrofit body declares zero length, preserving content supplied by interceptors and propagating read failures.
18. Verifies Maven local artifacts against actual publication coordinates and the configured local repository; regenerates OSV scan inputs on every invocation to avoid stale dependency versions.
19. Updates the bilingual release checklist to separate JVM tests, minified Android execution, independent AAR consumption, and unpublished version preparation.

Candidate notes: [中文](docs/release-notes-1.0.5.md) / [English](docs/en/release-notes-1.0.5.md)

## 1.0.4

Custom adapter boundary and open-source release readiness update.

1. Preserves the native Gson path for caller-owned `TypeAdapter`, `TypeAdapterFactory`, `registerTypeHierarchyAdapter(...)`, and `@JsonAdapter`; exceptions from those custom adapters now propagate instead of being reported as SafeParser field fallback.
2. Keeps Gson's last-registered-wins adapter ordering when SafeParser inspects caller-provided factories.
3. Keeps primitive and `String` mismatches delegated to native Gson by default; safe primitive fallback stays opt-in through `PrimitiveParsingPolicy.Safe`.
4. Keeps duplicate Map key handling aligned with native Gson, including the `duplicate key` exception boundary and the Gson 2.13.2 previous-`null` overwrite case.
5. Expands boundary tests for reflective objects, collections, maps, arrays, nested adapters, primitive policies, and Retrofit converter behavior.
6. Adds Gson internal adapter keepnames to core and retrofit AAR consumer ProGuard rules and verifies those rules in Maven local artifacts and demo release merging.
7. Improves README, configuration docs, API reference, examples, release checklist, and open-source collaboration files for public framework publication.
8. Normalizes documentation contract tests across Windows CRLF and Unix LF line endings.
9. Aligns Gradle publishing version, Demo version, dependency snippets, compatibility docs, release checklist, and release notes with `1.0.4`.

Details: [中文](docs/release-notes-1.0.4.md) / [English](docs/en/release-notes-1.0.4.md)

## 1.0.3

Explicit JSON shape coercion for field-level object and collection drift.

1. Adds opt-in `ShapeCoercionPolicy` for object fields returned as arrays and collection or object-array fields returned as single objects.
2. Adds field annotations `@SafeParseShapeCoercion` and `@SafeParseDisableShapeCoercion`.
3. Emits `ShapeCoercion` events and includes them in contract reports and observer failure reports.
4. Keeps shape coercion disabled by default and limits it to field-level reads; root objects, root collections, maps, scalar JSON, fatal exceptions, and transport I/O boundaries remain unchanged.
5. Adds the CI OSV dependency vulnerability scan gate and pins the scanner action to a concrete tag.
6. Publishes the Retrofit module with `OkHttp 4.12.0` and `Okio 3.6.0` as the documented network-stack safety baseline.
7. Redacts Maven Central deployment failure responses before logging and redacts Demo clipboard reports before copying them.
8. Adds the `maxRawJsonCaptureBytesTooLarge` diagnostic for unsafe raw JSON capture limits.
9. Redacts SafeParser event `reason` values before observer callbacks or `parseSafe` snapshots can log sensitive tokens.
10. Tightens the default `mapItemKeyPolicy` to `Omit`; use `MapItemKeyPolicy.Hash` explicitly when stable Map item aggregation is required.
11. Adds the `rawJsonCaptureEnabled` diagnostic warning whenever raw JSON capture is enabled.
12. Aligns Gradle publishing version, Demo version, dependency snippets, compatibility docs, release checklist, and release notes with `1.0.3`.

Details: [中文](docs/release-notes-1.0.3.md) / [English](docs/en/release-notes-1.0.3.md)

## 1.0.2

Transport exception boundary fix for Retrofit and safe adapter recovery.

1. Treats network and transport read failures such as `InterruptedIOException`, socket reset, broken pipe, and OkHttp stream reset as unsafe to isolate.
2. Keeps ordinary custom adapter `IOException` recoverable when it is safely scoped to one field, list item, or map entry.
3. Prevents Retrofit empty-response probing and raw JSON probing from emitting misleading SafeParser events during transport failures.
4. Aligns Gradle publishing version, Demo version, dependency snippets, compatibility docs, release checklist, and release notes with `1.0.2`.

Details: [中文](docs/release-notes-1.0.2.md) / [English](docs/en/release-notes-1.0.2.md)

## 1.0.1

Stabilization release for production adoption.

1. Aligns Gradle publishing version, Demo version, dependency snippets, compatibility docs, release checklist, and release notes with `1.0.1`.
2. Keeps the default constructor policy Gson-compatible so existing projects can migrate without fixing every missing non-null Kotlin field at once.
3. Documents and tests that `RequiredConstructorParameterPolicy.Strict` has priority over `useJdkUnsafe`.
4. Keeps `1.0.0` as the first public API compatibility baseline.

Details: [中文](docs/release-notes-1.0.1.md) / [English](docs/en/release-notes-1.0.1.md)

## 1.0.0

First public release for production adoption.

1. Provides field-level safe parsing for recoverable Gson shape mismatches while preserving native Gson fallback boundaries.
2. Supports Kotlin data class defaults, `parseSafe<T>()`, `fromJsonSafe<T>()`, diagnostics, integration checks, and contract reports.
3. Provides Retrofit integration for empty bodies, bounded raw JSON capture, and event dispatch.
4. Ships Android AAR artifacts with consumer ProGuard rules, sources, Dokka javadoc, LICENSE, and NOTICE.
5. Includes release checks for boxed Boolean dispatch, escaped Map keys, fatal exception boundaries, raw JSON limits, R8 / ProGuard, and Maven artifacts.

Details: [中文](docs/release-notes-1.0.0.md) / [English](docs/en/release-notes-1.0.0.md)
