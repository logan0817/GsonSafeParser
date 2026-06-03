# GsonSafeParser

[English](README_EN.md)

GsonSafeParser 是一个 Kotlin 优先的 Android Gson 扩展库，发布形式是 Android AAR。

它解决的主要问题是：后端某个字段的 JSON 形状不稳定时，原生 Gson 可能让整棵 Bean 解析失败。

GsonSafeParser 会尽量把问题隔离在当前字段，让外层对象继续解析。

它不会把错误数据悄悄吞掉。库会记录字段 path、期望形状、实际形状和兜底动作。

这些信息可以用来反馈后端，也可以用来在线上持续观察接口漂移。

## 核心能力

1. 字段级安全兜底：对象、集合、Map、基础类型出现 JSON 形状不一致时，只兜底当前字段，尽量保住外层 Bean。
2. 默认交回 Gson 原生 Adapter：Safe Adapter 创建失败、配置不完整或遇到无法确认的类型时，不由 SafeParser 改写读取行为。
3. Kotlin 友好：支持 Kotlin data class 默认值、reified API、`parseSafe<T>()` 和 `fromJsonSafe<T>()`。
4. Retrofit 接入：提供 `GsonSafeConverterFactory`，支持空响应策略和 raw JSON 捕获上限。
5. 契约证据：通过 `SafeParserEvent`、`TypeMismatchEvent`、`contractReport()` 和 `toBackendMarkdown()` 输出字段 path、期望形状、实际形状、兜底动作、客户端影响和后端修复建议。
6. Demo App：内置 Android 示例应用，可粘贴业务 JSON，在真机上对比 GsonSafeParser 和原生 Gson 解析结果。

## 默认行为

当前开箱默认配置：

| 配置 | 默认值 |
| --- | --- |
| `fallbackPolicy` | `FallbackPolicy.NullOnly` |
| `primitiveParsingPolicy` | `PrimitiveParsingPolicy.DelegateToGson` |
| `emptyResponsePolicy` | `EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly` |
| `useJdkUnsafe` | `false` |
| `requiredConstructorParameterPolicy` | `RequiredConstructorParameterPolicy.GsonCompatible` |
| `mapItemKeyPolicy` | `MapItemKeyPolicy.Hash` |

构造策略默认优先兼容原生 Gson。老项目先保持 `GsonCompatible + useJdkUnsafe = false`；如果确实要贴近原生 Gson 的 Unsafe 构造行为，可以显式开启 `useJdkUnsafe`；如果要把缺字段当成契约错误，改用 `Strict`。完整关系见 [配置说明](docs/configuration.md)。

固定行为：

| 场景 | 实际 JSON | 处理结果 |
| --- | --- | --- |
| `Object` 字段 | `[]`、`""`、`1` | 字段形状不一致默认返回 `null` 或保留构造默认值，外层对象继续解析。 |
| 顶层 `Object` | `[]`、`""`、`1` | 顶层 JSON 不是对象时通常返回 `null`；不可恢复 Gson 异常会继续抛出。 |
| `String` 字段 | `[]`、`{}` | 字段读取失败时保留构造默认值；根级交回 Gson 原生 Adapter。 |

`FallbackPolicy`（默认：`FallbackPolicy.NullOnly`）：

| 目标类型 | 实际 JSON | `FallbackPolicy.NullOnly`（默认） | `FallbackPolicy.Default` |
| --- | --- | --- | --- |
| List / Set | `{}`、`""` | 返回 `null`。 | 返回空集合。 |
| Map | `[]`、`""` | 返回 `null`。 | 返回空 Map。 |

说明：作为有构造默认值的反射字段时，字段会保留原默认值，不会被 `NullOnly` 的 `null` 覆盖；顶层解析或没有构造默认值的字段仍按表格返回 `null`。

`PrimitiveParsingPolicy`（默认：`PrimitiveParsingPolicy.DelegateToGson`）：

| 目标类型 | 实际 JSON | `PrimitiveParsingPolicy.DelegateToGson`（默认） | `PrimitiveParsingPolicy.Safe` |
| --- | --- | --- | --- |
| Int / Long / Boolean | `{}`、`[]`、`""` | 交回 Gson 原生 Adapter。 | 使用安全基础值。 |

