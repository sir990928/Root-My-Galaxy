# 全局R8深度优化
-optimizationpasses 7
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-verbose
# 激进裁剪冗余代码，保留基础类型转换逻辑
-optimizations !code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable

# Compose 轻量化保护，只保必要标识，其余全混淆压缩
-keepnames class androidx.compose.**
# 所有Composable UI函数必须保留，界面不会崩溃
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
# Stable标记类，Compose重组逻辑依赖
-keep @androidx.compose.runtime.Stable class *

# 自身业务包仅保留类名，方法/字段全部混淆、无用代码自动删除
-keepnames class com.glaxysu.root.**

# JNI native 本地方法强制保留，防止调用so找不到符号
-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlin协程最小保护范围，不锁住全部内部实现
-keepnames class kotlinx.coroutines.**
-keepclassmembers class ** {
    @kotlinx.coroutines.ExperimentalCoroutinesApi <methods>;
}

# Conscrypt加密库屏蔽不存在的旧平台类警告，不影响编译
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn org.conscrypt.KitKatPlatformOpenSSLSocketImplAdapter
-dontwarn org.conscrypt.PreKitKatPlatformOpenSSLSocketImplAdapter

# 彻底移除全部Log打印，字节码直接删除，有效减小包体积
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# shrinkResources 资源压缩兼容，防止视图回调被误删
-keepclassmembers class * {
    void *(android.view.View);
}
