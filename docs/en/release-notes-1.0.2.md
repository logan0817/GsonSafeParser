# 1.0.2 Release Notes

[中文](../release-notes-1.0.2.md)

`1.0.2` fixes the transport exception boundary.

This release keeps the same positioning: GsonSafeParser is an extension layer for Gson. It does not take over the network layer or disguise network failures.

Recoverable field-level shape mismatches can still be isolated by the library. Network interruption, read cancellation, and connection reset failures are unsafe to isolate and must be returned to the caller.

## Changes

1. Network and transport read failures are no longer treated as field mismatches. `InterruptedIOException`, socket reset, broken pipe, and OkHttp stream reset failures are thrown outward.
2. Retrofit empty-response probing no longer records `EmptyResponse` events when the response body fails because of a transport failure.
3. Retrofit raw JSON probing no longer records `RawJsonCaptureSkipped` events on transport failures, so troubleshooting points to the network layer instead of JSON shape handling.
4. Ordinary custom adapter `IOException` behavior in `1.0.2` still used local fallback; later releases tightened this boundary, and `1.0.4` keeps native Gson custom-adapter paths and throws those failures outward.
5. Aligns the current release version to `1.0.2` across Gradle publishing, Demo version, README, getting started docs, compatibility docs, release checklist, and release notes.

## Upgrade

When upgrading from `1.0.1` to `1.0.2`, most projects only need to change the dependency version to `1.0.2`.

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.2")
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.2")
```

If the app is offline, the request is canceled, or OkHttp resets a stream, `1.0.2` lets those failures propagate as network-layer errors.

Handle them with the same Retrofit / OkHttp error path your app already uses instead of treating them as JSON field mismatches, and do not hide them with `emptyResponsePolicy`.

## Compatibility Boundaries

1. Published artifacts remain Android AARs.
2. The verified matrix remains `minSdk 23`, `compileSdk 36`, `JDK 17`, `Kotlin 2.0.21`, `kotlin-reflect 2.0.21`, and `Gson 2.13.2`.
3. The Retrofit module is still verified with `Retrofit 2.8.1`.
4. Release builds with R8 / ProGuard still need business model field names, constructors, and Kotlin Metadata keep rules.
5. If a business project forces older Gson, Kotlin, Retrofit, or goes below `minSdk 23`, run full compatibility validation before production.

## Release Verification

This release verification should cover:

1. core, retrofit, and demo debug unit tests.
2. demo release unit tests.
3. core, retrofit, and demo release lint.
4. demo debug and release APK builds.
5. `publishToMavenLocal`.
6. Maven local AAR, POM, sources, Dokka javadoc, and consumer ProGuard rule verification.
7. `releaseToMavenCentral --dry-run`.
8. `git diff --check`.
