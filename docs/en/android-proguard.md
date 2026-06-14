# Android ProGuard

[中文](../android-proguard.md)

This document answers one question: when an Android release build enables R8 / ProGuard, what should a project configure to integrate GsonSafeParser safely?

Remember 3 rules first:

1. debug builds or non-minified builds can try the library with zero ProGuard config.
2. release builds with R8 / ProGuard must keep the required business model information.
3. GsonSafeParser AARs merge the framework rules automatically, so users only need to handle business model rules.

## 1. Choose An Integration Path First

| Project state | Recommended path | What to do |
| --- | --- | --- |
| New project with clear model packages | New project integration | Add `@SerializedName` to new fields, then keep constructors and required fields for real model packages. |
| Legacy project with Beans scattered across packages | Legacy project quick integration | Start with broad keep rules for bean, model, entity, response, dto packages so release behavior stabilizes first. |
| Integration is already stable and the keep scope should be reduced | Narrow gradually | Use release self-checks and real JSON comparison to narrow broad rules to real response model packages. |

GsonSafeParser cannot automatically know original business model field names after obfuscation.

Without `@SerializedName` and without field-name keep rules, Gson may read and write JSON with obfuscated field names such as `a`, `b`, and `c` in release builds.

## 2. New Project Integration

For new projects, add `@SerializedName` to JSON fields from the beginning. Then Gson can still bind JSON by annotation even if R8 renames the field.

```kotlin
data class UserResponse(
    @SerializedName("id")
    val id: Long = 0L,
    @SerializedName("name")
    val name: String = ""
)
```

Add rules for the real model package in the App module's `proguard-rules.pro`. Replace the package below with your own business package:

```proguard
-keep,allowobfuscation class com.yourcompany.api.model.** { # New project: fields use @SerializedName, so class names may still be obfuscated.
    public <init>(...); # Keeps constructors so R8 does not shrink the Kotlin default-value construction path.
}

-keepclassmembers,allowobfuscation class com.yourcompany.api.model.** { # Keeps fields with explicit JSON names.
    @com.google.gson.annotations.SerializedName <fields>; # Field names may be obfuscated because JSON names are fixed by @SerializedName.
}
```

If some model fields do not have `@SerializedName` yet, keep those field names:

```proguard
-keep class com.yourcompany.api.model.** { # Transition period: some fields still rely on original field names.
    <fields>;
    public <init>(...);
}
```

`@SerializedName` only fixes JSON field names. It does not replace Kotlin Metadata and constructor keep rules.

If a model depends on Kotlin data class defaults, primary constructor parameters, non-null fallback values, or `kotlin-reflect`, release builds still need constructors and Kotlin Metadata.

## 3. Legacy Project Quick Integration

The legacy project low-cost integration path is broad first, narrow later.

Do not require users to inventory every Bean or annotate every field with `@SerializedName` on day one. Stabilize release behavior first, then reduce the keep scope gradually.

If Beans are scattered across packages, start with common model package names. Replace `com.yourcompany` with the real package prefix:

```proguard
-keep class com.yourcompany.**.bean.** { # Legacy project: keeps scattered Bean field names and constructors first.
    <fields>;
    public <init>(...);
}

-keep class com.yourcompany.**.model.** { # Legacy project: keeps model packages first.
    <fields>;
    public <init>(...);
}

-keep class com.yourcompany.**.entity.** { # Legacy project: keeps entity packages first.
    <fields>;
    public <init>(...);
}

-keep class com.yourcompany.**.response.** { # Legacy project: keeps response packages first.
    <fields>;
    public <init>(...);
}

-keep class com.yourcompany.**.dto.** { # Legacy project: keeps dto packages first.
    <fields>; # Fields without @SerializedName must keep their original names.
    public <init>(...); # Keeps constructors for Kotlin defaults and fallback construction.
}
```

This keeps more code visible to Gson, but it is the practical quick-start path for legacy projects. After release self-checks and real API JSON are stable, narrow the rules by business module or response package.

## 4. Narrowing Template

Once you know which packages actually participate in Gson parsing, narrow the broad rules like this:

```proguard
-keep class com.yourcompany.feature.user.api.response.** { # Keeps only user API response models.
    <fields>;
    public <init>(...);
}

-keep class com.yourcompany.feature.order.api.response.** { # Keeps only order API response models.
    <fields>;
    public <init>(...);
}
```

Before narrowing, do 2 checks:

