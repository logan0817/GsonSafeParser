# 配置说明

[English](en/configuration.md)

GsonSafeParser 的配置目标很直接：能安全兜底的字段由库处理，不能确认安全的问题交回 Gson 原生 Adapter。

本文档说明配置项、构造策略、预设、事件、契约报告和注解。完整 JSON 形状不一致范围见 [错形能力矩阵（JSON 形状不一致）](mismatch-capability-matrix.md)。

## 1. 基础配置

下面示例保留完整字段，便于复制。每个配置项的含义和修改时机见后面的表格。

```kotlin
val config = SafeParserConfig(
    fallbackPolicy = FallbackPolicy.NullOnly,
    emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly,
    primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson,
    complexMapKeySerialization = false,
    useJdkUnsafe = false,
    skippedPlatformTypePrefixes = setOf("android."),
    nullValuePolicy = NullValuePolicy.WriteExplicitNulls,
    requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.GsonCompatible,
    mapItemKeyPolicy = MapItemKeyPolicy.Omit,
    captureRawJsonInCallbacks = false,
    maxRawJsonCaptureBytes = 1024 * 1024
)
```

默认配置偏向低干预：字段级问题优先局部兜底，不能确认安全的问题交回 Gson。

默认情况下，SafeParser 自己默认不使用 Unsafe；只有在 `GsonCompatible + useJdkUnsafe = true` 时，才允许 SafeParser 自己走 Unsafe 构造路径。

### 常用配置

| 配置项 | 说明 |
| --- | --- |
| `fallbackPolicy` | 默认 `NullOnly`。集合或 Map 整体错形时返回 `null`；业务确认空容器更安全时再改 `Default`。 |
| `emptyResponsePolicy` | 默认 `DefaultValueForUnitOrVoidOnly`。`Unit`、`Void` 空 body 低干预；业务模型空 body 需要默认对象或 `null` 时再改。 |
| `primitiveParsingPolicy` | 默认 `DelegateToGson`。基础类型和 `String` 继续交回 Gson；业务接受安全基础值时才改 `Safe`。 |
| `complexMapKeySerialization` | 默认 `false`。Map key 是对象、枚举别名或复杂类型，并且服务端能识别数组 entry 时再打开。 |
| `useJdkUnsafe` | 默认 `false`。SafeParser 自己不允许 Unsafe；老项目明确依赖 Gson Unsafe 构造时才打开。 |
| `skippedPlatformTypePrefixes` | 默认 `setOf("android.")`。跳过平台类型；不要放业务模型包名前缀，否则字段级兜底会失效。 |
| `nullValuePolicy` | 默认 `WriteExplicitNulls`。需要区分“字段缺失”和“后端明确返回 null”时保留默认。 |
| `requiredConstructorParameterPolicy` | 默认 `GsonCompatible`。老项目保持默认；新接口、支付、签名、鉴权模型可以评估 `Strict`。 |
| `mapItemKeyPolicy` | 默认 `Omit`。线上不输出 Map item key；需要聚合时用 `Hash`，联调时才临时明文。 |
| `captureRawJsonInCallbacks` | 默认 `false`。只在联调、CI 复盘或灰度排障时临时打开。 |
| `maxRawJsonCaptureBytes` | 默认 `1 MiB`。大响应排障可临时调大，小内存设备可调小。 |

验证配置时，用真实模型覆盖 List、Set、Map 字段错形、空 body、传输失败、缺字段、显式 `null`、未知枚举和基础类型非法值。打开 raw JSON 或 Map key 输出时，还要检查日志和契约报告的脱敏结果。

### 可选能力开关

| 能力 | 说明 |
| --- | --- |
| JSON 形态转换 | 默认关闭。只在 object/array 漂移已确认，且业务接受明确恢复规则时开启。 |

签名载荷、支付金额、鉴权信息、强契约字段不建议开启 JSON 形态转换；这些字段应该让错形暴露出来。

### raw JSON 捕获规则

1. 普通 Gson 解析会按 UTF-8 字节数安全截断，不会切断中文或 emoji。
2. Retrofit 已知长度响应会先看 `contentLength`。
3. gzip、chunked 这类未知长度响应会做有界探测，未超限才捕获，超限就跳过。

