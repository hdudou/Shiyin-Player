package com.shiyinplayer.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import com.shiyinplayer.data.media.SmartDataSourceFactory
import com.shiyinplayer.player.decoder.AudioFormatRegistry
import com.shiyinplayer.player.decoder.ndk.ffmpeg.FfmpegRenderer
import com.shiyinplayer.player.equalizer.LoudnessNormalizerAudioProcessor
import com.shiyinplayer.player.equalizer.ReplayGainAudioProcessor
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ExoPlayer 工厂（播放器热重建专用，2026-08-21）：
 * 每次 [create] 都重新读取当前 DataStore 设置构建全新播放器。
 * PlayerModule.provideExoPlayer 与 PlayerManager.rebuildPlayer（音频链开关 #9-12 即时化）
 * 共用此工厂——切换 low_latency / float32_processing / volume_normalize / silence_remover
 * 时热重建播放器（保留队列/进度/播放态），无需重启 App。
 */
@Suppress("OPT_IN_USAGE")
@Singleton
class PlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val equalizer: EqualizerManager,
    private val dataSourceFactory: SmartDataSourceFactory,
    private val settings: SettingsRepository,
    private val audioRoutingController: AudioRoutingController
) {

    @Suppress("OPT_IN_USAGE")
    fun create(): ExoPlayer {
        // 缓存设置（P2 接线：缓冲/低延迟/Float32/音量归一化 + silence_remover / replaygain_mode
        // 构建期音频链开关；eq_enabled 已改运行时即时——EQ 处理器始终注入，由 EqualizerManager
        // 观察 DataStore 流即时旁路/恢复，无需重建播放器）
        // P0-4：改用 SettingsRepository 内存快照同步读取，消除主线程 runBlocking 阻塞（ANR）。
        val s = ExoPlayerSettings(
            bufferMs = settings.bufferMsSync(),
            lowLatency = settings.lowLatencySync(),
            float32 = settings.float32ProcessingSync(),
            normalize = settings.volumeNormalizeSync(),
            silenceRemover = settings.silenceRemoverSync(),
            replaygainMode = settings.replaygainModeSync()
        )
        val minBuffer = if (s.lowLatency) 200 else s.bufferMs.coerceIn(300, 20_000)
        val maxBuffer = if (s.lowLatency) 2_000 else (minBuffer * 2).coerceAtLeast(5_000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, minBuffer / 2, minBuffer)
            .build()
        return ExoPlayer.Builder(context)
            .setRenderersFactory(
                EqualizerRenderersFactory(
                    context, equalizer, s.float32, s.normalize, audioRoutingController,
                    silenceRemover = s.silenceRemover,
                    replaygainMode = s.replaygainMode,
                    ffmpegSoftDecode = settings.ffmpegSoftDecodeSync()
                )
            )
            // 解码器感知路由：exotic 格式 → RawFileExtractor 透传，原生格式 → 默认工厂（零回归）
            .setMediaSourceFactory(DecoderAwareMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
    }
}

/**
 * 渲染器工厂：在默认音频渲染链中注入均衡器 AudioProcessor（及响度归一化），
 * 并按 CURRENT_PHASE 追加 NDK 解码渲染器（注入同一带均衡器的 [AudioSink]，保证效果链贯通）。
 * Media3 1.4.1 的 [DefaultRenderersFactory] 未暴露 setAudioProcessors，
 * 因此重写 [buildAudioRenderers]，用注入了处理器的 [DefaultAudioSink] 替换默认音频接收端。
 */
@Suppress("OPT_IN_USAGE")
private class EqualizerRenderersFactory(
    context: Context,
    private val equalizer: EqualizerManager,
    private val float32: Boolean,
    private val normalize: Boolean,
    private val audioRoutingController: AudioRoutingController,
    private val silenceRemover: Boolean,
    private val replaygainMode: String,
    private val ffmpegSoftDecode: Boolean
) : DefaultRenderersFactory(context) {

    @Suppress("OPT_IN_USAGE")
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        // 音频链顺序（靠前的先处理）：静音消除 → 均衡器 → ReplayGain → 响度归一化
        // EQ 处理器始终注入（主开关 eq_enabled 由 EqualizerManager 观察 DataStore 流即时旁路/恢复，
        // 无需重建播放器）；silence_remover / replaygain_mode 由热重建生效（见 PlayerFactory）。
        val processors = mutableListOf<AudioProcessor>().apply {
            if (silenceRemover) add(SilenceSkippingAudioProcessor())
            addAll(equalizer.getAudioProcessors())
            if (replaygainMode != "off") add(ReplayGainAudioProcessor(replaygainMode))
            if (normalize) add(LoudnessNormalizerAudioProcessor())
        }
        val rawSink: AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(float32)
            .setAudioProcessors(processors.toTypedArray())
            .build()
        audioRoutingController.attach(rawSink)
        val phase = AudioFormatRegistry.CURRENT_PHASE
        if (phase.ordinal >= AudioFormatRegistry.Phase.P2A.ordinal) {
            runCatching { FfmpegRenderer(context, rawSink) }.onSuccess { out.add(it) }
            if (ffmpegSoftDecode) {
                runCatching { FfmpegAudioRenderer(null, null, rawSink) }.onSuccess { out.add(it) }
            }
        }
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            rawSink,
            eventHandler,
            eventListener,
            out
        )
    }
}

/** ExoPlayer 构建所需设置快照（一次性读取，避免多次 runBlocking 阻塞主线程）。 */
private data class ExoPlayerSettings(
    val bufferMs: Int,
    val lowLatency: Boolean,
    val float32: Boolean,
    val normalize: Boolean,
    val silenceRemover: Boolean,
    val replaygainMode: String
)


