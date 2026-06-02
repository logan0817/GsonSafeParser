package io.github.logan.gsonsafeparser.internal

import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.sql.Time
import java.sql.Timestamp
import java.util.BitSet
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Gson 已经有稳定内置 Adapter 的类型白名单。
 *
 * 这些类型交给 Gson 原生处理更安全，Safe Reflective 不应该去反射它们的内部字段，
 * 否则很容易遇到 JDK 模块限制、字段冲突或平台类型不可访问问题。
 */
internal object GsonBuiltInTypes {
    /**
     * Gson 原生已经覆盖得很好的类型集合。
     *
     * 新增类型前要先确认 Gson 是否真的有内置 Adapter，不要把普通业务类型误放进来。
     */
    private val types = setOf(
        Number::class.java,
        AtomicInteger::class.java,
        AtomicBoolean::class.java,
        AtomicLong::class.java,
        AtomicLongArray::class.java,
        AtomicIntegerArray::class.java,
        Character::class.java,
        StringBuilder::class.java,
        StringBuffer::class.java,
        URL::class.java,
        URI::class.java,
        UUID::class.java,
        Currency::class.java,
        Locale::class.java,
        InetAddress::class.java,
        BitSet::class.java,
        Date::class.java,
        GregorianCalendar::class.java,
        Calendar::class.java,
        Time::class.java,
        java.sql.Date::class.java,
        Timestamp::class.java,
        Class::class.java
    )

    /**
     * 判断目标类型是否应该交给 Gson 内置 Adapter。
     *
     * @param rawType 目标类型的原始 Class。
     * @return `true` 表示 SafeParser 不接管这个类型。
     */
    fun contains(rawType: Class<*>): Boolean {
        return types.contains(rawType)
    }

    /**
     * Android 这类平台类型默认跳过字段绑定。
     *
     * 例如 View、ColorStateList 等对象内部字段复杂，业务 Bean 里通常只是临时状态，不适合作为 JSON 契约解析。
     *
     * @param rawType 字段类型。
     * @param prefixes 需要跳过的包名前缀。
     * @return 字段类型是否命中平台类型前缀。
     */
    fun isSkippedPlatformType(rawType: Class<*>, prefixes: Set<String>): Boolean {
        return prefixes.any { prefix -> rawType.name.startsWith(prefix) }
    }
}
