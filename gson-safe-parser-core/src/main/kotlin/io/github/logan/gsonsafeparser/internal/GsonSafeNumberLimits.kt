package io.github.logan.gsonsafeparser.internal

import java.math.BigDecimal

/**
 * 与当前 Gson 版本一致的数字输入边界。
 *
 * SafeParser 在安全基础类型和 `Any` 数字策略中会先构造 BigDecimal；先执行同样的长度、scale 限制，
 * 避免异常大的数字输入绕过 Gson 的资源保护。
 */
internal object GsonSafeNumberLimits {
    private const val MAX_NUMBER_STRING_LENGTH = 10_000

    fun parseBigDecimal(value: String): BigDecimal {
        require(value.length <= MAX_NUMBER_STRING_LENGTH) {
            "Number string too large"
        }
        val decimal = BigDecimal(value)
        // 转成 Long 后再取绝对值，避免 Int.MIN_VALUE 溢出。
        require(kotlin.math.abs(decimal.scale().toLong()) < MAX_NUMBER_STRING_LENGTH) {
            "Number has unsupported scale"
        }
        return decimal
    }
}
