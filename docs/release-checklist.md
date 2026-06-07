# 发布清单

[English](en/release-checklist.md)

发布 `1.0.3` 前按这份清单核对，目标是确认 Android AAR、consumer ProGuard、release 混淆自检、文档版本号和 Maven 本地产物都稳定。

## 1. 发布前验证命令

```bash
./gradlew \
  :gson-safe-parser-core:testDebugUnitTest \
  :gson-safe-parser-retrofit:testDebugUnitTest \
  :demo-app:testDebugUnitTest \
  :demo-app:testReleaseUnitTest \
  :gson-safe-parser-core:lintRelease \
  :gson-safe-parser-retrofit:lintRelease \
  :demo-app:lintRelease \
  :demo-app:assembleDebug \
  :demo-app:assembleRelease \
  publishToMavenLocal \
  --rerun-tasks \
  --warning-mode=fail
./gradlew verifyMavenLocalPublicationArtifacts --warning-mode=fail
./gradlew releaseToMavenCentral --dry-run --warning-mode=fail
osv-scanner --recursive .
git diff --check
```

检查结果：
1. core、retrofit、demo debug 单测通过。
2. demo release 单测通过。
3. core、retrofit、demo release lint 通过，不能有低 API 调用、资源或 Manifest 错误。
4. demo debug 和 release APK 都能构建，release 走 R8 minify。
5. `publishToMavenLocal` 能生成 core 和 retrofit 的 AAR 发布产物。
6. Dokka javadoc.jar 离线生成，不依赖外部 package-list。
7. `verifyMavenLocalPublicationArtifacts` 能复用 CI 的 AAR、POM、sources、javadoc 和 demo release 混淆合并配置校验。
8. `releaseToMavenCentral --dry-run` 能验证远程发布任务图、签名任务挂载和 `clean` 顺序。
9. OSV 依赖漏洞扫描没有命中阻塞发布的漏洞；CI 使用 `google/osv-scanner-action@v2.3.8` 跑同等门禁。
10. `git diff --check` 不能发现空白格式错误。
11. 构建过程中不能出现 Gradle warning、Kotlin warning 或配置期 classpath 解析告警。

## 2. AAR 产物检查

本地 Maven 产物由 `verifyMavenLocalPublicationArtifacts` 统一校验，必须满足：
1. `gson-safe-parser-core-1.0.3.aar` 和 `gson-safe-parser-retrofit-1.0.3.aar` 存在。
2. 主产物不能退回普通 `.jar`。
3. POM 使用 `<packaging>aar</packaging>`。
4. AAR 内包含 `classes.jar`、`proguard.txt`、`META-INF/LICENSE`、`META-INF/NOTICE`。
5. `sources.jar` 和 `javadoc.jar` 存在，javadoc jar 内包含 Dokka `index.html`。
6. retrofit POM 中 `gson-safe-parser-core` 依赖版本必须等于本次版本。
7. retrofit POM 中 `okhttp 4.12.0` 和 `okio 3.6.0` 依赖版本必须保留，不能回退到 Retrofit 2.8.1 的旧传递依赖。

## 3. 混淆与老项目接入检查

发布前必须确认：
1. core / retrofit AAR 的 `proguard.txt` 包含 `kotlin.Metadata`、`com.google.gson.GsonBuilder`、`@SerializedName <fields>`。
2. demo release 合并后的 `configuration.txt` 同时包含 demo model keep 和 AAR consumer rules。
3. demo 的 `proguard-rules.pro` 只保留业务模型规则，不手抄框架自身规则。
4. `docs/android-proguard.md` 明确说明零配置试用边界、release 最低配置、`android.enableR8.fullMode=true/false` 的选择关系。

## 4. 文档与版本检查

发布前必须确认：
1. 根 `build.gradle.kts` 版本是 `1.0.3`。
2. demo `versionName` 是 `1.0.3`，`versionCode` 已递增。
3. `README.md`、`README_EN.md`、`docs/getting-started.md`、`docs/en/getting-started.md` 同时包含 core 和 retrofit 的 `1.0.3` 坐标。
4. 中文文档和英文文档入口互相可跳转。
5. README 文档表能找到快速开始、兼容性说明、配置说明、错形能力矩阵（JSON 形状不一致）、Android 混淆、Demo App、排障指南、发布清单、1.0.3 发布说明、1.0.2 历史发布说明、1.0.1 历史发布说明和 1.0.0 历史发布说明。
6. `docs/compatibility.md` 和 `docs/en/compatibility.md` 明确列出 `minSdk 23`、`compileSdk 36`、`JDK 17`、`Kotlin 2.0.21`、`kotlin-reflect 2.0.21`、`Gson 2.13.2`、`Retrofit 2.8.1` 和 R8 / ProGuard 边界。
7. `CHANGELOG.md` 存在，并以 `1.0.3` 作为当前发布版本、`1.0.0` 作为首个公开兼容基线。
8. `docs/release-notes-1.0.3.md` 和 `docs/en/release-notes-1.0.3.md` 明确列出 JSON 形态转换、事件报告、边界规则和发布验证。
9. `docs/release-notes-1.0.2.md` 和 `docs/en/release-notes-1.0.2.md` 继续保留传输异常边界修正、兼容边界和发布验证。
10. `docs/release-notes-1.0.1.md` 和 `docs/en/release-notes-1.0.1.md` 继续保留历史稳定性修正、兼容边界和发布验证。
11. `docs/release-notes-1.0.0.md` 和 `docs/en/release-notes-1.0.0.md` 继续保留首发能力、兼容边界和发布验证。
12. `README.md`、`README_EN.md`、`docs/compatibility.md`、`docs/en/compatibility.md`、`docs/troubleshooting.md`、`docs/en/troubleshooting.md` 都明确说明网络或传输读流异常会交回 Retrofit / OkHttp，不能用 `emptyResponsePolicy` 隐藏。
13. `README.md`、`README_EN.md`、`docs/compatibility.md` 和 `docs/en/compatibility.md` 都明确说明 Retrofit 网络栈安全基线、`OkHttp 4.12.0`、`Okio 3.6.0`、`dependencyInsight` 和依赖解析验证。
14. `CHANGELOG.md`、1.0.3 中英文发布说明和本清单都记录 OSV、Maven Central 响应脱敏、Demo 剪贴板脱敏、`maxRawJsonCaptureBytesTooLarge`、OkHttp / Okio 基线。

## 5. 远程发布前检查

远程发布前再确认本机或 CI 已配置 Central Portal token 和 GPG 签名环境。`publishToMavenLocal` 不要求签名，`publishToMavenCentral` / `releaseToMavenCentral` 才要求远程发布账号和签名可用。

```bash
./gradlew releaseToMavenCentral
```

发布任务成功后，到 Central Portal deployments 页面确认部署状态，再决定 release 或 drop。不要把 token、GPG 私钥、JKS / keystore、`signing.properties`、`release.properties` 提交到仓库。
