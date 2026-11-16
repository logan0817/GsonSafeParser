# 1.0.5 发布说明（候选版）

[English](en/release-notes-1.0.5.md)

**状态：`1.0.5` 待发布。Maven Central 当前稳定版仍为 `1.0.4`。** 本文描述本次候选源码的变化，本地构建、测试或发布到临时仓库均不表示已经公开发布。

`1.0.5` 重点修复解析恢复、观察回调和 Retrofit 转换的可靠性问题。默认配置与依赖版本保持不变，但过去被错误吞掉的语法错误、被转换成假键的 Map 输入等行为会改变，升级前应覆盖下面的回归场景。

## 从 1.0.4 升级时需要关注

| 场景 | 1.0.5 候选版行为与迁移建议 |
| --- | --- |
| Map 键为空白或形状错误 | 数字 Map 的空白键不再变成 `0` 或 `null`；对象形式与数组 entry 形式都会跳过该条目。数组 entry 中内建 Adapter 无法接受的键形状也会跳过，不制造 `null` 键。显式 JSON `null` 键仍取决于目标 Map 和 Adapter 是否支持。不要依赖过去生成的假键。 |
| 非法转义、截断 JSON | 语法错误向外抛出，不能靠字段或元素兜底转换成功；数组读取也不会反复重试无法读取的元素。调用方应沿用正常的解析失败处理。 |
| Collection、对象数组与原始类型数组 | 可隔离的坏元素或容器拒绝插入的元素只影响当前项，保留有效相邻项；Safe 数字数组读取 `[1,"bad",2]` 时保留 `[1,2]`。原始类型数组跳过 `null`，对象数组保留显式 `null`。已消费的非法字符值不再导致后续字段丢失。 |
| 调用方 Adapter 与字段规则 | 字段 `@JsonAdapter` 的脱敏、协议转换等序列化逻辑优先保留；只注册反序列化器或在数组中存放子类时，序列化保留运行时子类字段。调用方 Adapter 可接管显式 `null`，包括 `@JsonAdapter(nullSafe = false)`；返回 `null` 后仍应用字段的空值策略与可空性。显式允许的 `transient` 字段遵循调用方排除规则，`static` 仍排除。 |
| 安全数值转换 | `PrimitiveParsingPolicy.Safe` 的整数、`BigDecimal`、`BigInteger`、数字布尔转换，以及默认动态数字策略，限制数字文本最多 10,000 个字符，且 `abs(BigDecimal.scale()) < 10,000`。限制在整数展开前检查；可隔离的字段保留默认值并记录事件，无法隔离的动态 `Any/Object` 数字失败会外抛。请回归超长数值和异常指数。 |
| 观察回调中再次解析 | `onTypeMismatch` 等观察回调及 `onObserverFailure` 执行时，原文、Map 路径和事件收集上下文相互隔离。回调中独立发起的解析或 Retrofit 调用不再继承外层原文，也不再把事件追加进外层 `parseSafe` 快照。若需要合并监控结果，应由调用方显式收集。 |

`ConcurrentMap` 等容器拒绝插入 `null` 键或值时，仅跳过当前 entry、记录 `MAP_ITEM` 事件，并保留前后有效条目。这与调用方 Adapter 的读取失败分开处理：Adapter 正常返回 `null` 后的容器拒绝可隔离，Adapter 自己抛出的读取异常继续外抛。

这些恢复仅适用于可安全隔离的内建解析或容器操作。调用方 Adapter 的读取异常、网络传输异常、取消和 fatal 异常继续外抛；Map 重复键继续遵循 Gson 2.13.2 原有语义。

同时修复 Safe 模式的 Boolean 转 String，并让根类型不匹配事件保留实际数字 token。Map 的隐私策略现在覆盖嵌套路径、由键推导的字段名、形态转换事件和数字键失败原因；`Omit` 或 `Hash` 不再被这些观察字段绕过。显式启用的 `rawJson` 仍是原文，不受 Map 键策略脱敏。

