package com.shiyinplayer.player.decoder.ndk.ffmpeg

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.shiyinplayer.player.decoder.OpaqueAudioRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FFmpeg 软解渲染器（R-B1：libffmpeg 流式软解 + AudioSink）。
 *
 * 接入 [OpaqueAudioRenderer] **流式模式**（[accumulateWholeFile]=false）：源数据块经
 * [onDataChunk] 逐块喂入原生侧（[FfmpegJni.nativeFeed]），原生 worker 线程跑完整 FFmpeg 管线
 * （demux + decode + swr 转 s16/2/44100），[renderPcm] 用 [FfmpegJni.nativeRender] 拉 PCM 经注入的
 * [AudioSink] 输出（带均衡器/响度归一化效果链贯通，同 XmpRenderer）。
 *
 * 覆盖 ExoPlayer 系统解码器缺失的 exotic 格式：wv/tta/mpc/spx/aa3/at3/oma/wma/tak/ofr
 * （原 [com.shiyinplayer.player.decoder.AudioFormatRegistry.DEFERRED_FORMATS] 无解码器而降级的 9 格式；
 * R-B1 借 FFmpeg 软解激活）。
 *
 * L3（2026-08-22，AIMP 对照）：**ape 也由本渲染器软解**（ffmpeg 内置 Monkey's Audio 解码器），
 * 取代旧 ApeExtractor→MediaCodec 路径——流式喂块、渐进解码、可 seek，远端大 APE 不再需整首缓冲。
 *
 * seek 为**重建式**（见 [FfmpegJni]）：onPositionReset → [FfmpegJni.nativeRelease] + recreate，
 * 源从 seek 位置重新逐块喂入。
 */
