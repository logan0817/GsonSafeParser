# 发布清单

[English](en/release-checklist.md)

每次发布新版本前都按这份清单核对。目标是确认解析行为、Android AAR、consumer ProGuard、混淆后运行、文档和 Maven 产物一致。

`CHANGELOG.md` 的 `Unreleased` 表示尚未发布的改动。发布前先确定目标版本，再统一更新构建版本、Demo 版本、依赖示例及中英文发布说明；不要把已有版本号或本地验证通过当成已经发布。

当前目标为 **1.0.5 候选版**，Demo 为 `versionName 1.0.5` / `versionCode 6`。候选阶段 README 和快速开始仍给出已发布的 1.0.4 安装坐标；1.0.5 坐标及升级说明见[候选版说明](release-notes-1.0.5.md)。

## 1. 发布前验证命令

```bash
./gradlew \
  :gson-safe-parser-core:testDebugUnitTest \
  :gson-safe-parser-retrofit:testDebugUnitTest \
  :gson-safe-parser-core:testReleaseUnitTest \
  :gson-safe-parser-retrofit:testReleaseUnitTest \
  :demo-app:testDebugUnitTest \
  :demo-app:testReleaseUnitTest \
  :gson-safe-parser-core:lintRelease \
  :gson-safe-parser-retrofit:lintRelease \
  :demo-app:lintRelease \
  :demo-app:assembleDebug \
  :demo-app:assembleRelease \
  --warning-mode=fail
./gradlew verifyMavenLocalPublicationArtifacts \
  -Dmaven.repo.local="$PWD/build/release-verification-m2" --warning-mode=fail
./gradlew releaseToMavenCentral --dry-run --warning-mode=fail
./gradlew writeOsvReleaseRuntimeLockfile --warning-mode=fail
osv-scanner scan source --lockfile build/osv/release-runtime/gradle.lockfile
git diff --check
git diff --cached --check
```

检查结果：
1. core、retrofit、demo debug 单测通过。
2. core、retrofit、demo release 单测通过。
3. core、retrofit、demo release lint 通过，不能有低 API 调用、资源或 Manifest 错误。
4. demo debug 和 release APK 都能构建，release 走 R8 minify。
5. `publishToMavenLocal` 能生成 core 和 retrofit 的 AAR 发布产物。
6. Dokka javadoc.jar 离线生成，不依赖外部 package-list。
7. `verifyMavenLocalPublicationArtifacts` 能复用 CI 的 AAR、POM、sources、javadoc 和 demo release 混淆合并配置校验。它按当前 publication 的真实坐标定位产物，并遵循 Gradle 的 `maven.repo.local` / Maven `settings.xml` 本地仓库位置；POM 和 module metadata 坐标必须匹配。
8. `releaseToMavenCentral --dry-run` 能验证远程发布任务图、签名任务挂载和 `clean` 顺序。
9. OSV 依赖漏洞扫描使用 `writeOsvReleaseRuntimeLockfile` 生成的 release runtime 依赖输入，没有命中阻塞发布的漏洞；CI 使用 `google/osv-scanner-action@v2.3.8` 跑同等门禁。
10. `git diff --check` 和 `git diff --cached --check` 都不能发现空白格式错误。
11. 构建过程中不能出现 Gradle warning、Kotlin warning 或配置期 classpath 解析告警。

增量验证可以复用输入未变化的成功结果，但交付记录要区分本次执行和 `UP-TO-DATE`。`writeOsvReleaseRuntimeLockfile` 每次都重新生成依赖列表；修改依赖后不能沿用旧扫描报告。需要全量重跑时再显式添加 `--rerun-tasks`。

上面的产物校验命令会先执行 `publishToMavenLocal`，并把产物写入指定的临时仓库，避免覆盖日常 Maven local 缓存；不需要另外手动发布一次。

## 2. AAR 产物检查

本地 Maven 产物由 `verifyMavenLocalPublicationArtifacts` 统一校验，必须满足：
1. `gson-safe-parser-core-<目标版本>.aar` 和 `gson-safe-parser-retrofit-<目标版本>.aar` 存在，坐标属于当前 publication。
2. 主产物不能退回普通 `.jar`。
3. POM 使用 `<packaging>aar</packaging>`。
4. AAR 内包含 `classes.jar`、`proguard.txt`、`META-INF/LICENSE`、`META-INF/NOTICE`。
5. `sources.jar` 和 `javadoc.jar` 存在，javadoc jar 内包含 Dokka `index.html`。
6. retrofit POM 中 `gson-safe-parser-core` 依赖版本必须等于本次版本。
7. retrofit POM 和 Gradle module metadata 中 `okhttp 4.12.0`、`okio 3.6.0` 依赖版本必须保留，scope / variant 必须是 runtime，不能回退到 Retrofit 2.8.1 的旧传递依赖，也不能暴露到 api variant。

