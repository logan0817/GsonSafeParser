# 排障指南

[English](en/troubleshooting.md)

本文档整理接入 GsonSafeParser 时最常见的问题，以及推荐处理方式。

## 先按现象找答案

| 你看到的现象 | 先做什么 |
| --- | --- |
| `parseSafe` / `enableSafeParser` 标红，提示找不到 | 它们是扩展函数，需要对应 `import`；先复制[快速开始](getting-started.md)的完整代码。Maven 坐标与代码包名不同。 |
| Retrofit 仍使用普通 Gson 报错 | 替换原 `GsonConverterFactory`，不要同时保留两个 Gson 转换器；再确认传入的外部 Gson 已启用 SafeParser。 |
| `Int` 收到 `"bad"` 仍报错 | 默认基础类型交回 Gson；需要业务明确接受恢复后，再看[基础类型配置](#5-基础类型想使用-safeparser-宽松解析)。 |
| 结果能用，却看不到日志 | 默认回调不打印日志；用 `parseSafe<T>().events` 读取本次事件，或配置 `onEvent`。恢复不代表后端数据正确。 |
| 回调中的 `rawJson` 为 `null` | 默认不记录原文；普通 `gson.fromJson` 也不会自动建立原文上下文，见[原文捕获](#2-回调里看不到原始-json)。 |
| 收到空结果，不知道是不是解析失败 | 区分 JSON `null`、根级错形、HTTP 空响应；先看[空响应规则](#1-retrofit-空响应)，不要直接写 `!!`。 |
| debug 正常，release 默认值或字段丢失 | 先查[业务模型混淆规则](#11-release-包行为和-debug-不一致)，再用同一份 JSON 对照。 |
| 输入被截断、转义损坏，或网络断开 | 走调用方错误处理；本库不会把这些问题转换成正常业务对象。 |

本文按当前源码说明；尚未发布的修复见 [Unreleased](../CHANGELOG.md#unreleased)。

## 1. Retrofit 空响应

空响应指响应体真实为空，不包含断网、取消请求、连接重置或 TLS 失败。

按业务选择策略：

```kotlin
SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly)
SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DefaultValue)
SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.Null)
SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DelegateToGson)
```

| 策略 | 普通业务模型空 body | 真实 Retrofit 服务的 `Unit` / `Void` |
| --- | --- | --- |
| `DefaultValueForUnitOrVoidOnly` | 返回 `null`。 | 返回 `Unit` / `null`。 |
| `DefaultValue` | 返回默认对象。 | 返回 `Unit` / `null`。 |
| `Null` | 返回 `null`。 | 返回 `Unit` / `null`。 |
| `DelegateToGson` | 交回 Gson，通常抛 `EOFException`。 | 返回 `Unit` / `null`。 |

`Unit` / `Void` 由 Retrofit 内置转换器优先处理，不进入 GsonSafeParser，也不派发其 `EmptyResponse` 事件。
HTTP 204/205 则在调用转换器前返回 `null`，对普通模型同样如此。上表的普通模型策略适用于会进入转换器的空响应（例如 HTTP 200）。
直接调用本库 `responseBodyConverter()` 的测试不代表这些服务入口的行为。

网络异常不走 `emptyResponsePolicy`：

| 场景 | 处理方式 |
| --- | --- |
| 断网、请求取消、连接重置、TLS 失败 | 交回 Retrofit / OkHttp 异常处理。 |
| `EmptyResponse`、`RawJsonCaptureSkipped`、`TypeMismatch` | 不记录这类事件。 |
| 业务处理 | 继续使用 App 原有网络异常流程。 |

## 2. 回调里看不到原始 JSON

默认不开启原始 JSON 捕获，避免大响应额外占用内存。排障时可以临时开启：

以下片段放在函数内；类型来自 `io.github.logan.gsonsafeparser`。配置需要传给 `GsonSafeParser.parseSafe<T>(json, config)`、`GsonSafeParser.fromJson(..., config)`、Parser 或 Retrofit 工厂。**只创建 config，或只调用 `GsonSafeParser.create(config).fromJson(...)`，不会建立原文捕获上下文。** `rawJson` 可能包含敏感数据，不要把整段内容长期写入线上日志。

```kotlin
SafeParserConfig(
    captureRawJsonInCallbacks = true,
    maxRawJsonCaptureBytes = 1024 * 1024,
    onTypeMismatch = { event ->
        println(event.rawJson)
        println(event.rawJsonTruncated)
    }
)
```

`rawJson` 是本次解析捕获到的原始响应，`rawJsonTruncated` 表示内容是否因为超过上限被截断。
嵌套 Retrofit 调用会隔离每次转换的上下文；捕获关闭或超限跳过时为 `null`，不会继承外层响应原文。

raw JSON 捕获按场景处理：

| 场景 | 结果 |
| --- | --- |
| `GsonSafeParser.fromJson` / `parseSafe` / Parser 入口 | 按 UTF-8 字节数安全截断，不会切断中文、emoji 或其他多字节字符。 |
| Retrofit 已知长度响应 | `contentLength` 超过上限时跳过捕获，事件里会有 `skipReason=ContentLengthExceedsLimit`。 |
| gzip、chunked 或缺少 `Content-Length` | 先做有界探测；未超限就捕获，超限就跳过，并记录 `skipReason=UnknownLengthExceedsLimit`。 |

## 3. Safe Adapter 创建失败

Safe Adapter 创建失败时，库会发出事件，然后交回 Gson 原生 Adapter。

这个行为是固定安全底线，不需要额外开关，避免扩展层在创建 Adapter 时成为新的崩溃源：

```kotlin
SafeParserConfig(
    onAdapterCreationFailure = { event ->
        println("${event.typeName}: ${event.reason}")
    }
)
```

`typeName` 指向创建失败的目标类型，`reason` 是已经脱敏的失败原因。

如果业务希望严格暴露这类问题，可以在测试环境收集事件并让测试失败；线上仍应保持“创建失败交回 Gson”的默认底线。

## 4. diagnostics 报告 GsonBuilder 字段不可读

默认 `GsonSafeParser.create(config)` 不读取 `GsonBuilder` 内部字段。只有 `.enableSafeParser(config)`、`GsonSafeConverterFactory.create(builder, config)` 这类 builder-first 入口需要读取内部字段，用来继承调用方已有 Builder 配置。

`diagnostics()` 会把这些字段拆开报告：

| 结果 | 含义 |
| --- | --- |
| `critical` 字段不可读 | builder-first 入口不能确认反射访问限制或 Unsafe 开关，会回到 Gson 原生链路。 |
| `optional` 字段不可读 | 字段级安全注册仍可继续，但对应 Builder 配置继承会降级。 |

优先检查 AAR consumer ProGuard 规则是否合并，再确认 Gson 版本是否被强制覆盖。只有业务确实依赖对应 Builder 配置时，才需要补版本回归。

如果没有自定义 `GsonBuilder`，优先使用 `GsonSafeParser.create(config)` 或 `GsonSafeConverterFactory.create(config)`。

## 5. 基础类型想使用 SafeParser 宽松解析

当前默认基础类型交回 Gson 原生 Adapter，更贴近原生 Gson 行为。只有需要旧的宽松基础类型解析，例如字符串数字、空字符串和形状不一致默认值时，才显式启用 SafeParser 基础类型解析：

```kotlin
SafeParserConfig(
    primitiveParsingPolicy = PrimitiveParsingPolicy.Safe
)
```

如果只想保持低干预，不需要额外配置，也可以直接使用：

```kotlin
SafeParserConfig.lowInterference()
```

## 6. 直接 gson.fromJson 和 SafeParser 入口异常不一致

结论：如果需要 SafeParser 的顶层异常边界，请使用 `parser.parseSafe(...)` 或 `parser.fromJson(...)`。

直接调用 `gson.fromJson(...)` 会保留 Gson 原生最外层包装。

`GsonSafeParser.create()` 返回的 Gson 会注册字段级 Safe Adapter。字段级 JSON 形状不一致仍会被隔离。

但最外层入口还是 Gson，Gson 可能把 Adapter 抛出的 `CancellationException` 等异常包装成 `JsonSyntaxException`。

推荐写法：

```kotlin
val parser = GsonSafeParser.parser(config)
val value = parser.fromJson<ApiResponse>(json, ApiResponse::class.java)
val result = parser.parseSafe<ApiResponse>(json)
```

高频解析建议复用 Parser；`fromJson(...)` 返回业务对象，`parseSafe(...)` 额外返回事件快照。

如果项目已经统一维护了 Gson，也可以先启用 Safe Adapter，再包装成 Parser：

```kotlin
val gson = GsonBuilder()
    .enableSafeParser(config)
    .create()
val parser = GsonSafeParser.parserWithExternalGson(gson, config)
```

保留直接 `gson.fromJson(...)` 的原生异常包装，是为了不替换 Gson 本体，也避免扩展层改变调用方已经依赖的 Gson 语义。

## 7. Android 平台对象

默认跳过 `android.*` 平台类型字段，避免平台对象触发反射风险；不要把业务模型包名前缀放这里，否则会导致业务字段被跳过解析：

```kotlin
SafeParserConfig(skippedPlatformTypePrefixes = setOf("android."))
```

这个配置只用于跳过 Android 平台类型，业务模型包名不要放进这里。

如果你把它改成空集合，相关字段会回到更接近 Gson 原生的处理方式，但也更容易遇到平台类反射失败。业务模型包名应该通过混淆 keep 规则保护，不应该放进跳过前缀里。

## 8. 业务字段有多种结构

同一个字段成功时是对象、失败时是字符串或数组，GsonSafeParser 只能避免解析崩溃，不能自动理解业务语义。推荐做法：

1. 使用 `JsonElement` 保存原始字段，再在业务层判断结构。
2. 为该字段写自定义 `TypeAdapter`。
3. 调整接口模型，用 wrapper 明确表达成功和失败结构。

## 9. 未定义字段透传

Bean 没有声明的字段不会被自动注入。推荐做法：

1. 先将整个响应解析为 `JsonObject` / `JsonElement`，保留这棵 JSON 树，再按需转换成业务模型。
2. 或在业务模型中显式声明需要透传的字段。

仅在模型里加一个 `raw` 属性，不会自动收集其他未声明字段；它只对应 JSON 本身的 `raw` 字段。需要自动收集额外字段时，使用自定义 `TypeAdapter`。记录网络原文是排障手段，不会替模型保存这些字段。

## 10. Gson 版本兼容

当前发布配置直接依赖 Gson `2.13.2`，Safe Adapter 会反射读取 `GsonBuilder` 的部分内部配置来继承业务侧已有选项。强制降级或升级 Gson 后，先运行：

```kotlin
val diagnostics = GsonSafeParser.diagnostics(SafeParserConfig.production())
val check = GsonSafeParser.integrationCheck(SafeParserConfig.production())
```

`diagnostics()` 检查 GsonBuilder 内部字段是否仍可读取，`integrationCheck()` 运行内置解析探针。

如果 `GsonSafeParser.diagnostics(config).safeAdapterAvailable` 为 `false`，说明当前 GsonBuilder 内部字段不可读。

这种情况下，`enableSafeParser()` 会保守地不注册 Safe Adapter，并在实际创建时发出 Adapter 创建失败事件。

如果 `GsonSafeParser.diagnostics(gson)` 里的 `externalGsonSafeAdapter` 是 `WARNING`，说明外部 Gson 没有字段级 Safe Adapter。后续解析会保持 Gson 原生行为，诊断本身不会发事件，也不会补注册。

如果 `integrationCheck().hasErrors` 为 `true`，先不要上线这组依赖版本。

## 11. release 包行为和 debug 不一致

优先检查：

1. Kotlin Metadata 是否保留。
2. 模型构造方法是否被混淆或裁剪。
3. 业务模型字段名是否被 R8 改成 `a`、`b`、`c`；没有 `@SerializedName` 的老项目模型应先用包级 keep 保住字段名。
4. `kotlin-reflect` 版本是否与 Kotlin 插件版本一致。
5. release 包是否开启了更激进的 R8 规则。

如果已经接入 `GsonSafeModelProbe`，看到 `modelFieldObfuscationSuspected`、`modelConstructorUnavailable` 或 `modelProbeFailure` 时，先按 [Android 混淆配置](android-proguard.md) 给真实模型包加宽范围 keep。

然后再用同一份 JSON 对比 debug 和 release。

## 12. AGP 升级后 Kotlin data class 默认值失效

典型表现是 debug 包正常，但 release 包在 AGP 8.6+ 或 R8 full mode 下无法保留 data class 默认值。

另一个常见信号是 `GsonSafeModelProbe` 报 `modelConstructorUnavailable`。这类问题通常不是 JSON 字段名本身，而是业务模型构造方法、Kotlin Metadata 或构造参数信息被 R8 裁剪。

处理顺序：

1. 先确认业务模型包已经按 [Android 混淆配置](android-proguard.md) 添加 `-keep class ... { <fields>; public <init>(...); }`。
2. 再确认 release 包里没有只依赖 `@SerializedName`。`@SerializedName` 不能替代构造方法和 Metadata keep，它只能固定 JSON 字段名。
3. 如果短期内无法梳理清楚所有模型包，可以先使用 bean、model、entity、response、dto 的宽范围 keep 模板，让 release 行为稳定。
4. 如果项目必须保持 `android.enableR8.fullMode=true`，把 `GsonSafeModelProbe`、debug/release 对照和 release 单测加入发版门禁。
5. 如果项目选择 `android.enableR8.fullMode=false`，仍要保留业务模型 keep；这个开关不能恢复已经被混淆或裁剪的字段和构造信息。

详细配置见 [Android 混淆配置](android-proguard.md)。

`testReleaseUnitTest` 运行 release 变体的本地 JVM 测试，不会运行最终经过 R8 的 APK。验证混淆结果需要在设备或模拟器上运行实际 release APK，执行模型探针，并与 debug 使用同一份 JSON 对照。
