# 快速开始

[English](en/getting-started.md)

本文档面向第一次接入 GsonSafeParser 的开发者。

你会看到 5 件事：怎么安装、普通 Gson 怎么接、Retrofit 怎么接、Kotlin API 怎么用、CI 怎么做接入自检。API 入口怎么选见 [API 参考](api-reference.md)，更细的兜底范围见 [错形能力矩阵（JSON 形状不一致）](mismatch-capability-matrix.md)。

## 1. 使用安装

发布产物是 Android AAR，使用 JDK 17 编译。接入前请确认业务工程使用 JDK 17 或更高版本。

先按场景选择：

| 场景 | 做法 |
| --- | --- |
| debug 或未开启 minify | 可以先零配置试用。 |
| release 开启 R8 / ProGuard | 必须按 [Android 混淆配置](android-proguard.md) 保留业务模型字段名和构造方法。 |
| 老项目 Bean 分散 | 先用宽范围包级 keep 覆盖 bean、model、entity、response、dto 等模型包。 |
| 只加 `@SerializedName` | 只能固定 JSON 字段名，不能替代构造方法和 Kotlin Metadata keep。 |

零配置只适合 debug 或未开启 minify 的试用。

`android.enableR8.fullMode=false` 只能作为老项目短期兼容策略，不能替代业务模型 keep。

普通 Gson 场景：

最新版本：[![Maven Central: core](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-core?label=core)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-core)

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.4")
```

如果项目使用 Retrofit，只需要：

最新版本：[![Maven Central: retrofit](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-retrofit?label=retrofit)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-retrofit)

```kotlin
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.4")
```

## 2. 普通 Gson 接入

依赖坐标使用 `io.github.logan0817`，代码 import 使用 `io.github.logan.gsonsafeparser`。这是 Maven Central namespace 和公开 Kotlin 包名的区别，不影响使用。

最小可复制样例：

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.contractReport
import io.github.logan.gsonsafeparser.parseSafe

data class ApiResponse(
    val code: Int = 0,
    val data: User = User()
)

data class User(
    val id: Long = 0L,
    val name: String = "anonymous"
)

val json = """{"code":200,"data":[]}"""
val result = GsonSafeParser.parseSafe<ApiResponse>(json)

println(result.value)
println(result.events)
println(result.contractReport().toBackendMarkdown())
```

```kotlin
val gson = GsonSafeParser.create()
val response = gson.fromJson(json, ApiResponse::class.java)
```

`GsonSafeParser.create()` 会创建已经注册 Safe Adapter 的 Gson，适合没有自定义 `GsonBuilder` 的普通接入。

默认入口不会读取 `GsonBuilder` 内部字段，适合没有自定义 GsonBuilder 的普通接入场景。

如果你已经有自己的 `GsonBuilder`：

```kotlin
val gson = GsonBuilder()
    .serializeNulls()
    .enableSafeParser()
    .create()
```

这段写法会保留你原本的 Gson 配置，并在同一个 Builder 上注册字段级安全解析能力。

`enableSafeParser()` 会在当前 `GsonBuilder` 上注册安全解析能力，并尽量保留你已有的 Gson 配置。

builder-first 入口才会读取 `GsonBuilder` 内部字段，用来继承 `InstanceCreator`、`ReflectionAccessFilter`、Object 数字策略、复杂 Map key 和 Unsafe 开关。读取失败时可以通过 `GsonSafeParser.diagnostics()` 看到具体字段。

同一个 `GsonBuilder` 重复调用时不会重复注册 Safe Adapter。如果要换配置，请新建 `GsonBuilder`。

## 3. Kotlin 便捷 API

```kotlin
val value = GsonSafeParser.fromJsonSafe<ApiResponse>(json)
val result = GsonSafeParser.parseSafe<ApiResponse>(json)
```

`fromJsonSafe<T>()` 适合只关心解析结果的场景。`parseSafe<T>()` 会额外返回事件列表，适合日志、监控和接口契约复盘。

