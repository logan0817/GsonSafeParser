# 1.0.5 Release Notes (Candidate)

[中文](../release-notes-1.0.5.md)

**Status: `1.0.5` is not yet published. The current stable Maven Central version remains `1.0.4`.** These notes describe the candidate source changes. A local build, test run, or publication to a temporary repository does not constitute a public release.

`1.0.5` focuses on reliable parsing recovery, observer callbacks, and Retrofit conversion. Default configuration and dependency versions remain unchanged. Some behavior changes, including propagating syntax errors that were previously swallowed and skipping Map inputs that produced artificial keys, require regression coverage before upgrading.

## Upgrading From 1.0.4

| Scenario | Candidate behavior and migration guidance |
| --- | --- |
| Blank or malformed Map keys | Blank numeric Map keys no longer become `0` or `null`; their entries are skipped in both object and array-entry forms. Array-entry keys whose shapes are rejected by built-in adapters are also skipped without manufacturing `null` keys. Explicit JSON `null` keys still depend on support from the target Map and adapter. Do not rely on previously manufactured keys. |
| Invalid escapes or truncated JSON | Syntax errors propagate instead of being treated as successful field or element recovery. Array parsing no longer repeatedly retries an unreadable element. Keep normal parse-failure handling at the call site. |
| Collections, object arrays, and primitive arrays | Isolatable invalid elements and container insertion rejections affect only the current item, preserving valid neighbors. In Safe mode, parsing `[1,"bad",2]` as a numeric array preserves `[1,2]`. Primitive arrays skip `null`; object arrays retain explicit `null`. A consumed invalid character value no longer causes subsequent fields to be lost. |
| Caller adapters and field rules | Field-level `@JsonAdapter` serialization retains masking and protocol conversion. Serialization preserves runtime subtype fields when only a deserializer is registered or subtype values are stored in arrays. Caller adapters can handle explicit `null`, including `@JsonAdapter(nullSafe = false)`; a returned `null` still follows field null policies and nullability. Explicitly included `transient` fields follow caller exclusion rules; `static` fields remain excluded. |
| Safe number conversion | Integer, `BigDecimal`, `BigInteger`, and numeric Boolean conversion under `PrimitiveParsingPolicy.Safe`, plus the default dynamic-number strategy, limit numeric text to 10,000 characters and require `abs(BigDecimal.scale()) < 10,000`. Checks run before integer expansion. Isolatable field failures retain defaults and emit events; dynamic `Any/Object` number failures propagate when they cannot be isolated. Regress excessively long numbers and extreme exponents. |
| Parsing inside observer callbacks | Raw JSON, Map paths, and event collectors are isolated while observers such as `onTypeMismatch`, and `onObserverFailure`, execute. Independent parses or Retrofit calls inside callbacks no longer inherit the outer body or append their events to the outer `parseSafe` snapshot. Explicitly collect results if combined monitoring is required. |

When a container such as `ConcurrentMap` rejects a `null` key or value, only that entry is skipped, a `MAP_ITEM` event is recorded, and valid neighboring entries are preserved. This is handled separately from caller-adapter read failures: container rejection after an adapter successfully returns `null` can be isolated, while read exceptions thrown by the adapter still propagate.

This recovery applies only to safely isolatable built-in parsing or container operations. Caller-adapter read failures, transport failures, cancellation, and fatal failures continue to propagate. Duplicate Map keys keep Gson 2.13.2 semantics.

Safe Boolean-to-String conversion is also fixed, and root mismatch events retain the actual numeric token. Map privacy policies now cover nested paths, inferred field names, shape-coercion events, and numeric-key failure reasons, preventing those observation fields from bypassing `Omit` or `Hash`. Explicitly enabled `rawJson` remains the original content and is not redacted by Map key policies.

## Retrofit Response Handling

1. Original response bodies close even when empty-response probing, raw-body probing, observer callbacks, or conversion fails. Transport read failures continue to propagate.
2. With raw capture enabled, Retrofit's official Gson converter still reads the original response, preserving caller-configured Gson strictness, full-consumption checks, charset, and BOM handling. Raw probing remains bounded; disabled or oversized capture isolates the outer raw-body context.
3. Empty-response detection reads actual bytes instead of relying solely on `contentLength() == 0`. Valid JSON supplied by an interceptor is parsed even when the declared length is zero. Truly empty streams still follow the applicable empty-response policy; read failures are not treated as empty responses.
4. For normal Retrofit service calls, built-in Unit/Void conversion and HTTP 204/205 handling take precedence over SafeParser. Direct SafeParser converter behavior does not guarantee an `EmptyResponse` event at the service entry point.
5. Regression tests cover actual Retrofit service calls, including raw-context isolation, strictness, malformed syntax, zero-length declarations, resource closure, and transport failure boundaries.

## Unchanged Defaults And Dependencies

- Primitive parsing still defaults to `PrimitiveParsingPolicy.DelegateToGson`; safe primitive recovery requires explicitly selecting `Safe`. The default fallback remains `FallbackPolicy.NullOnly`.
- Constructor parameters still default to `RequiredConstructorParameterPolicy.GsonCompatible`, with `useJdkUnsafe = false`. JSON shape coercion remains disabled by default.
- Raw capture remains disabled by default, with a default limit of `1 MiB`. Map key observation still defaults to `MapItemKeyPolicy.Omit`. Enabling capture configuration does not automatically attach raw JSON to direct `gson.fromJson(...)` calls; use `parseSafe`, Parser, or another parsing entry point that establishes context.
- Observers still run synchronously on the parsing thread. Event collections and logging facilities supplied by callers must be thread-safe when Gson or Parser is reused across threads.

