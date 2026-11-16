# 快速开始

[English](en/getting-started.md)

按下面的顺序，先让一个字段错形样例跑通，再接入业务解析。下面使用可直接安装的 **1.0.4**；当前源码的稳定性修复属于[待发布的 1.0.5 候选版](release-notes-1.0.5.md)，不包含在 Maven 上的 1.0.4 中。

## 1. 添加依赖

这是 Android AAR 库，应用使用 JDK 17 或更高版本、`minSdk 23` 或更高版本。完整版本范围见 [兼容性说明](compatibility.md)。纯 JVM 项目不能直接照搬 Android AAR 的接入方式。

打开项目根目录的 `settings.gradle.kts`，确认已有的依赖仓库中包含 `mavenCentral()`；已有就不重复添加：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

再打开**应用模块**的 `app/build.gradle.kts`。下面两种依赖只选一种，然后执行 Android Studio 的 **Sync Project with Gradle Files**。

手动调用 Gson：

```kotlin
dependencies {
    implementation("io.github.logan0817:gson-safe-parser-core:1.0.4")
}
```

使用 Retrofit：

```kotlin
dependencies {
    implementation("io.github.logan0817:gson-safe-parser-retrofit:1.0.4")
}
```

Retrofit 模块已传递依赖 core，无需再加 core。依赖坐标使用 `io.github.logan0817`，代码 import 使用 `io.github.logan.gsonsafeparser`。

## 2. 先运行一个完整样例

下面是一个完整 `.kt` 文件，可以复制到 Android 模块的 Kotlin 源码目录。用 IDE 运行 `main()`，或把函数内代码放入现有调试入口；后续示例块也各自作为独立文件使用。

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

预期输出：

```text
ApiResponse(code=200, data=User(id=0, name=anonymous))
$.data
true
```

`data` 期望对象却收到数组，库保留 `User()` 的构造默认值；外层 `code` 仍是 200。`parseSafe<T>()` 同时返回业务对象和本次事件。需要后端修复建议时，在函数内调用 `result.contractReport().toBackendMarkdown()`。

只要结果、不需要事件快照时，用 `GsonSafeParser.create()` 得到可复用的 Gson，再照常调用 `gson.fromJson(json, ApiResponse::class.java)`。

## 3. 业务中复用解析器，保留泛型

反复解析时，把 Parser 作为 Repository 或依赖注入对象的成员创建一次。Kotlin 的 `<List<User>>` 会保留元素类型，不要用 `List::class.java` 代替它。

```kotlin
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.fromJsonSafe

data class User(val id: Long = 0L, val name: String = "")

fun main() {
    val config = SafeParserConfig.production()
    val parser = GsonSafeParser.parser(config)
    val users = parser.fromJsonSafe<List<User>>(
        """[{"id":1,"name":"remote"}]"""
    )
    println(users)
}
```

预期输出为 `[User(id=1, name=remote)]`。也可以用 `parser.parseSafe<ApiResponse>(json)` 同时取事件，模型沿用第 2 节。

## 4. 已有 GsonBuilder 或 Gson 时怎么接

已有 `GsonBuilder` 时，推荐 `GsonSafeParser.parser(builder, config)`，它会在创建 Gson 前注册 Safe Adapter。若项目统一持有的是 Gson，应在它的创建位置补上 `.enableSafeParser(config)`，再传给 `GsonSafeParser.parserWithExternalGson(gson, config)`：

```kotlin
import com.google.gson.GsonBuilder
import io.github.logan.gsonsafeparser.GsonSafeParser
import io.github.logan.gsonsafeparser.SafeParserConfig
import io.github.logan.gsonsafeparser.enableSafeParser
import io.github.logan.gsonsafeparser.parseSafe

data class ApiResponse(val tags: List<String> = listOf("local"))

fun main() {
    val config = SafeParserConfig.production()
    val gson = GsonBuilder()
        .serializeNulls()
        .enableSafeParser(config)
        .create()
    val parser = GsonSafeParser.parserWithExternalGson(gson, config)
    val result = parser.parseSafe<ApiResponse>("""{"tags":{}}""")
    println(result.value)
}
```

预期输出为 `ApiResponse(tags=[local])`。包装器不会自动给外部 Gson 注册 Safe Adapter；Gson 创建后不能靠再传一份 config 补注册。字段级 Adapter 的事件回调归属创建 Gson 时的配置。Parser 和 Gson 可以跨线程复用，回调在执行解析的线程同步触发；回调写入的共享容器由调用方保证线程安全。

## 5. Retrofit：替换原来的 Gson 转换器

