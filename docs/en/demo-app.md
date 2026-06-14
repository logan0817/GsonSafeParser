# Demo App

[中文](../demo-app.md)

The repository includes `demo-app` for validating GsonSafeParser's public behavior in a real Android screen. It is not a marketing page. It is a test tool where you can paste JSON, run cases, inspect parse results, and inspect event details.

## 1. Build And Install

```bash
./gradlew :demo-app:assembleDebug
./gradlew :demo-app:installDebug
adb shell am start -n io.github.logan.gsonsafeparser.demo/.MainActivity
```

These commands build the debug Demo, install it on a connected device, and open the Demo main screen.

To verify the Android ProGuard example, run:

```bash
./gradlew :demo-app:assembleRelease
```

This command builds the release Demo App with R8 minify enabled.

Debug APK path:

```text
demo-app/build/outputs/apk/debug/demo-app-debug.apk
```

## 2. Screen Structure

| Screen | Purpose |
| --- | --- |
| Quick check | Runs all built-in cases and confirms whether the Demo and library behavior are healthy. |
| Custom JSON validation | Paste API responses, select target types and strategies, and compare GsonSafeParser with native Gson. |
| Core parsing | Validates objects, collections, Map, primitives, and Kotlin APIs. |
| Configuration and integration | Validates presets, layered policies, Builder passthrough, and annotations. |
| Observability | Shows event streams, contract reports, diagnostics, and observer failure reports. |
| Retrofit | Validates Converter behavior, empty-response policies, raw JSON capture, and request body conversion. |

## 3. Custom JSON Validation

Users can paste an API response into the input box and select:

1. Entry point: `Core fromJson` or `Retrofit Converter`.
2. Target type: for example `ApiResponse<User>`, `List<User>`, `MapResponse`, `AnyResponse`, or `OrgJsonResponse`.
3. Parse strategy: contract-first default, low-interference, debug raw JSON, primitive-to-Gson delegation, and others.

After running, the screen shows:

1. Input JSON.
2. GsonSafeParser parse result.
3. Native Gson comparison result.
4. Event stream, contract summary, backend report, `stableKey`, and structured rows.
5. Integration suggestions and exception details.

The Android Demo can only validate built-in target types. It cannot generate arbitrary business Beans at runtime. Custom JSON validation is useful for quick checks against common API shapes; real business models still need tests inside the business project.

If the integration self-check or a business model probe reports suspected model field obfuscation, first check whether the release build keeps field names for real response model packages.

Legacy projects should start with broad keep rules for bean, model, entity, response, and dto packages.

After behavior is stable, use modelProbes and real JSON in the business project to narrow the scope gradually.

## 4. Device Acceptance Suggestions

1. Start with "Quick check" and run all built-in cases. Confirm the failure count is 0.
2. Then open "Custom JSON validation", paste a real API response, and select the closest target type.
3. If native Gson fails but GsonSafeParser succeeds, inspect event paths and fallback values to confirm they match business expectations.
4. If both fail, check the exception details first, then decide whether a custom Adapter or model adjustment is needed.
5. For release acceptance, compare plain field-name models, `@SerializedName` models, defaults, and callback events with the same JSON.
6. If the result needs to be shared with collaborators, use the copy action on the screen to export the current result.