## Retrofit 响应处理

1. 原响应体在空响应探测、原文探测、观察回调或转换失败时也会关闭；传输读取失败继续交回调用方。
2. 开启原文捕获后，仍由 Retrofit 官方 Gson converter 读取原响应，保留调用方配置的 Gson 严格性、完整消费检查、字符集和 BOM 处理。原文探测受大小限制，关闭捕获或超限跳过时会隔离外层原文上下文。
3. 空响应判断读取实际字节，不再仅凭 `contentLength() == 0` 返回空值。因此拦截器提供了有效 JSON、但声明长度为零时仍会解析；真实空流继续执行对应空响应策略，读取异常不会被当成空响应。
4. 常规 Retrofit 服务入口中，内建 Unit/Void converter 与 HTTP 204/205 处理优先于 SafeParser。直接调用 SafeParser converter 的行为不能推导为服务入口一定产生 `EmptyResponse` 事件。
5. 回归测试覆盖实际 Retrofit 服务调用，包括原文隔离、严格性、坏语法、零长度声明、资源关闭和传输失败边界。

## 默认配置与依赖保持不变

- 基础类型默认仍为 `PrimitiveParsingPolicy.DelegateToGson`，只有显式选择 `Safe` 才启用安全基础值恢复；默认兜底仍为 `FallbackPolicy.NullOnly`。
- 构造参数默认仍为 `RequiredConstructorParameterPolicy.GsonCompatible`，`useJdkUnsafe = false`；JSON 形态转换默认关闭。
- 原文捕获默认关闭，默认上限仍为 `1 MiB`；Map 键观察默认仍为 `MapItemKeyPolicy.Omit`。直接 `gson.fromJson(...)` 不会因为打开捕获配置就自动获得原文，应使用 `parseSafe`、Parser 或有上下文的解析入口。
- 观察回调仍在解析线程同步执行；多线程复用 Gson 或 Parser 时，调用方提供的事件集合与日志设施需要线程安全。

| 项目 | 保持的版本或边界 |
| --- | --- |
| Android 产物 | AAR；`minSdk 23`、`compileSdk 36` |
| 构建与 Kotlin | JDK 17、Kotlin / kotlin-reflect 2.0.21 |
| Gson | 2.13.2 |
| Retrofit / converter-gson | 2.8.1 |
| Retrofit 运行时网络栈基线 | OkHttp 4.12.0、Okio 3.6.0，继续位于 runtime 依赖 |

具体兼容性与业务模型混淆要求见[兼容性说明](compatibility.md)和 [Android 混淆配置](android-proguard.md)。

## 新增低层 API

新增 `GsonSafeParser.withRawJsonContext(json, config, block)`，供第三方转换器桥接有限原文，需要 `@OptIn(GsonSafeParserLowLevelApi::class)`。它在当前线程执行 `block` 期间绑定观察上下文，遵循捕获开关和 UTF-8 字节上限；嵌套调用或异常退出后恢复旧上下文。

