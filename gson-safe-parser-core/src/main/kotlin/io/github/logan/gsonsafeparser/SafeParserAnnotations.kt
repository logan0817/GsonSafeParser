package io.github.logan.gsonsafeparser

/**
 * 标在类上，表示这个类型完全交回 Gson 原生链路。
 *
 * 适合已经有严格自定义 TypeAdapter、字段结构特殊、或者不希望 Safe Adapter 介入的模型。
 * 对新手来说，可以把它理解成“这个类不要走 GsonSafeParser，按原来的 Gson 方式解析”。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SafeParseDelegateToGson

/**
 * 标在字段上，表示 Safe Reflective 不读写这个字段。
 *
 * 常见用途是跳过 Android 平台对象、运行时缓存、懒加载字段，减少反射访问带来的误伤。
 * 被跳过的字段会保留对象构造后的默认值，不会从 JSON 里读，也不会被 Safe Reflective 写出。
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class SafeParseSkip

/**
 * 标在字段上，表示这个字段允许按指定策略做 JSON 对象和数组形态转换。
 *
 * 字段级策略优先于全局 `SafeParserConfig.shapeCoercionPolicy`。适合只放开少数后端返回不稳定的字段。
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class SafeParseShapeCoercion(
    val policy: ShapeCoercionPolicy = ShapeCoercionPolicy.ObjectAndCollection
)

/**
 * 标在字段上，表示即使全局开启 shape coercion，这个字段也保持原错形兜底行为。
 *
 * 常见用途是错误响应模型、强契约字段或不允许数组取首项的字段。
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class SafeParseDisableShapeCoercion
