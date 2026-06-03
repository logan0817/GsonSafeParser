# 快速开始

[English](en/getting-started.md)

本文档面向第一次接入 GsonSafeParser 的开发者。

你会看到 5 件事：怎么安装、普通 Gson 怎么接、Retrofit 怎么接、Kotlin API 怎么用、CI 怎么做接入自检。更细的兜底范围见 [错形能力矩阵（JSON 形状不一致）](mismatch-capability-matrix.md)。

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
implementation("io.github.logan0817:gson-safe-parser-core:1.0.1") // 接入 GsonSafeParser 核心解析能力。
```

如果项目使用 Retrofit，只需要：

最新版本：[![Maven Central: retrofit](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-retrofit?label=retrofit)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-retrofit)

```kotlin
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.1") // 接入 Retrofit Converter 扩展，并自动带上 core。
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
val gson = GsonSafeParser.create() // 创建带安全解析能力的 Gson 实例。
val response = gson.fromJson(json, ApiResponse::class.java) // 使用安全 Gson 解析业务响应。
```

如果你已经有自己的 `GsonBuilder`：

```kotlin
val gson = GsonBuilder() // 创建自定义 GsonBuilder。
    .serializeNulls() // 保留你原本需要的 Gson 序列化配置。
    .enableSafeParser() // 在当前 Builder 上注册安全解析能力。
    .create() // 生成最终 Gson 实例。
```

`enableSafeParser()` 会在当前 `GsonBuilder` 上注册安全解析能力，并尽量保留你已有的 Gson 配置。

同一个 `GsonBuilder` 重复调用时不会重复注册 Safe Adapter。如果要换配置，请新建 `GsonBuilder`。

## 3. Kotlin 便捷 API

```kotlin
val value = GsonSafeParser.fromJsonSafe<ApiResponse>(json) // 直接解析并返回业务对象。
val result = GsonSafeParser.parseSafe<ApiResponse>(json) // 解析并返回业务对象、事件列表和报告入口。
```

`fromJsonSafe<T>()` 适合只关心解析结果的场景。`parseSafe<T>()` 会额外返回事件列表，适合日志、监控和接口契约复盘。

入口选择：

| 需求 | 推荐入口 |
| --- | --- |
| 只要字段级兜底 | `GsonSafeParser.create()` 或 `GsonBuilder.enableSafeParser()`。 |
| 要解析结果和事件快照 | `GsonSafeParser.parseSafe<T>()`。 |
| 要复用同一套配置 | `GsonSafeParser.parser(config)`。 |
| 已有外部 Gson | 先在 Builder 上 `.enableSafeParser(config)`，再用 `parserWithExternalGson(gson, config)`。 |

异常边界：

| 问题 | 默认处理 |
| --- | --- |
| 字段级 JSON 形状不一致 | 当前字段兜底，外层对象继续解析，并产生事件。 |
| JSON 语法错误 | 继续抛出。 |
| 根级解析失败 | 继续遵循 Gson 边界。 |
| 不可安全隔离异常 | `Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 继续外抛。 |

字段级 Adapter 读取失败如果能被当前字段边界隔离，会产生事件并保留外层对象解析。

直接调用 `gson.fromJson(...)` 时，最外层异常仍按 Gson 原生规则包装。这不是 SafeParser 漏处理，而是为了保留 Gson 原生入口语义。

```kotlin
val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""") // 解析一份 Object 字段形状不一致的 JSON。

println(result.value) // 打印兜底后的业务对象。
println(result.events) // 打印解析过程中产生的事件。
println(result.contractReport().toMarkdown()) // 打印 Markdown 格式契约报告。
println(result.contractReport().toBackendMarkdown()) // 打印给后端修接口用的契约报告。
```

## 4. Java 调用和非 reified API

Kotlin reified API 只适合 Kotlin 调用；Java、反射 Type 或需要显式传类型的场景，使用 `Class` 或 `Type` 入口：

```java
SafeParserConfig config = new SafeParserConfig(); // Java 调用需要显式传配置对象。
ApiResponse value = GsonSafeParser.INSTANCE.fromJson(json, ApiResponse.class, config); // 使用 Java Class 解析。

GsonSafeParser.Parser parser = GsonSafeParser.INSTANCE.parser(config); // 创建可复用 Parser。
SafeParseResult<ApiResponse> result = parser.parseSafe(json, ApiResponse.class); // 非 reified 入口，返回对象和事件。
```

泛型类型用 Gson `TypeToken`：

```java
Type listType = new TypeToken<List<ApiResponse>>() {}.getType(); // 保留泛型信息。
SafeParseResult<List<ApiResponse>> result = parser.parseSafe(json, listType); // 非 reified 泛型解析。
```

