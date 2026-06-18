# 示例索引

[English](README_EN.md)

这个目录放最小可复制示例。示例以文档片段为主，不新增 Gradle 子模块，避免改变当前 Android AAR 发布结构。

## 示例前置依赖

普通 Gson 示例只需要 core：

```kotlin
implementation("io.github.logan0817:gson-safe-parser-core:1.0.4")
```

Retrofit 示例只需要 retrofit，它会传递 core：

```kotlin
implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.4")
```

## 1. 按目标选择示例

| 目标 | 示例 | 你会看到什么 |
| --- | --- | --- |
| 只验证字段级兜底 | [普通 Gson 最小示例](#2-普通-gson-最小示例) | `data` 收到数组时外层对象继续解析 |
| 解析结果加事件 | [事件和契约报告](#3-事件和契约报告) | `parseSafe<T>()`、`events`、`contractReport()` |
| Retrofit 接入 | [Retrofit 最小示例](#4-retrofit-最小示例) | `GsonSafeConverterFactory.create()` |
| 显式对象数组转换 | [JSON 形态转换](#5-json-形态转换) | `@SafeParseShapeCoercion` |
| CI 自检 | [CI 接入自检](#6-ci-接入自检) | `diagnostics()`、`integrationCheck()` |

## 2. 普通 Gson 最小示例

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser

data class ApiResponse(
    val code: Int = 0,
    val data: User = User()
)

data class User(
    val id: Long = 0L,
    val name: String = "local"
)

val json = """{"code":200,"data":[]}"""
val gson = GsonSafeParser.create()
val response = gson.fromJson(json, ApiResponse::class.java)

check(response.code == 200)
check(response.data == User())
```

这个示例验证：`data` 期望 JSON object，却收到 JSON array。GsonSafeParser 只兜底 `data` 字段，外层 `code` 继续保留。

## 3. 事件和契约报告

下面代码承接第 2 节的 `ApiResponse` / `User` 模型；单独复制时先复制第 2 节模型。

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.contractReport
import io.github.logan.gsonsafeparser.parseSafe

val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""")

println(result.value)
println(result.events)
println(result.contractReport().toBackendMarkdown())
```

这个示例验证：业务对象、解析事件和后端修复报告可以从同一次解析结果里拿到。`contractReport()` 不会重新解析 JSON，也不会改变业务对象。

## 4. Retrofit 最小示例

```kotlin
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.retrofit.GsonSafeConverterFactory
import com.google.gson.GsonBuilder
import retrofit2.Retrofit

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create())
    .build()
```

已有统一 `GsonBuilder` 时：

```kotlin
val config = SafeParserConfig.production()
val builder = GsonBuilder().serializeNulls()

val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addConverterFactory(GsonSafeConverterFactory.create(builder, config))
    .build()
```

这个示例验证：Retrofit 响应体会走带 Safe Adapter 的 Gson。断网、取消、连接重置和 TLS 失败仍交回 Retrofit / OkHttp，不会被当成空响应或字段错形。

## 5. JSON 形态转换

下面代码承接第 2 节的 `User` 模型；单独复制时先复制第 2 节模型。

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParseShapeCoercion
import io.github.logan.gsonsafeparser.ShapeCoercionPolicy
import io.github.logan.gsonsafeparser.parseSafe

data class FlexibleResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val data: User? = null
)

val result = GsonSafeParser.parseSafe<FlexibleResponse>(
    """{"data":[{"id":1,"name":"remote"}]}"""
)

println(result.value?.data)
println(result.events)
```

这个示例验证：对象字段收到数组时，可以显式读取数组第 1 个对象。该能力默认关闭，只建议用于业务明确接受这种恢复规则的字段。

## 6. CI 接入自检

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParserConfig

val diagnostics = GsonSafeParser.diagnostics(SafeParserConfig.production())
check(diagnostics.hasErrors.not()) {
    diagnostics.checks.joinToString(separator = "\n")
}

val integrationCheck = GsonSafeParser.integrationCheck(SafeParserConfig.production())
check(integrationCheck.hasErrors.not()) {
    integrationCheck.checks.joinToString(separator = "\n")
}
```

这个示例验证：当前 Gson、kotlin-reflect、Safe Adapter、事件流和契约报告链路是否适合接入。它不访问网络，也不依赖 Android 设备。

## 7. 继续阅读

| 内容 | 文档 |
| --- | --- |
| API 入口怎么选 | [API 参考](../docs/api-reference.md) |
| 完整兜底范围 | [错形能力矩阵](../docs/mismatch-capability-matrix.md) |
| Android release 混淆 | [Android 混淆](../docs/android-proguard.md) |
| Retrofit 空响应和 raw JSON | [排障指南](../docs/troubleshooting.md) |
