# 错形能力矩阵（JSON 形状不一致）

[English](en/mismatch-capability-matrix.md)

这份矩阵回答一个问题：后端返回的 JSON 形状和 Android Bean 定义不一致时，GsonSafeParser 会保住什么、记录什么、哪些问题仍要交回 Gson 或调用方处理。

## 1. 总体原则

1. 字段级问题只影响当前字段，外层对象能继续解析就继续解析。
2. 无法隔离到字段级的问题继续交回 Gson 原生 Adapter 或向外抛出，不把语法错误或 `Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 这类不可安全隔离问题伪装成业务默认值。
3. 每次可观测的 JSON 形状不一致都会进入 `SafeParserEvent`，`parseSafe<T>()` 可以把事件转换成 `contractReport()`，报告里包含 JSON path、期望形状、实际形状、兜底动作、客户端影响和后端修复建议。

## 2. 默认能力矩阵

开箱默认配置：

| 默认配置写法 | 说明 |
| --- | --- |
| `fallbackPolicy = FallbackPolicy.NullOnly` | 字段形状不一致时优先返回 `null` 或保留构造默认值。 |
| `primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson` | 基础类型默认交回 Gson 原生 Adapter。 |
| `emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly` | Retrofit 空 body 只为 `Unit` / `Void` 返回空值。 |
| `useJdkUnsafe = false` | 默认兼容模式下，SafeParser 自己不使用 JDK Unsafe 构造对象；开启 `Strict` 后会强制禁用 SafeParser 和 Gson 回退路径里的 Unsafe。 |
| `requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.GsonCompatible` | Kotlin 非空必填构造参数缺失时保持 Gson 兼容；引用字段保持 `null`，primitive 保持 JVM 默认值。 |
| `mapItemKeyPolicy = MapItemKeyPolicy.Hash` | Map item 事件默认输出稳定哈希。 |

下面的“默认处理”都按这组配置描述。只有显式切到 `FallbackPolicy.Default` 或 `PrimitiveParsingPolicy.Safe` 时，才会启用空集合、空 Map、数字 0、布尔 false 这类旧安全默认值。

总览：

| 类型 | 默认处理 | 主要边界 |
| --- | --- | --- |
| 对象字段 | 当前字段兜底，外层继续解析。 | 根级对象形状不一致不一定能字段级隔离。 |
| 集合字段 | 整体形状不一致返回 `null` 或保留默认值。 | 集合内单个坏 item 会被跳过。 |
| Map 字段 | 整体形状不一致返回 `null` 或保留默认值。 | `[]` 可能是复杂 Map key 的合法数组 entry。 |
| 基础类型 | 默认交回 Gson 原生 Adapter。 | 只有 `PrimitiveParsingPolicy.Safe` 才使用安全基础值。 |
| Kotlin 默认值 | 缺字段、可恢复的 JSON 形状不一致尽量保留默认值。 | 非空无默认值的必填参数默认按 Gson 兼容处理；严格失败需要显式配置。 |
| Retrofit 空 body | `Unit` 返回 `Unit`，其他常见目标返回 `null`。 | HTTP 错误码和业务错误码不由本库判断。 |

### 2.1 `data: User`
<!-- capability-id: object-field-mismatch -->

1. 后端实际返回：`[]`、`""`、`1`。
2. 默认处理：字段值读取为 `null`；反射字段读取时不会用 `null` 覆盖已构造出来的字段默认值。
3. 观测证据：`TypeMismatch`，`path=$.data`，`expectedJsonShape=JSON object`，`actualJsonShape=JSON array/string/number`。
4. 边界：根级对象形状不一致通常返回 `null`，不可恢复异常继续抛出。

### 2.2 `List<User>` / `Set<User>`
<!-- capability-id: collection-field-mismatch -->

1. 后端实际返回：`{}`、`""`、`false`。
2. 默认处理：集合整体形状不一致返回 `null`；反射字段读取时不会用 `null` 覆盖已构造出来的字段默认值。
3. 观测证据：`TypeMismatch`，字段 path，兜底动作说明集合字段被兜底。
4. 边界：集合里单个坏 item 会被跳过，不代表整个集合失败。

### 2.3 `Map<String, User>`
<!-- capability-id: map-field-mismatch -->

1. 后端实际返回：`""`、`false`，或数组 entry 里坏 key/value。
2. 默认处理：Map 整体形状不一致返回 `null`；反射字段读取时不会用 `null` 覆盖已构造出来的字段默认值。Map 内单个坏 entry 会被跳过。
3. 观测证据：`TypeMismatch`，字段 path，Map item 形状不一致默认带 `sha256:` 哈希后的 `mapItemKey`。
4. 边界：`[]` 可作为 Gson 复杂 Map key 的数组 entry 形式读取，空数组会得到空 Map，不一定产生类型错配事件。

### 2.4 `Int` / `Long` / `Short` / `Byte`
<!-- capability-id: integer-field-mismatch -->

1. 后端实际返回：`{}`、`[]`、非法字符串、越界数值、小数给整数。
2. 默认处理：字段级交回 Gson 原生 Adapter，读取失败时保留字段默认值；根级基础类型按 Gson 原生行为处理。
3. 观测证据：`TypeMismatch`，字段名、path、原因包含范围或取整失败。
4. 边界：只有配置为 `PrimitiveParsingPolicy.Safe` 时才使用安全基础值。

### 2.5 `BigDecimal` / `BigInteger`
<!-- capability-id: big-number-mismatch -->

1. 后端实际返回：`{}`、`[]`、非法字符串、小数给 `BigInteger`。
2. 默认处理：字段级交回 Gson 原生 Adapter，读取失败时保留字段默认值；根级数值按 Gson 原生行为处理。
3. 观测证据：`TypeMismatch`，原因说明数值无法按目标类型读取。
4. 边界：只有配置为 `PrimitiveParsingPolicy.Safe` 时才使用安全数值默认值；精度合法的大整数会保留，不强行截断。

### 2.6 `Boolean`
<!-- capability-id: boolean-field-mismatch -->

1. 后端实际返回：`{}`、`[]`、非法字符串。
2. 默认处理：字段级交回 Gson 原生 Adapter，读取失败时保留字段默认值；根级布尔值按 Gson 原生行为处理。
3. 观测证据：`TypeMismatch`，字段 path 和实际 token。
4. 边界：只有配置为 `PrimitiveParsingPolicy.Safe` 时才使用安全布尔值；普通 `"true"` / `"false"` 仍按 Gson 兼容方式读取。

### 2.7 `String`
<!-- capability-id: string-field-mismatch -->

1. 后端实际返回：`{}`、`[]`。
2. 默认处理：字段级交回 Gson 原生 Adapter，读取失败时保留字段默认值；根级字符串按 Gson 原生行为处理。
3. 观测证据：`TypeMismatch`，`expectedJsonShape=JSON string`。
4. 边界：数字转字符串仍保持 Gson 兼容行为。

### 2.8 Kotlin data class 默认值
<!-- capability-id: kotlin-defaults -->

1. 后端实际返回：缺字段、字段为 `null`、字段形状不一致。
2. 默认处理：缺字段保留构造默认值；显式 `null` 只写入 nullable 字段；字段形状不一致读取失败时保留构造默认值。
3. 观测证据：可通过类型错配事件和最终 value 对照确认。
4. 边界：非空构造参数如果没有默认值，默认按 Gson 兼容处理；缺失引用字段保持 `null`，primitive 保持 JVM 默认值。
5. 边界：如果你要把缺字段、`null`、形状不一致或未知枚举值作为接口契约错误处理，把 `requiredConstructorParameterPolicy` 改成 `Strict`。
6. 边界：严格模式会抛出 `JsonIOException`，也会阻止 Gson delegate 继续用 Unsafe 绕过构造；即使同时传入 `useJdkUnsafe = true`，也以 `Strict` 为准。

### 2.9 `JSONObject` / `JSONArray`
<!-- capability-id: org-json-mismatch -->

1. 后端实际返回：对象和数组形状正确。
2. 默认处理：通过 `org.json` 专用 Adapter 读取。
3. 观测证据：正常解析不产生类型错配事件；`JSONObject` 收到数组、`JSONArray` 收到对象时返回 `null`，并产生 `TypeMismatch`，字段内 path 指向对应字段，根级形状不一致为 `path=$`。
4. 边界：`org.json` 桥接会先读成 `JsonElement` 再转换成 `JSONObject` / `JSONArray`，大字段会有一次额外字符串转换成本。

### 2.10 Retrofit 空 body
<!-- capability-id: retrofit-empty-response -->

1. 后端实际返回：空响应体。
2. 默认处理：`Unit` 返回 `Unit`，`Void` 和普通业务模型返回 `null`；显式修改 `EmptyResponsePolicy` 后才返回默认对象或交回 Gson。
3. 观测证据：`EmptyResponse`，报告记录响应类型和空响应策略。
4. 边界：HTTP 错误码和业务错误码不由本库判断。

### 2.11 Retrofit raw JSON 捕获
<!-- capability-id: retrofit-raw-json-capture -->

1. 后端实际返回：响应体需要用于排障，或显式开启捕获后响应体过大、未知长度超限。
2. 默认处理：默认不捕获 raw JSON，也不会产生 `RawJsonCaptureSkipped`；响应继续走普通 Converter 路径。
3. 观测证据：显式开启 `captureRawJsonInCallbacks` 后，超限才会产生 `RawJsonCaptureSkipped`，报告记录长度、上限、`skipReason` 和 `captureSkipReason`。
4. 边界：报告默认不输出 raw body，避免日志侧漏；只有调试配置或手动开启捕获时，错配事件才会在上限内携带 raw JSON。

## 3. 契约报告能给后端什么

```kotlin
val result = GsonSafeParser.parseSafe<ApiResponse>("""{"code":200,"data":[]}""")
println(result.contractReport().toBackendMarkdown())
```

报告会把“Android 解析失败”翻成接口契约问题，例如：`$.data` 代码期望 JSON object，实际收到 JSON array；GsonSafeParser 已跳过该字段并保住外层对象；后端应在 `$.data` 返回对象而不是数组。

## 4. 上线策略

1. 联调阶段用 `SafeParserConfig.debug()`，保留有限 raw JSON，快速定位接口返回。
2. 灰度阶段用 `SafeParserConfig.lowInterference()`，先观察事件，降低行为改动。
3. 线上阶段用 `SafeParserConfig.production()`，关闭 raw JSON 正文，只上报结构化事件和契约报告字段。

## 5. 不处理的范围

1. JSON 语法错误不是字段级 JSON 形状不一致，会继续抛出。
2. 根级解析失败不能总是隔离到字段，会继续遵循 Gson 边界。
3. 业务协议错误、HTTP 错误码、签名失败、字段语义错误不由本库判断。
4. 自定义 Adapter 主动抛出的普通异常只有能被字段级读取边界隔离时才会变成当前字段事件；`Error`、`ThreadDeath`、`LinkageError`、`CancellationException` 这类不可安全隔离问题会继续外抛。
