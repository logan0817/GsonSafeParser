# GsonSafeParser framework rules automatically merged from the Android AAR.
# Business model package keep rules are still app-specific and must be configured by the app.

-keep class kotlin.Metadata { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclassmembers class com.google.gson.GsonBuilder {
    java.util.Map instanceCreators;
    java.util.List factories;
    java.util.ArrayDeque reflectionFilters;
    com.google.gson.ToNumberStrategy objectToNumberStrategy;
    boolean useJdkUnsafe;
    boolean complexMapKeySerialization;
}
