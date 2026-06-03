# 配置说明

[English](en/configuration.md)

GsonSafeParser 的配置目标很直接：能安全兜底的字段由库处理，不能确认安全的问题交回 Gson 原生 Adapter。

本文档说明配置项、构造策略、预设、事件、契约报告和注解。完整 JSON 形状不一致范围见 [错形能力矩阵（JSON 形状不一致）](mismatch-capability-matrix.md)。

## 1. 基础配置

```kotlin
val config = SafeParserConfig( // 创建一份完整的安全解析配置。
    fallbackPolicy = FallbackPolicy.NullOnly, // 字段形状不一致时默认只返回 null 或保留构造默认值。
    emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly, // Retrofit 空响应只为 Unit/Void 返回空值。
    primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson, // 基础类型默认交回 Gson 原生 Adapter。
    complexMapKeySerialization = false, // 默认不启用复杂 Map key 写出。
    useJdkUnsafe = false, // SafeParser 自己默认不使用 Unsafe 绕过构造函数。
    skippedPlatformTypePrefixes = setOf("android."), // 跳过 Android 平台类型，避免反射系统对象；不要把业务模型包名前缀放这里。
    nullValuePolicy = NullValuePolicy.WriteExplicitNulls, // 显式 JSON null 只写入 nullable 字段。
    requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.GsonCompatible, // Kotlin 非空必填构造参数缺失时保持 Gson 兼容。
    mapItemKeyPolicy = MapItemKeyPolicy.Hash, // Map item 事件默认输出稳定哈希。
    captureRawJsonInCallbacks = false, // 默认不在回调里携带原始 JSON。
    maxRawJsonCaptureBytes = 1024 * 1024 // 限制 raw JSON 最大捕获体积为 1 MiB。
) // 结束安全解析配置。
```

配置项说明：

| 配置项 | 默认值 | 什么时候改它 |
| --- | --- | --- |
| `fallbackPolicy` | `NullOnly` | 集合或 Map 整体形状不一致时，如果你希望返回空集合、空 Map，再改成 `Default`。 |
| `emptyResponsePolicy` | `DefaultValueForUnitOrVoidOnly` | Retrofit 空 body 要返回默认对象、`null` 或交回 Gson 时再改。 |
| `primitiveParsingPolicy` | `DelegateToGson` | 基础类型需要宽松兜底，例如字符串数字、空字符串、形状不一致默认值时再改成 `Safe`。 |
| `complexMapKeySerialization` | `false` | 需要兼容 Gson 复杂 Map key 的数组 entry 写法时再打开。 |
| `useJdkUnsafe` | `false` | 只在 `GsonCompatible` 模式下控制 SafeParser 自己是否用 Unsafe 构造对象；`Strict` 会强制禁用它。 |
| `skippedPlatformTypePrefixes` | `setOf("android.")` | 只用于跳过平台类型；不要放业务模型包名前缀。 |
| `nullValuePolicy` | `WriteExplicitNulls` | 后端显式返回 `null` 时，需要调整 nullable 字段写入策略时再改。 |
| `requiredConstructorParameterPolicy` | `GsonCompatible` | 已有项目希望保持 Gson 宽松行为时用默认值；新接口想强约束缺字段时改成 `Strict`。 |
| `mapItemKeyPolicy` | `Hash` | 本地排障想看到 Map 原始 key 时，可以在 debug 配置中改。 |
| `captureRawJsonInCallbacks` | `false` | 排障时临时打开，线上默认关闭。 |
| `maxRawJsonCaptureBytes` | `1 MiB` | raw JSON 排障需要更小或更大的捕获上限时再改。 |

raw JSON 捕获规则：

1. 普通 Gson 解析会按 UTF-8 字节数安全截断，不会切断中文或 emoji。
2. Retrofit 已知长度响应会先看 `contentLength`。
3. gzip、chunked 这类未知长度响应会做有界探测，未超限才捕获，超限就跳过。

