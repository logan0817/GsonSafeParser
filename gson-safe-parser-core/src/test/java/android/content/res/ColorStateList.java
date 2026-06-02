package android.content.res;

/**
 * ColorStateList 的父类替身。
 *
 * JVM 单元测试里没有真实 Android SDK，这里只保留一个同名字段，
 * 用来模拟 Android 平台类内部字段和子类字段重名的情况。
 */
class ColorStateListParent {
    /**
     * 模拟 Android 平台类里的内部字段。
     */
    @SuppressWarnings("unused")
    public int mChangingConfigurations;
}

/**
 * JVM 测试用的 ColorStateList 替身。
 *
 * 这个类只用于验证 Safe Reflective 默认会跳过 Android 平台对象，
 * 避免反射平台类内部字段时出现重复字段或访问限制问题。
 */
public class ColorStateList extends ColorStateListParent {
    /**
     * 故意和父类同名的字段。
     *
     * 如果 SafeParser 错误反射 Android 平台类，这个字段会触发重复字段问题。
     */
    @SuppressWarnings("unused")
    public String mChangingConfigurations;
}