`instanceCreators`、`reflectionAccessFilters` 和 `skippedPlatformTypePrefixes` 会在创建 `SafeParserConfig` 时保存快照。

后续修改调用方原来的可变集合，不会影响已经创建好的配置。

`GsonSafeParser.create(config)` 使用配置直接注册 Safe Adapter，不读取 `GsonBuilder` 内部字段。

`enableSafeParser()` 对同一个 `GsonBuilder` 是幂等的，重复调用不会重复注册 Safe Adapter。如果需要换配置，请新建一个 `GsonBuilder`。

`enableSafeParser()` 和 Retrofit 的 builder-first 入口会读取 `GsonBuilder` 内部字段，只为继承调用方已经配置的 `InstanceCreator`、`ReflectionAccessFilter`、Object 数字策略、复杂 Map key 和 Unsafe 开关。`diagnostics()` 会按字段拆分报告；`critical` 字段失败会阻断 builder-first 安全注册，`optional` 字段失败只会降级相关配置继承。

## 2. 构造策略与 Unsafe

这一节只回答一个问题：对象没有安全构造路径时，要不要允许 Unsafe 绕过构造函数。

默认推荐 `GsonCompatible + useJdkUnsafe = false`。这组配置适合直接接入已有 Gson 项目：SafeParser 自己不使用 Unsafe，Gson 回退路径保持原生行为。

| 配置组合 | 说明 |
| --- | --- |
| `GsonCompatible + useJdkUnsafe = false` | 默认配置。SafeParser 自己不允许 Unsafe，Gson 回退路径保持原生行为，适合大多数项目。 |
| `GsonCompatible + useJdkUnsafe = true` | 仅用于项目明确依赖原生 Gson Unsafe 构造的场景；Gson 回退路径仍保持原生行为。 |
| `Strict + useJdkUnsafe = false` | 新接口或强契约场景。Strict 下 SafeParser 和 Gson 回退路径都不允许 Unsafe。 |
| `Strict + useJdkUnsafe = true` | 不建议使用。`Strict` 优先级最高，`useJdkUnsafe = true` 会被忽略。 |

推荐用法：

| 你的目标 | 推荐配置 |
| --- | --- |
| 先稳定接入，不改变现有 Gson 习惯 | 保持默认配置。 |
| 兼容已有 Unsafe 构造依赖 | 使用 `GsonCompatible + useJdkUnsafe = true`，并补真实 JSON 回归。 |
| 尽早暴露缺字段、`null`、错形或未知枚举值 | 使用 `Strict + useJdkUnsafe = false`。 |

Unsafe 会绕过构造函数和 `init` 代码。

开启后对象可能被创建出来，但 Kotlin 默认值、非空约束和构造校验不一定执行。新项目不建议把 `useJdkUnsafe = true` 作为默认配置。

## 3. 预设配置

```kotlin
val production = SafeParserConfig.production()
val debug = SafeParserConfig.debug()
val lowInterference = SafeParserConfig.lowInterference()
```

预设说明：

| 预设 | 适合场景 | 主要行为 |
| --- | --- | --- |
| `production()` | 线上默认接入。 | 契约优先读策略、默认不输出 Map item key、事件观测，不携带整段 raw JSON。 |
| `debug()` | 联调、测试和接口排障。 | 与线上读策略一致，但开启有限长度 raw JSON 捕获，并输出明文 Map item key。 |
| `lowInterference()` | 灰度接入和低干预优先。 | 字段、集合、Map 整体形状不一致优先 `null`，基础类型交回 Gson 原生 Adapter，空响应默认 `null`。 |

## 4. 分层策略

分层策略把读取、写出和观测拆开，适合团队内部封装统一配置。

```kotlin
val config = SafeParserConfig.fromPolicies(
    readPolicy = SafeReadPolicy(
        fallbackPolicy = FallbackPolicy.NullOnly,
        primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson,
        useJdkUnsafe = false
    ),
    writePolicy = SafeWritePolicy(
        complexMapKeySerialization = false
    ),
    observerPolicy = SafeObserverPolicy(
        onEvent = { event -> println(event) }
    )
)
```