`EmptyResponsePolicy`（默认：`EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly`）：

| 场景 | 响应内容 | `DefaultValueForUnitOrVoidOnly`（默认） | `DefaultValue` | `Null` | `DelegateToGson` |
| --- | --- | --- | --- | --- | --- |
| Retrofit `Unit` / `Void` 空 body | 空响应体 | `Unit` 返回 `Unit`，`Void` 返回 `null`。 | 返回各自空值。 | 返回 `null`。 | 返回 Retrofit 空值 `Unit` / `null`，不向 Gson 请求 delegate。 |
| Retrofit 业务模型空 body | 空响应体 | 返回 `null`。 | 返回默认对象。 | 返回 `null`。 | 通常会得到 `EOFException`。 |

## 使用安装

发布产物是 Android AAR，使用 JDK 17 编译。业务工程请使用 JDK 17 或更高版本。

正式接入前先看 [兼容性说明](docs/compatibility.md)。当前验证矩阵是：`minSdk 23`、`compileSdk 36`、`JDK 17`、`Kotlin 2.0.21`、`kotlin-reflect 2.0.21`、`Gson 2.13.2`；Retrofit 模块当前验证版本是 `Retrofit 2.8.1`。

版本号以徽章为准。普通 Gson 或手动持有 Gson 实例时，只依赖 core：

最新版本：[![Maven Central: core](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-core?label=core)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-core)

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.1") // 接入 GsonSafeParser 核心解析能力。
```

如果项目使用 Retrofit，只依赖 retrofit 模块即可；它会传递带上 core：

最新版本：[![Maven Central: retrofit](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-retrofit?label=retrofit)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-retrofit)

```kotlin
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.1") // 接入 Retrofit Converter 扩展，并自动带上 core。
```

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
val gson = GsonSafeParser.create() // 创建带安全解析能力的 Gson 实例。
val response = gson.fromJson(json, ApiResponse::class.java) // 使用安全 Gson 解析接口响应。
```

原生 Gson 遇到 `data` 期望对象却收到 `[]` 时会抛异常；GsonSafeParser 会兜底 `data` 字段，并继续解析外层 `code`。

兜底边界先看这张表：

| 问题类型 | 默认处理 |
| --- | --- |
| 字段级 JSON 形状不一致 | 只兜底当前字段，外层对象继续解析。 |
| JSON 语法错误 | 继续抛出，不伪装成默认值。 |
| 根级解析失败 | 继续遵循 Gson 边界，不能保证字段级隔离。 |
| 不可安全隔离异常 | `Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 继续外抛。 |

字段级 Adapter 读取失败如果能被当前字段边界隔离，会产生事件并保留外层对象解析。

入口选择也要分清：

| 你想要什么 | 推荐入口 |
| --- | --- |
| 只要字段级安全解析 | `GsonSafeParser.create()` 或 `GsonBuilder.enableSafeParser()`。 |
| 要解析结果加事件快照 | `GsonSafeParser.parseSafe<T>()` 或 `parser.parseSafe<T>()`。 |
| 要高频复用同一配置 | `GsonSafeParser.parser(config)`。 |
| 已有外部 Gson | 先对同一个 `GsonBuilder` 调用 `.enableSafeParser(config)`，再用 `parserWithExternalGson(gson, config)`。 |
| Retrofit 响应转换 | `GsonSafeConverterFactory.create(...)`。 |

直接调用 `gson.fromJson(...)` 时，最外层仍保持 Gson 原生异常包装行为。

这样做是为了不替换 Gson 本体，也避免扩展层改变调用方已经依赖的 Gson 语义。

Kotlin 便捷 API：

```kotlin
val response = GsonSafeParser.fromJsonSafe<ApiResponse>(json) // 直接解析并返回业务对象。
val result = GsonSafeParser.parseSafe<ApiResponse>(json) // 解析并同时返回事件列表。

