# GsonSafeParser

[English](README_EN.md)

[![Maven Central: core](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-core?label=core)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-core)
[![Maven Central: retrofit](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-retrofit?label=retrofit)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-retrofit)
[![CI](https://github.com/logan0817/GsonSafeParser/actions/workflows/ci.yml/badge.svg)](https://github.com/logan0817/GsonSafeParser/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

GsonSafeParser 是一个 Kotlin 优先的 Android Gson 扩展库，发布形式是 Android AAR。

它解决的主要问题是：后端某个字段的 JSON 形状不稳定时，原生 Gson 可能让整棵 Bean 解析失败。

GsonSafeParser 会尽量把问题隔离在当前字段，让外层对象继续解析。

它不会把错误数据悄悄吞掉。库会记录字段 path、期望形状、实际形状和兜底动作。

这些信息可以用来反馈后端，也可以用来在线上持续观察接口漂移。

## 30 秒接入

如果你只想先验证字段级兜底，先加 core 依赖：

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.4")
```

然后在业务代码里创建 Gson：

```kotlin
val gson = GsonSafeParser.create()
val response = gson.fromJson(json, ApiResponse::class.java)
```

如果要同时拿到本次兜底事件：

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.contractReport
import io.github.logan.gsonsafeparser.parseSafe

val result = GsonSafeParser.parseSafe<ApiResponse>(json)
println(result.value)
println(result.contractReport().toBackendMarkdown())
```

如果项目使用 Retrofit：

```kotlin
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.4")
```

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create())
    .build()
```

第一次接入建议按这个顺序读：1. [快速开始](docs/getting-started.md) 2. [API 参考](docs/api-reference.md) 3. [错形能力矩阵](docs/mismatch-capability-matrix.md) 4. [Android 混淆](docs/android-proguard.md)。

## 一句话判断是否适合你

| 你的场景 | 是否适合 | 建议入口 |
| --- | --- | --- |
| Android 项目里 Gson 解析因为字段错形崩溃 | 适合 | `GsonSafeParser.create()` 或 `GsonBuilder.enableSafeParser()` |
| Retrofit 响应偶发字段错形或空 body | 适合 | `GsonSafeConverterFactory.create()` |
| 想保留 Gson 原有配置，只补字段级安全解析 | 适合 | 对同一个 `GsonBuilder` 调用 `.enableSafeParser(config)` |
| 纯 JVM 项目，不消费 Android AAR | 暂不适合 | 当前发布物是 Android AAR |
| 想把 JSON 语法错误、断网、取消请求都变成默认值 | 不适合 | 这类问题会继续交回 Gson、Retrofit 或 OkHttp |

## 核心能力

1. 字段级安全兜底：对象、集合、Map 出现 JSON 形状不一致时，只兜底当前字段，尽量保住外层 Bean；基础类型默认交回 Gson，显式配置 `PrimitiveParsingPolicy.Safe` 后才启用安全基础值。
2. 默认交回 Gson 原生 Adapter：Safe Adapter 创建失败、配置不完整或遇到无法确认的类型时，不由 SafeParser 改写读取行为。
3. Kotlin 友好：支持 Kotlin data class 默认值、reified API、`parseSafe<T>()` 和 `fromJsonSafe<T>()`。
4. Retrofit 接入：提供 `GsonSafeConverterFactory`，支持空响应策略和 raw JSON 捕获上限。
5. 显式形态转换：可选择把对象字段收到的数组取第 1 个对象，或把集合/数组字段收到的对象包装成单元素容器。
6. 契约证据：输出字段 path、期望形状、实际形状和兜底动作；也可以生成给后端看的 Markdown 报告。
7. Demo App：内置 Android 示例应用，可粘贴业务 JSON，在真机上对比 GsonSafeParser 和原生 Gson 解析结果。

## 默认行为

默认配置适合直接接入已有 Gson 项目。库只处理能安全隔离的字段问题；基础类型、根级异常和不可确认的问题继续交回 Gson。

### 默认配置

| 配置 | 默认值 |
| --- | --- |
| `fallbackPolicy` | `FallbackPolicy.NullOnly` |
| `primitiveParsingPolicy` | `PrimitiveParsingPolicy.DelegateToGson` |
| `emptyResponsePolicy` | `EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly` |
| `useJdkUnsafe` | `false` |
| `requiredConstructorParameterPolicy` | `RequiredConstructorParameterPolicy.GsonCompatible` |
| `mapItemKeyPolicy` | `MapItemKeyPolicy.Omit` |

### 可选能力状态

| 能力 | 默认状态 | 启用方式 |
| --- | --- | --- |
| JSON 形态转换 | `ShapeCoercionPolicy.Disabled` | 调用 `withShapeCoercionPolicy(...)`，或在字段上使用 `@SafeParseShapeCoercion`。 |

### 构造策略

默认使用 `GsonCompatible + useJdkUnsafe = false`。这组配置保持 Gson 兼容，同时避免 SafeParser 自己用 Unsafe 绕过构造函数。

| 目标 | 推荐配置 |
| --- | --- |
| 直接接入已有项目 | 保持默认配置。 |
| 项目明确依赖原生 Gson 的 Unsafe 构造行为 | 使用 `GsonCompatible + useJdkUnsafe = true`。 |
| 把缺字段、`null` 或未知枚举值当成接口契约错误 | 使用 `Strict + useJdkUnsafe = false`。 |

完整配置见 [配置说明](docs/configuration.md)。

### 固定边界

| 场景 | 实际 JSON | 处理结果 |
| --- | --- | --- |
| `Object` 字段 | `[]`、`""`、`1` | 字段形状不一致默认返回 `null` 或保留构造默认值，外层对象继续解析。 |
| 顶层 `Object` | `[]`、`""`、`1` | 顶层 JSON 不是对象时通常返回 `null`；不可恢复 Gson 异常会继续抛出。 |
| 基础类型 / `String` 字段 | `{}`、`[]` | 默认交回 Gson 原生 Adapter；读取失败会按 Gson 原生异常外抛，不产生 SafeParser 事件。 |

`FallbackPolicy`（默认：`FallbackPolicy.NullOnly`）：

| 目标类型 | 实际 JSON | `FallbackPolicy.NullOnly`（默认） | `FallbackPolicy.Default` |
| --- | --- | --- | --- |
| List / Set | `{}`、`""` | 返回 `null`。 | 返回空集合。 |
| Map | `[]`、`""` | 返回 `null`。 | 返回空 Map。 |

说明：字段有构造默认值时，会优先保留默认值。顶层解析或没有默认值的字段，仍按表格返回 `null`。

`PrimitiveParsingPolicy`（默认：`PrimitiveParsingPolicy.DelegateToGson`）：

| 目标类型 | 实际 JSON | `PrimitiveParsingPolicy.DelegateToGson`（默认） | `PrimitiveParsingPolicy.Safe` |
| --- | --- | --- | --- |
| Int / Long / Boolean / String | `{}`、`[]`、非法字符串 | 交回 Gson 原生 Adapter，失败时外抛。 | 使用安全基础值或保留构造默认值，并记录事件。 |

`EmptyResponsePolicy`（默认：`EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly`）：

| 场景 | 响应内容 | `DefaultValueForUnitOrVoidOnly`（默认） | `DefaultValue` | `Null` | `DelegateToGson` |
| --- | --- | --- | --- | --- | --- |
| Retrofit `Unit` / `Void` 空 body | 空响应体 | `Unit` 返回 `Unit`，`Void` 返回 `null`。 | 返回各自空值。 | 返回 `null`。 | 返回 Retrofit 空值 `Unit` / `null`，不向 Gson 请求 delegate。 |
| Retrofit 业务模型空 body | 空响应体 | 返回 `null`。 | 返回默认对象。 | 返回 `null`。 | 通常会得到 `EOFException`。 |

## 使用安装

发布产物是 Android AAR，使用 JDK 17 编译。业务工程请使用 JDK 17 或更高版本。

正式接入前先看 [兼容性说明](docs/compatibility.md)。

当前验证矩阵：`minSdk 23`、`compileSdk 36`、`JDK 17`、`Kotlin 2.0.21`、`kotlin-reflect 2.0.21`、`Gson 2.13.2`。Retrofit 模块验证版本是 `Retrofit 2.8.1`。

版本号以徽章为准。普通 Gson 或手动持有 Gson 实例时，只依赖 core：

最新版本：[![Maven Central: core](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-core?label=core)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-core)

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.4")
```

如果项目使用 Retrofit，只依赖 retrofit 模块即可；它会传递带上 core：

最新版本：[![Maven Central: retrofit](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-retrofit?label=retrofit)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-retrofit)

```kotlin
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.4")
```

Retrofit 模块仍保持 `Retrofit 2.8.1` API 兼容，同时会以运行时依赖提供 `OkHttp 4.12.0` 和 `Okio 3.6.0` 安全基线，避免 Retrofit 2.8.1 的旧传递依赖落回 OkHttp 3.14.x / Okio 1.x。接入已有网络栈时，先用 `./gradlew dependencyInsight --dependency okhttp` 和 `./gradlew dependencyInsight --dependency okio` 确认依赖解析结果，再跑断网、取消、连接重置、TLS 失败和 raw JSON 捕获回归。

Android release 额外要求：

| 场景 | 需要做什么 |
| --- | --- |
| debug 或未开启 minify | 可以先零配置试用。 |
| release 开启 R8 / ProGuard | 业务模型仍要按 [Android 混淆](docs/android-proguard.md) 配置 keep 规则。 |
| 老项目模型分散 | 先用宽范围包级 keep 保住字段名、构造方法和 Kotlin 默认值，稳定后再收窄。 |
| 只加了 `@SerializedName` | 只能固定 JSON 字段名，不能替代 Kotlin Metadata 和构造方法 keep。 |

## 快速开始

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser

data class ApiResponse(
    val code: Int = 0,
    val data: User? = null
)

data class User(
    val id: Long = 0L,
    val name: String = ""
)

val json = """{"code":200,"data":[]}"""
val gson = GsonSafeParser.create()
val response = gson.fromJson(json, ApiResponse::class.java)
```

原生 Gson 遇到 `data` 期望对象却收到 `[]` 时会抛异常；GsonSafeParser 会兜底 `data` 字段，并继续解析外层 `code`。

兜底边界先看这张表：

| 问题类型 | 默认处理 |
| --- | --- |
| 字段级 JSON 形状不一致 | 只兜底当前字段，外层对象继续解析。 |
| JSON 语法错误 | 继续抛出，不伪装成默认值。 |
| 根级解析失败 | 继续遵循 Gson 边界，不能保证字段级隔离。 |
| Retrofit 网络或传输读流失败 | 交回 Retrofit / OkHttp 错误处理，不记录成字段错形或空响应，也不能用 `emptyResponsePolicy` 隐藏。 |
| 不可安全隔离异常 | `Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 继续外抛。 |

SafeParser 内建 Adapter 读取到可隔离的字段错形时，会产生事件并保留外层对象解析。调用方通过 `registerTypeAdapter(...)`、`registerTypeAdapterFactory(...)`、`registerTypeHierarchyAdapter(...)` 或 `@JsonAdapter` 显式接管的类型，会优先走原生 Gson 链路；这些自定义 Adapter 自己抛出的异常会向外抛出，不会被伪装成字段兜底。

入口选择也要分清：

| 你想要什么 | 推荐入口 |
| --- | --- |
| 只要字段级安全解析 | `GsonSafeParser.create()` 或 `GsonBuilder.enableSafeParser()`。 |
| 要解析结果加事件快照 | `GsonSafeParser.parseSafe<T>()` 或 `parser.parseSafe<T>()`。 |
| 要高频复用同一配置 | `GsonSafeParser.parser(config)`。 |
| 已有外部 Gson | 先对同一个 `GsonBuilder` 调用 `.enableSafeParser(config)`，再用 `parserWithExternalGson(gson, config)`。 |
| Retrofit 响应转换 | `GsonSafeConverterFactory.create(...)`。 |

默认 `GsonSafeParser.create(config)` 不读取 `GsonBuilder` 内部字段。只有 builder-first 入口需要读取这些内部字段，用来继承调用方已经放进 Builder 的 `InstanceCreator`、`ReflectionAccessFilter`、Object 数字策略、复杂 Map key 和 Unsafe 开关。强制覆盖 Gson 版本后，先跑 `GsonSafeParser.diagnostics()`，它会按字段报告 critical / optional 兼容性。

直接调用 `gson.fromJson(...)` 时，最外层仍保持 Gson 原生异常包装行为。

这样做是为了不替换 Gson 本体，也避免扩展层改变调用方已经依赖的 Gson 语义。

Kotlin 便捷 API：

```kotlin
val response = GsonSafeParser.fromJsonSafe<ApiResponse>(json)
val result = GsonSafeParser.parseSafe<ApiResponse>(json)

println(result.value)
println(result.events)
println(result.contractReport().toMarkdown())
println(result.contractReport().toBackendMarkdown())
println(result.contractReport().summary.warningCount)
println(result.contractReport().toStructuredRows().firstOrNull()?.stableKey)
```

`fromJsonSafe<T>()` 直接返回业务对象，`parseSafe<T>()` 会同时返回解析事件，适合日志、监控和契约报告。

如果你要高频重复解析同一套接口，先创建一次可复用 Parser 更合适：

```kotlin
val parser = GsonSafeParser.parser(config)
val value = parser.fromJsonSafe<ApiResponse>(json)
val result = parser.parseSafe<ApiResponse>(json)
```

`parser(config)` 只创建一次安全 Parser，后续会复用同一个 Gson，适合 Repository、数据源和批量解析场景。

## JSON 形态转换

默认情况下，GsonSafeParser 不会把对象和数组互相转换。JSON 形态转换的默认状态是 `ShapeCoercionPolicy.Disabled`，所以 1.0.4 不会改变旧版本的默认解析结果。

这个能力只适合后端字段形态不稳定、但业务上可以接受恢复规则的场景：

| 代码字段 | 后端实际 JSON | 开启后处理 |
| --- | --- | --- |
| `data: User` | `"data":[{"id":1}]` | 读取数组第 1 个对象赋给 `data`。 |
| `users: List<User>` | `"users":{"id":1}` | 包装成只有 1 个元素的 List。 |
| `users: Array<User>` | `"users":{"id":1}` | 包装成长度为 1 的数组。 |

全局开启：

```kotlin
val config = SafeParserConfig()
    .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)

val gson = GsonSafeParser.create(config)
```

只给某个字段开启：

```kotlin
data class ApiResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val data: User? = null
)
```

全局开启后，也可以禁止某个强契约字段转换：

```kotlin
data class StrictEnvelope(
    @field:SafeParseDisableShapeCoercion
    val signedPayload: SignedPayload = SignedPayload()
)
```

如果 `errors: List<ApiError>` 本身就是后端 object/array 混合字段，不要禁用它；应使用 `CollectionFromSingleObject` 或 `ObjectAndCollection`。

边界很明确：根级对象、根级集合、根级对象数组、Map、字符串二次解析、数字和布尔值不会参与转换。空数组、数组首项不是对象、转换时 Adapter 失败都会记录 `ShapeCoercion` 事件并回到原兜底行为。`Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 和真实传输 I/O 仍然外抛。

## Retrofit 接入

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create())
    .build()
```

如果需要自定义空响应或观测策略：

```kotlin
val config = SafeParserConfig.production(
    observerPolicy = SafeObserverPolicy(
        onEvent = { event -> println(event) }
    )
)

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(config))
    .build()
```

`onEvent` 会接收统一解析事件，适合接入日志、监控或契约报告链路。

如果项目里已经统一维护了 GsonBuilder，推荐使用 builder-first 入口：

```kotlin
val config = SafeParserConfig.debug()

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(GsonBuilder().serializeNulls(), config))
    .build()
```

如果项目里已经统一维护的是创建好的 Gson，同时还想保留 Retrofit 层的空响应、rawJson 和事件策略，可以使用：

```kotlin
val config = SafeParserConfig.debug()
val gson = GsonBuilder()
    .serializeNulls()
    .enableSafeParser(config)
    .create()

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(gson, config))
    .build()
```

这段写法同时复用已有 Gson，并让 Retrofit 层继续使用同一份空响应、raw JSON 和事件策略。

这里容易误解，按当前情况选入口就行：

| 当前情况 | 推荐写法 | 原因 |
| --- | --- | --- |
| 还在配置 `GsonBuilder` | `GsonSafeConverterFactory.create(builder, config)` | 工厂会在 `builder.create()` 前注册 Safe Adapter。 |
| 已经有统一维护的 `Gson` | 先在创建它的 `GsonBuilder` 上调用 `.enableSafeParser(config)`，再传给 `create(gson, config)` | `Gson` 创建后配置已经固定，库不会偷偷改这个实例。 |
| 只写 `create(gson, config)` | 只复用这份 `Gson`，并使用 Retrofit 层的空响应、raw JSON 和事件配置 | 这不会自动给外部 Gson 注册 Safe Adapter。 |

如果不确定外部 `Gson` 是否已经启用字段级安全解析，可以用 `GsonSafeParser.diagnostics(gson)` 检查。

这里的默认 Retrofit `create(config)` 也会走低风险默认入口；只有 `create(builder, config)` 和 `.enableSafeParser(config)` 需要读取 Builder 内部字段来继承调用方配置。

## 常用配置

下面代码块只展示可复制写法。每个配置项的含义和修改场景见后面的表格。

```kotlin
val config = SafeParserConfig(
    fallbackPolicy = FallbackPolicy.NullOnly,
    emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly,
    primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson,
    skippedPlatformTypePrefixes = setOf("android."),
    nullValuePolicy = NullValuePolicy.WriteExplicitNulls,
    requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.GsonCompatible,
    mapItemKeyPolicy = MapItemKeyPolicy.Omit,
    captureRawJsonInCallbacks = false,
    maxRawJsonCaptureBytes = 1024 * 1024,
    onEvent = { event -> println(event) },
    onTypeMismatch = { event ->
        println("${event.path}: ${event.actualToken} -> ${event.expectedType}")
    }
)
```

预设配置：

```kotlin
val production = SafeParserConfig.production()
val debug = SafeParserConfig.debug()
val lowInterference = SafeParserConfig.lowInterference()
```

| 预设 | 适合场景 | 主要行为 | 风险取舍 |
| --- | --- | --- | --- |
| `production()` | 正式上线默认配置。 | 开启事件观测，默认不输出 Map item key，不携带整段 raw JSON。 | 排障信息够用，长期内存和隐私风险更低。 |
| `debug()` | 联调、测试、接口排障。 | 和线上读策略一致，但会在上限内携带 raw JSON。 | 更容易定位问题，不建议长期用于线上。 |
| `lowInterference()` | 灰度接入、低干预优先。 | 字段、集合、Map 整体形状不一致优先返回 `null`，基础类型交回 Gson 原生 Adapter。 | 更接近原生 Gson，但安全默认值更少。 |

## 注解

```kotlin
@SafeParseDelegateToGson
class StrictModel

data class PageState(
    @field:SafeParseSkip
    val runtimeCache: Any? = null
)

data class FlexibleResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val data: User? = null,

    @field:SafeParseDisableShapeCoercion
    val signedPayload: SignedPayload = SignedPayload()
)
```

1. `@SafeParseDelegateToGson` 标在类上，表示该类型直接交给 Gson 原生 Adapter。
2. `@SafeParseSkip` 标在字段上，表示 Safe Reflective 不读写该字段，适合运行时状态、缓存字段或平台对象。
3. `@SafeParseShapeCoercion` 标在字段上，表示这个字段允许按指定策略做对象和数组形态转换。
4. `@SafeParseDisableShapeCoercion` 标在字段上，表示即使全局开启形态转换，这个字段也保持原错形兜底行为。

## Demo App

仓库内置 `demo-app`，用于真机验证库能力：

```bash
./gradlew :demo-app:assembleDebug
./gradlew :demo-app:installDebug
adb shell am start -n io.github.logan.gsonsafeparser.demo/.MainActivity
```

这 3 个命令分别用于构建 debug 版 Demo、安装到已连接设备、启动 Demo 首页。

Demo App 支持内置用例和用户自定义 JSON。你可以把接口返回直接粘贴进去，对比 GsonSafeParser 和原生 Gson 的解析结果、事件流和接入建议。

## 文档

建议按场景阅读，不用从头读完整仓库文档。

| 场景 | 先读 | 再读 |
| --- | --- | --- |
| 第一次接入 | [快速开始](docs/getting-started.md) | [API 参考](docs/api-reference.md) |
| 判断兜底范围 | [错形能力矩阵（JSON 形状不一致）](docs/mismatch-capability-matrix.md) | [配置说明](docs/configuration.md) |
| Android release 上线 | [Android 混淆](docs/android-proguard.md) | [兼容性说明](docs/compatibility.md) |
| Retrofit 接入 | [快速开始](docs/getting-started.md) 的 Retrofit 部分 | [排障指南](docs/troubleshooting.md) |
| 真机体验 | [Demo App](docs/demo-app.md) | [examples](examples/README.md) |
| 贡献代码或反馈问题 | [贡献指南](CONTRIBUTING.md) | [安全策略](SECURITY.md) |
| 发版维护 | [发布清单](docs/release-checklist.md) | [CHANGELOG](CHANGELOG.md) |

完整文档索引：

| 分类 | 文档 |
| --- | --- |
| 入门 | [快速开始](docs/getting-started.md)、[API 参考](docs/api-reference.md)、[示例索引](examples/README.md) |
| 参考 | [配置说明](docs/configuration.md)、[错形能力矩阵](docs/mismatch-capability-matrix.md)、[兼容性说明](docs/compatibility.md)、[排障指南](docs/troubleshooting.md) |
| Android | [Android 混淆](docs/android-proguard.md)、[Demo App](docs/demo-app.md) |
| 开源协作 | [贡献指南](CONTRIBUTING.md)、[安全策略](SECURITY.md)、[行为准则](CODE_OF_CONDUCT.md) |
| 发布记录 | [1.0.4 发布说明](docs/release-notes-1.0.4.md)、[1.0.3 发布说明](docs/release-notes-1.0.3.md)、[1.0.2 发布说明](docs/release-notes-1.0.2.md)、[1.0.1 发布说明](docs/release-notes-1.0.1.md)、[1.0.0 发布说明](docs/release-notes-1.0.0.md) |

## 风险边界

GsonSafeParser 是 Gson 的增强层，不是新的 JSON 协议解释器。

它可以降低解析崩溃概率，并告诉你哪个字段出现了 JSON 形状不一致，但不会证明后端契约是正确的。

处理边界：

1. 能隔离到字段的 JSON 形状不一致，由库兜底并记录事件。
2. 不能安全隔离的问题，交回 Gson 原生 Adapter 或向外抛出。
3. Gson 版本差异、混淆信息缺失、配置不完整、Safe Adapter 创建失败时，库不应成为新的崩溃来源。
4. JSON 语法错误、根级解析失败和不可安全隔离异常不会被 `parseSafe` 静默吞掉。

## 致谢与来源说明

GsonSafeParser 是独立维护的 Kotlin 开源项目。

项目在设计、问题场景梳理、README 审阅和 issue 自查阶段参考过公开项目 [getActivity/GsonFactory](https://github.com/getActivity/GsonFactory)，相关许可证和原版权声明见 [NOTICE](NOTICE)。

## License

Apache License 2.0
