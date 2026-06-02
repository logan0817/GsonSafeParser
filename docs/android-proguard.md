# Android 混淆配置

[English](en/android-proguard.md)

这份文档只回答一个问题：Android release 包开启 R8 / ProGuard 后，接入 GsonSafeParser 要怎么配才不容易出问题。

先记住 3 个结论：

1. debug 包或未开启 minify 的包可以先零配置试用。
2. release 包开启 R8 / ProGuard 后，业务模型必须保留必要信息。
3. GsonSafeParser AAR 会自动合并框架自身规则，用户只需要处理业务模型规则。

## 1. 先选接入方案

| 项目状态 | 推荐方案 | 你要做什么 |
| --- | --- | --- |
| 新项目，模型包比较清楚 | 新项目接入 | 新字段加 `@SerializedName`，给真实模型包保留构造方法和必要字段。 |
| 老项目，Bean 散落在不同目录 | 老项目快速接入 | 先按 bean、model、entity、response、dto 等包名做宽范围 keep，让 release 先稳定。 |
| 已经接入稳定，想减少 keep 范围 | 逐步收窄 | 用 release 自检和真实 JSON 对照，把宽范围规则收窄到真实响应模型包。 |

GsonSafeParser 不能自动知道业务模型混淆前字段名。

没有 `@SerializedName`，也没有 keep 字段名时，Gson 在 release 包里可能按 `a`、`b`、`c` 这种混淆字段读写 JSON。

## 2. 新项目接入

新项目建议从一开始给 JSON 字段加 `@SerializedName`。这样字段被 R8 改名后，Gson 仍然能按注解里的 JSON 名称读写。

```kotlin
data class UserResponse(
    @SerializedName("id")
    val id: Long = 0L,
    @SerializedName("name")
    val name: String = ""
)
```

App 模块的 `proguard-rules.pro` 推荐加上真实模型包规则。下面的包名只是示例，要换成你的业务包：

```proguard
-keep,allowobfuscation class com.yourcompany.api.model.** { # 新项目：模型字段已使用 @SerializedName，可以允许类名混淆。
    public <init>(...); # 保留构造方法，避免 Kotlin 默认值构造链路被裁剪。
}

-keepclassmembers,allowobfuscation class com.yourcompany.api.model.** { # 保留显式声明 JSON 名称的字段成员。
    @com.google.gson.annotations.SerializedName <fields>; # 字段名可以混淆，JSON 名称由 @SerializedName 固定。
}
```

如果某些模型字段还没有 `@SerializedName`，这部分字段名仍要 keep：

```proguard
-keep class com.yourcompany.api.model.** { # 新项目过渡期：仍有字段依赖原始字段名。
    <fields>;
    public <init>(...);
}
```

`@SerializedName` 只能固定 JSON 字段名，不能替代 Kotlin Metadata 和构造方法 keep。

如果模型依赖 Kotlin data class 默认值、主构造参数、非空字段兜底或 `kotlin-reflect`，release 包仍要保留构造方法和 Kotlin Metadata。

## 3. 老项目快速接入

老项目低成本接入的原则是先宽后窄。

第一天不要要求用户整理全项目 Bean，也不要求立刻全量补 `@SerializedName`。先让 release 包稳定，再慢慢缩小规则。

如果 Bean 散落在不同目录，先用下面模板覆盖常见模型包名。把 `com.yourcompany` 换成你的真实包名前缀：

```proguard
-keep class com.yourcompany.**.bean.** { # 老项目：先保住散落的 Bean 字段名和构造方法。
    <fields>;
    public <init>(...);
}

-keep class com.yourcompany.**.model.** { # 老项目：先保住 model 包。
    <fields>;
    public <init>(...);
}

-keep class com.yourcompany.**.entity.** { # 老项目：先保住 entity 包。
    <fields>;
    public <init>(...);
}

-keep class com.yourcompany.**.response.** { # 老项目：先保住 response 包。
    <fields>;
    public <init>(...);
}

-keep class com.yourcompany.**.dto.** { # 老项目：先保住 dto 包。
    <fields>; # 没有 @SerializedName 的字段必须保留字段名。
    public <init>(...); # 保留构造方法，支持 Kotlin 默认值和兜底构造。
}
```

这套规则会增加一部分混淆保留范围，但适合老项目快速接入。等 release 自检和真实接口 JSON 都稳定后，再按业务模块或接口响应包收窄。

## 4. 逐步收窄模板

