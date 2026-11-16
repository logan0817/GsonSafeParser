# GsonSafeParser

[中文](README.md)

[![Maven Central: core](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-core?label=core)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-core)
[![Maven Central: retrofit](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-retrofit?label=retrofit)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-retrofit)
[![CI](https://github.com/logan0817/GsonSafeParser/actions/workflows/ci.yml/badge.svg)](https://github.com/logan0817/GsonSafeParser/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**A Gson extension for Android: when the server sends an array for an object field, keep that field's default and continue parsing the other fields.**

For example, your model expects `"data":{"id":1}`, but an endpoint sometimes returns `"data":[]`. Native Gson may fail the entire parse. GsonSafeParser tries to isolate the problem to `data` and provides events that explain what happened.

**To install now, use 1.0.4 below.** The current source targets the [1.0.5 release candidate](docs/en/release-notes-1.0.5.md), which has not been published to Maven Central. Its new fixes are not included in 1.0.4.

## 1. Add one dependency

You need an Android project with `minSdk 23` or higher and JDK 17. Published artifacts are Android AARs; plain JVM projects cannot consume them as ordinary JARs. See [compatibility](docs/en/compatibility.md) for the full requirements.

Open the module file that will use the library, usually **`app/build.gradle.kts`**, and put one of the following alternatives inside `dependencies`.

**Choose core when you call Gson yourself:**

```kotlin
dependencies {
    implementation("io.github.logan0817:gson-safe-parser-core:1.0.4")
}
```

**Choose retrofit when Retrofit parses your API responses:**

```kotlin
dependencies {
    implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.4")
}
```

The retrofit module already includes core, so you do not need both. It also brings Retrofit and Gson dependencies; projects with an existing network stack should check [dependency compatibility](docs/en/compatibility.md#3-retrofit-version-notes).

Your repositories must include Maven Central, usually configured in the root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Keep the existing configuration if it already contains these entries. Save the files and run **Sync Project with Gradle Files** in Android Studio.

## 2. Try a complete example

After syncing, put this code in a Kotlin file in your Android module. It includes all imports, models, and a `main` function. You can also move the body of `main` into an existing test or application entry point.

Android does not run this `main` automatically. Call it from an existing unit test or debug entry point; passing the checks and matching the output below confirms this example works.

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.parseSafe

data class ApiResponse(
    val code: Int = 0,
    val data: User = User()
)

data class User(
    val id: Long = 0L,
    val name: String = "anonymous"
)

fun main() {
    // The model expects an object, but the server sent an array.
    val json = """{"code":200,"data":[]}"""
    val result = GsonSafeParser.parseSafe<ApiResponse>(json)
    val response = requireNotNull(result.value)

    check(response.code == 200)
    check(response.data == User())
    check(result.events.size == 1)

    println(response)
    println("Recovered fields: ${result.events.size}")
}
```

Expected output:

```text
ApiResponse(code=200, data=User(id=0, name=anonymous))
Recovered fields: 1
```

`code` is still `200`, and `data` keeps its model default. `result.events` records the field problem so you can find where the API response differs from the model.

If you only need the object, create a Gson instance with `GsonSafeParser.create()`:

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser

val gson = GsonSafeParser.create()
val response = gson.fromJson("""{"code":200,"data":[]}""", ApiResponse::class.java)
```

This snippet uses the `ApiResponse` model from the complete example. In an application, create and reuse a Gson instance rather than creating one for each parse.

## 3. Connect Retrofit

**Replace** your existing `.addConverterFactory(GsonConverterFactory.create(...))` with the factory below. Do not register both Gson converter factories: the first one may handle the response before safe parsing can run.

```kotlin
import io.github.logan.gsonsafeparser.retrofit.GsonSafeConverterFactory
import retrofit2.Retrofit

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create())
    .build()
```

Use your own API base URL. Keep your existing Retrofit interfaces and request calls.

If your project already configures a GsonBuilder, pass it to the factory:

```kotlin
import com.google.gson.GsonBuilder
import io.github.logan.gsonsafeparser.retrofit.GsonSafeConverterFactory
import retrofit2.Retrofit

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(
        GsonSafeConverterFactory.create(GsonBuilder().serializeNulls())
    )
    .build()
```

Passing an already-created ordinary Gson to the factory does not automatically add safe parsing. For that integration, empty responses, and event observation, see the Retrofit section of [Getting Started](docs/en/getting-started.md) and the [API reference](docs/en/api-reference.md).

## 4. Keep existing Gson settings

If your project creates Gson directly, add `.enableSafeParser()` to the **same Builder**, before `.create()`:

```kotlin
import com.google.gson.GsonBuilder
import io.github.logan.gsonsafeparser.enableSafeParser

val gson = GsonBuilder()
    .serializeNulls()
    .enableSafeParser()
    .create()
```

Keep your existing naming rules, adapters, and other settings, then continue using `gson.fromJson(...)`. The [API reference](docs/en/api-reference.md) covers entry-point choices, reusable parsers, and Java calls.

## 5. Know these five defaults

| Situation | Default behavior |
| --- | --- |
| An object, collection, or Map field has the wrong JSON structure | Tries to isolate the affected field and preserve its constructed default. Parsing can still fail if recovery cannot be done safely. |
| An `Int`, `Boolean`, `String`, or other basic value is invalid | Delegates to Gson, which may throw. Read [configuration](docs/en/configuration.md) before enabling safe primitive parsing. |
| A root object is expected, but the response is an array or number | Usually returns `null` instead of inventing a complete business object. Callers must still handle null. |
| JSON is damaged, a custom adapter throws, or a request fails or is cancelled | These errors should remain visible; the library does not promise to turn them into defaults. See the version notes below for known fixes to 1.0.4. |
| Retrofit receives an empty body | Ordinary models return `null` by default; Retrofit handles `Unit` / `Void` through built-in converters. See [empty response rules](docs/en/troubleshooting.md#1-empty-retrofit-responses). |

Objects and arrays are not converted into each other by default. If you need “take the first object from an array” or “wrap an object in a one-item collection,” read [shape-conversion configuration](docs/en/configuration.md) and the [supported cases](docs/en/mismatch-capability-matrix.md).

**Before production:** if your release uses R8 / ProGuard, follow [Android ProGuard](docs/en/android-proguard.md) to keep your business models. Successful debug parsing does not prove that a minified release retains field names and Kotlin defaults.

## Read next

| What you need | Documentation |
| --- | --- |
| Integrate with an existing project and follow complete examples | [Getting started](docs/en/getting-started.md) |
| Find entry points, events, Java calls, and report APIs | [API reference](docs/en/api-reference.md) |
| Configure defaults, primitive values, empty bodies, and logging | [Configuration](docs/en/configuration.md) |
| Check whether a particular error can be recovered | [Mismatch capability matrix](docs/en/mismatch-capability-matrix.md) |
| Check dependency versions or minification requirements | [Compatibility](docs/en/compatibility.md), [Android ProGuard](docs/en/android-proguard.md) |
| Diagnose missing interception, missing fields, or exceptions | [Troubleshooting](docs/en/troubleshooting.md) |
| Compare parsing results on an Android device | [Demo app](docs/en/demo-app.md), [Example index](examples/README_EN.md) |
| Contribute or maintain a release | [Contributing](CONTRIBUTING.md), [Release checklist](docs/en/release-checklist.md) |

Versions and fixes:

- [1.0.4 release notes](docs/en/release-notes-1.0.4.md): the version used by the dependency examples above.
- [1.0.5 candidate notes](docs/en/release-notes-1.0.5.md): local candidate artifacts, fixes, and migration guidance; not yet publicly released.
- [CHANGELOG](CHANGELOG.md): previous versions and fixes that have not been published under `Unreleased`.
- Syntax boundaries, item-level container recovery, adapter priority, and Retrofit reliability fixes in the current source target the unpublished 1.0.5 candidate. Validate real responses when using 1.0.4, then follow the release notes when upgrading after publication.

## Credits and license

The design and problem scenarios reference [getActivity/GsonFactory](https://github.com/getActivity/GsonFactory). See [NOTICE](NOTICE) for source and copyright details.

Licensed under [Apache License 2.0](LICENSE). See [SECURITY.md](SECURITY.md) for reporting security issues.
