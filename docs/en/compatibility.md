# Compatibility

[中文](../compatibility.md)

This document answers one question: whether your Android project can safely adopt GsonSafeParser.

Start with the short version:

1. The published artifacts are Android AARs, not plain JVM jars.
2. The recommended integration baseline is `minSdk 23`, `compileSdk 36`, `JDK 17`, `Kotlin 2.0.21`, and `Gson 2.13.2`.
3. The Retrofit module is verified with `Retrofit 2.8.1`.
4. Retrofit network or transport read failures are not JSON mismatches or empty responses; do not hide them with `emptyResponsePolicy`.
5. Legacy projects should not go straight to production. Validate them against the matrix below first.

## 1. Version Matrix

| Area | Verified version | Boundary type | Legacy risk | Recommendation |
| --- | --- | --- | --- | --- |
| Artifact | Android AAR | Hard boundary | Plain JVM projects cannot consume it as a normal JVM jar. | Use it directly in Android projects; a JVM artifact would need separate publishing. |
| Library | `1.0.3` | Current stable release | `1.0.0` is the first public compatibility baseline; internal pre-1.0 iterations are not covered by the public compatibility promise. | Use `1.0.3` for new integration. |
| Android `minSdk` | `minSdk 23` | Hard boundary | Apps below 23 may fail AAR merge or runtime validation. | Keep the app at `minSdk 23` or higher. |
| Android `compileSdk` | `compileSdk 36` | Build boundary | Lower `compileSdk` values may hit AAR metadata, Lint, or toolchain differences. | Prefer `compileSdk 36`; run full validation if lower. |
| JDK | `JDK 17` | Hard boundary | JDK 8 / 11 build chains are risky for Java 17 artifacts. | Use JDK 17 or later. |
| Kotlin plugin | `Kotlin 2.0.21` | Compile boundary | Older Kotlin compilers may fail on Kotlin 2.x metadata, especially reified APIs, extension functions, and default parameters. | Kotlin projects should use `Kotlin 2.0.21` or later. |
| Kotlin runtime | `Kotlin 2.0.21` line | Runtime boundary | A forced downgrade of stdlib can cause missing runtime methods or metadata differences. | Do not force a Kotlin runtime downgrade. |
| `kotlin-reflect` | `kotlin-reflect 2.0.21` | Functional dependency | Kotlin data class defaults, primary constructor parameters, and non-null fallback may fail. | Keep `kotlin-reflect 2.0.21`, or align it with the project Kotlin version and run full regression. |
| Gson | `Gson 2.13.2` | Core dependency | A forced downgrade may break GsonBuilder internal-field snapshots, causing Safe Adapters to fall back to native Gson. | Use `Gson 2.13.2`; run `diagnostics()` after overriding. |
| Retrofit | `Retrofit 2.8.1` | Retrofit module dependency | Older versions may differ in Converter APIs. | Do not go below `2.8.1` for Retrofit integration. |
| `converter-gson` | `2.8.1` | Retrofit implementation detail | If it differs from the main Retrofit version, converter behavior may differ. | Keep Retrofit and converter-gson aligned. |
| R8 / ProGuard | Framework rules bundled in AAR | Release stability boundary | If business model field names, constructors, or Kotlin Metadata are stripped, Gson binding changes. | Configure business model keep rules for release builds. |

## 2. Legacy Project Decision Table

| Project state | Ready for production directly | Why | What to do |
| --- | --- | --- | --- |
| `minSdk >= 23`, JDK 17, Gson 2.13.2 | Yes, after rollout checks | It matches the verified matrix. | Run debug/release comparison and real JSON regression. |
| Kotlin below 2.0 | No | Kotlin metadata and reified API compatibility are uncertain. | Prefer upgrading Kotlin; if short-term blocked, use `Class` / `Type` APIs and run full regression. |
| Forced Gson downgrade | No | Safe Adapter setup depends on GsonBuilder internal snapshots; older Gson may miss fields. | Run `GsonSafeParser.diagnostics()` and `integrationCheck()`. |
| `minSdk < 23` | No | This AAR does not declare support below Android 23. | Raise business `minSdk`, or discuss a new compatibility target. |
| Release build with R8 | Yes, but not zero config | Business model rules cannot be inferred by the library. | Follow [Android ProGuard](android-proguard.md). |

## 3. Retrofit Version Notes

`gson-safe-parser-retrofit` exposes `Retrofit 2.8.1`.

If your project already uses a newer Retrofit version, Gradle can usually resolve to the newer version. Before production, verify 4 things:

1. Response converters are created successfully.
2. Empty-body policy matches expectations.
3. Offline state, request cancellation, connection reset, and TLS failures return to Retrofit / OkHttp error handling and are not recorded as `EmptyResponse`, `RawJsonCaptureSkipped`, or `TypeMismatch`.
4. Raw JSON capture and oversized-body skip events behave as expected.

This release does not upgrade Retrofit right before publishing, because that could introduce new Retrofit / OkHttp behavior changes.

## 4. Kotlin Version Notes

The easiest Kotlin dependency to miss is `kotlin-reflect`.

GsonSafeParser reads Kotlin reflection information for data class defaults and constructor parameters. If a release build strips `kotlin-reflect`, Kotlin Metadata, or constructors, field-level fallback can downgrade or fail.

Recommendations:

1. Use `Kotlin 2.0.21` or later in Kotlin projects.
2. Do not force downgrade `kotlin-reflect`.
3. Java callers should use `Class` / `Type` APIs; Kotlin reified APIs are Kotlin-only.
4. Release builds must keep business model constructors and Kotlin Metadata.

## 5. Pre-Release Self-Check

```kotlin
val diagnostics = GsonSafeParser.diagnostics()
check(diagnostics.hasErrors.not()) { diagnostics.checks.joinToString("\n") }

val integrationCheck = GsonSafeParser.integrationCheck(SafeParserConfig.production())
check(integrationCheck.hasErrors.not()) { integrationCheck.checks.joinToString("\n") }
```

If your project overrides Gson, Kotlin, Retrofit, or R8 behavior, run these checks first, then compare debug and release builds against real API JSON.
