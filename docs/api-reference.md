# API 参考

[English](en/api-reference.md)

这份文档面向已经决定接入 GsonSafeParser 的开发者。它只回答 3 件事：1. 该用哪个入口 2. 每个 API 做什么 3. 出错时会兜底、回退还是外抛。

## 1. 入口速查

先按手里有什么选入口：从零创建 Gson 用 `GsonSafeParser.create(config)`；已有 `GsonBuilder` 用 `GsonBuilder.enableSafeParser(config)` 或 `GsonSafeParser.parser(builder, config)`；已有创建好的 `Gson`，先确认它已经注册 Safe Adapter，再用 `parserWithExternalGson(gson, config)`；Retrofit 统一走 `GsonSafeConverterFactory.create(...)`。

| API | 适用场景 | 返回事件 | 是否复用 Gson | 关键边界 |
| --- | --- | --- | --- | --- |
| `GsonSafeParser.create(config)` | 从零创建一份带 Safe Adapter 的 Gson | 否 | 返回的 Gson 可复用 | 字段级兜底生效；直接 `gson.fromJson(...)` 仍保留 Gson 根级异常包装 |
| `GsonBuilder.enableSafeParser(config)` | 项目已经统一配置 `GsonBuilder` | 否 | 调用方继续持有 Builder / Gson | 会在 `create()` 前注册 Safe Adapter，并尽量继承 Builder 配置 |
| `GsonSafeParser.parser(config)` | Repository、数据源、批量解析等高频场景 | `parseSafe` 返回事件 | 是 | Parser 内部只创建一次 Gson |
| `GsonSafeParser.parser(builder, config)` | 既要复用 Builder 配置，又要复用 Parser | `parseSafe` 返回事件 | 是 | 推荐给已有统一 GsonBuilder 的项目 |
| `GsonSafeParser.parserWithExternalGson(gson, config)` | 已经创建好了 Gson | `parseSafe` 返回事件 | 是 | 不会自动补注册 Safe Adapter |
| `GsonSafeParser.fromJson(json, type, config)` | 临时解析或测试验证 | 否 | 否 | 每次按 config 创建 Gson |
| `GsonSafeParser.fromJson(gson, json, type, config)` | 使用调用方 Gson 解析 | 否 | 使用传入 Gson | 传入 Gson 是否具备字段级兜底，取决于它创建前是否启用了 Safe Adapter |
| `GsonSafeParser.fromJsonSafe<T>(json, config)` | Kotlin 调用，只关心业务对象 | 否 | 否 | reified API，只适合 Kotlin |
| `GsonSafeParser.parseSafe<T>(json, config)` | Kotlin 调用，需要业务对象和事件快照 | 是 | 否 | 适合日志、监控和契约报告 |
| `GsonSafeConverterFactory.create(...)` | Retrofit 响应转换 | 通过配置回调观察 | 取决于传入方式 | 只处理 JSON 转换、空响应和 raw JSON 观测，不吞网络异常 |

## 2. 最小用法

### 2.1 普通 Gson

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser

data class ApiResponse(
    val code: Int = 0,
    val data: User = User()
)

data class User(
    val id: Long = 0L,
    val name: String = ""
)

val json = """{"code":200,"data":[]}"""
val gson = GsonSafeParser.create()
val response = gson.fromJson(json, ApiResponse::class.java)
```

`data` 期望对象却收到数组时，GsonSafeParser 会兜底当前字段，外层 `code` 继续解析。字段有构造默认值时，默认值会保留下来。

### 2.2 解析结果加事件

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.contractReport
import io.github.logan.gsonsafeparser.parseSafe

val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""")

println(result.value)
println(result.events)
println(result.contractReport().toBackendMarkdown())
```

`value` 是兜底后的业务对象，`events` 是本次解析产生的事件快照，`contractReport()` 用于生成给客户端、后端或 CI 查看的一致格式报告。

### 2.3 高频复用 Parser

```kotlin
val parser = GsonSafeParser.parser(SafeParserConfig.production())

val value = parser.fromJsonSafe<ApiResponse>(json)
val result = parser.parseSafe<ApiResponse>(json)
```

Parser 适合放到 DI、Repository 或数据源中复用。它不会做全局缓存，也不会隐藏生命周期。