当你已经知道哪些包是真正参与 Gson 解析的响应模型，可以把宽范围规则收窄成这样：

```proguard
-keep class com.yourcompany.feature.user.api.response.** { # 只保留用户接口响应模型。
    <fields>;
    public <init>(...);
}

-keep class com.yourcompany.feature.order.api.response.** { # 只保留订单接口响应模型。
    <fields>;
    public <init>(...);
}
```

收窄前先做 2 件事：

1. 用同一份 JSON 对比 debug 和 release 包。
2. 给关键响应模型配置 `GsonSafeModelProbe`，确认没有 `modelFieldObfuscationSuspected`、`modelConstructorUnavailable` 或 `modelProbeFailure`。

## 5. R8 fullMode 选择

`android.enableR8.fullMode=true` 是长期推荐路径，前提是业务模型 keep 规则已经配好，并且 release 自检通过。

`android.enableR8.fullMode=false` 是可选兼容策略，适合复杂老项目先降低接入成本、快速稳定 release 行为。

它可以降低构造方法、Metadata 和反射信息被激进优化影响的风险，但不能恢复已经被混淆的字段名。

| 选择 | 适合场景 | 仍然必须做什么 |
| --- | --- | --- |
| `android.enableR8.fullMode=true` | 新项目，或老项目已经梳理清楚模型规则 | 保留业务模型字段名或使用 `@SerializedName`，保留构造方法，跑 debug/release 对照。 |
| `android.enableR8.fullMode=false` | 老项目短期内无法梳理完所有模型规则 | 仍然要 keep 业务模型字段名和构造方法，不能把它当成零配置上线方案。 |

如果选择兼容策略，在 `gradle.properties` 中配置：

```properties
android.enableR8.fullMode=false # 可选兼容策略：降低老项目 release 优化风险，但业务模型字段名仍要 keep。
```

`android.enableR8.fullMode=false` 不是零混淆配置开关。只要模型没有 `@SerializedName`，字段名就仍然需要 keep。

## 6. AAR 已经自动合并的规则

当前 core 和 retrofit 都是 Android AAR。Android AAR 会自动把框架自身 consumer ProGuard 规则合并进用户 App，用户不需要手抄这部分。

下面规则已经内置在 GsonSafeParser AAR 里。只有拷源码、自定义发布链路丢失 consumer rules，或需要排查合并结果时，才需要手动对照：

```proguard
-keep class kotlin.Metadata { *; } # 保留 Kotlin Metadata，支持 data class 默认值和反射信息读取。
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault # 保留 Gson 和 Kotlin 反射需要的属性。

-keepclassmembers class com.google.gson.GsonBuilder { # 保留 SafeParser 读取的 GsonBuilder 内部字段名。
    java.util.Map instanceCreators;
    java.util.List factories;
    java.util.ArrayDeque reflectionFilters;
    com.google.gson.ToNumberStrategy objectToNumberStrategy;
    boolean useJdkUnsafe;
    boolean complexMapKeySerialization;
}
```

如果 release 包出现 `AdapterCreationFailure`，但 debug 包正常，优先检查 AAR consumer ProGuard 规则是否真的合并进 App。

## 7. release 验证清单

发布前至少做这 5 项：

1. 用同一份真实 JSON 分别验证 debug 包和 release 包。
2. 确认普通字段名模型、`@SerializedName` 模型、Kotlin data class 默认值都能正常解析。
3. 打开 `onTypeMismatch` 和 `onAdapterCreationFailure`，确认 release 包中仍能看到字段路径和期望类型。
4. 对关键业务模型配置 `GsonSafeModelProbe`，确认没有 `modelFieldObfuscationSuspected`、`modelConstructorUnavailable` 或 `modelProbeFailure`。
5. 如果只有 release 包失败，先检查业务模型 keep 规则、Kotlin Metadata、构造方法、GsonBuilder 字段名和 `kotlin-reflect` 版本。

## 8. 平台对象

默认配置会跳过 `android.*` 平台类型字段，避免 `View`、`ColorStateList` 等平台对象被 Safe Reflective 接管。

不要把业务模型包名前缀放这里，也不要放进 `skippedPlatformTypePrefixes`，否则业务字段会被跳过解析。

```kotlin
SafeParserConfig(
    skippedPlatformTypePrefixes = setOf("android.") // 跳过 Android 平台类型；不要把业务模型包名前缀放这里。
)
```

业务模型包应该通过 ProGuard keep 规则保护，不应该放进 `skippedPlatformTypePrefixes`。
