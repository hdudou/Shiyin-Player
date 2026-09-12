package com.shiyinplayer.player

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import com.shiyinplayer.player.decoder.AudioFormatRegistry
import com.shiyinplayer.player.decoder.RawFileExtractor

/**
 * 解码器感知的 MediaSource 工厂（解码器接入方案第 1 层）。
 *
 * 按 [MediaItem] 的 mimeType（MediaItemBuilder 已写入 song.mimeType）路由：
 * - NDK/软件解码路径的格式（APE/MIDI/模块音乐等，[AudioFormatRegistry.DecodePath] 非 SYSTEM
 *   且 [AudioFormatRegistry.isDeliverable]）→ [ProgressiveMediaSource] + [RawFileExtractor]，
 *   由下游渲染器完成解码
 * - 其余（原生/系统直通）→ 委托 [DefaultMediaSourceFactory]，行为与改造前完全一致（零回归）
 *
 * 注（media3 1.4.1）：[ExtractorsFactory] 的抽象方法为无参 [ExtractorsFactory.createExtractors]，
 * mimeType 需在 [createMediaSource] 内按 MediaItem 闭包捕获；[ProgressiveMediaSource.Factory] 通过
 * 构造函数注入 extractorsFactory（无 setExtractorsFactory）。
 *
 * 详见 specs/audio_decoder_opensource/design.md §2.1.3.8。
 */
@UnstableApi
class DecoderAwareMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    private val defaultFactory: DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(dataSourceFactory)
) : MediaSource.Factory {

    /** L3.1（2026-08-22 R-B2 修复）：flac 移交官方 FfmpegAudioRenderer（Track A）。
     * 原 Track B 自定义 FfmpegRenderer 的时钟/缓冲与 media3 LoadControl 集成缺陷导致
     * "Target buffer size reached with less than 500ms buffered" 死循环（播放几秒后归零重播）。
     * 官方 FfmpegAudioRenderer 用 AudioSink 驱动时钟（StreamPositionManager），天然规避该缺陷；
     * 且 FFmpeg 软解本身接受 duplicate STREAMINFO，容错不丢失。 */
    private val TRACK_A_HANDOVER: Set<String> = setOf("audio/flac", "audio/x-flac")

    private val exoticMimeTypes: Set<String> by lazy {
        AudioFormatRegistry.allFormats
            .filter { AudioFormatRegistry.isDeliverable(it.phase, it.extension) && it.decodePath != AudioFormatRegistry.DecodePath.SYSTEM }
            .map { it.mimeType }
            .filterNot { it in TRACK_A_HANDOVER }
            .toSet()
    }

    /** 全部 exotic 经整文件透传提取器 + 对应 NDK/软解渲染器。 */

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val mime = mediaItem.localConfiguration?.mimeType
        return if (mime != null && mime in exoticMimeTypes) {
            // L3（2026-08-22，AIMP 对照）：APE 也走 RawFileExtractor 整文件透传 + FfmpegRenderer 软解
            // （流式喂块、渐进解码、可 seek），不再用 ApeExtractor→MediaCodec（其 accumulateWholeFile
            // 模式需整首下载才播，远端大 APE 启动极慢）。ffmpeg(ape) 解码器由 FfmpegRenderer 认领。
            val extractorsFactory = ExtractorsFactory { arrayOf(RawFileExtractor(mime)) }
            ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem)
        } else {
            defaultFactory.createMediaSource(mediaItem)
        }
    }

    override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }
}
