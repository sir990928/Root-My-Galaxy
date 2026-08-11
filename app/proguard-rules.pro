# ============ 极致压缩优化 ============

# 多次优化迭代
-optimizationpasses 10

# 启用代码压缩、混淆、优化
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-verbose

# 激进优化（移除所有安全限制）
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable

# 合并所有能合并的类和接口
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively

# 更短的类和成员名
-repackageclasses ''
-useuniqueclassmembernames

# 移除所有未使用的类和成员
-dontshrink false
-dontoptimize false
-dontobfuscate false

# 移除所有行号和源文件信息，二进制不可调试
-renamesourcefileattribute ''
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,EnclosingMethod,*Annotations*

# ============ Compose 最小保护 ============
-keepnames class androidx.compose.**
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keep @androidx.compose.runtime.Stable class *
-keep @androidx.compose.runtime.Immutable class *

# ============ 业务代码全混淆 ============
# 不保留类名，所有都可重命名
-keep class com.glaxysu.root.** {
    public protected *;
}

# ============ JNI 最小保留 ============
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============ Kotlin 协程最小化 ============
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class ** {
    @kotlinx.coroutines.ExperimentalCoroutinesApi <methods>;
}

# ============ 反射/序列化保护 ============
# Gson/FastJson 实体类（如果有）
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ============ 去掉无用警告 ============
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn org.conscrypt.**
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn sun.misc.**

# ============ 完全移除 Log ============
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# 移除 assert 语句
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNullParameter(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkNotNull(...);
}

# ============ 资源保护 ============
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
    void *(android.view.View);
}