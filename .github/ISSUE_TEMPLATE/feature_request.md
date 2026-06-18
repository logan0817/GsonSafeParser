---
name: 功能建议 / Feature Request
about: 提议新的解析能力、配置项、文档或示例 / Propose parsing behavior, config, docs, or examples
title: "[Feature] "
labels: enhancement
assignees: ""
---

## 1. 主目标

请用一句话说明你希望 GsonSafeParser 增加什么能力。

English: describe the capability you want in one sentence.

## 2. 使用场景

| 问题 | 说明 |
| --- | --- |
| 当前 JSON 形状 / Current JSON shape |  |
| 当前模型定义 / Current model |  |
| 现有配置为什么不够 / Why existing config is not enough |  |
| 期望默认是否开启 / Should it be enabled by default | 是 / 否 / Yes / No |

## 3. 期望行为

请说明新能力应该字段级兜底、交回 Gson、记录事件，还是只提供显式开关。

English: explain whether the new behavior should do field-level fallback, delegate to Gson, emit events, or stay behind an explicit opt-in.

## 4. 风险边界

请说明它是否可能影响原生 Gson 兼容性、R8 / ProGuard、Retrofit、raw JSON、Map key 或已有默认行为。

English: describe whether it may affect native Gson compatibility, R8 / ProGuard, Retrofit, raw JSON, Map keys, or existing defaults.

## 5. 可接受替代方案

请说明是否可以通过自定义 `TypeAdapter`、`JsonElement`、`@SafeParseDelegateToGson` 或业务 wrapper 解决。

English: mention whether a custom `TypeAdapter`, `JsonElement`, `@SafeParseDelegateToGson`, or a business wrapper would be acceptable.
