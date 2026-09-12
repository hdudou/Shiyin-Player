# ============================================================
# MusicPlayer 混淆规则（基础）
# ============================================================

# 保留 Android 组件入口
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# 保留 Hilt / Dagger 生成类
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# 保留 Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 保留 Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.**

# 保留 jcifs-ng（SMB）
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# 保留 okhttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# ============================================================
# 保留 androidx.security.crypto + tink（I-R8：导出/导入网络源凭据的
# EncryptedSharedPreferences 加密链路，涉及反射/内部状态，需显式保留）
# ============================================================
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# 保留协程
-keep class kotlin.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# 保留序列化模型（如需 Gson/Moshi 再加对应规则）
-keepattributes Signature
-keepattributes *Annotation*

# 不混淆数据模型包（便于调试 DB 实体字段）
-keep class com.shiyinplayer.data.model.** { *; }
-keep class com.shiyinplayer.data.local.entity.** { *; }

# ============================================================
# JNI native 方法保留（P1A libxmp-jni / P2B libfluidsynth_jni）
# ============================================================

# 保留所有 native 方法及其所在类
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留 com.lossydragon.native.Player（libxmp-jni JNI 绑定）
-keep class com.lossydragon.native.Player { *; }
-keep class com.lossydragon.native.model.** { *; }

# 保留 FluidSynthJni（libfluidsynth_jni JNI 绑定）
-keep class com.shiyinplayer.player.decoder.ndk.fluidsynth.FluidSynthJni { *; }

# 保留 NDK 渲染器类层次
-keep class com.shiyinplayer.player.decoder.ndk.** { *; }

# ============================================================
# 保留 ZeroTier libzt 原生绑定（P3 / 版本更新 / WebDAV-ZT 必需）
# ============================================================
# 经 Class.forName("com.zerotier.sockets.ZeroTierNative") 反射加载，R8 无法静态跟踪，
# 若无 keep 规则会在 release 中被整库移除，导致真机报「libzt 原生库缺失」。
-keep class com.zerotier.sockets.** { *; }
-dontwarn com.zerotier.sockets.**

# ============================================================
# SLF4J 缺失类抑制（R8 minifyReleaseWithR8 需要）
# ============================================================
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.LoggerFactory
