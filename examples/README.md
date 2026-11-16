# 示例索引

[English](README_EN.md)

每个 Kotlin 示例都是包含 import、模型和 `fun main()` 的独立 `.kt` 文件。一次复制一个示例；在 Android 模块里运行 `main()`，或把函数内容放入现有调试入口。它们是文档示例，不额外新增 Gradle 模块。

安装仓库应放在哪里、依赖如何选择，先看[快速开始](../docs/getting-started.md#1-添加依赖)：在 `settings.gradle.kts` 中确认 `mavenCentral()`，在 `app/build.gradle.kts` 中只选择 core 或 retrofit 一种依赖并同步 Gradle。Retrofit 已传递依赖 core。

本文基本用法适用于已发布的 **1.0.5**。修复内容和升级注意事项见 [1.0.5 发布说明](../docs/release-notes-1.0.5.md)。

## 1. 按目标选择

| 目标 | 示例 |
| --- | --- |
| 验证对象字段错形时保留外层数据 | [普通 Gson 最小示例](#2-普通-gson-最小示例) |
| 获取结果、事件和问题 path | [事件和契约报告](#3-事件和契约报告) |
| 配置 Retrofit 工厂 | [Retrofit 最小示例](#4-retrofit-最小示例) |
| 仅在已知字段启用对象/数组互转 | [JSON 形态转换](#5-json-形态转换) |
| 运行不访问网络的内置探针 | [CI 接入自检](#6-ci-接入自检) |

## 2. 普通 Gson 最小示例

只需要 `io.github.logan0817:gson-safe-parser-core:1.0.5`：

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser

data class ApiResponse(val code: Int = 0, val data: User = User())
data class User(val id: Long = 0L, val name: String = "local")

fun main() {
    val json = """{"code":200,"data":[]}"""
    val gson = GsonSafeParser.create()
    val response = requireNotNull(gson.fromJson(json, ApiResponse::class.java))
    check(response.code == 200)
    check(response.data == User())
    println(response)
}
```

预期输出：`ApiResponse(code=200, data=User(id=0, name=local))`。`data` 期望对象却收到数组时保留构造默认值，外层 `code` 仍是 200。

解析泛型列表时，使用 `parser.fromJsonSafe<List<User>>(json)`，不要使用丢失元素类型的 `List::class.java`。完整代码见[复用 Parser 和泛型](../docs/getting-started.md#3-业务中复用解析器保留泛型)。

## 3. 事件和契约报告

下面不依赖上一个示例的模型，可以独立复制运行：

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParserEvent
import io.github.logan.gsonsafeparser.contractReport
import io.github.logan.gsonsafeparser.parseSafe

data class ApiResponse(val code: Int = 0, val data: User = User())
data class User(val id: Long = 0L, val name: String = "anonymous")

fun main() {
    val json = """{"code":200,"data":[]}"""
    val result = GsonSafeParser.parseSafe<ApiResponse>(json)
    val mismatch = result.events.single() as SafeParserEvent.TypeMismatch
    println(result.value)
    println(mismatch.detail.path)
    println(result.contractReport().hasIssues)
}
```

预期依次输出 `ApiResponse(code=200, data=User(id=0, name=anonymous))`、`$.data` 和 `true`。在函数内调用 `result.contractReport().toBackendMarkdown()` 可生成后端修复报告；报告不会重新解析 JSON。

如果只是直接调用 `gson.fromJson(...)`，即使配置开启 raw JSON 捕获，也不会自动附带原文。需要原文时通过配置捕获的 Parser、`parseSafe` 或 Retrofit 入口解析，详见[原文捕获排障](../docs/troubleshooting.md)。

## 4. Retrofit 最小示例

只需要 `io.github.logan0817:gson-safe-parser-retrofit:1.0.5`。已有 `GsonConverterFactory.create(...)` 时，将其替换为 Safe 工厂，不要在同一 Retrofit 同时保留两个 Gson 工厂。

```kotlin
import io.github.logan.gsonsafeparser.retrofit.GsonSafeConverterFactory
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.GET

data class ApiResponse(val code: Int = 0)

interface ApiService {
    @GET("profile")
    fun profile(): Call<ApiResponse>
}

fun main() {
    val retrofit = Retrofit.Builder()
        .baseUrl("https://example.com/")
        .addConverterFactory(GsonSafeConverterFactory.create())
        .build()
    val service = retrofit.create(ApiService::class.java)
    println(service is ApiService)
}
```

预期输出 `true`。这里只配置示例地址、创建接口代理，没有调用 `execute()` 或 `enqueue()`，不会发网络请求。

已有 Builder 使用 `GsonSafeConverterFactory.create(builder, config)`；已有 Gson 使用 `GsonSafeConverterFactory.create(gson, config)`，但创建这份 Gson 前必须先 `.enableSafeParser(config)`。后者不会补注册，完整分支见[快速开始](../docs/getting-started.md#4-已有-gsonbuilder-或-gson-时怎么接)。

## 5. JSON 形态转换

只有业务接受“读取数组第一个对象”时才对该字段启用；默认配置不做对象/数组互转：

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParseShapeCoercion
import io.github.logan.gsonsafeparser.ShapeCoercionPolicy
import io.github.logan.gsonsafeparser.parseSafe

data class User(val id: Long = 0L, val name: String = "")

data class FlexibleResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val data: User? = null
)

fun main() {
    val result = GsonSafeParser.parseSafe<FlexibleResponse>(
        """{"data":[{"id":1,"name":"remote"}]}"""
    )
    println(result.value?.data)
    println(result.events.size)
}
```

预期输出 `User(id=1, name=remote)` 和 `1`。这个事件说明发生了显式形态转换。基础类型的宽松兜底同样只在业务确认后选择 `PrimitiveParsingPolicy.Safe`，详细选项见[配置说明](../docs/configuration.md)。

## 6. CI 接入自检

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.integrationCheck

fun main() {
    val config = SafeParserConfig.production()
    val diagnostics = GsonSafeParser.diagnostics(config)
    check(diagnostics.hasErrors.not()) {
        diagnostics.checks.joinToString("\n")
    }
    val integrationCheck = GsonSafeParser.integrationCheck(config)
    check(integrationCheck.hasErrors.not()) {
        integrationCheck.checks.joinToString("\n")
    }
    println("Self-check passed")
}
```

成功输出 `Self-check passed`。默认只解析库内置探针；传入 `modelProbes` 时才额外解析指定业务模型。回调在解析调用线程同步执行，共享日志或事件容器由调用方保证线程安全。

普通 JVM 单测和 `testReleaseUnitTest` 不能证明 R8 后模型仍可用。上线前按[Android 混淆](../docs/android-proguard.md)配置 keep，并在真实混淆 APK 内用关键业务 JSON 运行模型自检。

## 7. 继续阅读

| 内容 | 文档 |
| --- | --- |
| 已有 Builder / Gson、泛型解析 | [快速开始](../docs/getting-started.md) |
| API 入口与 Java 显式类型 | [API 参考](../docs/api-reference.md) |
| 字段兜底、根级和异常边界 | [错形能力矩阵](../docs/mismatch-capability-matrix.md) |
| Retrofit 空响应和 raw JSON | [排障指南](../docs/troubleshooting.md) |

语法错误、截断 JSON、自定义 Adapter 异常和 Retrofit 网络异常继续交回调用方处理，不能靠安全兜底假装成正常业务结果。
