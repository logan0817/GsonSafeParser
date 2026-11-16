# GsonSafeParser

[English](README_EN.md)

[![Maven Central: core](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-core?label=core)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-core)
[![Maven Central: retrofit](https://img.shields.io/maven-central/v/io.github.logan0817/gson-safe-parser-retrofit?label=retrofit)](https://central.sonatype.com/artifact/io.github.logan0817/gson-safe-parser-retrofit)
[![CI](https://github.com/logan0817/GsonSafeParser/actions/workflows/ci.yml/badge.svg)](https://github.com/logan0817/GsonSafeParser/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**Android 的 Gson 扩展：后端把某个对象字段返回成数组时，保留这个字段的默认值，让其他字段继续解析。**

例如，代码需要 `"data":{"id":1}`，接口偶尔却返回 `"data":[]`。原生 Gson 可能让整次解析失败；GsonSafeParser 会尽量隔离到 `data`，并提供事件告诉你哪里出了问题。

**当前稳定版：1.0.5，已发布到 Maven Central。** 按下面的步骤添加依赖；从旧版本升级时，先查看 [1.0.5 发布说明](docs/release-notes-1.0.5.md)中的行为变化和迁移建议。

## 1. 添加依赖，只选一种

需要 Android 项目、`minSdk 23` 及以上、JDK 17。发布物是 Android AAR；纯 JVM 项目暂不能按普通 JAR 接入。更多版本要求见[兼容性说明](docs/compatibility.md)。

打开实际使用此库的模块文件，例如 **`app/build.gradle.kts`**，把下面两种写法中的一种放进 `dependencies`。

**自己调用 Gson 解析 JSON，选 core：**

```kotlin
dependencies {
    implementation("io.github.logan0817:gson-safe-parser-core:1.0.5")
}
```

**使用 Retrofit 解析接口响应，选 retrofit：**

```kotlin
dependencies {
    implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.5")
}
```

retrofit 模块已经包含 core，不需要再加一遍 core。它也会传递 Retrofit 和 Gson 依赖；已有网络栈的项目先核对[依赖兼容性](docs/compatibility.md#3-retrofit-版本说明)。

项目仓库源需要包含 Maven Central，通常已在根目录的 `settings.gradle.kts` 配置：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

已有这段配置时无需重复添加。保存后执行 Android Studio 的 **Sync Project with Gradle Files**。

## 2. 先看一个完整例子

依赖同步后，可以将下面代码放进 Android 模块的 Kotlin 文件。它包含完整导入、模型和 `main`；也可以把 `main` 的函数体放进已有测试或业务入口。

Android 不会自动执行这个 `main`。请从已有单元测试或调试入口调用它；`check` 全部通过且输出与下方一致，就说明这个接入样例正常。

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.parseSafe

data class ApiResponse(
    val code: Int = 0,
    val data: User = User()
)

data class User(
    val id: Long = 0L,
    val name: String = "anonymous"
)

fun main() {
    // data 应该是对象，这次后端却返回了数组。
    val json = """{"code":200,"data":[]}"""
    val result = GsonSafeParser.parseSafe<ApiResponse>(json)
    val response = requireNotNull(result.value)

    check(response.code == 200)
    check(response.data == User())
    check(result.events.size == 1)

    println(response)
    println("Recovered fields: ${result.events.size}")
}
```

预期输出：

```text
ApiResponse(code=200, data=User(id=0, name=anonymous))
Recovered fields: 1
```

`code` 仍然读到 `200`，`data` 保留模型里的默认值。`result.events` 记录这次字段问题，方便定位接口返回哪里不符合预期。

只要对象、不需要事件快照时，使用 `GsonSafeParser.create()` 创建 Gson：

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser

val gson = GsonSafeParser.create()
val response = gson.fromJson("""{"code":200,"data":[]}""", ApiResponse::class.java)
```

上面继续使用前一个例子的 `ApiResponse` 模型。实际项目通常创建并复用一个 Gson，无需每次解析都重新创建。

## 3. Retrofit 项目这样接

将原来的 `.addConverterFactory(GsonConverterFactory.create(...))` **替换成**下面的工厂。不要同时注册两个 Gson 转换工厂；前面的工厂可能先接走响应，让安全解析没有机会生效。

```kotlin
import io.github.logan.gsonsafeparser.retrofit.GsonSafeConverterFactory
import retrofit2.Retrofit

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create())
    .build()
```

把 `baseUrl` 换成你的接口地址，原有 Retrofit 接口和请求调用方式保持不变。

已有统一的 GsonBuilder 配置时，把 Builder 传给工厂：

```kotlin
import com.google.gson.GsonBuilder
import io.github.logan.gsonsafeparser.retrofit.GsonSafeConverterFactory
import retrofit2.Retrofit

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(
        GsonSafeConverterFactory.create(GsonBuilder().serializeNulls())
    )
    .build()
```

已经创建好的普通 Gson 不会因为传进工厂就自动获得安全解析。如何复用它、处理空响应和观察事件，见[快速开始](docs/getting-started.md)的 Retrofit 部分和 [API 参考](docs/api-reference.md)。

## 4. 已有 Gson 配置怎么保留

如果项目自己创建 Gson，把 `.enableSafeParser()` 加到**同一个 Builder**，放在 `.create()` 前面：

```kotlin
import com.google.gson.GsonBuilder
import io.github.logan.gsonsafeparser.enableSafeParser

val gson = GsonBuilder()
    .serializeNulls()
    .enableSafeParser()
    .create()
```

保留项目原有的命名规则、Adapter 等配置，继续使用这个 `gson.fromJson(...)`。完整入口选择、高频解析复用和 Java 调用方式见 [API 参考](docs/api-reference.md)。

## 5. 接入前记住这 5 条

| 情况 | 默认行为 |
| --- | --- |
| 对象、集合或 Map 字段的结构与模型不符 | 尽量只处理当前字段；字段有构造默认值时优先保留，无法安全恢复时仍可能失败。 |
| `Int`、`Boolean`、`String` 等基础值不合法 | 默认交回 Gson，仍可能抛异常。需要容错时先看[配置说明](docs/configuration.md)，再决定是否开启安全基础值解析。 |
| 顶层需要对象却收到数组或数字 | 通常返回 `null`，不会凭空生成完整业务对象。调用方仍要处理空值。 |
| JSON 损坏、自定义 Adapter 抛错、请求失败或取消 | 属于应当暴露的错误；库不承诺把它们转成默认值。具体边界见下方版本说明。 |
| Retrofit 收到空响应体 | 默认普通业务模型返回 `null`；`Unit` / `Void` 由 Retrofit 内置转换器处理。具体边界见[排障指南](docs/troubleshooting.md#1-retrofit-空响应)。 |

对象和数组也不会默认相互转换。确实需要“数组取第一个对象”或“对象包装成单元素集合”时，再看[形态转换配置](docs/configuration.md)和[支持范围](docs/mismatch-capability-matrix.md)。

**准备上线前：**如果 release 开启了 R8 / ProGuard，必须按 [Android 混淆说明](docs/android-proguard.md)保留业务模型。debug 能解析成功，不代表混淆后的版本也能保留字段名和 Kotlin 默认值。

## 接下来按需要阅读

| 你要做什么 | 文档 |
| --- | --- |
| 接入现有项目、了解完整示例 | [快速开始](docs/getting-started.md) |
| 查入口、事件、Java 调用和报告 API | [API 参考](docs/api-reference.md) |
| 调整默认值、基础值、空响应和日志策略 | [配置说明](docs/configuration.md) |
| 确认某种错误是否能恢复 | [错形能力矩阵](docs/mismatch-capability-matrix.md) |
| 检查版本、依赖或混淆要求 | [兼容性](docs/compatibility.md)、[Android 混淆](docs/android-proguard.md) |
| 接入后没生效、找不到字段或出现异常 | [排障指南](docs/troubleshooting.md) |
| 在 Android 设备上对比解析结果 | [Demo App](docs/demo-app.md)、[示例索引](examples/README.md) |
| 贡献或维护发布 | [贡献指南](CONTRIBUTING.md)、[发布清单](docs/release-checklist.md) |

版本与修复记录：

- [1.0.5 发布说明](docs/release-notes-1.0.5.md)：上面依赖示例对应的版本，包含修复内容、升级注意事项和实际验证记录。
- [CHANGELOG](CHANGELOG.md)：包含历史版本与尚未发布的 `Unreleased` 修复。
- 1.0.5 已包含语法边界、容器逐项恢复、Adapter 优先级及 Retrofit 可靠性修复；从 1.0.4 或更早版本升级时，请按发布说明用真实响应回归验证。

## 致谢与许可

项目在设计和问题场景梳理时参考了 [getActivity/GsonFactory](https://github.com/getActivity/GsonFactory)。来源及版权说明见 [NOTICE](NOTICE)。

采用 [Apache License 2.0](LICENSE)。安全问题反馈方式见 [SECURITY.md](SECURITY.md)。
