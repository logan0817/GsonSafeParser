# Demo app release minify rules.
# AAR consumer rules already keep GsonSafeParser framework internals.
# The app only owns business model keep rules.

# Keep demo model fields and constructors so release minify can still validate JSON field
# names, data class defaults, and fallback construction.
# In real apps, old projects should start with package-level keep for bean/model/entity/response/dto
# packages first, then narrow the scope after release self-checks pass.
# Replace this package with your real API model package in your app.
-keep class io.github.logan.gsonsafeparser.demo.model.** {
    <fields>;
    public <init>(...);
}
