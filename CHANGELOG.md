# Changelog

All notable changes are summarized here. Detailed bilingual release notes stay under `docs/`.

## 1.0.0

First public release for production adoption.

1. Provides field-level safe parsing for recoverable Gson shape mismatches while preserving native Gson fallback boundaries.
2. Supports Kotlin data class defaults, `parseSafe<T>()`, `fromJsonSafe<T>()`, diagnostics, integration checks, and contract reports.
3. Provides Retrofit integration for empty bodies, bounded raw JSON capture, and event dispatch.
4. Ships Android AAR artifacts with consumer ProGuard rules, sources, Dokka javadoc, LICENSE, and NOTICE.
5. Includes release checks for boxed Boolean dispatch, escaped Map keys, fatal exception boundaries, raw JSON limits, R8 / ProGuard, and Maven artifacts.

Details: [中文](docs/release-notes-1.0.0.md) / [English](docs/en/release-notes-1.0.0.md)
