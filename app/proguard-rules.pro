# Compose 修正规则，不要全盘keep
-keepnames class androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.node.** { *; }
-keep class androidx.compose.ui.platform.** { *; }

# 你的代码
-keep class dev.busung.s25uroot.** { *; }

# JNI native 方法强制保留
-keepclasseswithmembernames class * {
    native <methods>;
}

# 协程基础保留
-keepnames class kotlinx.coroutines.**

# 可选：关闭日志（瘦身）
# -assumenosideeffects class android.util.Log {
#     public static boolean isLoggable(java.lang.String, int);
#     public static int v(...); public static int i(...); public static int w(...);
#     public static int d(...); public static int e(...);
# }
