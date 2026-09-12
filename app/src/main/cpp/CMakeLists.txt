cmake_minimum_required(VERSION 3.22.1)
project(shiyin_ffmpeg_native)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -fvisibility=hidden")

# ---- FFmpeg 库（R-B1 软解底座 + R-B2 独立 .so 动态分发；覆盖 ape/wma/wv/tta/mpc/spx/aa3/at3/oma/tak/ofr）----
# 头文件共用 ffmpeg_prebuilt/include（与 ABI 无关）。
set(FFMPEG_JNI_INC ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg_prebuilt/include)
set(FFMPEG_PREBUILT ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg_prebuilt/${ANDROID_ABI})

# 默认静态模式（兜底）：链接 ffmpeg_prebuilt/<abi>/lib 下的 .a，并隐藏归档符号
# （避免 NEON asm 的 ADR_PREL 重定位视为可抢占全局，见 R-B1 备注）。
set(FFMPEG_AVFORMAT   "${FFMPEG_PREBUILT}/lib/libavformat.a")
set(FFMPEG_AVCODEC    "${FFMPEG_PREBUILT}/lib/libavcodec.a")
set(FFMPEG_SWRESAMPLE "${FFMPEG_PREBUILT}/lib/libswresample.a")
set(FFMPEG_AVUTIL     "${FFMPEG_PREBUILT}/lib/libavutil.a")
set(FFMPEG_EXCLUDE_LIBS -Wl,--exclude-libs,ALL)

# 共享模式（R-B2）：独立 libav*.so 已放入 src/main/jniLibs/<abi>（由 build_ffmpeg_android_shared.sh
# 产出）。启用后 ffmpeg_jni / ffmpegJNI 改为链到动态库，APK 运行时由 linker 按 DT_NEEDED 自动加载，
# 同一份 FFmpeg 实例在两条解码路径间共享，LGPL 义务降为「可替换 .so」。
set(FFMPEG_SHARED_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
if(EXISTS "${FFMPEG_SHARED_LIBS_DIR}/libavcodec.so")
  foreach(n avformat avcodec swresample avutil)
    add_library(ffmpeg_shared_${n} SHARED IMPORTED)
    set_target_properties(ffmpeg_shared_${n} PROPERTIES
      IMPORTED_LOCATION "${FFMPEG_SHARED_LIBS_DIR}/lib${n}.so")
  endforeach()
  set(FFMPEG_AVFORMAT ffmpeg_shared_avformat)
  set(FFMPEG_AVCODEC  ffmpeg_shared_avcodec)
  set(FFMPEG_SWRESAMPLE ffmpeg_shared_swresample)
  set(FFMPEG_AVUTIL   ffmpeg_shared_avutil)
  set(FFMPEG_EXCLUDE_LIBS "")
endif()

# ---- ffmpeg_jni：libffmpeg 的 JNI 壳（AVIO 流式喂块 + demux/decode → PCM 到 AudioSink）----
add_library(ffmpeg_jni SHARED
    ffmpeg_jni.c
)
target_include_directories(ffmpeg_jni PRIVATE
    ${FFMPEG_JNI_INC}
)
target_link_options(ffmpeg_jni PRIVATE ${FFMPEG_EXCLUDE_LIBS})
target_link_libraries(ffmpeg_jni
    ${FFMPEG_AVFORMAT}
    ${FFMPEG_AVCODEC}
    ${FFMPEG_SWRESAMPLE}
    ${FFMPEG_AVUTIL}
    log
    m
)

# ---- 官方 FfmpegAudioRenderer 端 JNI（libffmpegJNI.so）----
# 由 media3 decoder_ffmpeg 源码模块的 ffmpeg_jni.cc 编出（vendored 为 ffmpeg_official_jni.cc），
# 供本例 vendored 的 androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer 使用（System.loadLibrary("ffmpegJNI")）。
# 与上面的自研 ffmpeg_jni 共享同一批 FFmpeg 库，lib 名/符号互不影响。
add_library(ffmpegJNI SHARED
    ffmpeg_official_jni.cc
)
target_include_directories(ffmpegJNI PRIVATE
    ${FFMPEG_JNI_INC}
)
set_target_properties(ffmpegJNI PROPERTIES CXX_STANDARD 11 CXX_STANDARD_REQUIRED ON)
target_link_options(ffmpegJNI PRIVATE ${FFMPEG_EXCLUDE_LIBS})
target_link_libraries(ffmpegJNI
    ${FFMPEG_AVCODEC}
    ${FFMPEG_SWRESAMPLE}
    ${FFMPEG_AVUTIL}
    log
    m
)