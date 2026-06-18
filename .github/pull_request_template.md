## 1. 主目标

请说明这个 PR 解决什么问题。

English: explain what this PR solves.

## 2. 修改范围

| 类型 | 是否涉及 |
| --- | --- |
| core 解析逻辑 | 是 / 否 |
| Retrofit 转换逻辑 | 是 / 否 |
| Demo App | 是 / 否 |
| 文档 | 是 / 否 |
| 发布脚本 | 是 / 否 |

## 3. 行为变化

请说明默认行为是否变化，是否影响字段级兜底、Gson 回退、不可安全隔离异常或 Retrofit 传输异常。

English: explain whether defaults changed and whether this affects field-level fallback, Gson delegation, unsafe-to-isolate exceptions, or Retrofit transport failures.

## 4. 验证

请勾选或填写已运行的命令。English: check or list the commands you ran:

- [ ] `./gradlew :gson-safe-parser-core:testDebugUnitTest --warning-mode=fail`
- [ ] `./gradlew :gson-safe-parser-retrofit:testDebugUnitTest --warning-mode=fail`
- [ ] `./gradlew :demo-app:testDebugUnitTest --warning-mode=fail`
- [ ] `./gradlew :gson-safe-parser-core:lintRelease :gson-safe-parser-retrofit:lintRelease :demo-app:lintRelease --warning-mode=fail`
- [ ] `git diff --check`

## 5. 文档同步

| 文档 | 是否已同步 |
| --- | --- |
| README / README_EN | 是 / 否 / 不涉及 / Yes / No / N/A |
| API 参考 / API Reference | 是 / 否 / 不涉及 / Yes / No / N/A |
| 配置说明 / Configuration | 是 / 否 / 不涉及 / Yes / No / N/A |
| 错形能力矩阵 / Mismatch Matrix | 是 / 否 / 不涉及 / Yes / No / N/A |
| CHANGELOG / 发布说明 / Release notes | 是 / 否 / 不涉及 / Yes / No / N/A |
