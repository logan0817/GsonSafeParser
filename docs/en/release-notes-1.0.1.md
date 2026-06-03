# 1.0.1 Release Notes

[中文](../release-notes-1.0.1.md)

`1.0.1` is a stabilization release for production adoption.

This release keeps the same positioning: GsonSafeParser is an extension layer for Gson. Recoverable field-level issues are isolated by the library; problems that cannot be safely isolated are delegated back to native Gson adapters or thrown outward.

## Changes

1. Aligns the current release version to `1.0.1` across Gradle publishing, Demo version, README, getting started docs, compatibility docs, release checklist, and release notes.
2. Keeps the default constructor policy as `RequiredConstructorParameterPolicy.GsonCompatible`. Existing projects can adopt the library without fixing every missing non-null Kotlin field at once.
3. Gives `RequiredConstructorParameterPolicy.Strict` the highest priority. Once `Strict` is enabled, SafeParser disables Unsafe for itself and for the Gson fallback path; if `useJdkUnsafe = true` is passed together with `Strict`, `Strict` wins.
4. Updates Demo default-policy text to the current version so users do not mistake the screen for an older release.
5. Keeps `1.0.0` as the first public API compatibility baseline.

## Upgrade

When upgrading from `1.0.0` to `1.0.1`, most projects only need to change the dependency version to `1.0.1`.

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.1")
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.1")
```

If the project explicitly enables `RequiredConstructorParameterPolicy.Strict`, make sure every non-null required constructor parameter can be read from JSON, or give those parameters defaults.

## Compatibility Boundaries

1. Published artifacts remain Android AARs.
2. The verified matrix remains `minSdk 23`, `compileSdk 36`, `JDK 17`, `Kotlin 2.0.21`, `kotlin-reflect 2.0.21`, and `Gson 2.13.2`.
3. The Retrofit module is still verified with `Retrofit 2.8.1`.
4. Release builds with R8 / ProGuard still need business model field names, constructors, and Kotlin Metadata keep rules.
5. If a business project forces older Gson, Kotlin, Retrofit, or goes below `minSdk 23`, run full compatibility validation before production.

## Release Verification

Before publishing, this release should be checked with:

1. core, retrofit, and demo debug unit tests.
2. demo release unit tests.
3. core, retrofit, and demo release lint.
4. demo debug and release APK builds.
5. `publishToMavenLocal`.
6. Maven local AAR, POM, sources, Dokka javadoc, and consumer ProGuard rule verification.
7. `releaseToMavenCentral --dry-run`.
8. `git diff --check`.
