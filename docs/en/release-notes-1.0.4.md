# 1.0.4 Release Notes

[中文](../release-notes-1.0.4.md)

`1.0.4` is a custom-adapter boundary and open-source release readiness update.

This release does not expand the default fallback scope. It keeps caller-owned Gson adapter behavior on the native Gson path and tightens the documentation, examples, and release gates before public publication.

## Behavior Changes

1. Caller-registered `TypeAdapter`, `TypeAdapterFactory`, `registerTypeHierarchyAdapter(...)`, and `@JsonAdapter` matches keep the native Gson path first.
2. `IOException`, `JsonParseException`, `IllegalStateException`, `NumberFormatException`, and ordinary `RuntimeException` thrown by custom adapters propagate outward instead of being disguised as field fallback.
3. SafeParser follows Gson's last-registered-wins factory order when inspecting caller-provided factories.
4. Primitive types and `String` continue to delegate to Gson by default; safe primitive defaults remain opt-in through `PrimitiveParsingPolicy.Safe`.
5. Duplicate Map keys keep native Gson behavior: duplicate keys with a non-null previous value throw `JsonSyntaxException("duplicate key: ...")`, while duplicate keys whose previous value is `null` keep Gson 2.13.2 overwrite behavior.

## Tests And Boundaries

1. Boundary tests now cover reflective objects, collections, maps, arrays, nested field adapters, class-level `@JsonAdapter`, hierarchy adapters, and Retrofit converters.
2. Map object form, array-entry form, complex-key duplicates, and duplicate-key event boundaries are covered so duplicate keys are not misreported as field fallback events.
3. AAR consumer ProGuard rules now include Gson internal `TreeTypeAdapter`, `TypeAdapters`, `EnumTypeAdapter`, and `NumberTypeAdapter` keepnames in both core and retrofit modules.
4. Documentation contract tests normalize CRLF / LF line endings so Windows and CI behave consistently.

## Documentation And Release

1. README, getting started docs, API reference, configuration docs, examples, release checklist, and open-source collaboration files have been prepared for public framework publication.
2. Documentation now tightens default behavior, external Gson entries, ShapeCoercion use cases, R8 / ProGuard risks, and release verification guidance.
3. Gradle publishing version, Demo version, dependency snippets, compatibility docs, release checklist, and release notes are aligned to `1.0.4`.

## Behavior Boundaries

1. SafeParser built-in adapters still emit events and keep the outer object parsing when a field-level mismatch can be isolated.
2. When a caller-owned custom adapter matches, read failures follow native Gson exception propagation and do not emit SafeParser mismatch events.
3. `PrimitiveParsingPolicy.DelegateToGson` remains the default; primitive and `String` mismatches are not swallowed by safe primitive defaults unless callers opt in.
4. `ShapeCoercionPolicy` remains disabled by default; the explicit shape coercion feature from `1.0.3` stays compatible in `1.0.4`.
5. Network, transport, cancellation, and fatal failures still propagate outward and are not hidden by empty-response handling or field fallback.

## Upgrade

When upgrading from `1.0.3` to `1.0.4`, most projects only need to change the dependency version:

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.4")
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.4")
```

If your business logic depends on custom adapter failures being recovered while the outer object continues parsing, review that adapter's error handling. `1.0.4` behaves closer to native Gson: caller-owned adapter failures propagate outward.

After upgrading, run 3 regression inputs: 1. a custom adapter that actively throws, confirming the exception propagates 2. primitive fields receiving invalid strings, objects, or arrays, confirming the default still delegates to Gson 3. duplicate Map keys, confirming ordinary duplicates throw and previous-`null` duplicates keep native Gson overwrite behavior.

## Release Verification

This release verification should cover:

1. core, retrofit, and demo debug unit tests.
2. demo release unit tests.
3. core, retrofit, and demo release lint.
4. demo debug and release APK builds.
5. custom adapter propagation boundaries for fields, collections, maps, arrays, nested objects, and hierarchy registrations.
6. default primitive delegation to Gson and explicit `PrimitiveParsingPolicy.Safe` fallback boundaries.
7. `publishToMavenLocal`.
8. `verifyMavenLocalPublicationArtifacts`.
9. `releaseToMavenCentral --dry-run`.
10. Markdown local relative link checks.
11. `git diff --check`.
