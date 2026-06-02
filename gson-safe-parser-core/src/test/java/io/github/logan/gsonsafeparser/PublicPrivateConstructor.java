package io.github.logan.gsonsafeparser;

/**
 * 私有无参构造的测试模型。
 *
 * ReflectionAccessFilter 相关用例会用它确认 SafeParser 是否尊重反射访问限制，
 * 尤其是不能在用户禁止访问时强行打开私有构造。
 */
public final class PublicPrivateConstructor {
    /**
     * 构造成功后能读取到的固定字段。
     */
    public final String name = "created";

    /**
     * 私有无参构造。
     *
     * 测试会通过它判断 SafeObjectConstructor 是否按配置决定能不能访问私有构造。
     */
    private PublicPrivateConstructor() {
    }
}