println(result.value)
println(result.events)
println(result.contractReport().toMarkdown())
println(result.contractReport().toBackendMarkdown())
println(result.contractReport().summary.warningCount)
println(result.contractReport().toStructuredRows().firstOrNull()?.stableKey)
```

如果你要高频重复解析同一套接口，先创建一次可复用 Parser 更合适：

```kotlin
val parser = GsonSafeParser.parser(config) // 只创建一次安全 Parser，后续反复复用同一个 Gson。
val value = parser.fromJsonSafe<ApiResponse>(json)
val result = parser.parseSafe<ApiResponse>(json)
```

## Retrofit 接入

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create()) // 注册 GsonSafeParser 的响应转换器。
    .build()
```

如果需要自定义空响应或观测策略：

```kotlin
val config = SafeParserConfig.production(
    observerPolicy = SafeObserverPolicy(
        onEvent = { event -> println(event) } // 接收并上报解析事件。
    )
)

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(config)) // 使用自定义配置注册转换器。
    .build()
```

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
val config = SafeParserConfig.debug() // 既控制 Safe Gson，也控制 Retrofit 层的空响应和观测策略。
val gson = GsonBuilder()
    .serializeNulls()
    .enableSafeParser(config) // 把同一份 SafeParserConfig 注册到 Gson。
    .create()

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(gson, config)) // 同时复用已有 Gson 和 Retrofit 层 SafeParserConfig。
    .build()
```

这里容易误解，按当前情况选入口就行：

| 当前情况 | 推荐写法 | 原因 |
| --- | --- | --- |
| 还在配置 `GsonBuilder` | `GsonSafeConverterFactory.create(builder, config)` | 工厂会在 `builder.create()` 前注册 Safe Adapter。 |
| 已经有统一维护的 `Gson` | 先在创建它的 `GsonBuilder` 上调用 `.enableSafeParser(config)`，再传给 `create(gson, config)` | `Gson` 创建后配置已经固定，库不会偷偷改这个实例。 |
| 只写 `create(gson, config)` | 只复用这份 `Gson`，并使用 Retrofit 层的空响应、raw JSON 和事件配置 | 这不会自动给外部 Gson 注册 Safe Adapter。 |

如果不确定外部 `Gson` 是否已经启用字段级安全解析，可以用 `GsonSafeParser.diagnostics(gson)` 检查。

## 常用配置

```kotlin
val config = SafeParserConfig(
    fallbackPolicy = FallbackPolicy.NullOnly, // 字段形状不一致时返回 null 或保留构造默认值。
    emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly, // Retrofit 空响应只为 Unit/Void 返回空值。
    primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson, // 基础类型交回 Gson 原生 Adapter。
    skippedPlatformTypePrefixes = setOf("android."), // 跳过 Android 平台类型，避免反射系统对象；不要把业务模型包名前缀放这里。
    nullValuePolicy = NullValuePolicy.WriteExplicitNulls, // 显式 JSON null 只写入 nullable 字段。
    requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.GsonCompatible, // Kotlin 非空必填构造参数缺失时保持 Gson 兼容。
    mapItemKeyPolicy = MapItemKeyPolicy.Hash, // Map item 事件默认输出稳定哈希。
    captureRawJsonInCallbacks = false, // 线上默认不在事件中携带原始 JSON。
    maxRawJsonCaptureBytes = 1024 * 1024, // 限制 raw JSON 最大捕获体积为 1 MiB。
    onEvent = { event -> println(event) }, // 监听统一解析事件。
    onTypeMismatch = { event ->
        println("${event.path}: ${event.actualToken} -> ${event.expectedType}")
    } // 输出字段路径、实际 token 和期望类型。
)
```

预设配置：

```kotlin
val production = SafeParserConfig.production() // 线上默认配置。
val debug = SafeParserConfig.debug() // 联调配置，默认开启 raw JSON 捕获。
val lowInterference = SafeParserConfig.lowInterference() // 低干预配置，行为更接近原生 Gson。
```

| 预设 | 适合场景 | 主要行为 | 风险取舍 |
| --- | --- | --- | --- |
| `production()` | 正式上线默认配置。 | 开启事件观测，Map item key 默认哈希，不携带整段 raw JSON。 | 排障信息够用，长期内存和隐私风险更低。 |
| `debug()` | 联调、测试、接口排障。 | 和线上读策略一致，但会在上限内携带 raw JSON。 | 更容易定位问题，不建议长期用于线上。 |
| `lowInterference()` | 灰度接入、低干预优先。 | 字段、集合、Map 整体形状不一致优先返回 `null`，基础类型交回 Gson 原生 Adapter。 | 更接近原生 Gson，但安全默认值更少。 |

## 注解

```kotlin
@SafeParseDelegateToGson // 让这个类型直接使用 Gson 原生 Adapter。
class StrictModel

