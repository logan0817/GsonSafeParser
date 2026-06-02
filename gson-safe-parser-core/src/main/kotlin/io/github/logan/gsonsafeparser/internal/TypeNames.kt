package io.github.logan.gsonsafeparser.internal

import java.lang.reflect.Type

/**
 * 把 Type 转成兼容低 Android 版本的类型名。
 *
 * 这里不能直接调用 `Type.typeName`，因为它会把 demo 的 minSdk 直接抬到 API 28。
 * 对于 Class 用 `name`，对于其他 Type 退回 `toString()`，这样既能保留泛型信息，也不会触发低版本 API 依赖。
 */
internal fun Type.toSafeTypeName(): String {
    return if (this is Class<*>) {
        name
    } else {
        toString()
    }
}
