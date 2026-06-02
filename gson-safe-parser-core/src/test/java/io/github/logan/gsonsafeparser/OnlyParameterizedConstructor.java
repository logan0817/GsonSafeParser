package io.github.logan.gsonsafeparser;

/**
 * 只有带参构造的测试模型。
 *
 * 用来验证 JDK Unsafe 开关和构造失败回退，避免 SafeObjectConstructor 偷偷绕过用户配置。
 */
public final class OnlyParameterizedConstructor {
    /**
     * 测试字段。
     *
     * 如果对象能被构造出来，测试可以通过这个字段判断构造链路是否真的运行过。
     */
    public String value;

    /**
     * 唯一构造函数。
     *
     * @param value 写入测试字段的字符串。
     */
    public OnlyParameterizedConstructor(String value) {
        this.value = value;
    }
}
