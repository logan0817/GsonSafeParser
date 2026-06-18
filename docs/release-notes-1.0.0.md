# 1.0.0 发布说明

[English](en/release-notes-1.0.0.md)

`1.0.0` 是 GsonSafeParser 的首个公开发布版本。

这个版本的定位很明确：GsonSafeParser 是 Gson 的扩展层，不是新的 JSON 协议解释器。能隔离到字段级的 JSON 形状不一致由库兜底；不能安全隔离的问题继续交回 Gson 原生 Adapter 或向外抛出。

## 首发能力

1. 字段级安全解析：对象、集合、Map、Kotlin data class 默认值和 `org.json` 类型按配置处理可恢复错形；基础类型只有显式启用 `PrimitiveParsingPolicy.Safe` 时才使用安全基础值。
2. Gson 回退边界：Safe Adapter 创建失败、GsonBuilder 兼容信息缺失、不可确认类型和不可恢复异常都会回到 Gson 或继续外抛。
3. Kotlin API：提供 `fromJsonSafe<T>()`、`parseSafe<T>()`、事件快照和可复用 Parser。
4. Retrofit 接入：提供 `GsonSafeConverterFactory`，支持空响应策略、raw JSON 有界捕获和超限跳过事件。
5. 接入自检：提供 `diagnostics()`、`explainType()`、`integrationCheck()` 和 `GsonSafeModelProbe`，便于 CI 与 release 包自查。
6. 契约报告：`contractReport()`、`toBackendMarkdown()` 和 `toStructuredRows()` 可把解析事件转成后端可读的问题说明。

## 兼容边界

1. 发布产物是 Android AAR。
2. 当前验证矩阵是 `minSdk 23`、`compileSdk 36`、`JDK 17`、`Kotlin 2.0.21`、`kotlin-reflect 2.0.21`、`Gson 2.13.2`。
3. Retrofit 模块当前验证版本是 `Retrofit 2.8.1`。
4. release 包开启 R8 / ProGuard 时，业务模型仍要按文档保留字段名、构造方法和 Kotlin Metadata。
5. 如果业务项目强制降级 Gson、Kotlin、Retrofit 或低于 `minSdk 23`，必须先跑完整兼容验证，不能直接上线。

## 发布验证

本版本发布前已覆盖以下门禁：

1. core、retrofit、demo debug 单测。
2. demo release 单测。
3. core、retrofit、demo release lint。
4. demo debug 和 release APK 构建。
5. `publishToMavenLocal`。
6. Maven local AAR、POM、sources、Dokka javadoc 和 consumer ProGuard 规则校验。
7. `releaseToMavenCentral --dry-run`。
8. `git diff --check`。