| Component | Unchanged version or boundary |
| --- | --- |
| Android artifact | AAR; `minSdk 23`, `compileSdk 36` |
| Build and Kotlin | JDK 17, Kotlin / kotlin-reflect 2.0.21 |
| Gson | 2.13.2 |
| Retrofit / converter-gson | 2.8.1 |
| Retrofit runtime network baseline | OkHttp 4.12.0 and Okio 3.6.0, still runtime dependencies |

See [compatibility](compatibility.md) and [Android obfuscation configuration](android-proguard.md) for environment and business-model keep requirements.

## New Low-Level API

`GsonSafeParser.withRawJsonContext(json, config, block)` lets third-party converters bridge bounded raw content and requires `@OptIn(GsonSafeParserLowLevelApi::class)`. It binds observation context while `block` runs on the current thread, honors the capture switch and UTF-8 byte limit, and restores the previous context after nested calls or failures.

This API does not parse JSON, register adapters, change strictness, swallow exceptions, or close the converter's original response. Ordinary application code should keep using existing entry points. See the [API reference](api-reference.md#6-observation-and-reports) for the full contract.

## Candidate Coordinates And Local Verification

The following `1.0.5` coordinates are **only for use after public release, or when consuming candidate artifacts from a temporary local Maven repository**. Until publication, keep using `1.0.4` from Maven Central. Select one dependency in the application module; the Retrofit module already includes core transitively:

```kotlin
dependencies {
    implementation("io.github.logan0817:gson-safe-parser-core:1.0.5")
    // For Retrofit, replace the line above with:
    // implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.5")
}
```

After configuring the version, run these checks from the repository root. The first command publishes to the specified temporary repository and verifies AARs, POMs, module metadata, sources, javadoc, and obfuscation rules; it does not upload to Maven Central. The second regenerates OSV scan input; the actual scan must still run as described in the checklist:

```bash
./gradlew verifyMavenLocalPublicationArtifacts \
  -Dmaven.repo.local="$PWD/build/release-verification-m2" --warning-mode=fail
./gradlew writeOsvReleaseRuntimeLockfile --warning-mode=fail
git diff --check
git diff --cached --check
```

An independent Android consumer must point `dependencyResolutionManagement.repositories` in `settings.gradle.kts` at the actual absolute path of that temporary repository and use Maven coordinates. A `project(...)` dependency is not a substitute. See the [release checklist](release-checklist.md) for the complete JVM tests, lint, builds, vulnerability scan, device execution, and prepublication gates. Regenerate OSV input on every invocation so old dependency versions are not mistaken for current scan input.

## Verification Record And Pending Work

The following checks were completed on November 16, 2026. Stability fixes and version preparation were committed as `d438cd4`; after correcting CI SDK package installation, commit `21a0c64` passed remote CI #25. Local verification covers the same runtime code; device results correspond to the APK digest below.

| Check | Result from this round |
| --- | --- |
| Debug / Release JVM tests | 604 passed in each variant: core 479, retrofit 76, Demo 49; no failures, errors, or skips |
| Release lint for all three project modules | 0 errors, 0 warnings |
| Demo builds | Debug and R8-minified Release passed with `1.0.5` / `versionCode 6` |
| Temporary Maven repository | Both 1.0.5 AARs, POMs, module metadata, sources, javadoc, and rules passed verification |
| Independent Android consumer | Consumed 1.0.5 by Maven coordinates; Release compilation, minification, and lint passed with automatic consumer-rule merging. Lint also reported five tool/dependency update notices; the compatibility baseline was retained |
| Device execution: Android 13 | OnePlus 8 Pro (IN2020), Android 13 (API 33, `IN2020_13.1.0.190(CN01)`); the minified APK passed installed-version verification as 1.0.5 / 6 and 51/51 self-checks |
| Device execution: Android 16 | Redmi K60 Ultra, Android 16 (API 36); the same minified APK passed installed-version verification and 51/51 self-checks |
| Vulnerability scan | OSV Scanner 2.3.8 scanned 15 runtime dependencies and reported no vulnerabilities |
| Remote publication task graph | `releaseToMavenCentral --dry-run` passed; only the task graph was checked, with no remote publication |
| Remote CI | CI #25 for commit `21a0c64` passed all dependency scanning, Debug / Release tests, lint, builds, and publication artifact checks |
| Central deployment | Both modules passed signing, upload, and Central validation; deployment `3b792422-2216-4ead-aad4-c6ebcb45c77f` was submitted for public release and is awaiting public repository synchronization |

Central returned a warning that the organization's monthly `Release Count` limit was exceeded, with a reset date of **2026-12-01**. The public-release request was accepted, but the observed state remained `PUBLISHING` and neither module could be resolved from the public repository. This does not establish a successful release. Check the existing deployment and public repository before attempting recovery to avoid duplicate uploads.

SHA-256 of this round's Demo Release APK:

```text
0826d6f8b13839ecd4ca8205450df6a2bc8e3e33e99dc0c9cb5a1e8c4dfe3c30
```

Still pending: confirm the final Central publication state and independently resolve the artifacts from Maven Central, then switch public installation examples. Device testing covered the Android 13 and Android 16 devices above, not other models or Android versions. Release JVM tests and `assembleRelease` do not replace actual execution; successful signing, upload, and validation do not establish public artifact availability.