## 3. 混淆与老项目接入检查

发布前必须确认：
1. core / retrofit AAR 的 `proguard.txt` 包含 `kotlin.Metadata`、`com.google.gson.GsonBuilder`、`@SerializedName <fields>`。
2. demo release 合并后的 `configuration.txt` 同时包含 demo model keep 和 AAR consumer rules。
3. demo 的 `proguard-rules.pro` 只保留业务模型规则，不手抄框架自身规则。
4. `docs/android-proguard.md` 明确说明零配置试用边界、release 最低配置、`android.enableR8.fullMode=true/false` 的选择关系。
5. 安装本次 `demo-app/build/outputs/apk/release/demo-app-release.apk`，在设备或模拟器运行「快速体检」，失败数为 0；记录 APK 摘要、设备/API、版本和结果。Release JVM 单测及 `assembleRelease` 不能替代这一步。
6. 在独立 Android 消费工程中通过 Maven 坐标接入本次 AAR，验证依赖解析、公开 API 编译及 consumer rules 自动合并，不使用 `project(...)` 或手工复制框架规则。
7. 回归至少覆盖观察回调重入的原文/事件隔离、数组 Map 错形键、拒绝 null 的 Map、Retrofit 声明零长度但实际有内容，以及自定义 Adapter、取消和传输异常的外抛边界。

## 4. 文档与版本检查

发布前必须确认：
1. 根 `build.gradle.kts` 已切换到目标版本，且不是已经发布过的版本。
2. demo `versionName` 与目标版本一致，`versionCode` 已递增。
3. 候选阶段的 README / 快速开始明确区分已发布版本与候选版本；Maven Central 发布并验证可解析后，再将这些公开安装坐标切换到目标版本，同时更新版本契约测试和安全策略中的维护版本。
4. 中文文档和英文文档入口互相可跳转。
5. README 保留快速开始、兼容性、配置、混淆、Demo、排障和发布清单入口。历史发布说明通过 `CHANGELOG.md` 查找，不要求 README 重复罗列。
6. `docs/compatibility.md` 和 `docs/en/compatibility.md` 明确列出 `minSdk 23`、`compileSdk 36`、`JDK 17`、`Kotlin 2.0.21`、`kotlin-reflect 2.0.21`、`Gson 2.13.2`、`Retrofit 2.8.1` 和 R8 / ProGuard 边界。
7. 将本次已交付的 `Unreleased` 条目归入目标版本；历史发布记录保持原意。
8. 目标版本的中英文发布说明准确列出行为修复、兼容边界、迁移要求、实际验证及尚未验证的场景。
9. `docs/release-notes-1.0.3.md` 和 `docs/en/release-notes-1.0.3.md` 继续保留 JSON 形态转换、事件报告、边界规则和发布验证。
10. `docs/release-notes-1.0.2.md` 和 `docs/en/release-notes-1.0.2.md` 继续保留传输异常边界修正、兼容边界和发布验证。
11. `docs/release-notes-1.0.1.md` 和 `docs/en/release-notes-1.0.1.md` 继续保留历史稳定性修正、兼容边界和发布验证。
12. `docs/release-notes-1.0.0.md` 和 `docs/en/release-notes-1.0.0.md` 继续保留首发能力、兼容边界和发布验证。
13. 中英文兼容性与排障文档说明：网络或传输读流异常交回 Retrofit / OkHttp，不能用 `emptyResponsePolicy` 隐藏；README 保留对应指引。
14. 中英文兼容性文档说明网络栈安全基线、runtime scope、`OkHttp 4.12.0`、`Okio 3.6.0`、`dependencyInsight` 和依赖解析验证；README 不重复展开这些细节。
15. 示例与注释区分常规 Retrofit 服务和直接调用 converter 的边界，不能承诺 Unit/Void 或 HTTP 204/205 一定派发 SafeParser 空响应事件。

## 5. 远程发布前检查

远程发布前再确认本机或 CI 已配置 Central Portal token 和 GPG 签名环境。`publishToMavenLocal` 不要求签名，`publishToMavenCentral` / `releaseToMavenCentral` 才要求远程发布账号和签名可用。

```bash
./gradlew releaseToMavenCentral
```

发布任务成功后，到 Central Portal deployments 页面确认部署状态，再决定 release 或 drop。不要把 token、GPG 私钥、JKS / keystore、`signing.properties`、`release.properties` 提交到仓库。
