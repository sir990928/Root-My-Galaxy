# ============ 极致压缩优化 ============

# 多次优化迭代
-optimizationpasses 10

# 基础配置
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-verbose

# 激进优化
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable

# 合并类和接口
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively

# 所有类移到根包，类名最小化
-repackageclasses ''
-useuniqueclassmembernames

# 移除调试信息
-renamesourcefileattribute ''
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,EnclosingMethod,*Annotations*

# ============ Compose 最小保护 ============
-keepnames class androidx.compose.**
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keep @androidx.compose.runtime.Stable class *
-keep @androidx.compose.runtime.Immutable class *

# ============ 业务代码 ============
-keep class com.glaxysu.root.** {
    public protected *;
}

# ============ JNI ============
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============ Kotlin 协程 ============
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class ** {
    @kotlinx.coroutines.ExperimentalCoroutinesApi <methods>;
}

# ============ 反射/序列化 ============
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ============ 屏蔽警告 ============
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

# ============ 移除 Kotlin 空检查 ============
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNullParameter(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkNotNull(...);
}

# ============ View 回调保护 ============
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
    void *(android.view.View);
}

# ============ ADB 无线调试证书保留 ============
-keep class android.sun.security.x509.** { *; }
-keep class android.sun.security.provider.** { *; }

# ============ 移除后量子加密 ============
-dontwarn org.bouncycastle.pqc.**