## 5. 高频复用 Parser

便利入口 `GsonSafeParser.fromJson(json, type, config)` 适合临时验证和低频调用。

如果在 Repository、数据源或批量任务里反复解析同一套配置，推荐先创建一次 Parser。

```kotlin
val config = SafeParserConfig.production() // 创建当前业务场景要复用的配置。
val parser = GsonSafeParser.parser(config) // 创建可复用 Parser，内部只持有一个安全 Gson。

val first = parser.fromJson(json, ApiResponse::class.java) // 第一次解析，复用 Parser 内部 Gson。
val second = parser.fromJsonSafe<ApiResponse>(json) // Kotlin reified 写法，同样复用 Parser 内部 Gson。
val result = parser.parseSafe<ApiResponse>(json) // 复用 Parser，并返回本次解析的事件快照和报告入口。
```

如果项目已经统一维护了 Gson，可以先在 Builder 上启用 SafeParser，再包装成 Parser：

```kotlin
val gson = GsonBuilder() // 创建项目统一维护的 GsonBuilder。
    .serializeNulls() // 保留项目已有 Gson 配置。
    .enableSafeParser(config) // 注册 SafeParser 能力。
    .create() // 创建共享 Gson。

val parser = GsonSafeParser.parserWithExternalGson(gson, config) // 包装已有 Gson，不重新创建、替换或补注册它。
```

Parser 和 Gson 都可以复用，也可以作为单例、DI 对象或 Repository 成员持有。

外部 Gson 的规则请按这张表判断：

`parserWithExternalGson(gson, config)` 不会自动给外部 Gson 注册 Safe Adapter。

| 问题 | 说明 |
| --- | --- |
| 外部 Gson 是否会自动补注册 Safe Adapter | 不会。需要在创建这份 Gson 前，对同一个 `GsonBuilder` 调用 `.enableSafeParser(config)`。 |
| 怎么确认外部 Gson 是否安全 | 调用 `GsonSafeParser.diagnostics(gson)`，查看是否已经包含字段级 Safe Adapter。 |
| `parserWithExternalGson(gson, config)` 的 config 管什么 | 主要控制 raw JSON 捕获、根基础类型兜底和 `parseSafe` 事件快照。 |
| 字段级 Adapter 的事件回调归属 | 归创建 Gson 时传给 `.enableSafeParser(...)` 的配置。 |
| 回调在哪个线程执行 | 在实际解析调用线程同步触发；多线程并发时，调用方要保证日志缓冲、指标容器或外部集合线程安全。 |

## 6. Retrofit 接入

```kotlin
val retrofit = Retrofit.Builder() // 创建 Retrofit 构建器。
    .baseUrl("https://example.com/") // 设置接口基础地址。
    .addConverterFactory(GsonSafeConverterFactory.create()) // 注册 GsonSafeParser 响应转换器。
    .build() // 构建 Retrofit 实例。
```

自定义配置：

```kotlin
val config = SafeParserConfig.production( // 创建线上推荐配置。
    observerPolicy = SafeObserverPolicy( // 配置解析事件观察策略。
        onEvent = { event -> // 接收统一解析事件。
            println(event) // 输出事件，方便接日志或监控。
        } // 结束事件回调。
    ) // 结束观察策略。
) // 结束配置创建。

val retrofit = Retrofit.Builder() // 创建 Retrofit 构建器。
    .baseUrl("https://example.com/") // 设置接口基础地址。
    .addConverterFactory(GsonSafeConverterFactory.create(config)) // 使用自定义配置注册转换器。
    .build() // 构建 Retrofit 实例。
```

如果项目里已经统一维护了 GsonBuilder，推荐使用 builder-first 入口，让工厂在 `.create()` 前注册 Safe Adapter：

```kotlin
val config = SafeParserConfig.debug() // 统一控制 Gson 和 Retrofit 层的 SafeParser 行为。
val retrofit = Retrofit.Builder() // 创建 Retrofit 构建器。
    .baseUrl("https://example.com/") // 设置接口基础地址。
    .addConverterFactory(GsonSafeConverterFactory.create(GsonBuilder().serializeNulls(), config)) // 保留 Builder 配置并启用字段级 Safe Adapter。
    .build() // 构建 Retrofit 实例。
```

如果项目里已经统一维护的是创建好的 Gson，同时又想保留 Retrofit 层的空响应、rawJson 和事件策略：