如果已有 `.addConverterFactory(GsonConverterFactory.create(...))`，把这一行替换为下面的 Safe 工厂。**同一个 Retrofit 不要同时注册这两个 Gson 工厂**，否则先注册者会接管 JSON，后注册者可能没有机会执行。

以下完整样例只创建 Retrofit 和接口代理，不发送请求。实际接入时把 `baseUrl` 换成业务地址。

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

预期输出为 `true`。示例没有调用 `execute()` 或 `enqueue()`。

按已有对象选择工厂：

| 你已有的对象 | 替换成的工厂 |
| --- | --- |
| 没有自定义 Gson | `GsonSafeConverterFactory.create()` |
| 正在配置的 `GsonBuilder` | `GsonSafeConverterFactory.create(builder, config)` |
| 已通过 `.enableSafeParser(config)` 创建的 Gson | `GsonSafeConverterFactory.create(gson, config)` |

最后一行不会给普通外部 Gson 补注册 Safe Adapter。Retrofit 的 Unit/Void、HTTP 204/205 和空业务响应边界见 [排障指南](troubleshooting.md)。

## 6. 只在业务需要时调整兜底

先用默认配置观察真实模型，再按明确需求选择：

| 需求 | 调整 |
| --- | --- |
| 非法 Int、Boolean、String 也允许安全值兜底 | 显式设置 `PrimitiveParsingPolicy.Safe`，并检查金额、状态等关键字段。 |
| 业务接受整段集合错形等同“无数据” | 评估 `FallbackPolicy.Default`，默认是 `NullOnly`。 |
| 指定字段确实存在对象/数组漂移 | 按字段启用 `@SafeParseShapeCoercion`；默认不做互转。 |
| 本地排查要看原文 | 临时使用 `SafeParserConfig.debug()`；上线使用 `production()`，默认不捕获原文。 |
| 尽量保留原有 Gson 基础值行为 | 使用默认配置，或查看 `SafeParserConfig.lowInterference()`。 |

开启捕获后，使用 `parseSafe`、Parser 的解析方法或 Retrofit 工厂才会建立本次 raw JSON 上下文。单独给 Gson 配置捕获、然后直接调用 `gson.fromJson(...)`，不会自动附带原文。捕获存在大小限制，详见 [排障指南](troubleshooting.md)。

SafeParser 内建 Adapter 读取到可隔离的字段错形时会兜底并产生事件。基础类型默认交回 Gson；JSON 语法错误、截断文档、自定义 Adapter 异常及不可恢复异常继续外抛。根级解析失败按目标类型和配置处理，不能假设一定得到完整默认对象。完整范围见 [错形能力矩阵](mismatch-capability-matrix.md) 和 [配置说明](configuration.md)。

## 7. 上线前自检和混淆验证

下面的默认自检运行库内置探针，不解析业务 Bean、不访问网络，也不需要 Android 设备：

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

成功时输出 `Self-check passed`。已有 Gson 还要用 `GsonSafeParser.diagnostics(gson)` 确认 Safe Adapter 确实注册。额外传入 `modelProbes` 时，自检会解析探针中指定的业务模型，检查预期字段；用法见 [API 参考](api-reference.md)。

零配置只适合 debug 或未开启 minify 的试用。开启 R8 / ProGuard 的 release 包必须按 [Android 混淆配置](android-proguard.md) 保留业务模型字段、构造方法及必要 Kotlin Metadata；只加 `@SerializedName` 不够。`testReleaseUnitTest` 不会对测试模型执行 R8，不能代替混淆验证。上线前用关键业务 JSON 对照 debug 和实际混淆 APK，并在混淆后的运行环境执行模型自检。

<details>
<summary>已有自定义 Adapter、Java 调用或内部兼容性问题</summary>

通过 `registerTypeAdapter(...)`、`registerTypeAdapterFactory(...)`、`registerTypeHierarchyAdapter(...)` 或 `@JsonAdapter` 接管的类型保留调用方读取规则，自定义 Adapter 的异常会向外抛出。直接 `gson.fromJson(...)` 的最外层异常仍遵循 Gson 包装规则。

默认入口不会读取 `GsonBuilder` 内部字段；builder-first 入口才会读取 `GsonBuilder` 内部字段，以继承已有设置。强制覆盖 Gson 版本后先看 `diagnostics()`，更多解释见 [兼容性说明](compatibility.md)。

Java 调用、反射 Type 和非 reified API 使用显式 `Class` 或 Gson `TypeToken`，见 [API 参考](api-reference.md)。

</details>
