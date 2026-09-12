package com.shiyinplayer.player.decoder

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 不透明文件渲染器抽象基类（解码器接入方案第 3 层）。
 *
 * 消费 [RawFileExtractor] 透传的整文件样本流（64KB 分块），将数据交给子类解码器：
 * - 整文件模式（[accumulateWholeFile]=true，Midi/Xmp）：累积全部块，EOS 后一次性
 *   [onFileReady] 交给解码器（libfluidsynth / libxmp 需要完整文件数据）
 * - 流式模式（[accumulateWholeFile]=false，Ffmpeg）：每块即时 [onDataChunk] 转发
 *   （ffmpeg 子进程 stdin 边下边喂）
 *
 * 解码输出统一经 [audioSink]（由 PlayerModule 注入的带均衡器 DefaultAudioSink）播放，
 * EOS 后调用 [AudioSink.playToEndOfStream]。位置重置走 [resetDecoder]（seek 重启进程/seek 解码器）。
 *
 * 详见 specs/audio_decoder_opensource/design.md §2.1.3.4。
 */
@UnstableApi
abstract class OpaqueAudioRenderer(
    protected val audioSink: AudioSink?
) : BaseRenderer(C.TRACK_TYPE_AUDIO) {

    companion object {
        private const val TAG = "OpaqueAudioRenderer"
        /** 整文件模式最大累积上限（字节）。超出抛错，防止异常大文件 OOM。 */
        private const val MAX_ACCUMULATE_BYTES = 32 * 1024 * 1024

        /**
         * media3 首项渲染器时间偏移（MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US）。
         * media3 为让首项时间戳恒为正，将渲染器时间整体偏移 1e12 µs；
         * [onPositionReset] 收到的 positionUs 是**渲染器时间**（含偏移），
         * 转媒体时间（seek 用）必须减去该偏移。
         */
        protected const val INITIAL_RENDERER_POSITION_OFFSET_US = 1_000_000_000_000L
    }

    /** 渲染器时间 → 媒体时间（下限 0，防止后续 period 偏移变化时误减）。 */
    protected fun toMediaPositionUs(positionUs: Long): Long =
        (positionUs - INITIAL_RENDERER_POSITION_OFFSET_US).coerceAtLeast(0L)

    /** 子类声明支持的 MIME 类型集合。 */
    abstract val supportedMimeTypes: Set<String>

    /** 整文件模式（true）或流式模式（false）。 */
    abstract val accumulateWholeFile: Boolean

    // ---- ExoPlayer 状态 ----
    @Volatile protected var decoderStarted: Boolean = false
    @Volatile protected var streamEnded: Boolean = false
    private var sourceEos: Boolean = false

    /**
     * R-B2 背压保留块：流式模式下 [onDataChunk] 返回 false（解码忙）时保留该块待下次重投，
     * 避免 readData 已消费该块却丢弃导致数据丢失。仅一个块在途，天然限速源读取。
     */
    private var pendingChunk: ByteArray? = null

    /** 渲染器时钟位置（微秒）。因透传样本 timeUs=0，由子类按实际解码进度推进。 */
    @Volatile protected var clockPositionUs: Long = 0L

    /**
     * 推进时钟位置（子类在 renderPcm 产出 PCM 后调用）。
     * @param bytes 本次解码产出的 PCM 字节数（已乘以声道数）
     * @param sampleRate 输出采样率
     * @param channels 输出声道数
     */
    protected fun advanceClock(bytes: Int, sampleRate: Int, channels: Int) {
        val bytesPerSecond = sampleRate * channels * 2L
        if (bytesPerSecond <= 0) return
        val deltaUs = bytes * 1_000_000L / bytesPerSecond
        if (deltaUs > 0) clockPositionUs += deltaUs
    }

    // ---- 整文件累积 ----
    private var accumulator: ByteArrayOutputStream? = null
    private var accumulatedBytes: Int = 0

    private val formatHolder: FormatHolder = getFormatHolder()
    private val inputBuffer: DecoderInputBuffer = DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL
    )

    /**
     * 渲染器时钟。透传样本 timeUs=0，ExoPlayer 无法从样本推断位置，
     * 故由本时钟按实际解码进度（[advanceClock] / 子类直设 [clockPositionUs]）上报。
     * 变速（speed != 1）暂不支持：忽略并返回 DEFAULT（ExoPlayer 会保持 1x）。
     */
    private val mediaClock: MediaClock = object : MediaClock {
        override fun getPositionUs(): Long = clockPositionUs
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
            if (playbackParameters.speed != 1f) {
                Log.i(TAG, "变速不支持，忽略 speed=${playbackParameters.speed}")
            }
        }
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    }

    override fun getMediaClock(): MediaClock? = mediaClock

    // ================= 子类钩子 =================

    /** 流式模式：源数据块到达（每块 ≤64KB）。返回 false 表示背压（暂停读取源数据，让出给解码输出）。 */
    protected open fun onDataChunk(chunk: ByteArray): Boolean = true

    /** 整文件模式：全部数据累积完毕，一次性交给解码器（仅调用一次）。 */
    protected open fun onFileReady(bytes: ByteArray) {}

    /** 源流 EOS（两种模式均回调；整文件模式在 onFileReady 之后）。 */
    protected open fun onStreamEnd() {}

    /**
     * 解码输出循环（每次 render 调用一次）：产出 PCM 到 [outputPcm]，
     * 返回 false 表示解码器输出结束（触发 EOS 收尾）。
     */
    protected abstract fun renderPcm(positionUs: Long): Boolean

    /** 位置重置：seek 解码器 / 重启子进程。 */
    protected abstract fun resetDecoder(positionUs: Long)

    /** 释放解码器资源（onReset/onRelease/onDisabled/seek 重启时调用）。 */
    protected abstract fun releaseDecoder()

    /** PCM 输出参数（音频端配置）。默认 44100/2/PCM16，子类可覆写（如 ffmpeg 源采样率）。 */
    protected open fun configurePcmFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(44100)
        .setChannelCount(2)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .build()

    // ================= 基类实现 =================

    override fun getName(): String = "OpaqueAudioRenderer"

    override fun supportsFormat(format: Format): Int {
        val mime = format.sampleMimeType ?: return C.FORMAT_UNSUPPORTED_TYPE
        return if (mime in supportedMimeTypes) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE
    }

    override fun supportsMixedMimeTypeAdaptation(): Int = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED

    /** 输出 PCM 到注入的 AudioSink（带均衡器）。 */
    protected fun outputPcm(pcm: ByteArray, presentationTimeUs: Long) {
        val size = pcm.size
        if (size == 0) return
        audioSink?.handleBuffer(ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN), presentationTimeUs, size)
    }

    /** 输出 PCM（从 DirectByteBuffer，零拷贝保留）。 */
    protected fun outputPcm(pcm: ByteBuffer, presentationTimeUs: Long) {
        if (!pcm.hasRemaining()) return
        audioSink?.handleBuffer(pcm, presentationTimeUs, pcm.remaining())
    }

    /** 源格式到达：配置音频端（PCM 输出参数）。 */
    protected open fun onInputFormatChangedInternal(format: Format) {}

    /** 处理源格式变更（BaseRenderer 1.4.1 无 onInputFormatChanged 钩子，由 RESULT_FORMAT_READ 驱动）。 */
    private fun handleInputFormatChanged() {
        val format = formatHolder.format ?: return
        onInputFormatChangedInternal(format)
        try {
            val pcmFmt = configurePcmFormat()
            audioSink?.configure(pcmFmt, 0, null)
        } catch (e: Exception) {
            throw createRendererException(
                e, format, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            )
        }
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (streamEnded) return

        // 1) 消费源数据（累积或转发）
        if (!sourceEos) readData(positionUs)
        if (streamEnded) return
        if (!decoderStarted) return // 仍在累积或等待首块

        // 2) 驱动解码输出
        val continuePlaying = renderPcm(positionUs)
        if (!continuePlaying) {
            streamEnded = true
            onStreamEnd()
            try {
                audioSink?.playToEndOfStream()
            } catch (e: Exception) {
                Log.w(TAG, "playToEndOfStream 失败: ${e.message}")
            }
        }
    }

    private fun readData(positionUs: Long) {
        val buffer = inputBuffer
        while (true) {
            // R-B2 背压重放：上次因解码忙未消费的块先重投；仍忙则等下次 render，不丢数据
            val pending = pendingChunk
            if (pending != null) {
                if (onDataChunk(pending)) pendingChunk = null else return
                continue
            }
            buffer.clear()
            val result = readSource(formatHolder, buffer, 0)
            when (result) {
                C.RESULT_FORMAT_READ -> {
                    handleInputFormatChanged()
                }
                C.RESULT_BUFFER_READ -> {
                    if (buffer.isEndOfStream) {
                        handleSourceEos()
                        return
                    }
                    buffer.flip()
                    val data = buffer.data ?: continue
                    val size = data.remaining()
                    if (size <= 0) continue
                    val chunk = ByteArray(size)
                    data.get(chunk)
                    if (accumulateWholeFile) {
                        val acc = accumulator ?: ByteArrayOutputStream().also { accumulator = it }
                        acc.write(chunk)
                        accumulatedBytes += size
                        if (accumulatedBytes > MAX_ACCUMULATE_BYTES) {
                            sourceEos = true
                            throw createRendererException(
                                IllegalStateException("整文件模式超上限 $MAX_ACCUMULATE_BYTES bytes"),
                                null,
                                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                            )
                        }
                    } else {
                        decoderStarted = true
                        if (!onDataChunk(chunk)) {
                            pendingChunk = chunk // R-B2 背压：保留该块待下次重投，避免丢数据
                            return
                        }
                    }
                }
                C.RESULT_END_OF_INPUT -> {
                    handleSourceEos()
                    return
                }
                C.RESULT_NOTHING_READ -> return
                else -> return
            }
        }
    }

    private fun handleSourceEos() {
        sourceEos = true
        if (accumulateWholeFile) {
            val bytes = accumulator?.toByteArray() ?: ByteArray(0)
            accumulator = null
            accumulatedBytes = 0
            decoderStarted = true
            onFileReady(bytes)
        }
        onStreamEnd()
    }

    override fun isReady(): Boolean = decoderStarted && !streamEnded

    override fun isEnded(): Boolean = streamEnded

    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        super.onEnabled(joining, mayRenderStartOfStream)
        streamEnded = false
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        Log.i(TAG, "onPositionReset(positionUs=$positionUs, joining=$joining)")
        streamEnded = false
        sourceEos = false
        decoderStarted = false
        accumulator = null
        accumulatedBytes = 0
        pendingChunk = null
        clockPositionUs = positionUs
        audioSink?.flush() // seek 后清空音频端缓冲与时间戳基线
        resetDecoder(positionUs)
    }

    override fun onRelease() {
        releaseDecoder()
        streamEnded = true
    }

    override fun onReset() {
        releaseDecoder()
        streamEnded = true
    }

    override fun onDisabled() {
        super.onDisabled()
        releaseDecoder()
        streamEnded = true
    }
}
