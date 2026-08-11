# ========== Compose 保护（精简不冗余，避免膨胀） ==========
-keepnames class androidx.compose.**
# 保留所有Composable函数，防止UI失效
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
# 保留Stable稳定标记
-keep @androidx.compose.runtime.Stable class *

# 业务包仅保留类名，内部方法/字段全混淆裁剪，减少体积
-keepnames class com.glaxysu.root.**

# ========== JNI Native 方法必保（防止so调用崩溃） ==========
-keepclasseswithmembernames class * {
    native <methods>;
}

# ========== Kotlin 协程精简保护 ==========
-keepnames class kotlinx.coroutines.**
-keepclassmembers class ** {
    @kotlinx.coroutines.ExperimentalCoroutinesApi <methods>;
}

# ========== Conscrypt 加密库屏蔽无用警告 ==========
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn org.conscrypt.KitKatPlatformOpenSSLSocketImplAdapter
-dontwarn org.conscrypt.PreKitKatPlatformOpenSSLSocketImplAdapter

# ========== 极致瘦身：彻底删除所有Log代码（取消注释启用，大幅减字节码） ==========
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# ========== shrinkResources 资源压缩兼容规则 ==========
-keepclassmembers class * {
    void *(android.view.View);
}

# ========== 全局R8优化配置（新增，强力瘦身） ==========
-optimizationpasses 7
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-verbose
# 移除无用注解、空构造、无用get/set
-optimizations !code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable
