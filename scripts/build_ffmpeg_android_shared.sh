#!/bin/bash
# ============================================================================
# build_ffmpeg_android_shared.sh —— 输出「独立 .so 共享库」的 FFmpeg 构建脚本（R-B2）
#
# 目的：
#   1) LGPL 合规：FFmpeg 以独立 .so 动态链接分发（而非静态链进应用 .so），
#      消费者可直接替换 libav*.so，无需 relink 整个应用（最小 LGPL 义务）。
#   2) 体积可控：TRIM 参数按需裁剪 demuxer/decoder 组件。
#   相比 build_ffmpeg_android.sh（静态库）：
#       --enable-shared --disable-static（静态库版反之）
#       产物输出到 <源码>/out_android_shared/<abi>/lib/libav{codec,format,util,swresample}.so
#       不覆盖/不删除原静态库 out_android（保留既可用产物作 fallback）。
#
# 用法（MSYS2 bash，NDK clang 交叉编译，需 export NDK）：
#   export NDK=<你的Android NDK路径，如 /opt/android-sdk/ndk/26.1.10909125>
#   bash build_ffmpeg_android_shared.sh </ffmpeg源码> [arm64-v8a|armeabi-v7a] [api] [TRIM]
#   ABI 以 .so 释放到 <源码>/out_android_shared/<abi>/lib/
#   TRIM=0（默认）：仅共享化 + 基础裁剪（保功能）
#   TRIM=1：--disable-everything + 仅启用软解所需的音频 demuxer/decoder（激进裁剪，逐个校验后启用）
# ============================================================================
set -euo pipefail

FFMPEG_SRC="${1:?用法: build_ffmpeg_android_shared.sh <ffmpeg源码> [abi] [api] [trim]}"
ABI="${2:-arm64-v8a}"
API="${3:-24}"
TRIM="${4:-0}"

case "$ABI" in
  arm64-v8a)   TRIPLE="aarch64-linux-android"; ARCH="aarch64";;
  armeabi-v7a) TRIPLE="armv7a-linux-androideabi";  ARCH="arm";;
  *) echo "不支持 ABI: $ABI（仅 arm64-v8a / armeabi-v7a）"; exit 1;;
esac

: "${NDK:?请 export NDK=<ndk绝对路径>}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
CC="$TOOLCHAIN/bin/clang"
SYSROOT="$TOOLCHAIN/sysroot"

if [ "$ABI" = "armeabi-v7a" ]; then
  TARGET_FLAG="--target=${TRIPLE}${API} -mfpu=vfpv3-d16 -mfloat-abi=softfp"
else
  TARGET_FLAG="--target=${TRIPLE}${API}"
fi

OUT="$FFMPEG_SRC/out_android_shared/$ABI"
rm -rf "$OUT"
(
cd "$FFMPEG_SRC"

# 同一源码树被多 ABI 交叉编译过，in-source 构建会残留其它 ABI 的 .o → 共享库 ld.lld
# 报 "object file is incompatible with aarch64linux"。构建前统一 distclean，保证对象属当前 ABI。
make distclean >/dev/null 2>&1 || true

if [ "$TRIM" = "1" ]; then
  # 激进裁剪（--disable-everything + 仅音频软解所需组件）。
  # 注意：ffmpeg_jni 走 avformat（含 demuxer）+ avcodec(decoder) + swresample。
  # 首轮未启用（TRIM=0 保功能），后续逐个真机校验后再切 TRIM=1。
  EXTRA_TRIM=(--disable-everything
    --enable-avformat --enable-avcodec --enable-avutil --enable-swresample
    --enable-demuxer=ape,wavpack,tta,mpc,musepack,ogg,atrac3,asf,tak,optimfrog,oma,aac,mp3,flac,wav,aiff
    --enable-decoder=ape,wavpack,tta,musepack7,musepack8,speex,atrac3,atrac3p,wmav1,wmav2,tak,optimfrog,aac,mp3,flac,pcm_s16le
    --enable-parser=aac,aac_latm,flac,mpeg4audio,vorbis
    --enable-protocol=file)
else
  EXTRA_TRIM=()
fi

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
  --enable-shared
  --disable-static
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
  "${EXTRA_TRIM[@]}"
)

echo "================ 配置 FFmpeg ($ABI shared) api=$API trim=$TRIM ================"
./configure "${CONFIGURE_FLAGS[@]}"
echo "================ 编译 FFmpeg ($ABI shared) ================"
make -j"$(nproc 2>/dev/null || echo 4)"
echo "================ 安装独立 .so 到 $OUT ================"
make install
)

echo "DONE: 独立共享库位于 $OUT/lib"
ls -1 "$OUT"/lib/libav*.so 2>/dev/null