这样可以避免一个构造方法里塞太多参数，也方便把读策略、写策略和观测策略分别交给不同模块维护。

## 5. JSON 形态转换

JSON 形态转换默认关闭。不开启时，对象字段收到数组、集合字段收到对象，仍按原来的字段错形兜底处理。

它只处理字段级对象和数组漂移，不处理根级 JSON，也不把字符串里的 JSON 再解析一遍。

| 策略 | 支持行为 |
| --- | --- |
| `Disabled` | 不做对象和数组互转。 |
| `ObjectFromFirstArrayItem` | 对象字段收到数组时，读取数组第 1 个对象。 |
| `CollectionFromSingleObject` | 集合字段或对象数组字段收到对象时，包装成 1 个元素的容器。 |
| `ObjectAndCollection` | 同时开启上面两类转换。 |

全局开启：

```kotlin
val config = SafeParserConfig()
    .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
```

`withShapeCoercionPolicy(...)` 是简写。团队内部封装统一配置时，也可以使用 `withShapeCoercion(ShapeCoercionOptions(...))`。

只给某个字段开启：

```kotlin
data class ApiResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val data: User? = null
)
```

全局开启后，也可以让某个强契约字段保持原行为：

```kotlin
data class StrictEnvelope(
    @field:SafeParseDisableShapeCoercion
    val signedPayload: SignedPayload = SignedPayload()
)
```

如果 `errors: List<ApiError>` 本身就是后端 object/array 混合字段，不要禁用它；应使用 `CollectionFromSingleObject` 或 `ObjectAndCollection`。

边界：

1. 根级对象、根级集合和根级对象数组不会被转换。
2. Map 不会被转换，避免和 Gson 复杂 Map key 语义冲突。
3. 数字、布尔值、字符串不会被转换，也不会把字符串内容当 JSON 再读一遍。
4. 对象字段收到空数组、数组第 1 项不是对象、转换时 Adapter 失败，会记录 `ShapeCoercion` 事件并回到原兜底行为。
5. `Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 和真实传输 I/O 仍然外抛。

## 6. 事件观测

下面示例展示统一事件和几个兼容回调的组合写法。每个回调的职责见代码块后的说明。

```kotlin
val config = SafeParserConfig(
    onEvent = { event ->
        println(event)
    },
    onTypeMismatch = { event ->
        println("${event.path}: ${event.actualToken} -> ${event.expectedType}")
    },
    onAdapterCreationFailure = { event ->
        println("${event.typeName}: ${event.reason}")
    },
    onObserverFailure = { event ->
        println("${event.callbackName}: ${event.reason}")
    }
)
```

1. `onEvent` 是统一事件流，会收到类型错配、ShapeCoercion、Adapter 创建失败、空响应和 raw JSON 捕获跳过等事件。
2. `onTypeMismatch` 适合只关心字段类型错配的项目。
3. `onAdapterCreationFailure` 用来观察 Safe Adapter 创建失败；默认仍会交回 Gson。
4. `onObserverFailure` 用来观察业务日志、埋点等回调自身抛异常的情况。

回调自身抛出的普通异常不会中断解析。GsonSafeParser 会隔离观察者失败，避免日志系统问题影响接口解析。

`Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 这类不可安全隔离问题仍然外抛。

事件回调会在实际解析调用线程同步触发。如果多个线程共享同一个 Parser 或 Gson，回调里写入的外部集合、日志缓冲或指标容器需要由调用方保证线程安全。

`dispatchEvent` 是低层事件注入口，主要用于跨模块桥接。不建议业务代码直接调用。手动调用只会触发观察回调，不会写入当前 `parseSafe` 事件快照，也不代表真实解析已经发生。

## 7. 契约报告

```kotlin
val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""")
val report = result.contractReport()

if (report.hasIssues) {
    println(report.toMarkdown())
    println(report.toBackendMarkdown())
    println(report.summary.warningCount)
    println(report.toStructuredRows().firstOrNull()?.stableKey)
    println(report.toStructuredRows().firstOrNull()?.fields?.get("captureSkipReason"))
}
```