```kotlin
val config = SafeParserConfig.debug() // 统一控制 Gson 和 Retrofit 层的 SafeParser 行为。
val gson = GsonBuilder() // 创建项目里统一维护的 GsonBuilder。
    .serializeNulls() // 保留调用方自己的 Gson 选项。
    .enableSafeParser(config) // 把同一份 SafeParserConfig 注册到 Gson。
    .create() // 生成最终 Gson。

val retrofit = Retrofit.Builder() // 创建 Retrofit 构建器。
    .baseUrl("https://example.com/") // 设置接口基础地址。
    .addConverterFactory(GsonSafeConverterFactory.create(gson, config)) // 同时复用已有 Gson 和 Retrofit 层 SafeParserConfig。
    .build() // 构建 Retrofit 实例。
```

这里容易误解，按当前情况选入口就行：

| 当前情况 | 推荐写法 | 原因 |
| --- | --- | --- |
| 还在配置 `GsonBuilder` | `GsonSafeConverterFactory.create(builder, config)` | 工厂会在 `builder.create()` 前注册 Safe Adapter。 |
| 已经有统一维护的 `Gson` | 先在创建它的 `GsonBuilder` 上调用 `.enableSafeParser(config)`，再传给 `create(gson, config)` | `Gson` 创建后配置已经固定，库不会偷偷改这个实例。 |
| 只写 `create(gson, config)` | 只复用这份 `Gson`，并使用 Retrofit 层的空响应、raw JSON 和事件配置 | 这不会自动给外部 Gson 注册 Safe Adapter。 |

## 7. CI 自检

```kotlin
val diagnostics = GsonSafeParser.diagnostics(SafeParserConfig.production()) // 检查 GsonBuilder 兼容性和高风险配置。
val externalGsonDiagnostics = GsonSafeParser.diagnostics(gson) // 检查外部 Gson 是否已经注册字段级 Safe Adapter。
val integrationCheck = GsonSafeParser.integrationCheck(SafeParserConfig.production()) // 运行库内置接入自检。

integrationCheck.checks.forEach { item -> // 遍历每一条自检结果。
    println("${item.severity}: ${item.name} - ${item.message}") // 打印级别、名称和说明。
} // 结束自检结果遍历。

check(integrationCheck.hasErrors.not()) // 如果存在阻断问题，让测试或 CI 失败。
```

`diagnostics()` 只检查当前 Gson 反射兼容性和配置风险，适合在强制覆盖 Gson 版本后先看 Safe Adapter 是否可用。

`integrationCheck()` 还会运行库内置探针。它不访问网络，不依赖 Android 设备，也不会解析业务 Bean，适合放进 JVM 单元测试。

它可以确认 Safe Adapter、事件流和契约报告能正常工作。

如果要把老项目 release 混淆风险也放进 CI，可以额外传少量关键业务模型探针。探针失败会进入 `checks`，不会让自检调用直接抛出混淆异常：

```kotlin
val modelProbe = GsonSafeModelProbe( // 选一个关键响应模型做 release 字段名探针。
    name = "coreApiResponse", // 用接口名或模型名定位问题。
    json = """{"code":200}""", // 最小业务 JSON。
    type = ApiResponse::class.java, // 真实业务模型类型。
    expectedFields = mapOf("code" to 200) // 用混淆前字段名和值做断言。
) // 结束业务模型探针。

val releaseCheck = GsonSafeParser.integrationCheck( // 运行库自检和业务模型探针。
    config = SafeParserConfig.production(), // 使用线上配置。
    modelProbes = listOf(modelProbe) // 只挑关键模型，不要求覆盖全项目 Bean。
) // 结束 release 自检。

check(releaseCheck.hasErrors.not()) // 如果疑似模型字段被混淆，让 CI 失败并查看 checks。
```

推荐分 4 层接入：

| 层级 | 检查什么 |
| --- | --- |
| 第 1 层 | `diagnostics()` 检查库和 Gson 版本兼容性。 |
| 第 2 层 | `integrationCheck()` 检查内置探针。 |
| 第 3 层 | `modelProbes` 检查关键业务模型 release 字段名和构造方法。 |
| 第 4 层 | 用同一份真实 JSON 对比 debug 和 release 包。 |

## 8. 建议接入顺序

1. 先在测试环境使用 `SafeParserConfig.debug()`，观察事件流和契约报告。
2. Android 老项目先按 [Android 混淆配置](android-proguard.md) 使用宽范围包级 keep，让 release 字段名稳定。
3. 对核心接口跑一遍业务 JSON 回归测试，确认普通字段名模型、`@SerializedName` 模型、默认值和回调事件都符合业务预期。
4. 上线前改为 `SafeParserConfig.production()`，避免长期记录大体积 raw JSON。
5. 如果项目特别担心误伤，先用 `SafeParserConfig.lowInterference()` 灰度观察。
