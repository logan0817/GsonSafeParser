# 安全策略

GsonSafeParser 处理 JSON 解析、事件观测和 raw JSON 捕获，因此安全问题请优先私下报告，不要直接公开敏感 payload。

## 1. 支持版本

| 版本 | 状态 |
| --- | --- |
| `1.0.4` | 当前维护版本 |
| `1.0.3` | 历史版本，仅接受高风险安全问题回溯判断 |
| `1.0.1` 及更早 | 不承诺常规安全修复 |

## 2. 如何报告安全问题

请通过 [GitHub Security Advisory](https://github.com/logan0817/GsonSafeParser/security/advisories/new) 或维护者在项目主页公开的安全联系渠道报告。报告中尽量包含：

1. 受影响版本。
2. 触发问题的最小 JSON、模型和配置。
3. 是否涉及 raw JSON、日志、契约报告、Map key、异常 reason 或 Retrofit 响应体。
4. 是否会导致崩溃、敏感信息泄露、内存异常或错误兜底。
5. 可复现的测试片段或 Demo。

不要在公开 issue 中粘贴 token、cookie、手机号、身份证、真实用户数据、生产接口响应或完整异常栈。

## 3. Reporting Security Issues

Please report security issues through [GitHub Security Advisory](https://github.com/logan0817/GsonSafeParser/security/advisories/new) instead of public issues. Include the affected version, the smallest reproducible JSON/model/config, and whether the issue involves raw JSON capture, logs, contract reports, Map keys, exception reasons, Retrofit response bodies, crashes, memory pressure, or unexpected fallback.

Do not paste tokens, cookies, phone numbers, identity numbers, real user data, production payloads, or full production stack traces into public issues.

## 4. 项目安全边界

| 范围 | 处理原则 |
| --- | --- |
| JSON 语法错误 | 继续抛出，不伪装成默认值 |
| 字段级 JSON 形状不一致 | 尽量局部兜底，并记录结构化事件 |
| 调用方显式注册的 `TypeAdapter` / `TypeAdapterFactory` / `registerTypeHierarchyAdapter(...)` / `@JsonAdapter` | 优先交回原生 Gson，异常外抛，不伪装成字段兜底 |
| 不可安全隔离异常 | `Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 外抛 |
| Retrofit 传输异常 | 交回 Retrofit / OkHttp，不记录成字段错形 |
| raw JSON 捕获 | 默认关闭，开启后受 `maxRawJsonCaptureBytes` 限制 |
| Map item key | 默认 `Omit`，避免线上泄露业务 key |
| 契约报告 | 不直接输出 raw JSON 正文或 Throwable |

## 5. 处理流程

1. 收到报告后先确认是否能复现。
2. 如果问题有效，会评估影响范围、默认配置是否受影响、是否需要紧急发布。
3. 修复会优先补测试，覆盖正常路径、攻击路径和回归边界。
4. 发布后在 CHANGELOG 和 release notes 中说明影响范围和升级建议。

## 6. 临时缓解建议

| 风险 | 临时处理 |
| --- | --- |
| raw JSON 日志风险 | 关闭 `captureRawJsonInCallbacks`，或调小 `maxRawJsonCaptureBytes` |
| Map key 泄露风险 | 使用默认 `MapItemKeyPolicy.Omit` |
| 自定义 Adapter 行为不确定 | 给类型加 `@SafeParseDelegateToGson`，先交回 Gson |
| release 混淆导致行为异常 | 按 [Android 混淆](docs/android-proguard.md) 保留业务模型字段、构造方法和 Kotlin Metadata |
