# 1.0.2 发布说明

[English](en/release-notes-1.0.2.md)

`1.0.2` 是传输异常边界修正版本。

这个版本继续保持 GsonSafeParser 的定位：它是 Gson 的扩展层，不接管网络层，也不伪装网络失败。

字段级错形可以安全隔离时由库兜底。网络中断、读流取消、连接重置这类问题不能安全隔离，必须交回调用方处理。

## 本次变化

1. 网络和传输读流异常不再被当成字段错形。`InterruptedIOException`、socket reset、broken pipe、OkHttp stream reset 等异常会直接外抛。
2. Retrofit 空响应探测遇到传输失败时，不再记录 `EmptyResponse` 事件，也不会把失败伪装成空响应。
3. Retrofit raw JSON 探测遇到传输失败时，不再记录 `RawJsonCaptureSkipped` 事件，避免误导排障方向。
4. 普通自定义 Adapter 抛出的 `IOException` 在 `1.0.2` 当时仍保持局部兜底行为；当前版本已在 `1.0.3` 收紧为保留原生 Gson 自定义 Adapter 链路，异常向外抛出。
5. 当前发布版本统一为 `1.0.2`，包括 Gradle 发布版本、Demo 版本、README、快速开始、兼容性说明、发布清单和发布说明。

## 升级说明

从 `1.0.1` 升级到 `1.0.2` 时，通常只需要把依赖坐标里的版本号改成 `1.0.2`。

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.2")
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.2")
```

如果 App 关机网络、断网、请求取消或 OkHttp 流被重置，`1.0.2` 会让这些异常按网络层错误继续向外传播。

业务侧应按原 Retrofit / OkHttp 错误处理流程处理，不要把它们当成 JSON 字段错形，也不要用 `emptyResponsePolicy` 隐藏。

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