`instanceCreators`、`reflectionAccessFilters` 和 `skippedPlatformTypePrefixes` 会在创建 `SafeParserConfig` 时保存快照。

后续修改调用方原来的可变集合，不会影响已经创建好的配置。

`enableSafeParser()` 对同一个 `GsonBuilder` 是幂等的，重复调用不会重复注册 Safe Adapter。如果需要换配置，请新建一个 `GsonBuilder`。

## 2. 构造策略与 Unsafe

这两个配置只解决一个问题：对象没有安全构造路径时，库要不要允许 Unsafe 绕过构造函数。

先看结论：

| 配置组合 | SafeParser 自己是否允许 Unsafe | Gson 回退路径是否允许 Unsafe | 适合场景 |
| --- | --- | --- | --- |
| `GsonCompatible + useJdkUnsafe = false` | 不允许。 | 保留 Gson 原生行为。 | 默认推荐。适合大多数老项目迁移，降低 SafeParser 自己引入额外构造风险的概率。 |
| `GsonCompatible + useJdkUnsafe = true` | 允许。 | 保留 Gson 原生行为。 | 需要最大程度贴近原生 Gson 构造行为时使用。 |
| `Strict + useJdkUnsafe = false` | 不允许。 | 不允许。 | 新接口或强契约场景。缺字段、`null`、错形或未知枚举值要尽早暴露。 |
| `Strict + useJdkUnsafe = true` | 不允许。 | 不允许。 | 不建议这样写；`Strict` 优先级最高，`useJdkUnsafe = true` 会被忽略。 |

`GsonCompatible` 是兼容模式。它的目标是尽量不打断已经依赖 Gson 宽松行为的项目。

在这个模式下，`useJdkUnsafe` 只控制 SafeParser 自己的构造层：

1. `useJdkUnsafe = false` 时，SafeParser 自己不会用 Unsafe 绕过构造函数。
2. `useJdkUnsafe = true` 时，如果没有可用构造方法、没有默认值构造路径、也没有 `InstanceCreator`，SafeParser 最后可以用 Unsafe 创建对象。
3. 如果 SafeParser 无法安全处理当前类型，仍会交回 Gson 原生 Adapter；这条回退路径继续遵循 Gson 自己的 Unsafe 设置。

`Strict` 是严格模式。它的目标是把缺失的必填构造参数当成接口契约问题。

只要开启 `Strict`，SafeParser 会同时关闭两条 Unsafe 路径：

1. SafeParser 自己不会用 Unsafe 创建对象。
2. Gson delegate 回退路径也不会继续用 Unsafe 绕过构造校验。
3. 即使同时传入 `useJdkUnsafe = true`，也以 `Strict` 为准。

推荐用法：

| 你的目标 | 推荐配置 |
| --- | --- |
| 老项目低成本迁移，先保证不增加解析失败 | 保持默认配置：`GsonCompatible + useJdkUnsafe = false`。 |
| 老项目过去明显依赖 Gson Unsafe 构造，短期内无法改模型 | 临时使用 `GsonCompatible + useJdkUnsafe = true`，同时补业务模型 keep 规则和真实 JSON 回归。 |
| 新接口、强契约、希望尽早发现后端缺字段 | 使用 `Strict + useJdkUnsafe = false`。 |

Unsafe 的风险很明确：它会绕过构造函数和 `init` 代码。对象可能被创建出来，但 Kotlin 默认值、非空约束和构造校验不一定执行。因此，`useJdkUnsafe = true` 只适合作为兼容旧行为的过渡方案，不建议作为新项目默认配置。

## 3. 预设配置

```kotlin
val production = SafeParserConfig.production() // 创建线上默认配置。
val debug = SafeParserConfig.debug() // 创建联调配置，默认开启 raw JSON 捕获。
val lowInterference = SafeParserConfig.lowInterference() // 创建低误伤配置，行为更接近原生 Gson。
```

预设说明：

