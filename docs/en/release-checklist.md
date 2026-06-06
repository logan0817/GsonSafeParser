# Release Checklist

[中文](../release-checklist.md)

Run this checklist before publishing `1.0.3`. The goal is to verify Android AAR artifacts, consumer ProGuard rules, release minification checks, documentation versions, and local Maven artifacts.

## 1. Pre-Release Verification Command

```bash
./gradlew \
  :gson-safe-parser-core:testDebugUnitTest \
  :gson-safe-parser-retrofit:testDebugUnitTest \
  :demo-app:testDebugUnitTest \
  :demo-app:testReleaseUnitTest \
  :gson-safe-parser-core:lintRelease \
  :gson-safe-parser-retrofit:lintRelease \
  :demo-app:lintRelease \
  :demo-app:assembleDebug \
  :demo-app:assembleRelease \
  publishToMavenLocal \
  --rerun-tasks \
  --warning-mode=fail
./gradlew verifyMavenLocalPublicationArtifacts --warning-mode=fail
./gradlew releaseToMavenCentral --dry-run --warning-mode=fail
git diff --check
```

Expected result:
1. core, retrofit, and demo debug unit tests pass.
2. demo release unit tests pass.
3. core, retrofit, and demo release lint pass without low-API calls, resource errors, or manifest errors.
4. demo debug and release APKs build, and release uses R8 minify.
5. `publishToMavenLocal` creates core and retrofit AAR publication artifacts.
6. Dokka javadoc.jar is generated offline and does not depend on external package-list URLs.
7. `verifyMavenLocalPublicationArtifacts` reuses the CI checks for AAR, POM, sources, javadoc, and demo release merged ProGuard configuration.
8. `releaseToMavenCentral --dry-run` verifies the remote release task graph, signing task wiring, and `clean` ordering.
9. `git diff --check` reports no whitespace formatting issues.
10. The build emits no Gradle warning, Kotlin warning, or configuration-time classpath resolution warning.

## 2. AAR Artifact Checks

Local Maven artifacts are verified by `verifyMavenLocalPublicationArtifacts` and must satisfy:
1. `gson-safe-parser-core-1.0.3.aar` and `gson-safe-parser-retrofit-1.0.3.aar` exist.
2. The main artifact does not fall back to a plain `.jar`.
3. The POM uses `<packaging>aar</packaging>`.
4. The AAR contains `classes.jar`, `proguard.txt`, `META-INF/LICENSE`, and `META-INF/NOTICE`.
5. `sources.jar` and `javadoc.jar` exist, and the javadoc jar contains Dokka `index.html`.
6. The retrofit POM depends on `gson-safe-parser-core` with the same release version.

## 3. ProGuard And Legacy Integration Checks

Before publishing, confirm:
1. core / retrofit AAR `proguard.txt` contains `kotlin.Metadata`, `com.google.gson.GsonBuilder`, and `@SerializedName <fields>`.
2. demo release merged `configuration.txt` contains both demo model keep rules and AAR consumer rules.
3. demo `proguard-rules.pro` keeps only business model rules and does not copy framework rules manually.
4. `docs/en/android-proguard.md` explains the zero-config trial boundary, minimum release configuration, and the `android.enableR8.fullMode=true/false` choice.

## 4. Documentation And Version Checks

Before publishing, confirm:
1. root `build.gradle.kts` version is `1.0.3`.
2. demo `versionName` is `1.0.3`, and `versionCode` has been incremented.
3. `README.md`, `README_EN.md`, `docs/getting-started.md`, and `docs/en/getting-started.md` contain both core and retrofit `1.0.3` coordinates.
4. Chinese and English docs can link to each other.
5. The README documentation table links to Getting Started, Compatibility, Configuration, Mismatch Capability Matrix, Android ProGuard, Demo App, Troubleshooting, Release Checklist, 1.0.3 Release Notes, historical 1.0.2 Release Notes, historical 1.0.1 Release Notes, and historical 1.0.0 Release Notes.
6. `docs/compatibility.md` and `docs/en/compatibility.md` list `minSdk 23`, `compileSdk 36`, `JDK 17`, `Kotlin 2.0.21`, `kotlin-reflect 2.0.21`, `Gson 2.13.2`, `Retrofit 2.8.1`, and R8 / ProGuard boundaries.
7. `CHANGELOG.md` exists and treats `1.0.3` as the current release and `1.0.0` as the first public compatibility baseline.
8. `docs/release-notes-1.0.3.md` and `docs/en/release-notes-1.0.3.md` list JSON shape coercion, event reporting, boundaries, and release verification.
9. `docs/release-notes-1.0.2.md` and `docs/en/release-notes-1.0.2.md` keep the transport exception boundary fix, compatibility boundaries, and release verification.
10. `docs/release-notes-1.0.1.md` and `docs/en/release-notes-1.0.1.md` keep the historical stabilization fixes, compatibility boundaries, and release verification.
11. `docs/release-notes-1.0.0.md` and `docs/en/release-notes-1.0.0.md` keep the initial capabilities, compatibility boundaries, and release verification.
12. `README.md`, `README_EN.md`, `docs/compatibility.md`, `docs/en/compatibility.md`, `docs/troubleshooting.md`, and `docs/en/troubleshooting.md` all state that network or transport read failures return to Retrofit / OkHttp and must not be hidden with `emptyResponsePolicy`.

## 5. Before Remote Publication

Before remote publication, confirm the local machine or CI has Central Portal tokens and GPG signing configured. `publishToMavenLocal` does not require signing; `publishToMavenCentral` / `releaseToMavenCentral` require remote publishing credentials and signing.

```bash
./gradlew releaseToMavenCentral
```

After the publication task succeeds, check the deployment in Central Portal, then decide whether to release or drop it. Do not commit tokens, GPG private keys, JKS / keystore files, `signing.properties`, or `release.properties`.
