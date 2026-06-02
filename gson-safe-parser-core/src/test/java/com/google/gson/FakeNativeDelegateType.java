package com.google.gson;

/**
 * 放在 com.google.gson 包下的测试类型。
 *
 * SafeTypeAdapterFactory 看到 Gson 自己包名时应该直接交回原生链路，
 * 这个类用于验证“库内置类型不被 Safe Adapter 抢走”这个边界。
 */
public final class FakeNativeDelegateType {
    /**
     * 测试字段。
     *
     * 用公开字段模拟普通 Gson Bean，方便断言原生 Gson Adapter 是否还能正常读写它。
     */
    public int value;

    /**
     * Gson 原生反射需要的无参构造。
     */
    public FakeNativeDelegateType() {
    }
}