入口选择：

| 需求 | 推荐入口 | 适合场景 | 失败先查 |
| --- | --- | --- | --- |
| 只要字段级兜底 | `GsonSafeParser.create()` 或 `GsonBuilder.enableSafeParser()`。 | 没有观测需求，只想让对象、集合、Map 字段错形不拖垮外层 Bean。 | 模型是否被混淆、类型是否被 `@SafeParseDelegateToGson` 或自定义 Adapter 接管。 |
| 要解析结果和事件快照 | `GsonSafeParser.parseSafe<T>()`。 | 想把字段 path、实际 JSON 形状和兜底动作记录到日志、监控或契约报告。 | 是否真的走了 `parseSafe`，以及回调里是否过滤了事件。 |
| 要复用同一套配置 | `GsonSafeParser.parser(config)`。 | Repository、数据源、批量解析任务里高频解析同一类接口。 | Parser 是否被重复创建，配置是否和线上预期一致。 |
| 已有外部 Gson | 先在 Builder 上 `.enableSafeParser(config)`，再用 `parserWithExternalGson(gson, config)`。 | App 已统一维护 Gson，但还想使用 `parseSafe` 事件快照。 | `GsonSafeParser.diagnostics(gson)` 是否显示 Safe Adapter 已注册。 |

异常边界：