这些输出分别用于人工查看、后端修复、CI 判断、线上聚合和 raw JSON 捕获诊断。

契约报告只消费本次解析产生的事件，不重新解析 JSON，也不会修改解析结果。它适合接日志、CI 报表和接口问题复盘。

报告会保留字段 path、期望 JSON 形状、实际 JSON 形状、兜底动作、客户端影响、后端修复建议、`shapeCoercionAction` 和 `captureSkipReason`。

报告不会直接输出 raw JSON 正文或 Throwable。

机器侧接入优先用 `summary`、单条 issue 的 `stableKey` 和 `toStructuredRows()`，不要反向解析 Markdown。

## 8. 观察者失败报告

```kotlin
val observerFailures = mutableListOf<ObserverFailureEvent>()

val gson = GsonSafeParser.create(
    SafeParserConfig(
        onObserverFailure = observerFailures::add
    )
)

println(observerFailures.observerFailureReport().toMarkdown())
```

`observerFailureReport()` 会输出脱敏后的观察者失败报告，适合排查日志或埋点回调自身的问题。

报告会脱敏整理失败回调名称、来源事件类型、字段路径和异常类型，不直接输出原始 JSON 或异常栈。`ShapeCoercion` 事件会复用错形分类并保留事件名、路径和字段信息，不会落到 Unknown。

## 9. 注解

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

1. `@SafeParseDelegateToGson` 用于类，让该类型直接走 Gson 原生 Adapter。
2. `@SafeParseSkip` 用于字段，让 Safe Reflective 跳过该字段，适合缓存、运行时状态和平台对象。
3. `@SafeParseShapeCoercion` 用于字段，让该字段按指定策略做对象和数组形态转换；如果字段类型已由调用方自定义 Adapter 接管，仍优先保留 Gson 原生 Adapter。
4. `@SafeParseDisableShapeCoercion` 用于字段，让该字段忽略全局形态转换配置，保持原兜底行为。

## 10. 默认处理摘要

更完整的对象、集合、Map、基础类型、Kotlin 默认值、`org.json`、Retrofit 空响应和 raw JSON 捕获范围见 [错形能力矩阵（JSON 形状不一致）](mismatch-capability-matrix.md)。

当前开箱默认配置和可选能力状态：

| 项目 | 默认状态 |
| --- | --- |
| `fallbackPolicy` | `FallbackPolicy.NullOnly` |
| `primitiveParsingPolicy` | `PrimitiveParsingPolicy.DelegateToGson` |
| `emptyResponsePolicy` | `EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly` |
| `useJdkUnsafe` | `false` |
| `requiredConstructorParameterPolicy` | `RequiredConstructorParameterPolicy.GsonCompatible` |
| `mapItemKeyPolicy` | `MapItemKeyPolicy.Omit` |
| JSON 形态转换 | 默认关闭，状态为 `ShapeCoercionPolicy.Disabled`；调用 `withShapeCoercionPolicy(...)` 或字段注解后才启用。 |

默认处理重点：

| 类型 | 默认行为 |
| --- | --- |
| 对象、集合、Map 字段整体形状不一致 | 兜底当前字段，外层对象继续解析；`NullOnly` 下优先返回 `null` 或保留构造默认值。 |
| Kotlin 非空必填构造参数缺失 | 默认保持 Gson 兼容；引用字段为 `null`，primitive 保持 JVM 默认值。 |
| 基础类型形状不一致 | 默认交回 Gson 原生 Adapter；只有 `PrimitiveParsingPolicy.Safe` 才使用安全基础值。 |
| 调用方自定义 Adapter 命中 | 优先保留原生 Gson Adapter；自定义 Adapter 自己抛出的异常外抛，不伪装成字段兜底，也不会被字段级形态转换覆盖。 |
| 对象和数组形态转换 | 默认关闭；只有调用 `withShapeCoercionPolicy(...)` 或字段注解才会启用。 |
| Retrofit 空 body | `Unit` 返回 `Unit`，`Void` 和普通业务模型返回 `null`。 |
| 不可安全隔离问题 | JSON 语法错误、根级失败、`Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 继续外抛。 |
