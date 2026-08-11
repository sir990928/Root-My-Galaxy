## Compose R8 优化规则，不全盘keep，只保护运行必需
-keepnames class androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.node.** { *; }
-keep class androidx.compose.ui.platform.** { *; }

# Compose 生成Composable合成类必须保留，否则界面空白闪退
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}

## 业务代码：不要写 -keep class com.glaxysu.root.** { *; }
# 全盘keep会废掉minify压缩！改用keepnames，允许混淆类名，保留类签名
-keepnames class com.glaxysu.root.**

## JNI native方法保护，防止被R8删掉
-keepclasseswithmembernames class * {
    native <methods>;
}

## Kotlin协程
-keepnames class kotlinx.coroutines.**
# 协程内部状态机不能被删掉
-keepclassmembers class ** {
    @kotlinx.coroutines.ExperimentalCoroutinesApi <methods>;
}

## Conscrypt 旧平台适配器抑制警告 minSdk33完全用不到
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn org.conscrypt.KitKatPlatformOpenSSLSocketImplAdapter
-dontwarn org.conscrypt.PreKitKatPlatformOpenSSLSocketImplAdapter

## 可选：Release构建抹除Log，进一步缩减+关闭日志输出
# -assumenosideeffects class android.util.Log {
#     public static boolean isLoggable(java.lang.String, int);
#     public static int v(...);
#     public static int i(...);
#     public static int w(...);
#     public static int d(...);
#     public static int e(...);
# }

## R8资源压缩防护 shrinkResources=true 需要
-keepclassmembers class * {
    void *(android.view.View);
}
