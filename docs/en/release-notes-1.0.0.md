# 1.0.0 Release Notes

[中文](../release-notes-1.0.0.md)

`1.0.0` is the first public release of GsonSafeParser.

The positioning is strict: GsonSafeParser is an extension layer for Gson, not a new JSON protocol interpreter. Recoverable field-level JSON shape mismatches are isolated by the library; problems that cannot be safely isolated are delegated back to native Gson adapters or thrown outward.

## Initial Capabilities

1. Field-level safe parsing: objects, collections, maps, Kotlin data class defaults, and `org.json` types handle recoverable mismatches according to config; primitive values use safe primitive defaults only when `PrimitiveParsingPolicy.Safe` is explicitly enabled.
2. Gson fallback boundaries: Safe Adapter creation failures, missing GsonBuilder compatibility snapshots, uncertain types, and unrecoverable failures fall back to Gson or keep throwing.
3. Kotlin APIs: `fromJsonSafe<T>()`, `parseSafe<T>()`, event snapshots, and reusable Parser.
4. Retrofit integration: `GsonSafeConverterFactory` supports empty-body policies, bounded raw JSON capture, and oversized-body skip events.
5. Integration self-checks: `diagnostics()`, `explainType()`, `integrationCheck()`, and `GsonSafeModelProbe` help CI and release builds validate integration.
6. Contract reports: `contractReport()`, `toBackendMarkdown()`, and `toStructuredRows()` convert parse events into backend-readable issue reports.

## Compatibility Boundaries

1. Published artifacts are Android AARs.
2. The current verified matrix is `minSdk 23`, `compileSdk 36`, `JDK 17`, `Kotlin 2.0.21`, `kotlin-reflect 2.0.21`, and `Gson 2.13.2`.
3. The Retrofit module is currently verified with `Retrofit 2.8.1`.
4. Release builds with R8 / ProGuard still need business model field names, constructors, and Kotlin Metadata keep rules.
5. If a business project forces older Gson, Kotlin, Retrofit, or goes below `minSdk 23`, run full compatibility validation before production.

## Release Verification

This release was checked with:

1. core, retrofit, and demo debug unit tests.
2. demo release unit tests.
3. core, retrofit, and demo release lint.
4. demo debug and release APK builds.
5. `publishToMavenLocal`.
6. Maven local AAR, POM, sources, Dokka javadoc, and consumer ProGuard rule verification.
7. `releaseToMavenCentral --dry-run`.
8. `git diff --check`.
