# Changelog

All notable changes are summarized here. Detailed bilingual release notes stay under `docs/`.

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