data class PageState(
    @field:SafeParseSkip // 告诉 Safe Reflective 跳过这个字段。
    val runtimeCache: Any? = null
)
```

1. `@SafeParseDelegateToGson` 标在类上，表示该类型直接交给 Gson 原生 Adapter。
2. `@SafeParseSkip` 标在字段上，表示 Safe Reflective 不读写该字段，适合运行时状态、缓存字段或平台对象。

## Demo App

仓库内置 `demo-app`，用于真机验证库能力：

```bash
./gradlew :demo-app:assembleDebug # 构建 debug 版本 Demo App。
./gradlew :demo-app:installDebug # 把 debug Demo App 安装到已连接设备。
adb shell am start -n io.github.logan.gsonsafeparser.demo/.MainActivity # 启动 Demo App 首页。
```

Demo App 支持内置用例和用户自定义 JSON。你可以把接口返回直接粘贴进去，对比 GsonSafeParser 和原生 Gson 的解析结果、事件流和接入建议。

## 文档

建议阅读顺序：先看 [快速开始](docs/getting-started.md)，再看 [兼容性说明](docs/compatibility.md)、[配置说明](docs/configuration.md) 和 [错形能力矩阵（JSON 形状不一致）](docs/mismatch-capability-matrix.md)；如果是 Android release 接入，再补看 [Android 混淆](docs/android-proguard.md)。

1. [快速开始](docs/getting-started.md)：安装、普通 Gson、Retrofit、Kotlin API 和 CI 自检。
2. [错形能力矩阵（JSON 形状不一致）](docs/mismatch-capability-matrix.md)：对象、集合、Map、基础类型、Kotlin 默认值、Retrofit 空响应和 raw JSON 捕获的处理范围。
3. [兼容性说明](docs/compatibility.md)：Android、JDK、Kotlin、Gson、Retrofit 和 R8 的版本边界。
4. [配置说明](docs/configuration.md)：配置项、预设策略、事件流、注解和默认行为。
5. [Android 混淆](docs/android-proguard.md)：新项目接入、老项目快速接入、R8 fullMode 选择和 release 验证。
6. [Demo App](docs/demo-app.md)：真机测试方式、页面说明和用户 JSON 验证入口。
7. [排障指南](docs/troubleshooting.md)：空响应、raw JSON、Adapter 创建失败、平台对象和业务协议问题。
8. [发布清单](docs/release-checklist.md)：1.0.1 发版前的 AAR、混淆、文档版本和 Maven 本地产物检查。
9. [1.0.1 发布说明](docs/release-notes-1.0.1.md)：本次稳定性修正、兼容边界和发布验证说明。
10. [1.0.0 发布说明](docs/release-notes-1.0.0.md)：首发能力、兼容边界和发布验证说明。

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

当前仓库代码以维护者主导、AI 辅助重构的方式持续演进，最终结果由维护者审核、调整并验证。AI 使用情况单独说明如下：

1. ChatGPT Codex：用于重构、测试补强、文档整理和自查。
2. DeepSeek DeepSeek-V4-Pro：用于重构辅助、文档润色和问题复核。

项目在设计、问题场景梳理、README 审阅和 issue 自查阶段参考过公开项目 [getActivity/GsonFactory](https://github.com/getActivity/GsonFactory)，相关许可证、原版权声明和更完整的 AI 透明说明见 [NOTICE](NOTICE)。

## License

Apache License 2.0
