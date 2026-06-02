package io.github.logan.gsonsafeparser.demo;

/**
 * 只有带参数构造函数的 demo 模型。
 *
 * Gson 解析普通对象时只会自动调用无参构造函数，或者在允许 Unsafe 的情况下绕过构造函数创建对象。
 * 这个类故意不提供无参构造函数，页面里的 `useJdkUnsafe=false` 用例就能验证：
 * 当调用方明确关闭 Unsafe 时，GsonSafeParser 不会私自绕过限制，而是把异常行为交回 Gson。
 */
public final class OnlyParameterizedConstructor {
    /** 远端 JSON 里的 value 字段。 */
    public final String value;

    /**
     * 唯一构造函数。
     *
     * @param value 业务字段值；这里保留参数只是为了让类没有无参构造函数。
     */
    public OnlyParameterizedConstructor(String value) {
        this.value = value;
    }
}
