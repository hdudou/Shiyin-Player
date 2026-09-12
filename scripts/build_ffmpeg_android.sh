#!/bin/bash
# ============================================================================
# build_ffmpeg_android.sh —— 用 Android NDK 在 MSYS2/MinGW bash 环境交叉编译
# 静态 FFmpeg（R-B1：ape/wv/tta/mpc/spx/aa3/at3/oma/wma/tak/ofr 软解底座）。
#
# 前置：
#   1) 已装 MSYS2，并已安装：pacman -S --needed base-devel diffutils nasm yasm pkgconf
#   2) 已解压 FFmpeg 源码（configure 是 POSIX shell 脚本，必须在 bash 中运行）。
#   NDK 用自带 windows-x86_64 clang 交叉编译（CC 直接指向 clang.exe，跨到 android 用 --target），
#   无需安装 Linux toolchain。
#
# 用法：
#   export NDK=<你的Android NDK路径，如 /opt/android-sdk/ndk/26.1.10909125>
#   bash build_ffmpeg_android.sh </路径/ffmpeg源码> [arm64-v8a|armeabi-v7a] [api]
#   例：bash build_ffmpeg_android.sh /path/to/ffmpeg-6.1 arm64-v8a 24
#
# 产物（静态库 + 头，供 CMake 的 ffmpeg_jni 目标链接）：
#   <ffmpeg源码>/out_android/<abi>/lib/libavformat.a libavcodec.a libavutil.a libswresample.a
#   <ffmpeg源码>/out_android/<abi>/include/...
# ============================================================================
set -euo pipefail

FFMPEG_SRC="${1:?用法: build_ffmpeg_android.sh <ffmpeg源码> [abi] [api]}"
ABI="${2:-arm64-v8a}"
API="${3:-24}"

case "$ABI" in
  arm64-v8a)   TRIPLE="aarch64-linux-android"; ARCH="aarch64";;
  armeabi-v7a) TRIPLE="armv7a-linux-androideabi";  ARCH="arm";;
  *) echo "不支持的 ABI: $ABI（仅 arm64-v8a / armeabi-v7a）"; exit 1;;
esac

: "${NDK:?请 export NDK=<ndk绝对路径>}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
CC="$TOOLCHAIN/bin/clang"
SYSROOT="$TOOLCHAIN/sysroot"

# v7a: 用 32 位 target + softfp
if [ "$ABI" = "armeabi-v7a" ]; then
  TARGET_FLAG="--target=${TRIPLE}${API} -mfpu=vfpv3-d16 -mfloat-abi=softfp"
else
  TARGET_FLAG="--target=${TRIPLE}${API}"
fi

OUT="$FFMPEG_SRC/out_android/$ABI"
rm -rf "$OUT"
(
cd "$FFMPEG_SRC"

# 覆盖默认检测：bionic 内建 pthread，无需 -lpthread
PROTO=""
CONFIGURE_FLAGS=(
  --prefix="$OUT"
  --target-os=android
  --arch="$ARCH"
  --enable-cross-compile
  --cc="$CC"
  --ar="$TOOLCHAIN/bin/llvm-ar"
  --nm="$TOOLCHAIN/bin/llvm-nm"
  --ranlib="$TOOLCHAIN/bin/llvm-ranlib"
  --strip="$TOOLCHAIN/bin/llvm-strip"
  --sysroot="$SYSROOT"
  --extra-cflags="$TARGET_FLAG"
  --extra-ldflags="$TARGET_FLAG"
  --enable-static
  --disable-shared
  --disable-programs
  --disable-doc
  --disable-avdevice
  --disable-postproc
  --disable-swscale
  --disable-network
  --disable-iconv
  --disable-zlib
  --disable-bzlib
  --disable-lzma
  --enable-pthreads
  --disable-encoders
  --disable-muxers
  --enable-small
  --enable-pic
)

echo "================ 配置 FFmpeg ($ABI, api=$API) ================"
./configure "${CONFIGURE_FLAGS[@]}"
echo "================ 编译 FFmpeg ($ABI) ================"
make -j"$(nproc 2>/dev/null || echo 4)"
echo "================ 安装到 $OUT ================"
make install
)

echo "DONE: 静态库位于 $OUT/lib"
case "$ABI" in
  arm64-v8a)   ls -1 "$OUT"/lib/libav*.a ;;
  armeabi-v7a) ls -1 "$OUT"/lib/libav*.a ;;
esac