| 预设 | 适合场景 | 主要行为 |
| --- | --- | --- |
| `production()` | 线上默认接入。 | 契约优先读策略、Map item key 哈希、事件观测，不携带整段 raw JSON。 |
| `debug()` | 联调、测试和接口排障。 | 与线上读策略一致，但开启有限长度 raw JSON 捕获，并输出明文 Map item key。 |
| `lowInterference()` | 灰度接入和低干预优先。 | 字段、集合、Map 整体形状不一致优先 `null`，基础类型交回 Gson 原生 Adapter，空响应默认 `null`。 |

## 4. 分层策略

```kotlin
val config = SafeParserConfig.fromPolicies( // 使用分层策略创建配置。
    readPolicy = SafeReadPolicy( // 配置读取 JSON 时的策略。
        fallbackPolicy = FallbackPolicy.NullOnly, // 字段形状不一致时只返回 null 或保留构造默认值。
        primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson, // 基础类型交回 Gson 原生 Adapter。
        useJdkUnsafe = false // SafeParser 自己不使用 Unsafe 绕过构造函数。
    ), // 结束读取策略配置。
    writePolicy = SafeWritePolicy( // 配置写出 JSON 时的策略。
        complexMapKeySerialization = false // 默认不启用复杂 Map key 写出。
    ), // 结束写出策略配置。
    observerPolicy = SafeObserverPolicy( // 配置解析事件观察策略。
        onEvent = { event -> // 接收统一解析事件。
            println(event) // 输出事件，方便接日志或监控。
        } // 结束事件回调。
    ) // 结束观察策略配置。
) // 结束分层配置创建。
```

分层策略适合团队内部封装统一配置，把读取、写出和观测职责拆开，避免一个构造方法里塞太多参数。

## 5. 事件观测

```kotlin
val config = SafeParserConfig( // 创建带事件回调的安全解析配置。
    onEvent = { event -> // 接收统一解析事件。
        println(event) // 输出事件对象。
    }, // 结束统一事件回调。
    onTypeMismatch = { event -> // 接收字段类型错配事件。
        println("${event.path}: ${event.actualToken} -> ${event.expectedType}") // 输出字段路径、实际 token 和期望类型。
    }, // 结束类型错配回调。
    onAdapterCreationFailure = { event -> // 接收 Safe Adapter 创建失败事件。
        println("${event.typeName}: ${event.reason}") // 输出失败类型和失败原因。
    }, // 结束 Adapter 创建失败回调。
    onObserverFailure = { event -> // 接收业务回调自身抛异常的事件。
        println("${event.callbackName}: ${event.reason}") // 输出失败回调名称和原因。
    } // 结束观察者失败回调。
) // 结束事件配置。
```

1. `onEvent` 是统一事件流，会收到类型错配、Adapter 创建失败、空响应和 raw JSON 捕获跳过等事件。
2. `onTypeMismatch` 适合只关心字段类型错配的项目。
3. `onAdapterCreationFailure` 用来观察 Safe Adapter 创建失败；默认仍会交回 Gson。
4. `onObserverFailure` 用来观察业务日志、埋点等回调自身抛异常的情况。

回调自身抛出的普通异常不会中断解析。GsonSafeParser 会隔离观察者失败，避免日志系统问题影响接口解析。

`Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 这类不可安全隔离问题仍然外抛。

事件回调会在实际解析调用线程同步触发。如果多个线程共享同一个 Parser 或 Gson，回调里写入的外部集合、日志缓冲或指标容器需要由调用方保证线程安全。

`dispatchEvent` 是低层事件注入口，主要用于跨模块桥接。不建议业务代码直接调用。手动调用只会触发观察回调，不会写入当前 `parseSafe` 事件快照，也不代表真实解析已经发生。

## 6. 契约报告

```kotlin
val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""") // 解析一份 Object 字段形状不一致的 JSON。
val report = result.contractReport() // 把解析事件转换成契约报告。

if (report.hasIssues) { // 判断本次解析是否发现契约问题。
    println(report.toMarkdown()) // 输出 Markdown 格式报告。
    println(report.toBackendMarkdown()) // 输出给后端修接口用的契约报告。
    println(report.summary.warningCount) // 输出 warning 数，方便 CI 或日志平台判断。
    println(report.toStructuredRows().firstOrNull()?.stableKey) // 输出稳定指纹，方便线上聚合同类问题。
    println(report.toStructuredRows().firstOrNull()?.fields?.get("captureSkipReason")) // 输出 raw JSON 捕获跳过原因。
} // 结束问题报告输出。
```

契约报告只消费本次解析产生的事件，不重新解析 JSON，也不会修改解析结果。它适合接日志、CI 报表和接口问题复盘。

报告会保留字段 path、期望 JSON 形状、实际 JSON 形状、兜底动作、客户端影响、后端修复建议和 `captureSkipReason`。

报告不会直接输出 raw JSON 正文或 Throwable。

机器侧接入优先用 `summary`、单条 issue 的 `stableKey` 和 `toStructuredRows()`，不要反向解析 Markdown。

## 6. 观察者失败报告

```kotlin
val observerFailures = mutableListOf<ObserverFailureEvent>() // 创建列表收集观察者失败事件。

val gson = GsonSafeParser.create( // 创建带观察者失败收集能力的 Gson。
    SafeParserConfig( // 创建安全解析配置。
        onObserverFailure = observerFailures::add // 将观察者失败事件加入列表。
    ) // 结束安全解析配置。
) // 结束 Gson 创建。

println(observerFailures.observerFailureReport().toMarkdown()) // 输出脱敏后的观察者失败报告。
```

报告会脱敏整理失败回调名称、来源事件类型、字段路径和异常类型，不直接输出原始 JSON 或异常栈。

## 7. 注解

```kotlin
@SafeParseDelegateToGson // 让这个类型直接使用 Gson 原生 Adapter。
class StrictModel // 定义一个需要严格按原生 Gson 解析的模型。

data class PageState( // 定义包含运行时状态的页面模型。
    @field:SafeParseSkip // 告诉 Safe Reflective 跳过这个字段。
    val runtimeCache: Any? = null // 保存运行时缓存，不从 JSON 中读取。
) // 结束页面模型定义。
```

1. `@SafeParseDelegateToGson` 用于类，让该类型直接走 Gson 原生 Adapter。
2. `@SafeParseSkip` 用于字段，让 Safe Reflective 跳过该字段，适合缓存、运行时状态和平台对象。

## 8. 默认处理摘要

更完整的对象、集合、Map、基础类型、Kotlin 默认值、`org.json`、Retrofit 空响应和 raw JSON 捕获范围见 [错形能力矩阵（JSON 形状不一致）](mismatch-capability-matrix.md)。

当前开箱默认配置：

| 配置 | 默认值 |
| --- | --- |
| `fallbackPolicy` | `FallbackPolicy.NullOnly` |
| `primitiveParsingPolicy` | `PrimitiveParsingPolicy.DelegateToGson` |
| `emptyResponsePolicy` | `EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly` |
| `useJdkUnsafe` | `false` |
| `requiredConstructorParameterPolicy` | `RequiredConstructorParameterPolicy.GsonCompatible` |
| `mapItemKeyPolicy` | `MapItemKeyPolicy.Hash` |

默认处理重点：

| 类型 | 默认行为 |
| --- | --- |
| 对象、集合、Map 字段整体形状不一致 | 兜底当前字段，外层对象继续解析；`NullOnly` 下优先返回 `null` 或保留构造默认值。 |
| Kotlin 非空必填构造参数缺失 | 默认保持 Gson 兼容；引用字段为 `null`，primitive 保持 JVM 默认值。 |
| 基础类型形状不一致 | 默认交回 Gson 原生 Adapter；只有 `PrimitiveParsingPolicy.Safe` 才使用安全基础值。 |
| Retrofit 空 body | `Unit` 返回 `Unit`，`Void` 和普通业务模型返回 `null`。 |
| 不可安全隔离问题 | JSON 语法错误、根级失败、`Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 继续外抛。 |