| 问题 | 默认处理 |
| --- | --- |
| 字段级 JSON 形状不一致 | 当前字段兜底，外层对象继续解析，并产生事件。 |
| JSON 语法错误 | 继续抛出。 |
| 根级解析失败 | 继续遵循 Gson 边界。 |
| 不可安全隔离异常 | `Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 继续外抛。 |

SafeParser 内建 Adapter 读取到可隔离的字段错形时，会产生事件并保留外层对象解析。调用方通过 `registerTypeAdapter(...)`、`registerTypeAdapterFactory(...)`、`registerTypeHierarchyAdapter(...)` 或 `@JsonAdapter` 显式接管的类型，会优先走原生 Gson 链路；这些自定义 Adapter 自己抛出的异常会向外抛出，不会被伪装成字段兜底。

直接调用 `gson.fromJson(...)` 时，最外层异常仍按 Gson 原生规则包装。这不是 SafeParser 漏处理，而是为了保留 Gson 原生入口语义。

```kotlin
val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""")

println(result.value)
println(result.events)
println(result.contractReport().toMarkdown())
println(result.contractReport().toBackendMarkdown())
```

这几个输出分别对应兜底后的业务对象、解析事件、通用 Markdown 契约报告和后端修接口用的报告。

## 4. JSON 形态转换

默认不做对象和数组互转。只有你调用 `withShapeCoercionPolicy(...)` 或字段注解时，才会启用这项能力。

```kotlin
val config = SafeParserConfig()
    .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)

val gson = GsonSafeParser.create(config)
```

局部开启更适合生产接入：

```kotlin
data class ApiResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val data: User? = null
)
```

支持范围：

| 字段类型 | 后端实际 JSON | 开启后处理 |
| --- | --- | --- |
| `data: User` | `"data":[{"id":1}]` | 读取数组第 1 个对象。 |
| `users: List<User>` | `"users":{"id":1}` | 包装成 1 个元素的 List。 |
| `users: Array<User>` | `"users":{"id":1}` | 包装成长度为 1 的数组。 |

根级对象、根级集合、根级对象数组、Map、字符串二次解析、数字和布尔值不会参与转换。转换失败会记录 `ShapeCoercion` 事件，并回到原兜底行为。

## 5. Java 调用和非 reified API

Kotlin reified API 只适合 Kotlin 调用；Java、反射 Type 或需要显式传类型的场景，使用 `Class` 或 `Type` 入口：

```java
SafeParserConfig config = new SafeParserConfig();
ApiResponse value = GsonSafeParser.INSTANCE.fromJson(json, ApiResponse.class, config);

GsonSafeParser.Parser parser = GsonSafeParser.INSTANCE.parser(config);
SafeParseResult<ApiResponse> result = parser.parseSafe(json, ApiResponse.class);
```

泛型类型用 Gson `TypeToken`：

```java
Type listType = new TypeToken<List<ApiResponse>>() {}.getType();
SafeParseResult<List<ApiResponse>> result = parser.parseSafe(json, listType);
```

Java 调用、反射 `Type` 和泛型解析都要显式传类型；Kotlin 的 reified 便捷入口只适合 Kotlin 调用。

## 6. 高频复用 Parser

便利入口 `GsonSafeParser.fromJson(json, type, config)` 适合临时验证和低频调用。

如果在 Repository、数据源或批量任务里反复解析同一套配置，推荐先创建一次 Parser。

```kotlin
val config = SafeParserConfig.production()
val parser = GsonSafeParser.parser(config)

val first = parser.fromJson(json, ApiResponse::class.java)
val second = parser.fromJsonSafe<ApiResponse>(json)
val result = parser.parseSafe<ApiResponse>(json)
```

如果项目已经统一维护了 Gson，可以先在 Builder 上启用 SafeParser，再包装成 Parser：

```kotlin
val gson = GsonBuilder()
    .serializeNulls()
    .enableSafeParser(config)
    .create()

val parser = GsonSafeParser.parserWithExternalGson(gson, config)
```

`parserWithExternalGson(gson, config)` 只包装已有 Gson，不会重新创建、替换或自动补注册它。

Parser 和 Gson 都可以复用，也可以作为单例、DI 对象或 Repository 成员持有。

外部 Gson 的规则请按这张表判断：`parserWithExternalGson(gson, config)` 不会自动给外部 Gson 注册 Safe Adapter。

| 场景 | 正确做法 | 失败表现 | 怎么验证 |
| --- | --- | --- | --- |
| 外部 Gson 需要字段级兜底 | 在创建这份 Gson 前，对同一个 `GsonBuilder` 调用 `.enableSafeParser(config)`。 | 对象字段收到数组时仍按原生 Gson 抛异常，外层 Bean 无法继续解析。 | 调用 `GsonSafeParser.diagnostics(gson)`，确认字段级 Safe Adapter 已注册。 |
| 只想复用已有 Gson 做 `parseSafe` 事件快照 | 使用 `parserWithExternalGson(gson, config)` 包装它。 | `parseSafe` 有事件容器，但字段级兜底取决于这份 Gson 创建时是否已经注册 Safe Adapter。 | 用 `{"data":[]}` 这类字段错形 JSON 跑一次真实模型。 |
| `parserWithExternalGson(gson, config)` 的 config 生效范围 | 主要控制 raw JSON 捕获、显式 `PrimitiveParsingPolicy.Safe` 下的根基础类型兜底和 `parseSafe` 事件快照。 | 以为传入新 config 就能改变外部 Gson 内部字段级 Adapter 的回调。 | 字段级 Adapter 的事件回调归属归创建 Gson 时传给 `.enableSafeParser(...)` 的配置。 |
| 多线程解析共享同一个 Parser 或 Gson | 回调里写入的外部集合、日志缓冲或指标容器由调用方保证线程安全。 | 并发解析时日志丢失、顺序错乱或外部集合异常。 | 在并发单测里使用线程安全队列或指标对象承接回调。 |

## 7. Retrofit 接入

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create())
    .build()
```

自定义配置：

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

`onEvent` 会接收统一解析事件，线上通常接日志、监控或契约报告链路。

如果项目里已经统一维护了 GsonBuilder，推荐使用 builder-first 入口，让工厂在 `.create()` 前注册 Safe Adapter：

```kotlin
val config = SafeParserConfig.debug()
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(GsonBuilder().serializeNulls(), config))
    .build()
```

如果项目里已经统一维护的是创建好的 Gson，同时又想保留 Retrofit 层的空响应、rawJson 和事件策略：

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

这段写法同时复用已有 Gson，并保留 Retrofit 层的空响应、raw JSON 和事件策略。

这里容易误解，按当前情况选入口就行：

| 当前情况 | 推荐写法 | 原因 |
| --- | --- | --- |
| 还在配置 `GsonBuilder` | `GsonSafeConverterFactory.create(builder, config)` | 工厂会在 `builder.create()` 前注册 Safe Adapter。 |
| 已经有统一维护的 `Gson` | 先在创建它的 `GsonBuilder` 上调用 `.enableSafeParser(config)`，再传给 `create(gson, config)` | `Gson` 创建后配置已经固定，库不会偷偷改这个实例。 |
| 只写 `create(gson, config)` | 只复用这份 `Gson`，并使用 Retrofit 层的空响应、raw JSON 和事件配置 | 这不会自动给外部 Gson 注册 Safe Adapter。 |

## 8. CI 自检

```kotlin
val diagnostics = GsonSafeParser.diagnostics(SafeParserConfig.production())
val externalGsonDiagnostics = GsonSafeParser.diagnostics(gson)
val integrationCheck = GsonSafeParser.integrationCheck(SafeParserConfig.production())

integrationCheck.checks.forEach { item ->
    println("${item.severity}: ${item.name} - ${item.message}")
}

check(integrationCheck.hasErrors.not())
```

`diagnostics()` 看环境和外部 Gson 注册状态；`integrationCheck()` 运行库内置探针；最后的 `check(...)` 用来把阻断问题变成测试或 CI 失败。

`diagnostics()` 会检查当前 Gson 反射兼容性和配置风险，适合在强制覆盖 Gson 版本后先看 Safe Adapter 是否可用。结果会按字段拆分 `GsonBuilder` 内部兼容性，`critical` 字段失败会阻断 builder-first 安全注册，`optional` 字段失败只会降级相关配置继承。

`integrationCheck()` 还会运行库内置探针。它不访问网络，不依赖 Android 设备，也不会解析业务 Bean，适合放进 JVM 单元测试。

它可以确认 Safe Adapter、事件流和契约报告能正常工作。

如果要把老项目 release 混淆风险也放进 CI，可以额外传少量关键业务模型探针。探针失败会进入 `checks`，不会让自检调用直接抛出混淆异常：

```kotlin
val modelProbe = GsonSafeModelProbe(
    name = "coreApiResponse",
    json = """{"code":200}""",
    type = ApiResponse::class.java,
    expectedFields = mapOf("code" to 200)
)

val releaseCheck = GsonSafeParser.integrationCheck(
    config = SafeParserConfig.production(),
    modelProbes = listOf(modelProbe)
)

check(releaseCheck.hasErrors.not())
```

`modelProbes` 只需要覆盖关键响应模型，用来在 release 混淆后检查字段名和构造路径是否还能满足业务 JSON。

推荐分 4 层接入：

| 层级 | 检查什么 |
| --- | --- |
| 第 1 层 | `diagnostics()` 检查库和 Gson 版本兼容性。 |
| 第 2 层 | `integrationCheck()` 检查内置探针。 |
| 第 3 层 | `modelProbes` 检查关键业务模型 release 字段名和构造方法。 |
| 第 4 层 | 用同一份真实 JSON 对比 debug 和 release 包。 |

## 9. 建议接入顺序

1. 先在测试环境使用 `SafeParserConfig.debug()`，观察事件流和契约报告。
2. Android 老项目先按 [Android 混淆配置](android-proguard.md) 使用宽范围包级 keep，让 release 字段名稳定。
3. 对核心接口跑一遍业务 JSON 回归测试，确认普通字段名模型、`@SerializedName` 模型、默认值和回调事件都符合业务预期。
4. 上线前改为 `SafeParserConfig.production()`，避免长期记录大体积 raw JSON。
5. 如果项目特别担心误伤，先用 `SafeParserConfig.lowInterference()` 灰度观察。
