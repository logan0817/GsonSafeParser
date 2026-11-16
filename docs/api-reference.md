# API 参考

[English](en/api-reference.md)

这份文档面向已经决定接入 GsonSafeParser 的开发者。它只回答 3 件事：1. 该用哪个入口 2. 每个 API 做什么 3. 出错时会兜底、回退还是外抛。

第一次使用请先看[快速开始](getting-started.md)，那里有完整 import、模型、调用代码和预期结果。本页用于查方法，不必从头读完。

| 你现在要做什么 | 先记住这个入口 |
| --- | --- |
| 替换原来的 `Gson()` | `GsonSafeParser.create()`，后续继续 `gson.fromJson(...)` |
| 同时拿到结果和恢复记录 | `GsonSafeParser.parseSafe<T>(json)`，看 `value` 和 `events` |
| 接入 Retrofit | `GsonSafeConverterFactory.create()`，替换普通 Gson 转换器 |

下文短片段用于展示 API；除完整模型示例外，调用语句放在函数内，`json`、`ApiResponse` 沿用第 2 节。Kotlin 扩展函数需显式导入：`parseSafe`、`fromJsonSafe`、`enableSafeParser`、`contractReport` 均来自 `io.github.logan.gsonsafeparser`。

标为低层 API 的 `withRawJsonContext` / `dispatchEvent` 用于框架桥接，普通业务无需调用。新接口和修复的发布状态以 [Unreleased](../CHANGELOG.md#unreleased) 为准。

## 1. 入口速查

先按手里有什么选入口：从零创建 Gson 用 `GsonSafeParser.create(config)`；已有 `GsonBuilder` 用 `GsonBuilder.enableSafeParser(config)` 或 `GsonSafeParser.parser(builder, config)`；已有创建好的 `Gson`，先确认它已经注册 Safe Adapter，再用 `parserWithExternalGson(gson, config)`；Retrofit 统一走 `GsonSafeConverterFactory.create(...)`。

| API | 适用场景与边界 |
| --- | --- |
| `GsonSafeParser.create(config)` | 从零创建带 Safe Adapter 的 Gson；返回的 Gson 可复用。直接 `gson.fromJson(...)` 时，根级异常仍按 Gson 原生规则包装。 |
| `GsonBuilder.enableSafeParser(config)` | 项目已有统一 `GsonBuilder` 时使用。它会在 `create()` 前注册 Safe Adapter，并尽量继承 Builder 配置。 |
| `GsonSafeParser.parser(config)` | Repository、数据源、批量解析等高频场景使用。Parser 内部只创建一次 Gson，`parseSafe` 会返回事件。 |
| `GsonSafeParser.parser(builder, config)` | 既要复用 Builder 配置，又要复用 Parser 时使用；推荐给已有统一 GsonBuilder 的项目。 |
| `GsonSafeParser.parserWithExternalGson(gson, config)` | 包装已经创建好的 Gson，`parseSafe` 会返回事件；它不会自动补注册 Safe Adapter。 |
| `GsonSafeParser.fromJson(json, type, config)` | 临时解析或测试验证使用。每次按 config 创建 Gson，不返回事件。 |
| `GsonSafeParser.fromJson(gson, json, type, config)` | 使用调用方 Gson 解析。字段级兜底取决于这份 Gson 创建前是否启用了 Safe Adapter。 |
| `GsonSafeParser.fromJsonSafe<T>(json, config)` | Kotlin 便捷入口，只关心业务对象；reified API 只适合 Kotlin 调用。 |
| `GsonSafeParser.parseSafe<T>(json, config)` | Kotlin 便捷入口，同时需要业务对象和事件快照；适合日志、监控和契约报告。 |
| `GsonSafeConverterFactory.create(...)` | Retrofit 响应转换入口。只处理 JSON 转换、空响应和 raw JSON 观测，不吞网络异常。 |

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

### 3.6 Java 调用：显式传入类型

下面是完整 `JavaExample.java`，依赖与 Kotlin 相同。调用 `main` 后应输出 `200` 和 `1`；Android 不会自动执行此入口，可从现有单元测试调用。

```java
import io.github.logan.gsonsafeparser.GsonSafeParser;
import io.github.logan.gsonsafeparser.SafeParseResult;
import io.github.logan.gsonsafeparser.SafeParserConfig;

public class JavaExample {
    public static class User {
        public long id = 0;
    }

    public static class ApiResponse {
        public int code = 0;
        public User data = new User();
    }

    public static void main(String[] args) {
        GsonSafeParser.Parser parser = GsonSafeParser.INSTANCE.parser(new SafeParserConfig());
        SafeParseResult<ApiResponse> result = parser.parseSafe(
            "{\"code\":200,\"data\":[]}", ApiResponse.class
        );
        ApiResponse response = result.getValue();
        if (response == null || response.code != 200 || response.data == null) {
            throw new AssertionError("Unexpected parse result");
        }
        System.out.println(response.code);
        System.out.println(result.getEvents().size());
    }
}
```

Java 的 `List<User>` 等泛型使用 `new com.google.gson.reflect.TypeToken<java.util.List<User>>() {}.getType()` 传入 `parser.parseSafe(json, type)`，不要只传 `List.class`。

## 4. 配置速查

| 配置 | 默认值与修改时机 |
| --- | --- |
| `fallbackPolicy` | 默认 `NullOnly`。集合、Map 整体错形要返回空容器时，再改成 `Default`。 |
| `primitiveParsingPolicy` | 默认 `DelegateToGson`。业务接受字符串数字、空字符串或错形基础值兜底时，再改成 `Safe`。 |
| `emptyResponsePolicy` | 默认 `DefaultValueForUnitOrVoidOnly`。业务模型空 body 要默认对象、`null` 或 Gson 原生异常时再调整。 |
| `useJdkUnsafe` | 默认 `false`。只有明确依赖 Gson Unsafe 构造行为时才开启。 |
| `requiredConstructorParameterPolicy` | 默认 `GsonCompatible`。新接口要强契约时改成 `Strict`。 |
| `mapItemKeyPolicy` | 默认 `Omit`。需要聚合定位时改成 `Hash`，不建议线上明文输出。 |
| `captureRawJsonInCallbacks` | 默认 `false`。只在联调或排障时临时开启。 |
| `maxRawJsonCaptureBytes` | 默认 `1 MiB`。大响应场景应调小并压测内存。 |

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
| 调用方显式注册的自定义 Adapter 命中 | 优先交回调用方 Adapter | `TypeAdapter`、`TypeAdapterFactory`、`registerTypeHierarchyAdapter(...)` 或 `@JsonAdapter` 自己抛出的异常会向外抛出 |
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
| 转换器 raw JSON 上下文 | `GsonSafeParser.withRawJsonContext(...)` | 仅供 Retrofit 等低级转换器传递有限 raw JSON 观察内容，不改变原生解析严格性 |

`withRawJsonContext(json, config, block)` 需要 `@OptIn(GsonSafeParserLowLevelApi::class)`。它只在当前线程执行 `block` 期间绑定观察内容，沿用捕获开关和 UTF-8 字节上限，嵌套调用或异常退出后恢复旧上下文。它不会解析 JSON、注册 Adapter 或吞异常；第三方转换器仍负责原始响应的读取和关闭。

<details>
<summary>展开：检查自己的业务模型</summary>

默认 `integrationCheck()` 只检查库内置样例。下面完整示例额外验证一个业务模型，成功输出 `Model probe passed`。实际项目替换为关键响应模型、真实 JSON 和预期字段：

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeModelProbe
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.integrationCheck

data class ReleaseResponse(val code: Int = 0)

fun main() {
    val probe = GsonSafeModelProbe(
        name = "releaseResponse",
        json = """{"code":200}""",
        type = ReleaseResponse::class.java,
        expectedFields = mapOf("code" to 200)
    )
    val result = GsonSafeParser.integrationCheck(
        config = SafeParserConfig.production(),
        modelProbes = listOf(probe)
    )
    check(result.hasErrors.not()) { result.checks.joinToString("\n") }
    println("Model probe passed")
}
```

要验证 R8，请按混淆规则保留真实模型，并从实际 release APK 的调试入口调用这段检查。仅在 JVM 单测运行不能证明混淆产物正确。

</details>

## 7. 推荐接入路线

1. 第 1 轮：debug 环境使用 `SafeParserConfig.debug()`，开启有限 raw JSON，确认事件能帮助定位接口问题。
2. 第 2 轮：Android release 包按 [Android 混淆](android-proguard.md) 配置业务模型 keep 规则。
3. 第 3 轮：对核心接口跑真实 JSON 回归，确认字段默认值、`@SerializedName`、集合、Map 和事件都符合预期。
4. 第 4 轮：上线前切到 `SafeParserConfig.production()`，关闭 raw JSON 正文，只保留结构化事件。
5. 第 5 轮：如果担心误伤，先用 `SafeParserConfig.lowInterference()` 小流量灰度。
