# Compatibility

[中文](../compatibility.md)

This document answers one question: whether your Android project can safely adopt GsonSafeParser.

Start with the short version:

1. The published artifacts are Android AARs, not plain JVM jars.
2. The recommended integration baseline is `minSdk 23`, `compileSdk 36`, `JDK 17`, `Kotlin 2.0.21`, and `Gson 2.13.2`.
3. The Retrofit module is verified with `Retrofit 2.8.1`, and it carries the `OkHttp 4.12.0` and `Okio 3.6.0` safety baselines as runtime dependencies.
4. Retrofit network or transport read failures are not JSON mismatches or empty responses; do not hide them with `emptyResponsePolicy`.
5. Legacy projects should not go straight to production. Validate them against the matrix below first.

## 1. Version Matrix

| Area | Verified version and meaning |
| --- | --- |
| Artifact | Android AAR. Plain JVM projects cannot consume it as a normal JVM jar; Android projects can use it directly. |
| Library | `1.0.4` is the current stable release; `1.0.0` is the first public compatibility baseline. |
| Android `minSdk` | `minSdk 23` is a hard boundary. Apps below 23 may fail AAR merge or runtime validation. |
| Android `compileSdk` | `compileSdk 36` is the build baseline. Run full validation if your project uses a lower value. |
| JDK | `JDK 17` is a hard boundary. JDK 8 / 11 build chains are risky for Java 17 artifacts. |
| Kotlin plugin | `Kotlin 2.0.21` is the compile baseline. Older compilers may fail on Kotlin 2.x metadata. |
| Kotlin runtime | The `Kotlin 2.0.21` line is the runtime baseline. Do not force a Kotlin runtime downgrade. |
| `kotlin-reflect` | `kotlin-reflect 2.0.21` is a functional dependency. Without it, data class defaults and non-null fallback may fail. |
| Gson | `Gson 2.13.2` is the core dependency. Forced downgrades may push Safe Adapters back to native Gson. |
| Retrofit | `Retrofit 2.8.1` is the Retrofit module dependency. Older versions may differ in Converter APIs. |
| `converter-gson` | `2.8.1` should stay aligned with the main Retrofit version. |
| OkHttp | `OkHttp 4.12.0` is the network-stack safety baseline. Let Gradle resolve to `4.12.0` or later. |
| Okio | `Okio 3.6.0` is the network-stack safety baseline. Let Gradle resolve to `3.6.0` or later. |
| R8 / ProGuard | Framework rules are bundled in the AAR, but business model keep rules are still required. |

## 2. Legacy Project Decision Table

| Project state | Production readiness and reason |
| --- | --- |
| `minSdk >= 23`, JDK 17, Gson 2.13.2 | Ready for gray release. It matches the verified matrix; still run debug/release comparison and real JSON regression. |
| Kotlin below 2.0 | Not ready for direct production. Kotlin metadata and reified API compatibility are uncertain, so upgrade Kotlin first when possible. |
| Forced Gson downgrade | Not ready for direct production. Run `GsonSafeParser.diagnostics()` and `integrationCheck()` before rollout. |
| `minSdk < 23` | Not supported by the current AAR. Raise the business `minSdk`, or define a new compatibility target first. |
| Release build with R8 | Production is possible, but not zero config. Follow [Android ProGuard](android-proguard.md) for business model keep rules. |

## 3. Retrofit Version Notes

`gson-safe-parser-retrofit` exposes `Retrofit 2.8.1`, while carrying `OkHttp 4.12.0` and `Okio 3.6.0` as runtime dependencies. This keeps the Retrofit 2.x Converter API while preventing consumers that do not declare a network stack from resolving back to Retrofit 2.8.1's OkHttp 3.14.x / Okio 1.x transitive baseline, without expanding the converter module's compile-time API to the network stack.

If your project already uses a newer Retrofit version, Gradle can usually resolve to the newer version. Before production, verify 4 things:

1. Response converters are created successfully.
2. Empty-body policy matches expectations.
3. Offline state, request cancellation, connection reset, and TLS failures return to Retrofit / OkHttp error handling and are not recorded as `EmptyResponse`, `RawJsonCaptureSkipped`, or `TypeMismatch`.
4. Raw JSON capture and oversized-body skip events behave as expected.

If your project already owns OkHttp 5 or another unified network stack, first run `./gradlew dependencyInsight --dependency okhttp` and `./gradlew dependencyInsight --dependency okio` to confirm the final dependency resolution, then rerun offline, cancellation, connection reset, TLS failure, and raw JSON capture regressions.

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

A forced downgrade of Gson, Kotlin, Retrofit, OkHttp, or Okio must be treated as a compatibility change and validated before rollout.
