# 兼容性说明

[English](en/compatibility.md)

这份文档只回答一个问题：你的 Android 项目能不能安全接入 GsonSafeParser。

先看结论：

1. 当前产物是 Android AAR，不是普通 JVM Jar。
2. 推荐接入环境是 `minSdk 23`、`compileSdk 36`、`JDK 17`、`Kotlin 2.0.21`、`Gson 2.13.2`。
3. Retrofit 模块当前验证版本是 `Retrofit 2.8.1`，并以运行时依赖携带 `OkHttp 4.12.0` 和 `Okio 3.6.0` 安全基线。
4. Retrofit 网络或传输读流异常不属于 JSON 错形，也不属于空响应；不要用 `emptyResponsePolicy` 隐藏这类异常。
5. 低版本项目不要直接上线，先按下面表格做兼容验证。

## 1. 版本矩阵

| 内容 | 当前验证版本与含义 |
| --- | --- |
| 发布产物 | Android AAR。纯 JVM 项目不能按普通 JVM jar 消费，Android 项目可直接接入。 |
| 库版本 | `1.0.4` 是当前稳定发布版本；`1.0.0` 是首个公开兼容基线。 |
| Android `minSdk` | `minSdk 23` 是硬边界。低于 23 时，AAR 合并或运行验证可能失败。 |
| Android `compileSdk` | `compileSdk 36` 是构建边界。低于 36 必须跑完整验证。 |
| JDK | `JDK 17` 是硬边界。JDK 8 / 11 构建链消费 Java 17 产物风险高。 |
| Kotlin 插件 | `Kotlin 2.0.21` 是编译兼容基线。老编译器可能读不稳 Kotlin 2.x metadata。 |
| Kotlin runtime | `Kotlin 2.0.21` 系列是运行基线。不要强制降级 Kotlin runtime。 |
| `kotlin-reflect` | `kotlin-reflect 2.0.21` 是功能依赖。缺失后 data class 默认值和非空字段兜底可能失效。 |
| Gson | `Gson 2.13.2` 是核心依赖。强制降级可能让 Safe Adapter 回退到原生 Gson。 |
| Retrofit | `Retrofit 2.8.1` 是 Retrofit 模块依赖。更低版本可能有 Converter API 差异。 |
| `converter-gson` | `2.8.1` 应与 Retrofit 主版本保持一致。 |
| OkHttp | `OkHttp 4.12.0` 是网络栈安全基线。让 Gradle 解析到 `4.12.0` 或更高。 |
| Okio | `Okio 3.6.0` 是网络栈安全基线。让 Gradle 解析到 `3.6.0` 或更高。 |
| R8 / ProGuard | AAR 内置框架规则已提供基础支持，但业务模型仍要补 keep 规则。 |

## 2. 低版本项目接入判断

| 项目状态 | 是否建议直接上线与原因 |
| --- | --- |
| `minSdk >= 23`，JDK 17，Gson 2.13.2 | 可以进入灰度。它符合当前验证矩阵，后续跑 debug/release 对照和业务 JSON 回归即可。 |
| Kotlin 低于 2.0 | 不建议直接上线。Kotlin metadata 和 reified API 兼容性不确定，先升级 Kotlin 再跑完整回归。 |
| 强制降级 Gson | 不建议直接上线。先跑 `GsonSafeParser.diagnostics()` 和 `integrationCheck()`，确认 Safe Adapter 没有回退风险。 |
| `minSdk < 23` | 不建议接入当前 AAR。这个库没有声明支持低于 23 的 Android 运行环境。 |
| release 开启 R8 | 可以上线，但不能零配置。业务模型规则不属于库能自动推断的范围，必须按 [Android 混淆配置](android-proguard.md) 配置 keep。 |

## 3. Retrofit 版本说明

`gson-safe-parser-retrofit` 公开依赖 `Retrofit 2.8.1`，并以运行时依赖携带 `OkHttp 4.12.0`、`Okio 3.6.0`。这样做是为了保留 Retrofit 2.x Converter API 的同时，避免消费者在没有声明网络栈版本时解析到 Retrofit 2.8.1 自带的 OkHttp 3.14.x / Okio 1.x，也避免把网络栈类型扩成 converter 模块的编译期 API。

如果业务项目已经使用更高 Retrofit 版本，通常可以由 Gradle 解析到更高版本，但上线前必须验证 4 件事：

1. 响应转换器能正常创建。
2. 空 body 策略符合预期。
3. 断网、请求取消、连接重置、TLS 失败等传输异常会交回 Retrofit / OkHttp 错误处理，不会被记录成 `EmptyResponse`、`RawJsonCaptureSkipped` 或 `TypeMismatch`。
4. raw JSON 捕获和超限跳过事件符合预期。

如果业务项目已经用 OkHttp 5 或其他统一网络栈，先用 `./gradlew dependencyInsight --dependency okhttp` 和 `./gradlew dependencyInsight --dependency okio` 确认最终依赖解析结果，再跑断网、取消、连接重置、TLS 失败和 raw JSON 捕获回归。

当前版本不主动升级到更高 Retrofit，是为了避免在发布前引入新的 Retrofit 行为变化。

## 4. Kotlin 版本说明

Kotlin 调用方最容易忽略的是 `kotlin-reflect`。

GsonSafeParser 为 Kotlin data class 做默认值和构造参数兜底时，需要读取 Kotlin 反射信息。release 包里如果缺少 `kotlin-reflect`、Kotlin Metadata 或构造方法，字段级兜底会降级或失败。

建议：

1. Kotlin 项目使用 `Kotlin 2.0.21` 或更高版本。
2. 不要强制降级 `kotlin-reflect`。
3. Java 调用方优先使用 `Class` / `Type` 入口；Kotlin reified API 只给 Kotlin 使用。
4. release 包必须保留业务模型的构造方法和 Kotlin Metadata。

## 5. 发布前自检

```kotlin
val diagnostics = GsonSafeParser.diagnostics()
check(diagnostics.hasErrors.not()) { diagnostics.checks.joinToString("\n") }

val integrationCheck = GsonSafeParser.integrationCheck(SafeParserConfig.production())
check(integrationCheck.hasErrors.not()) { integrationCheck.checks.joinToString("\n") }
```

如果项目覆盖了 Gson、Kotlin、Retrofit 或 R8 版本，先跑上面的自检，再跑真实接口 JSON 的 debug/release 对照。
