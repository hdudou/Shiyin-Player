# jniLibs 占位目录

本目录用于放置 **libzt（ZeroTier 用户态）** 原生库，对应架构文档 §2 / T13。

## 现状
- 首版仅打包 `arm64-v8a` 与 `armeabi-v7a` 两个 ABI（见 app/build.gradle.kts 的 ndk.abiFilters）。
- 目录下需包含：
  - `arm64-v8a/libzt.so`
  - `armeabi-v7a/libzt.so`

## 获取方式（见 ARCHITECTURE §8 Q12/Q17）
1. **首选**：libzt 官方 Android 预编译包（AAR 或 jniLibs，含 Java 绑定）。
2. **备选**：自行 NDK + CMake 编译 libzt 源码（ZeroTier core + lwIP 用户态栈 + JNI）。
   - 注意：`libzt.so` 与 `zerotier-one.apk` 中的 `libZeroTierOneJNI.so` 不是同一库，不可混用。

## 降级说明
在缺少 `libzt.so` 时，`ZtNode` 会进入优雅降级模式（ZeroTier 功能不可用，但本地/SMB/WebDAV 播放不受影响）。
工程仍可正常编译与构建。
