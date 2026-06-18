---
name: Bug 报告 / Bug Report
about: 报告解析崩溃、兜底不符合预期或文档错误 / Report parsing crashes, fallback issues, or documentation errors
title: "[Bug] "
labels: bug
assignees: ""
---

## 1. 结论

请用一句话说明问题，例如：`List<User>` 字段收到对象时没有按预期兜底。

English: summarize the problem in one sentence, for example: a `List<User>` field receives an object and does not fall back as expected.

## 2. 环境

| 项目 | 内容 |
| --- | --- |
| GsonSafeParser 版本 |  |
| Gson 版本 |  |
| Kotlin / AGP 版本 |  |
| Android minSdk / compileSdk |  |
| 是否 Retrofit / Retrofit used | 是 / 否 / Yes / No |
| 是否 release + R8 / ProGuard | 是 / 否 / Yes / No |

## 3. 最小复现

English: please provide the smallest JSON, model, and config that reproduce the issue.

JSON：

```json

```

模型：

```kotlin

```

配置：

```kotlin

```

## 4. 实际结果

请贴出 GsonSafeParser 的返回值、事件或异常。不要粘贴 token、cookie、手机号、真实用户数据或生产完整响应。

English: paste the GsonSafeParser value, event, or exception. Do not paste tokens, cookies, phone numbers, real user data, or full production payloads.

## 5. 期望结果

请说明你期望它字段级兜底、交回 Gson，还是向外抛出。

English: explain whether you expected field-level fallback, Gson delegation, or a rethrown exception.

## 6. 原生 Gson 对照

请说明同一份 JSON 和模型在原生 Gson 下的结果。

English: describe what native Gson does with the same JSON and model.

## 7. 其他信息

是否只在 release 包、混淆后、自定义 Adapter、Retrofit、断网或取消请求时出现。如果和自定义 Adapter 有关，请贴出最小注册片段，例如 `registerTypeAdapter(...)`、`registerTypeAdapterFactory(...)`、`registerTypeHierarchyAdapter(...)` 或 `@JsonAdapter`。

English: mention whether it only happens in release builds, after obfuscation, with a custom Adapter, Retrofit, offline state, or request cancellation. If custom adapters are involved, include the minimal `registerTypeAdapter(...)`, `registerTypeAdapterFactory(...)`, `registerTypeHierarchyAdapter(...)`, or `@JsonAdapter` snippet.
