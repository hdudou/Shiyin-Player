package com.shiyinplayer.player.decoder.ndk.ffmpeg

import java.nio.ByteBuffer

/**
 * FFmpeg JNI 绑定（R-B1：libffmpeg 软解底座）。
 *
 * 解码采用**流式喂入 + 独立解码线程**模型（native 侧 ANativeWindow 无关）：
 * - [nativeFeed] 把源数据块追加到输入缓冲（由 OpaqueAudioRenderer 流式模式 onDataChunk 逐块回调）；
 * - 原生侧 worker 线程从输入缓冲拉数据，AVIO 自定义 read 回调在「数据不足」时阻塞等待条件变量，
 *   直到有新块（[nativeFeed] signal）或输入结束（[nativeFeedEos]）；
 * - [nativeRender] 从解码输出的 PCM 缓冲取数据填到 outBuffer：>0 本次产出字节，
 *   0=解码器已到 EOF，<0=暂无输出（输入不足或暂尚未解码出帧，调用方应继续喂数据后重试）。
 *
 * seek 采用**重建式**（与 libxmp 的 seek 不同）：因输入为一次性流式（AVIO 已消费字节不保留），
 * 无法反向 seek，故 ExoPlayer onPositionReset 时由 renderer 调 [nativeRelease]+[nativeCreate] 重建，
 * 源会从 seek 位置重新逐块喂入。
 *
 * 同步约定（audio/decoder 单线程调用）：feed/feedEos/render 均由播放线程顺序调用，
 * 原生侧用互斥锁+条件变量协调 worker 与喂入。
 */
object FfmpegJni {

    /**
     * 创建解码上下文。
     * @return 原生句柄（long），失败返回 0。
     */
    external fun nativeCreate(): Long

    /**
     * 追加源数据块到输入缓冲。
     * @return 0 成功；1 = 输入缓冲已满（背压，调用方应保留该块稍后重投）；其他 < 0 失败。
     */
    external fun nativeFeed(ctx: Long, data: ByteArray): Int

    /**
     * 标记源输入结束（EOS）。之后 AVIO read 在缓冲耗尽后返回 EOF。
     */
    external fun nativeFeedEos(ctx: Long): Int

    /**
     * 从解码 PCM 缓冲取数据填到 outBuffer。
     * @return >0 产出字节数；0=解码结束（EOF）；<0=暂无输出（输入不足/尚未解码出首帧），调用方稍后重试。
     */
    external fun nativeRender(ctx: Long, outBuffer: ByteBuffer, size: Int): Int

    /** 当前播放位置（毫秒）。 */
    external fun nativeGetTimeMs(ctx: Long): Long

    /** 预计总时长（毫秒），未知返回 -1。 */
    external fun nativeGetDurationMs(ctx: Long): Long

    /** 释放解码上下文（重建式 seek 也调它 + [nativeCreate] 重新创建）。 */
    external fun nativeRelease(ctx: Long): Unit
}