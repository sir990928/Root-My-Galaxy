# Compose 轻量化保护，合法语法
-keepnames class androidx.compose.**
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
# 单独保留 Stable 标记类，正确写法
-keep @androidx.compose.runtime.Stable class *

# 业务代码仅保留类名，允许混淆、裁剪无用函数
-keepnames class com.glaxysu.root.**

# JNI native 方法强制保留，防止被R8删除
-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlin 协程基础保护
-keepnames class kotlinx.coroutines.**
-keepclassmembers class ** {
    @kotlinx.coroutines.ExperimentalCoroutinesApi <methods>;
}

# Conscrypt 屏蔽过时API警告
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn org.conscrypt.KitKatPlatformOpenSSLSocketImplAdapter
-dontwarn org.conscrypt.PreKitKatPlatformOpenSSLSocketImplAdapter

# 取消注释彻底移除所有日志，进一步瘦身
# -assumenosideeffects class android.util.Log {
#     public static boolean isLoggable(java.lang.String, int);
#     public static int v(...); public static int i(...); public static int w(...);
#     public static int d(...); public static int e(...);
# }

# shrinkResources 资源压缩兼容
-keepclassmembers class * {
    void *(android.view.View);
}