@UnstableApi
class FfmpegRenderer(
    // 默认 null 仅供 JVM 单元测试无参构造；运行时始终由 PlayerFactory 传入真实参数
    @Suppress("unused") private val context: Context? = null,
    audioSink: AudioSink? = null
) : OpaqueAudioRenderer(audioSink) {

    companion object {
        private const val TAG = "FfmpegRenderer"
        private const val SAMPLE_RATE = 44100
        private const val PCM_CHANNELS = 2
        private const val PCM_BYTES_PER_SAMPLE = 2
        private const val RENDER_BUFFER_SIZE = 8192
        /** 单次 drain 对应的时钟推进（µs）：8192B @ 44100/2/16bit ≈ 46.4ms。 */
        private const val RENDER_STEP_US = 46440L
        /** R-B2 播放时钟上限：presentation 写头领先真实播放最多 500ms，杜绝 position 虚高竞态。 */
        private const val SINK_AHEAD_US = 500_000L

        val SUPPORTED_MIMES: Set<String> = setOf(
            "audio/x-ape",
            "audio/x-wavpack", "audio/x-tta", "audio/x-musepack",
            "audio/speex", "audio/x-atrac3", "audio/x-oma",
            "audio/x-ms-wma", "audio/x-tak", "audio/x-ofr",
            // 2026-08 新增：DSD(DSF/DFF)/CAF/Shorten/AC-4 走 FFmpeg 软解
            "audio/x-dsf", "audio/x-dff", "audio/x-caf",
            "audio/x-shorten", "audio/ac4"
            // R-B2（2026-08-22）：flac 已移交官方 FfmpegAudioRenderer（Track A）。自定义渲染器的
            // 时钟/缓冲上报与 LoadControl 集成缺陷致"Target buffer size reached ... 500ms buffered"
            // 死循环（播放几秒后归零重播）；官方渲染器用 AudioSink(StreamPositionManager) 驱动时钟，
            // 且 FFmpeg 软解本身接受 duplicate STREAMINFO，容错不丢失。此处不再认领 flac。
        )
    }

    @Volatile private var ctxHandle: Long = 0L
    @Volatile private var libLoaded: Boolean = false
    @Volatile private var eosCalled: Boolean = false
    @Volatile private var pcmBuffer: ByteBuffer? = null
    private var feedCounter = 0
    private var renderCounter = 0
    private var lastRenderLogBytes = -1

    /** R-B2：真实时间时钟基准（renderer 时间位置 + 该位置对应的 elapsedRealtime）。 */
    @Volatile private var clockBaseUs: Long = 0L
    @Volatile private var clockBaseElapsedUs: Long = 0L

    override val supportedMimeTypes: Set<String> = SUPPORTED_MIMES

    /** FFmpeg 处理的是大容器（无损音乐），必须流式喂入而非整文件累积。 */
    override val accumulateWholeFile: Boolean = false

    override fun getName(): String = "FfmpegRenderer"

    override fun onDataChunk(chunk: ByteArray): Boolean {
        if (!ensureLoadedAndCreated()) return false
        try {
            feedCounter++
            val ret = FfmpegJni.nativeFeed(ctxHandle, chunk)
            if (feedCounter <= 3 || feedCounter % 200 == 0)
                Log.i(TAG, "feed #$feedCounter chunk=${chunk.size}B ret=$ret")
            if (ret == 1) {
                // R-B2 输入缓冲已满：背压，返回 false 让 base 保留该块待重投（pendingChunk）
                return false
            }
            if (ret != 0) Log.w(TAG, "nativeFeed 返回 $ret")
        } catch (e: UnsatisfiedLinkError) {
            throw createRendererException(
                e, null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            )
        }
        return true
    }

    override fun onStreamEnd() {
        super.onStreamEnd()
        if (ctxHandle != 0L && !eosCalled) {
            runCatching { FfmpegJni.nativeFeedEos(ctxHandle) }
            eosCalled = true
        }
    }

    override fun renderPcm(positionUs: Long): Boolean {
        if (ctxHandle == 0L) return false

        // R-B2 真实时间驱动走位：presentation 写头（clockPositionUs）领先真实播放不超过 SINK_AHEAD_US，
        // 否则让出渲染等真实时间追上（worker 亦因 pcm 高水位阻塞，不空转、不整首瞬时解码）。
        val nowUs = SystemClock.elapsedRealtime() * 1000L
        val wallPosUs = clockBaseUs + (nowUs - clockBaseElapsedUs)
        if (clockPositionUs + RENDER_STEP_US > wallPosUs + SINK_AHEAD_US) {
            return true
        }

        // handleBuffer 断言要求 LITTLE_ENDIAN；ByteBuffer.allocateDirect 默认大端，必须先转小端，
        // 否则首帧 renderPcm 确定性抛 IllegalArgumentException（DefaultAudioSink.handleBuffer）。
        // 缓冲对象全局稳定（不在 releaseDecoder 置 null），seek 重建后复用同一对象，避免复用断言失败。
        val buf = pcmBuffer
            ?: ByteBuffer.allocateDirect(RENDER_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN).also { pcmBuffer = it }
        buf.clear()

        val bytes = FfmpegJni.nativeRender(ctxHandle, buf, RENDER_BUFFER_SIZE)
        if (++renderCounter <= 10 || bytes != lastRenderLogBytes)
            Log.i(TAG, "render #$renderCounter nativeRender=$bytes (buf.cap=${buf.capacity()})")
        if (bytes > 0) lastRenderLogBytes = bytes
        return when {
            bytes > 0 -> {
                buf.limit(bytes)
                buf.position(0)
                // presentationTimeUs 必须与 AudioSink 的"链式字节期望"保持一致、跨调用单调递增，
                // 否则 DefaultAudioSink 抛 UnexpectedDiscontinuityException。不能用 render() 的 live
                // positionUs（落后于已消费），而用随写入字节单调推进的 clockPositionUs（onPositionReset
                // 已置为渲染器时间并 flush 音频端）。
                outputPcm(buf, clockPositionUs)
                advanceClock(bytes, SAMPLE_RATE, PCM_CHANNELS)
                true
            }
            bytes < 0 -> {
                // 暂无输出（输入不足/尚未解码出首帧），return true 等待下轮 feed 更多数据；
                // yield 防止 worker 尚无输出时的 -1 空转烧 CPU
                Thread.yield()
                true
            }
            else -> {
                Log.i(TAG, "FFmpeg 解码结束（EOF）")
                false
            }
        }
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        // R-B2：以当前 renderer 位置为真实时间时钟基准（super 已把 clockPositionUs 置为 positionUs）
        clockBaseUs = positionUs
        clockBaseElapsedUs = SystemClock.elapsedRealtime() * 1000L
    }

    override fun resetDecoder(positionUs: Long) {
        // 流式输入不支持反向 seek：重建解码器，源会从 seek 位置重新喂入
        releaseDecoder()
        recreate()
    }

    override fun releaseDecoder() {
        if (ctxHandle != 0L) {
            runCatching { FfmpegJni.nativeRelease(ctxHandle) }
            ctxHandle = 0L
        }
        eosCalled = false
        // pcmBuffer 全局复用，不置 null：seek 重建后 handleBuffer 需传回同一对象
        Log.i(TAG, "FfmpegRenderer 释放完成")
    }

    /** 车道：加载 .so 并创建解码上下文。 */
    private fun ensureLoadedAndCreated(): Boolean {
        if (ctxHandle != 0L) return true
        return recreate()
    }

    private fun recreate(): Boolean {
        if (!libLoaded) {
            try {
                System.loadLibrary("ffmpeg_jni")
                libLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                throw PlaybackException(
                    "无法加载 libffmpeg_jni: ${e.message}",
                    e, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                )
            }
        }
        try {
            ctxHandle = FfmpegJni.nativeCreate()
            if (ctxHandle == 0L) {
                throw PlaybackException(
                    "FFmpeg 上下文创建失败",
                    null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                )
            }
            eosCalled = false
            Log.i(TAG, "FFmpeg 解码器已创建")
            return true
        } catch (e: PlaybackException) {
            throw e
        } catch (e: UnsatisfiedLinkError) {
            throw PlaybackException(
                "native 方法未绑定: ${e.message}",
                e, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            )
        }
    }
}