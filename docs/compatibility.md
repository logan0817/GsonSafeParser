# 兼容性说明

[English](en/compatibility.md)

这份文档只回答一个问题：你的 Android 项目能不能安全接入 GsonSafeParser。

先看结论：

1. 当前产物是 Android AAR，不是普通 JVM Jar。
2. 推荐接入环境是 `minSdk 23`、`compileSdk 36`、`JDK 17`、`Kotlin 2.0.21`、`Gson 2.13.2`。
3. Retrofit 模块当前验证版本是 `Retrofit 2.8.1`。
4. 低版本项目不要直接上线，先按下面表格做兼容验证。

## 1. 版本矩阵

| 内容 | 当前验证版本 | 限制类型 | 低版本风险 | 建议 |
| --- | --- | --- | --- | --- |
| 发布产物 | Android AAR | 硬边界 | 纯 JVM 项目不能按 Android AAR 方式消费。 | Android 项目直接接入；纯 JVM 需要单独产物。 |
| 库版本 | `1.0.1` | 当前稳定发布版本 | `1.0.0` 是首个公开兼容基线，低于 `1.0.0` 的内部迭代版本不作为公开兼容承诺。 | 新接入使用 `1.0.1`。 |
| Android `minSdk` | `minSdk 23` | 硬边界 | App 低于 23 时，AAR 合并或运行验证可能失败。 | 业务 App 保持 `minSdk 23` 或更高。 |
| Android `compileSdk` | `compileSdk 36` | 构建边界 | 低 `compileSdk` 可能遇到 AAR metadata、Lint 或工具链差异。 | 推荐 `compileSdk 36`；低于 36 必须跑完整验证。 |
| JDK | `JDK 17` | 硬边界 | JDK 8 / 11 构建链消费 Java 17 产物风险高。 | 使用 JDK 17 或更高版本。 |
| Kotlin 插件 | `Kotlin 2.0.21` | 编译兼容边界 | 老 Kotlin 编译器读取 Kotlin 2.x metadata 时可能失败，尤其是 reified API、扩展函数和默认参数。 | Kotlin 项目推荐 `Kotlin 2.0.21` 或更高。 |
| Kotlin runtime | `Kotlin 2.0.21` 系列 | 运行边界 | 强制降级 stdlib 可能出现运行期方法缺失或 metadata 行为差异。 | 不要强制降级 Kotlin runtime。 |
| `kotlin-reflect` | `kotlin-reflect 2.0.21` | 功能依赖 | Kotlin data class 默认值、主构造参数和非空字段兜底可能失效。 | 保持 `kotlin-reflect 2.0.21`，或与项目 Kotlin 主版本一致后完整回归。 |
| Gson | `Gson 2.13.2` | 核心依赖 | 强制降级 Gson 可能导致 GsonBuilder 内部字段读取失败，Safe Adapter 降级回 Gson 原生链路。 | 使用 `Gson 2.13.2`；覆盖版本后先跑 `diagnostics()`。 |
| Retrofit | `Retrofit 2.8.1` | Retrofit 模块依赖 | 强制更低版本可能出现 Converter API 差异。 | Retrofit 场景不要低于 `2.8.1`。 |
| `converter-gson` | `2.8.1` | Retrofit 内部实现 | 与 Retrofit 主版本不一致时，转换链路行为可能不同。 | 保持 Retrofit 和 converter-gson 版本一致。 |
| R8 / ProGuard | AAR 内置框架规则 | release 稳定边界 | 业务模型字段名、构造方法、Kotlin Metadata 被裁剪后，Gson 绑定会失真。 | release 包必须配置业务模型 keep 规则。 |

## 2. 低版本项目接入判断

| 项目状态 | 是否建议直接上线 | 原因 | 处理方式 |
| --- | --- | --- | --- |
| `minSdk >= 23`，JDK 17，Gson 2.13.2 | 可以进入灰度 | 符合当前验证矩阵。 | 跑 debug/release 对照和业务 JSON 回归。 |
| Kotlin 低于 2.0 | 不建议直接上线 | Kotlin metadata 和 reified API 兼容性不确定。 | 优先升级 Kotlin；短期内用 `Class` / `Type` API 并跑完整回归。 |
| 强制降级 Gson | 不建议直接上线 | Safe Adapter 依赖 GsonBuilder 内部字段快照，旧 Gson 可能缺字段。 | 调用 `GsonSafeParser.diagnostics()` 和 `integrationCheck()`。 |
| `minSdk < 23` | 不建议接入当前 AAR | 当前库没有声明支持低于 23 的 Android 运行环境。 | 升级业务 `minSdk`，或讨论是否需要新兼容目标。 |
| release 开启 R8 | 可以上线但不能零配置 | 业务模型规则不属于库能自动推断的范围。 | 按 [Android 混淆配置](android-proguard.md) 配置 keep。 |

## 3. Retrofit 版本说明

`gson-safe-parser-retrofit` 公开依赖 `Retrofit 2.8.1`。

如果业务项目已经使用更高 Retrofit 版本，通常可以由 Gradle 解析到更高版本，但上线前必须验证 3 件事：

1. 响应转换器能正常创建。
2. 空 body 策略符合预期。
3. raw JSON 捕获和超限跳过事件符合预期。

当前版本不主动升级到更高 Retrofit，是为了避免在发布前引入新的 Retrofit / OkHttp 行为变化。

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
