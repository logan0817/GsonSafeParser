# Demo App 使用说明

[English](en/demo-app.md)

仓库内置 `demo-app`，用于在真实 Android 页面里验证 GsonSafeParser 的公开能力。它不是宣传页，而是一个可以直接粘贴 JSON、运行用例、查看解析结果和事件详情的测试工具。

## 1. 构建和安装

```bash
./gradlew :demo-app:assembleDebug
./gradlew :demo-app:installDebug
adb shell am start -n io.github.logan.gsonsafeparser.demo/.MainActivity
```

这 3 个命令分别用于构建 debug 版 Demo、安装到已连接设备、启动 Demo 首页。

如果想验证 Android 混淆配置示例，可以运行：

```bash
./gradlew :demo-app:assembleRelease
```

这个命令会构建开启 R8 minify 的 release Demo App。

debug APK 路径：

```text
demo-app/build/outputs/apk/debug/demo-app-debug.apk
```

## 2. 页面结构

| 页面 | 用途 |
| --- | --- |
| 快速体检 | 一键运行全部内置用例，确认 Demo 和库能力是否正常。 |
| 用户 JSON 验证 | 粘贴接口返回，选择目标类型和策略，对比 GsonSafeParser 与原生 Gson。 |
| 核心解析 | 验证对象、集合、Map、基础类型和 Kotlin API。 |
| 配置与接入 | 验证预设配置、分层策略、Builder 透传和注解能力。 |
| 可观测性 | 查看事件流、契约报告、诊断信息和观察者失败报告。 |
| Retrofit | 验证 Converter、空响应策略、raw JSON 捕获和请求体转换。 |

## 3. 用户 JSON 验证

用户可以把接口返回直接粘贴到输入框，然后选择：

1. 验证入口：`Core fromJson` 或 `Retrofit Converter`。
2. 目标类型：例如 `ApiResponse<User>`、`List<User>`、`MapResponse`、`AnyResponse`、`OrgJsonResponse`。
3. 解析策略：默认契约优先、低误伤、调试 raw JSON、基础类型交回 Gson 等。

运行后页面会展示：

1. 输入 JSON。
2. GsonSafeParser 解析结果。
3. 原生 Gson 对比结果。
4. 事件流、契约汇总、后端报告、`stableKey` 和结构化行。
5. 接入建议和异常信息。

Android Demo 只能验证内置目标类型，不能在运行时生成任意业务 Bean。用户 JSON 验证适合快速判断常见接口结构是否适合接入；真实业务模型仍要在业务工程里补测试。

如果接入自检或业务模型探针出现“疑似模型字段被混淆”，先检查 release 包是否对真实响应模型包做了字段名 keep。

老项目建议先按 bean、model、entity、response、dto 等包名做宽范围 keep。

稳定后，再用业务工程里的 modelProbes 和真实 JSON 逐步收窄。

## 4. 真机验收建议

1. 先进入「快速体检」，运行全部内置用例，确认失败数为 0。
2. 再进入「用户 JSON 验证」，粘贴一个真实接口返回，选择最接近的目标类型。
3. 如果原生 Gson 失败而 GsonSafeParser 成功，继续查看事件流里的字段路径和兜底值是否符合业务预期。
4. 如果两者都失败，优先查看异常信息，再判断是否需要自定义 Adapter 或调整业务模型。
5. release 包验收时，用同一份 JSON 对比普通字段名模型、`@SerializedName` 模型、默认值和回调事件。
6. 如果需要把结果发给协作者，使用页面上的复制功能导出当前结果。