1. Compare the same JSON in debug and release builds.
2. Configure `GsonSafeModelProbe` for key response models and confirm there are no `modelFieldObfuscationSuspected`, `modelConstructorUnavailable`, or `modelProbeFailure` checks.

## 5. R8 fullMode Choice

`android.enableR8.fullMode=true` is the long-term recommended path when business model keep rules are clear and release self-checks pass.

`android.enableR8.fullMode=false` is an optional compatibility strategy for complex legacy projects that need to reduce integration cost and stabilize release behavior first.

It can reduce risks around constructors, Metadata, and reflection information, but it cannot restore obfuscated field names.

| Choice | Fits | Still required |
| --- | --- | --- |
| `android.enableR8.fullMode=true` | New projects, or legacy projects with clear model rules | Keep business field names or use `@SerializedName`, keep constructors, and compare debug/release behavior. |
| `android.enableR8.fullMode=false` | Legacy projects that cannot finish model cleanup immediately | Still keep business field names and constructors. Do not treat it as a zero-config release setup. |

If you choose the compatibility strategy, configure it in `gradle.properties`:

```properties
android.enableR8.fullMode=false # Optional compatibility strategy: reduces legacy release optimization risk, but model field names still need keep rules.
```

`android.enableR8.fullMode=false` is not a zero-ProGuard-config switch. If a model has no `@SerializedName`, its field names still need keep rules.

## 6. Rules Already Merged From AARs

The current core and retrofit artifacts are Android AARs.

Android AAR automatically merges the framework consumer ProGuard rules into the user's App, so users do not need to copy those framework rules by hand.

The default `GsonSafeParser.create(config)` entry does not read `GsonBuilder` internals. The GsonBuilder rules below mainly protect `.enableSafeParser(config)`, `GsonSafeConverterFactory.create(builder, config)`, and external Builder configuration inheritance. The Gson rule only protects `diagnostics(gson, config)` when it checks whether an external Gson already registered Safe Adapters; it does not change parsing behavior.

The following rules are already bundled in GsonSafeParser AARs.

Only compare or copy them manually when source-copy integration, custom publication, or troubleshooting shows that consumer rules were not merged:

```proguard
-keep class kotlin.Metadata { *; } # Keeps Kotlin Metadata for data class defaults and reflection info.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault # Keeps attributes required by Gson and Kotlin reflection.

-keepclassmembers class com.google.gson.Gson { # Keeps the factories field read by external Gson registration diagnostics.
    java.util.List factories;
}

-keepclassmembers class com.google.gson.GsonBuilder { # Keeps GsonBuilder internals read by SafeParser.
    java.util.Map instanceCreators;
    java.util.List factories;
    java.util.ArrayDeque reflectionFilters;
    com.google.gson.ToNumberStrategy objectToNumberStrategy;
    boolean useJdkUnsafe;
    boolean complexMapKeySerialization;
}
```

If release builds report `AdapterCreationFailure` while debug builds work, first verify that the AAR consumer ProGuard rules are actually merged into the App.

If `GsonSafeParser.diagnostics()` reports only optional GsonBuilder fields as unreadable, field-level safe registration can continue. If it reports a critical field as unreadable, builder-first entries return to native Gson behavior; check whether consumer rules were merged.

## 7. Release Verification Checklist

Before publishing, run at least these 5 checks:

1. Verify the same real JSON in both debug and release builds.
2. Confirm plain field-name models, `@SerializedName` models, and Kotlin data class defaults all parse correctly.
3. Enable `onTypeMismatch` and `onAdapterCreationFailure`; confirm release builds still report field paths and expected types.
4. Configure `GsonSafeModelProbe` for key business models and confirm there are no `modelFieldObfuscationSuspected`, `modelConstructorUnavailable`, or `modelProbeFailure` checks.
5. If only the release build fails, check business model keep rules, Kotlin Metadata, constructors, GsonBuilder field names, and the `kotlin-reflect` version first.

## 8. Platform Objects

The default config skips `android.*` platform type fields so `View`, `ColorStateList`, and similar system objects are not handled by Safe Reflective.

Do not add business model package prefixes to `skippedPlatformTypePrefixes`, or matching business fields will be skipped.

```kotlin
SafeParserConfig(
    skippedPlatformTypePrefixes = setOf("android.") // Skips Android platform types; do not add business model package prefixes here.
)
```

Business model packages should be protected through ProGuard keep rules. They should not be added to `skippedPlatformTypePrefixes`.