### 2.4 Retrofit

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create())
    .build()
```

已有 `GsonBuilder` 时，优先使用 builder-first 入口：

```kotlin
val config = SafeParserConfig.production()
val gsonBuilder = GsonBuilder().serializeNulls()

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(gsonBuilder, config))
    .build()
```

## 3. API 说明

### 3.1 `GsonSafeParser.create`

| 项目 | 说明 |
| --- | --- |
| 方法 | `fun create(config: SafeParserConfig = SafeParserConfig()): Gson` |
| 作用 | 创建已注册 Safe Adapter 的 Gson |
| 适用 | 没有自定义 `GsonBuilder`，或想快速验证字段级兜底 |
| 兜底 | 对象、集合、Map、对象数组等字段级错形按配置处理 |
| 回退 | Safe Adapter 创建失败、跳过类型、Gson 内置类型、默认基础类型等交回 Gson |
| 风险 | 直接调用返回的 `gson.fromJson(...)` 时，根级异常仍按 Gson 原生行为包装 |

### 3.2 `GsonBuilder.enableSafeParser`

| 项目 | 说明 |
| --- | --- |
| 方法 | `fun GsonBuilder.enableSafeParser(config: SafeParserConfig = SafeParserConfig()): GsonBuilder` |
| 作用 | 在现有 Builder 上注册 Safe Adapter |
| 适用 | 项目已有 `serializeNulls()`、`registerTypeAdapter(...)`、`ReflectionAccessFilter` 等配置 |
| 兜底 | 注册成功后，字段级 Safe Adapter 生效 |
| 回退 | 关键 GsonBuilder 内部字段不可读时，不强行注册 Safe Adapter，保留 Gson 原生链路 |
| 验证 | 用 `GsonSafeParser.diagnostics()` 查看注册风险 |

### 3.3 `GsonSafeParser.parser`

| 项目 | 说明 |
| --- | --- |
| 方法 | `parser(config)` 或 `parser(builder, config)` |
| 作用 | 创建可复用 Parser |
| 适用 | 高频解析、统一数据源、批量任务 |
| 事件 | `parser.parseSafe(...)` 会返回本次解析事件快照 |
| 风险 | 回调在实际解析线程同步触发，外部日志缓冲或指标容器要保证线程安全 |

### 3.4 `parserWithExternalGson`

| 项目 | 说明 |
| --- | --- |
| 方法 | `parserWithExternalGson(gson, config)` |
| 作用 | 包装调用方已经创建好的 Gson |
| 适用 | 业务已有统一 Gson 单例 |
| 重要边界 | 不会自动给这份 Gson 注册 Safe Adapter |
| 正确接入 | 创建 Gson 前先对同一个 `GsonBuilder` 调用 `.enableSafeParser(config)` |
| 检查方式 | `GsonSafeParser.diagnostics(gson)` |

### 3.5 `parseSafe` 与 `fromJsonSafe`

| API | 返回 | 适用场景 |
| --- | --- | --- |
| `fromJsonSafe<T>(json)` | `T?` | 只关心业务对象 |
| `parseSafe<T>(json)` | `SafeParseResult<T>` | 需要解析事件、契约报告或线上观测 |
| `parser.fromJsonSafe<T>(json)` | `T?` | 高频复用 Parser |
| `parser.parseSafe<T>(json)` | `SafeParseResult<T>` | 高频复用 Parser，同时收集事件 |

## 4. 配置速查

| 配置 | 默认值 | 作用 | 什么时候改 |
| --- | --- | --- | --- |
| `fallbackPolicy` | `NullOnly` | 字段错形后的兜底值策略 | 希望集合、Map 整体错形时返回空容器，再改成 `Default` |
| `primitiveParsingPolicy` | `DelegateToGson` | 基础类型是否由 SafeParser 宽松解析 | 需要字符串数字、空字符串、错形基础值兜底时改成 `Safe` |
| `emptyResponsePolicy` | `DefaultValueForUnitOrVoidOnly` | Retrofit 空 body 怎么处理 | 业务模型空 body 要默认对象、`null` 或 Gson 原生异常时调整 |
| `useJdkUnsafe` | `false` | SafeParser 自己是否允许 Unsafe 构造对象 | 只有明确依赖 Gson Unsafe 构造行为时才开启 |
| `requiredConstructorParameterPolicy` | `GsonCompatible` | Kotlin 非空必填参数缺失时怎么处理 | 新接口要强契约时改成 `Strict` |
| `mapItemKeyPolicy` | `Omit` | Map item 事件是否输出 key | 需要聚合定位时改成 `Hash`，不建议线上明文输出 |
| `captureRawJsonInCallbacks` | `false` | 错配事件是否附带 raw JSON | 只在联调或排障临时开启 |
| `maxRawJsonCaptureBytes` | `1 MiB` | raw JSON 捕获上限 | 大响应场景应调小并压测内存 |

## 5. 兜底、回退和外抛边界

| 场景 | 默认行为 | 结果怎么看 |
| --- | --- | --- |
| 对象字段收到数组、字符串或数字 | 当前字段兜底，外层对象继续解析 | `TypeMismatch` 事件里有 path、期望类型、实际 token |
| 集合字段整体错形 | `NullOnly` 下返回 `null` 或保留字段默认值 | 字段默认值为空集合时，看起来会是空集合 |
| 集合内单个坏 item | 跳过当前 item，继续读取后续 item | 事件 kind 通常为 `LIST_ITEM` |
| Map 整体错形 | `NullOnly` 下返回 `null` 或保留字段默认值 | `FallbackPolicy.Default` 才主动给空 Map |
| Map 内单个坏 entry | 跳过当前 entry，继续读取其他 entry | Map item key 默认不输出 |
| 基础类型字段错形 | 默认交回 Gson；读取失败会外抛且不产生 SafeParser 事件 | 只有 `PrimitiveParsingPolicy.Safe` 才启用宽松基础值和事件 |
| Safe Adapter 创建失败 | 记录创建失败事件，然后交回 Gson | 防止扩展层成为新的崩溃来源 |
| 调用方显式注册的 `TypeAdapter`、`TypeAdapterFactory`、`registerTypeHierarchyAdapter(...)` 或 `@JsonAdapter` 命中 | 优先交回调用方 Adapter | 自定义 Adapter 自己抛出的异常向外抛出，不伪装成字段兜底 |
| 类级 `@JsonAdapter`、`@SafeParseDelegateToGson` | 交回 Gson 原生 Adapter | 适合强契约或自定义解析类型 |
| JSON 语法错误 | 外抛 | 不伪装成默认值 |
| `Error`、`ThreadDeath`、`LinkageError`、`CancellationException` | 外抛 | 不吞掉 VM、线程、类加载或取消信号 |
| Retrofit 断网、取消、连接重置、TLS 失败 | 交回 Retrofit / OkHttp | 不记录成空响应或字段错形 |

## 6. 观测与报告

| 能力 | API | 用途 |
| --- | --- | --- |
| 统一事件流 | `SafeParserConfig(onEvent = { ... })` | 上报日志、监控和接口漂移 |
| 类型错配回调 | `onTypeMismatch` | 兼容只关心字段错形的旧接入 |
| Adapter 创建失败回调 | `onAdapterCreationFailure` | 观察哪些类型回到了 Gson |
| 观察者失败回调 | `onObserverFailure` | 避免日志或埋点回调异常影响解析 |
| 契约报告 | `result.contractReport()` | 输出给客户端、后端或 CI 的结构化问题报告 |
| 环境诊断 | `GsonSafeParser.diagnostics()` | 检查 GsonBuilder 内部字段、kotlin-reflect 和配置风险 |
| 集成自检 | `GsonSafeParser.integrationCheck()` | 在 CI 里验证 Safe Adapter、事件和报告链路 |

## 7. 推荐接入路线

1. 第 1 轮：debug 环境使用 `SafeParserConfig.debug()`，开启有限 raw JSON，确认事件能帮助定位接口问题。
2. 第 2 轮：Android release 包按 [Android 混淆](android-proguard.md) 配置业务模型 keep 规则。
3. 第 3 轮：对核心接口跑真实 JSON 回归，确认字段默认值、`@SerializedName`、集合、Map 和事件都符合预期。
4. 第 4 轮：上线前切到 `SafeParserConfig.production()`，关闭 raw JSON 正文，只保留结构化事件。
5. 第 5 轮：如果担心误伤，先用 `SafeParserConfig.lowInterference()` 小流量灰度。
