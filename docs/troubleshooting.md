# 排障指南

[English](en/troubleshooting.md)

本文档整理接入 GsonSafeParser 时最常见的问题，以及推荐处理方式。

## 1. Retrofit 空响应

如果接口可能返回空 body，按业务选择策略：

```kotlin
SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly) // 默认只为 Unit/Void 返回空值。
SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DefaultValue) // 普通业务模型空响应返回默认对象。
SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.Null) // 空响应直接返回 null。
SafeParserConfig(emptyResponsePolicy = EmptyResponsePolicy.DelegateToGson) // 空响应交回 Gson 原生策略。
```

`DelegateToGson` 会交给 Gson / Retrofit 原生 converter，普通对象通常会抛 `EOFException`，适合严格要求后端不得返回空 body 的接口。Retrofit 的 `Unit` 和 `Void` 属于空响应语义，分别返回 `Unit` 和 `null`，避免为了委托制造新崩溃。

## 2. 回调里看不到原始 JSON

默认不开启原始 JSON 捕获，避免大响应额外占用内存。排障时可以临时开启：

```kotlin
SafeParserConfig( // 创建排障用安全解析配置。
    captureRawJsonInCallbacks = true, // 在回调事件中携带原始 JSON。
    maxRawJsonCaptureBytes = 1024 * 1024, // 限制 raw JSON 最大捕获体积为 1 MiB。
    onTypeMismatch = { event -> // 接收字段类型错配事件。
        println(event.rawJson) // 输出本次解析的原始 JSON。
        println(event.rawJsonTruncated) // 输出 raw JSON 是否被截断。
    } // 结束类型错配回调。
) // 结束排障配置。
```

raw JSON 捕获按场景处理：

| 场景 | 结果 |
| --- | --- |
| 普通 Gson 解析 | 按 UTF-8 字节数安全截断，不会切断中文、emoji 或其他多字节字符。 |
| Retrofit 已知长度响应 | `contentLength` 超过上限时跳过捕获，事件里会有 `skipReason=ContentLengthExceedsLimit`。 |
| gzip、chunked 或缺少 `Content-Length` | 先做有界探测；未超限就捕获，超限就跳过，并记录 `skipReason=UnknownLengthExceedsLimit`。 |

## 3. Safe Adapter 创建失败

Safe Adapter 创建失败时，库会发出事件，然后交回 Gson 原生 Adapter。

这个行为是固定安全底线，不需要额外开关，避免扩展层在创建 Adapter 时成为新的崩溃源：

```kotlin
SafeParserConfig( // 创建带 Adapter 创建失败观测的配置。
    onAdapterCreationFailure = { event -> // 接收 Safe Adapter 创建失败事件。
        println("${event.typeName}: ${event.reason}") // 输出失败类型和原因。
    } // 结束 Adapter 创建失败回调。
) // 结束安全解析配置。
```

如果业务希望严格暴露这类问题，可以在测试环境收集事件并让测试失败；线上仍应保持“创建失败交回 Gson”的默认底线。

## 4. 基础类型想使用 SafeParser 宽松解析

当前默认基础类型交回 Gson 原生 Adapter，更贴近原生 Gson 行为。只有需要旧的宽松基础类型解析，例如字符串数字、空字符串和形状不一致默认值时，才显式启用 SafeParser 基础类型解析：

```kotlin
SafeParserConfig( // 创建启用宽松基础类型解析的配置。
    primitiveParsingPolicy = PrimitiveParsingPolicy.Safe // 基础类型形状不一致时使用 SafeParser 的局部兜底。
) // 结束安全解析配置。
```

如果只想保持低干预，不需要额外配置，也可以直接使用：

```kotlin
SafeParserConfig.lowInterference() // 使用低误伤预设配置。
```

## 5. 直接 gson.fromJson 和 SafeParser 入口异常不一致

结论：如果需要 SafeParser 的顶层异常边界，请使用 `parser.parseSafe(...)` 或 `parser.fromJson(...)`。

直接调用 `gson.fromJson(...)` 会保留 Gson 原生最外层包装。

`GsonSafeParser.create()` 返回的 Gson 会注册字段级 Safe Adapter。字段级 JSON 形状不一致仍会被隔离。

但最外层入口还是 Gson，Gson 可能把 Adapter 抛出的 `CancellationException` 等异常包装成 `JsonSyntaxException`。

推荐写法：

```kotlin
val parser = GsonSafeParser.parser(config) // 推荐在高频场景复用 Parser。
val value = parser.fromJson<ApiResponse>(json, ApiResponse::class.java) // 通过 SafeParser 入口解析。
val result = parser.parseSafe<ApiResponse>(json) // 同时拿到事件快照。
```

如果项目已经统一维护了 Gson，也可以先启用 Safe Adapter，再包装成 Parser：