该接口不解析 JSON、不注册 Adapter、不改变严格性、不吞异常，也不负责关闭转换器的原始响应。普通业务继续使用现有入口；完整约定见 [API 参考](api-reference.md#6-观测与报告)。

## 候选版坐标与本地验证

以下 `1.0.5` 坐标**仅用于正式发布后接入，或消费本地临时 Maven 仓库中的候选产物**。公开发布前，从 Maven Central 接入请继续使用 `1.0.4`。应用模块只选一个依赖；Retrofit 模块已传递依赖 core：

```kotlin
dependencies {
    implementation("io.github.logan0817:gson-safe-parser-core:1.0.5")
    // 使用 Retrofit 时，用下面这一行替换上一行：
    // implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.5")
}
```

在仓库根目录完成版本配置后，可先执行以下本地校验。第一条会发布到指定临时仓库并校验 AAR、POM、module metadata、sources、javadoc 与混淆规则；不会上传 Maven Central。第二条重新生成 OSV 扫描输入，仍需按发布清单实际执行扫描：

```bash
./gradlew verifyMavenLocalPublicationArtifacts \
  -Dmaven.repo.local="$PWD/build/release-verification-m2" --warning-mode=fail
./gradlew writeOsvReleaseRuntimeLockfile --warning-mode=fail
git diff --check
git diff --cached --check
```

独立 Android 消费工程需要在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 中指向上述临时仓库的实际绝对路径，再用 Maven 坐标验证，不能以 `project(...)` 依赖代替。完整 JVM 测试、lint、构建、漏洞扫描、设备运行和远程发布前门禁见[发布清单](release-checklist.md)。每次重新生成 OSV 输入，避免旧依赖版本被误当成最新扫描依据。

## 验证记录与待办

2026-11-16 已完成以下验证。稳定性修复和定版改动已提交为 `d438cd4`；修复 CI 的 SDK 工具包安装后，提交 `21a0c64` 通过远端 CI #25。本地验证覆盖同一份运行时代码；设备结果对应下方 APK 摘要。

| 检查 | 本轮结果 |
| --- | --- |
| Debug / Release JVM 单测 | 各 604 项通过：core 479、retrofit 76、Demo 49；无失败、错误或跳过 |
| 项目三个模块 Release lint | 0 错误、0 警告 |
| Demo 构建 | Debug 与开启 R8 的 Release 均通过，版本为 `1.0.5` / `versionCode 6` |
| 临时 Maven 仓库 | core 与 retrofit 的 1.0.5 AAR、POM、module metadata、sources、javadoc 及规则检查通过 |
| 独立 Android 消费工程 | 通过 Maven 坐标接入 1.0.5，Release 编译、混淆与 lint 通过；consumer rules 自动合并。lint 另有 5 条工具或依赖新版本提示，未改变兼容基线 |
| 真机运行：Android 13 | 一加 8 Pro（IN2020）、Android 13（API 33，`IN2020_13.1.0.190(CN01)`），混淆 APK 安装版本核验为 1.0.5 / 6，自检 51/51 通过 |
| 真机运行：Android 16 | Redmi K60 Ultra、Android 16（API 36），同一份混淆 APK 安装版本核验通过，自检 51/51 通过 |
| 漏洞扫描 | OSV Scanner 2.3.8 扫描 15 个运行时依赖，未报告漏洞 |
| 远程发布任务图 | `releaseToMavenCentral --dry-run` 通过，仅检查任务图，未执行远程发布 |
| 远端 CI | 提交 `21a0c64` 的 CI #25 全部通过，覆盖漏洞扫描、Debug / Release 单测、lint、构建及发布产物校验 |
| Central 部署 | 两个模块完成签名、上传和 Central 校验；部署 `3b792422-2216-4ead-aad4-c6ebcb45c77f` 已提交公开发布，当前仍在等待公开仓库同步 |

Central 在本轮返回组织月度 `Release Count` 超限警告，提示额度于 **2026-12-01** 重置。公开发布请求已受理，但检查时状态仍为 `PUBLISHING`，公开仓库尚不能解析两个模块；不能据此认定发布成功。后续应先检查现有部署与公开仓库状态，再决定是否需要恢复操作，避免重复上传。

本轮 Demo Release APK 的 SHA-256：

```text
0826d6f8b13839ecd4ca8205450df6a2bc8e3e33e99dc0c9cb5a1e8c4dfe3c30
```

仍待完成：确认 Central 最终发布状态并完成 Maven Central 独立解析验证，随后切换公开安装示例。实机覆盖上述 Android 13 与 Android 16 设备，不能视为其他机型或 Android 版本也已实测。Release JVM 单测和 `assembleRelease` 不能替代实际运行；签名、上传和校验通过也不代表公开依赖已经可用。
