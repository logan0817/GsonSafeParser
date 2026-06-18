# 贡献指南

感谢你愿意改进 GsonSafeParser。这个项目的目标是做一个可靠、低误伤、边界清晰的 Android Gson 安全解析扩展库。

## 1. 贡献前先确认范围

| 贡献类型 | 推荐做法 |
| --- | --- |
| 发现解析崩溃或兜底不符合预期 | 先提 Bug issue，附最小 JSON、目标模型、配置和期望结果 |
| 想新增解析策略 | 先提 Feature issue，说明为什么现有配置不够用 |
| 文档不清楚 | 可以直接提 PR，尽量附修改前后的对比 |
| 安全问题 | 不要公开 issue，按 [安全策略](SECURITY.md) 私下报告 |
| 大范围重构 | 先开 issue 讨论边界，避免一次 PR 改太多文件 |

## 2. 本地开发环境

| 项目 | 要求 |
| --- | --- |
| JDK | 17 或更高 |
| Android SDK | `compileSdk 36`，建议安装 `build-tools;36.0.0` |
| Gradle | 使用仓库内 `gradlew` / `gradlew.bat` |
| Kotlin | 项目当前使用 `2.0.21` |
| Gson | 项目当前验证 `2.13.2` |

Windows：

```powershell
.\gradlew.bat :gson-safe-parser-core:testDebugUnitTest :gson-safe-parser-retrofit:testDebugUnitTest --warning-mode=fail
```

macOS / Linux：

```bash
./gradlew :gson-safe-parser-core:testDebugUnitTest :gson-safe-parser-retrofit:testDebugUnitTest --warning-mode=fail
```

## 3. 提交代码前的检查

| 改动范围 | 至少运行 |
| --- | --- |
| core 解析逻辑 | `:gson-safe-parser-core:testDebugUnitTest` |
| Retrofit 转换逻辑 | `:gson-safe-parser-retrofit:testDebugUnitTest` |
| Android release、混淆或 consumer rules | `:gson-safe-parser-core:lintRelease :gson-safe-parser-retrofit:lintRelease :demo-app:assembleRelease` |
| Demo App | `:demo-app:testDebugUnitTest :demo-app:assembleDebug` |
| 文档 | `git diff --check`，并人工点检新增链接 |
| 发布脚本 | `publishToMavenLocal`、`verifyMavenLocalPublicationArtifacts`、`releaseToMavenCentral --dry-run` |

完整发布级检查见 [发布清单](docs/release-checklist.md)。

## 4. PR 要求

1. 一个 PR 只解决一个主题，例如「修复 Map entry 兜底」或「补充 API 文档」。
2. 修改解析行为时必须补测试，测试要覆盖正常输入、错形输入、Gson 回退边界和不可安全隔离异常。
3. 修改公开 API 时必须同步更新 [API 参考](docs/api-reference.md)、README 和相关测试。
4. 修改默认行为时必须说明迁移影响，并更新 [错形能力矩阵](docs/mismatch-capability-matrix.md)。
5. 修改 Retrofit 行为时必须确认断网、取消、连接重置和 TLS 失败仍交回 Retrofit / OkHttp。
6. 不要提交本地凭据、签名文件、keystore、`local.properties`、Maven Central token 或构建产物。

## 5. 文档写作规则

1. 中文与英文、数字之间保留空格，例如 `Gson 2.13.2`、`JDK 17`。
2. 中文语境使用全角标点，命令、代码和配置名保持原样。
3. 新 API 文档优先用表格说明「适用场景、默认行为、风险边界」。
4. 不要只写「处理异常」这类模糊描述，要写清楚是「字段级兜底」「交回 Gson」还是「向外抛出」。
5. 示例应尽量能复制运行，或者明确说明是片段示例。

## 6. 解析行为设计原则

| 原则 | 说明 |
| --- | --- |
| 低误伤 | 不能确认安全隔离的问题，交回 Gson 或向外抛出 |
| 字段级隔离 | 能定位到字段、List item 或 Map entry 的问题，只影响当前位置 |
| 可观测 | 兜底要尽量产出 path、期望形状、实际形状和兜底动作 |
| 不吞 fatal | `Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 不应被伪装成默认值 |
| 不藏传输异常 | 断网、取消、连接重置和 TLS 失败不属于 JSON 错形 |

## 7. 提交信息建议

| 类型 | 示例 |
| --- | --- |
| 新能力 | `feat(core): 支持显式对象数组形态转换` |
| 修复 | `fix(retrofit): 保留传输异常外抛边界` |
| 文档 | `docs: 补充 API 入口速查表` |
| 测试 | `test(core): 覆盖 Map entry 兜底事件` |
| 发布 | `chore(release): 更新 1.0.4 发布清单` |

## 8. 反馈模板

提 Bug 时尽量提供：

1. GsonSafeParser 版本、Gson 版本、Kotlin 版本、AGP 版本。
2. 最小 JSON 示例。
3. 目标 data class / Java Bean。
4. 使用的 `SafeParserConfig`。
5. 原生 Gson 结果、GsonSafeParser 实际结果、你期望的结果。
6. 是否在 release 包、R8 / ProGuard、Retrofit 或自定义 Adapter 场景复现。

## 9. Contributing

Thanks for improving GsonSafeParser. Please keep each pull request focused on one topic, such as a parsing fix, Retrofit behavior, documentation, tests, or release tooling.

Before opening a pull request, run the checks that match your change. Core parsing changes should run `:gson-safe-parser-core:testDebugUnitTest`; Retrofit changes should run `:gson-safe-parser-retrofit:testDebugUnitTest`; release, R8, or publishing changes should also run release lint, demo release assembly, Maven local publication, and artifact verification. Documentation-only changes should at least run `git diff --check` and local link checks.

When reporting a bug, include the GsonSafeParser version, Gson version, Kotlin / AGP versions, the smallest JSON payload, the target model, the `SafeParserConfig`, native Gson behavior, actual GsonSafeParser behavior, expected behavior, and whether the issue appears only in release, R8 / ProGuard, Retrofit, or custom adapter scenarios.

Security issues should be reported privately through [GitHub Security Advisory](https://github.com/logan0817/GsonSafeParser/security/advisories/new).