```kotlin
val gson = GsonBuilder()
    .enableSafeParser(config)
    .create()
val parser = GsonSafeParser.parserWithExternalGson(gson, config)
```

保留直接 `gson.fromJson(...)` 的原生异常包装，是为了不替换 Gson 本体，也避免扩展层改变调用方已经依赖的 Gson 语义。

## 6. Android 平台对象

默认跳过 `android.*` 平台类型字段，避免平台对象触发反射风险；不要把业务模型包名前缀放这里，否则会导致业务字段被跳过解析：

```kotlin
SafeParserConfig(skippedPlatformTypePrefixes = setOf("android.")) // 跳过 Android 平台类型，避免反射系统对象；不要把业务模型包名前缀放这里。
```

如果你把它改成空集合，相关字段会回到更接近 Gson 原生的处理方式，但也更容易遇到平台类反射失败。业务模型包名应该通过混淆 keep 规则保护，不应该放进跳过前缀里。

## 7. 业务字段有多种结构

同一个字段成功时是对象、失败时是字符串或数组，GsonSafeParser 只能避免解析崩溃，不能自动理解业务语义。推荐做法：

1. 使用 `JsonElement` 保存原始字段，再在业务层判断结构。
2. 为该字段写自定义 `TypeAdapter`。
3. 调整接口模型，用 wrapper 明确表达成功和失败结构。

## 8. 未定义字段透传

Bean 没有声明的字段不会被自动注入。推荐做法：

1. 在统一响应外层保留 `JsonObject raw`。
2. 使用 OkHttp 拦截器记录原始响应。
3. 在业务模型中显式声明需要透传的字段。

## 9. Gson 版本兼容

当前发布配置直接依赖 Gson `2.13.2`，Safe Adapter 会反射读取 `GsonBuilder` 的部分内部配置来继承业务侧已有选项。强制降级或升级 Gson 后，先运行：

```kotlin
val diagnostics = GsonSafeParser.diagnostics(SafeParserConfig.production()) // 检查 GsonBuilder 内部字段是否还能读取。
val check = GsonSafeParser.integrationCheck(SafeParserConfig.production()) // 运行内置解析探针。
```

如果 `GsonSafeParser.diagnostics(config).safeAdapterAvailable` 为 `false`，说明当前 GsonBuilder 内部字段不可读。

这种情况下，`enableSafeParser()` 会保守地不注册 Safe Adapter，并在实际创建时发出 Adapter 创建失败事件。

如果 `GsonSafeParser.diagnostics(gson)` 里的 `externalGsonSafeAdapter` 是 `WARNING`，说明外部 Gson 没有字段级 Safe Adapter。后续解析会保持 Gson 原生行为，诊断本身不会发事件，也不会补注册。

如果 `integrationCheck().hasErrors` 为 `true`，先不要上线这组依赖版本。

## 10. release 包行为和 debug 不一致

优先检查：

1. Kotlin Metadata 是否保留。
2. 模型构造方法是否被混淆或裁剪。
3. 业务模型字段名是否被 R8 改成 `a`、`b`、`c`；没有 `@SerializedName` 的老项目模型应先用包级 keep 保住字段名。
4. `kotlin-reflect` 版本是否与 Kotlin 插件版本一致。
5. release 包是否开启了更激进的 R8 规则。

如果已经接入 `GsonSafeModelProbe`，看到 `modelFieldObfuscationSuspected`、`modelConstructorUnavailable` 或 `modelProbeFailure` 时，先按 [Android 混淆配置](android-proguard.md) 给真实模型包加宽范围 keep。

然后再用同一份 JSON 对比 debug 和 release。

## 11. AGP 升级后 Kotlin data class 默认值失效

典型表现是 debug 包正常，但 release 包在 AGP 8.6+ 或 R8 full mode 下无法保留 data class 默认值。

另一个常见信号是 `GsonSafeModelProbe` 报 `modelConstructorUnavailable`。这类问题通常不是 JSON 字段名本身，而是业务模型构造方法、Kotlin Metadata 或构造参数信息被 R8 裁剪。

处理顺序：

1. 先确认业务模型包已经按 [Android 混淆配置](android-proguard.md) 添加 `-keep class ... { <fields>; public <init>(...); }`。
2. 再确认 release 包里没有只依赖 `@SerializedName`。`@SerializedName` 不能替代构造方法和 Metadata keep，它只能固定 JSON 字段名。
3. 如果短期内无法梳理清楚所有模型包，可以先使用 bean、model、entity、response、dto 的宽范围 keep 模板，让 release 行为稳定。
4. 如果项目必须保持 `android.enableR8.fullMode=true`，把 `GsonSafeModelProbe`、debug/release 对照和 release 单测加入发版门禁。
5. 如果项目选择 `android.enableR8.fullMode=false`，仍要保留业务模型 keep；这个开关不能恢复已经被混淆或裁剪的字段和构造信息。

详细配置见 [Android 混淆配置](android-proguard.md)。
