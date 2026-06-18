# 1.0.4 发布说明

[English](en/release-notes-1.0.4.md)

`1.0.4` 是自定义 Adapter 边界与开源发布准备版本。

这个版本不扩大默认兜底范围，重点是把调用方显式接管的 Gson Adapter 行为还给原生 Gson 链路，同时补齐上线前文档、示例和发布门禁。

## 本次变化

1. 调用方显式注册的 `TypeAdapter`、`TypeAdapterFactory`、`registerTypeHierarchyAdapter(...)` 和 `@JsonAdapter` 优先保留原生 Gson 链路。
2. 自定义 Adapter 自己抛出的 `IOException`、`JsonParseException`、`IllegalStateException`、`NumberFormatException` 和普通 `RuntimeException` 会向外抛出，不再被伪装成字段兜底。
3. SafeParser 检查调用方工厂时遵循 Gson 的后注册优先规则，避免和原生 `GsonBuilder` 行为不一致。
4. 基础类型和 `String` 默认继续交回 Gson；只有显式配置 `PrimitiveParsingPolicy.Safe` 时才启用安全基础值或默认值兜底。
5. 补强对象、集合、Map、数组、嵌套字段 Adapter、类级 `@JsonAdapter`、层级 Adapter 和 Retrofit 转换器边界测试。
6. README、快速开始、API 参考、examples、发布清单和开源协作文件按开源框架上线标准整理。
7. 文档契约测试统一归一化 CRLF / LF 行尾，避免 Windows 与 CI 结果不一致。
8. Gradle 发布版本、Demo 版本、依赖坐标、兼容性文档、发布清单和发布说明统一到 `1.0.4`。

## 行为边界

1. SafeParser 内建 Adapter 读取到可隔离的字段错形时，仍会记录事件并保留外层对象解析。
2. 调用方自定义 Adapter 命中后，读取失败按 Gson 原生异常传播处理，不产生 SafeParser 错形事件。
3. `PrimitiveParsingPolicy.DelegateToGson` 仍是默认值；基础类型和 `String` 错形默认不会被安全基础值吞掉。
4. `ShapeCoercionPolicy` 仍默认关闭；`1.0.3` 的显式形态转换能力在 `1.0.4` 中保持兼容。
5. 网络、传输、取消、fatal 异常继续外抛，不会被空响应策略或字段兜底隐藏。

## 升级方式

从 `1.0.3` 升级到 `1.0.4` 时，通常只需要把依赖版本号改成 `1.0.4`：

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.4")
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.4")
```

如果你的业务依赖自定义 Adapter 抛错后仍继续解析外层对象，需要重新确认该 Adapter 的错误处理策略。`1.0.4` 会更贴近原生 Gson：调用方接管的 Adapter 抛错后向外传播。

## 发布验证

本版本发布前应覆盖以下门禁：

1. core、retrofit、demo debug 单测。
2. demo release 单测。
3. core、retrofit、demo release lint。
4. demo debug 和 release APK 构建。
5. 自定义 Adapter 在字段、集合、Map、数组、嵌套对象和层级注册中的外抛边界。
6. 基础类型默认委托 Gson 与显式 `PrimitiveParsingPolicy.Safe` 兜底边界。
7. `publishToMavenLocal`。
8. `verifyMavenLocalPublicationArtifacts`。
9. `releaseToMavenCentral --dry-run`。
10. Markdown 本地相对链接检查。
11. `git diff --check`。
