# Changelog

All notable changes are summarized here. Detailed bilingual release notes stay under `docs/`.

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
