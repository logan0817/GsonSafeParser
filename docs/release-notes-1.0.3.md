# 1.0.3 发布说明

[English](en/release-notes-1.0.3.md)

`1.0.3` 增加 JSON 形态转换能力。

这个能力用于处理后端偶发把对象和数组返回错形的场景。它不是默认兜底，也不会改变旧版本默认行为。

默认配置仍然是 `ShapeCoercionPolicy.Disabled`。只有调用方使用 `withShapeCoercionPolicy(...)`，或在字段上使用注解，才会进行转换。

## 本次变化

1. 新增 `ShapeCoercionPolicy`，支持普通对象字段从数组第 1 个对象恢复。
2. 新增集合和对象数组字段恢复能力，支持把单个对象包装成长度为 1 的集合或数组。
3. 新增 `@SafeParseShapeCoercion`，允许单个字段使用指定转换策略。
4. 新增 `@SafeParseDisableShapeCoercion`，允许单个字段忽略全局转换策略。
5. 新增 `ShapeCoercion` 事件，并在契约报告和观察者失败报告中输出 `shapeCoercionAction`、字段路径和丢弃元素数量。
6. 根级对象、根级集合、根级对象数组、Map、字符串二次解析、数字、布尔值、网络传输异常和 fatal 异常不参与转换。

## 使用方式

全局开启对象和集合转换：

```kotlin
val config = SafeParserConfig()
    .withShapeCoercionPolicy(ShapeCoercionPolicy.ObjectAndCollection)
```

只给单个字段开启对象从数组恢复：

```kotlin
data class ApiResponse(
    @field:SafeParseShapeCoercion(ShapeCoercionPolicy.ObjectFromFirstArrayItem)
    val user: User?
)
```

全局开启后，给高风险字段单独关闭：

```kotlin
data class ApiResponse(
    @field:SafeParseDisableShapeCoercion
    val error: ErrorBody?
)
```

## 行为边界

1. 对象字段收到 `[{"id":1}]` 时，可以取第 1 个对象解析为字段值。
2. 对象字段收到 `[]` 时，不会构造虚假对象，会回到原兜底行为。
3. 对象字段收到 `[1]`、`["x"]` 或 `[true]` 时，不会把标量强行转成对象，会记录 `ShapeCoercion` 并回到原兜底行为。
4. 集合字段收到 `{"id":1}` 时，可以解析为 1 个元素的集合。
5. 对象数组字段收到 `{"id":1}` 时，可以解析为长度为 1 的数组。
6. Map 字段不做对象数组转换，避免把业务字典误判为列表。
7. 根级对象、根级集合和根级对象数组不做转换，避免改变 `Gson.fromJson(...)` 的整体失败语义。

## 兼容边界

1. 默认行为保持关闭，不调用 `withShapeCoercionPolicy(...)` 时，旧版本解析结果不应变化。
2. 发布产物仍是 Android AAR。
3. 当前验证矩阵仍是 `minSdk 23`、`compileSdk 36`、`JDK 17`、`Kotlin 2.0.21`、`kotlin-reflect 2.0.21`、`Gson 2.13.2`。
4. Retrofit 模块当前验证版本仍是 `Retrofit 2.8.1`。
5. release 包开启 R8 / ProGuard 时，业务模型仍要按文档保留字段名、构造方法和 Kotlin Metadata。

## 发布验证

本版本发布前应覆盖以下门禁：

1. shape coercion 默认关闭、全局开启、字段注解开启和字段注解关闭。
2. 对象字段从数组第 1 个对象恢复、空数组回退、数组首项不是对象回退、多余元素跳过。
3. List、Set 和对象数组从单个对象包装。
4. 根级对象、根级集合、根级对象数组、Map、基础类型和字符串二次解析不参与转换。
5. `ShapeCoercion` 事件、契约报告、观察者失败报告输出正确。
6. `ThreadDeath`、`LinkageError`、`CancellationException` 和真实传输 I/O 不被吞掉。
7. Retrofit builder-first 入口能使用字段级 Safe Adapter；传入 plain Gson 的入口不会偷偷注册字段级转换。
8. core、retrofit、demo debug 单测。
9. demo release 单测。
10. core、retrofit、demo release lint。
11. demo debug 和 release APK 构建。
12. `publishToMavenLocal`。
13. Maven local AAR、POM、sources、Dokka javadoc 和 consumer ProGuard 规则校验。
14. `releaseToMavenCentral --dry-run`。
15. `git diff --check`。
