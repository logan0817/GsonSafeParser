# 1.0.1 发布说明

[English](en/release-notes-1.0.1.md)

`1.0.1` 是面向生产接入的稳定性修正版本。

这个版本不改变 GsonSafeParser 的定位：它仍然只是 Gson 的扩展层。能隔离到字段级的问题由库兜底；不能安全隔离的问题继续交回 Gson 原生 Adapter 或向外抛出。

## 本次变化

1. 当前发布版本统一为 `1.0.1`，包括 Gradle 发布版本、Demo 版本、README、快速开始、兼容性说明、发布清单和发布说明。
2. 默认构造参数策略继续保持 `RequiredConstructorParameterPolicy.GsonCompatible`。老项目接入时，不会因为某个 Kotlin 非空必填字段缺失就强制失败。
3. `RequiredConstructorParameterPolicy.Strict` 的优先级最高。只要开启 `Strict`，SafeParser 会禁用自身 Unsafe，也会禁用 Gson 回退路径里的 Unsafe；即使同时传入 `useJdkUnsafe = true`，也以 `Strict` 为准。
4. Demo 默认策略文案改为当前版本，避免用户误以为页面仍停留在旧发布版本。
5. `1.0.0` 继续作为首个公开 API 兼容基线保留在发布说明和兼容测试中。

## 升级说明

从 `1.0.0` 升级到 `1.0.1` 时，通常只需要把依赖坐标里的版本号改成 `1.0.1`。

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.1")
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.1")
```

如果项目显式开启了 `RequiredConstructorParameterPolicy.Strict`，请确认业务模型的非空必填构造参数都能从 JSON 中拿到值，或给这些参数提供默认值。

## 兼容边界

1. 发布产物仍是 Android AAR。
2. 当前验证矩阵仍是 `minSdk 23`、`compileSdk 36`、`JDK 17`、`Kotlin 2.0.21`、`kotlin-reflect 2.0.21`、`Gson 2.13.2`。
3. Retrofit 模块当前验证版本仍是 `Retrofit 2.8.1`。
4. release 包开启 R8 / ProGuard 时，业务模型仍要按文档保留字段名、构造方法和 Kotlin Metadata。
5. 如果业务项目强制降级 Gson、Kotlin、Retrofit 或低于 `minSdk 23`，必须先跑完整兼容验证，不能直接上线。

## 发布验证

本版本发布前应覆盖以下门禁：

1. core、retrofit、demo debug 单测。
2. demo release 单测。
3. core、retrofit、demo release lint。
4. demo debug 和 release APK 构建。
5. `publishToMavenLocal`。
6. Maven local AAR、POM、sources、Dokka javadoc 和 consumer ProGuard 规则校验。
7. `releaseToMavenCentral --dry-run`。
8. `git diff --check`。
