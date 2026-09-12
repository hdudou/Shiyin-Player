package com.shiyinplayer.player.decoder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput

/**
 * 通用「整文件透传」提取器（解码器接入方案第 2 层）。
 *
 * 对 NDK/软件解码路径的格式（APE/MIDI/模块音乐等），Media3 无内置容器解析器，
 * 且解码器本身（libfluidsynth / ffmpeg 子进程 / libxmp）需要接收完整文件数据。
 * 本提取器不做容器解析：将整个文件分块（[CHUNK_SIZE]）作为 opaque samples 透传给
 * 下游 [OpaqueAudioRenderer]，由渲染器侧决定「整文件累积」或「流式转发」。
 *
 * - MIME 类型由构造注入（工厂层按 MediaItem.mimeType 路由，见 DecoderAwareMediaSourceFactory）
 * - [sniff] 用 [MagicNumberValidator] 校验魔数（对 ByExtension 的格式退化为返回 true）
 * - seek 语义：透传流不可精确定位，seek 后从头重新透传（由渲染器 onPositionReset 处理）
 *
 * 详见 specs/audio_decoder_opensource/design.md §2.1.3.7。
 */
@UnstableApi
class RawFileExtractor(
    private val mimeType: String,
    private val extension: String = mimeToExtension(mimeType)
) : Extractor {

    companion object {
        private const val TAG = "RawFileExtractor"
        /** 透传分块大小（字节）。64KB 平衡缓冲与内存。 */
        private const val CHUNK_SIZE = 64 * 1024

        private fun mimeToExtension(mimeType: String): String = when (mimeType) {
            "audio/x-ape" -> "ape"
            "audio/x-wavpack" -> "wv"
            "audio/x-tta" -> "tta"
            "audio/x-musepack" -> "mpc"
            "audio/speex" -> "spx"
            "audio/x-atrac3" -> "aa3"
            "audio/x-oma" -> "oma"
            "audio/x-ms-wma" -> "wma"
            "audio/x-tak" -> "tak"
            "audio/x-ofr" -> "ofr"
            else -> "bin"
        }
    }

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var formatOutput: Boolean = false
    private var streamEnded: Boolean = false

    override fun sniff(input: ExtractorInput): Boolean {
        // 透传提取器：由工厂按 mime 路由，此处仅做魔数校验（ByExtension 格式直接放行）。
        val magic = AudioFormatRegistry.magicOf(extension)
        if (magic == null) return true
        if (magic is AudioFormatRegistry.MagicSpec.ByExtension) return true
        val prefix = ByteArray(64)
        val bytesRead = input.peek(prefix, 0, prefix.size)
        if (bytesRead < 4) return false
        return MagicNumberValidator.validate(prefix.copyOf(bytesRead), extension)
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        // 透传流无容器元数据，仅声明 MIME；采样率/声道由渲染器按解码器实际输出上报。
        trackOutput?.format(
            Format.Builder()
                .setSampleMimeType(mimeType)
                .setMaxInputSize(CHUNK_SIZE)
                .build()
        )
        formatOutput = true
        output.endTracks()
        output.seekMap(object : SeekMap {
            override fun isSeekable(): Boolean = false
            override fun getDurationUs(): Long = C.TIME_UNSET
            override fun getSeekPoints(positionUs: Long): SeekMap.SeekPoints =
                SeekMap.SeekPoints(SeekPoint(0, 0))
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        if (streamEnded) return Extractor.RESULT_END_OF_INPUT

        val buffer = ByteArray(CHUNK_SIZE)
        val bytesRead = input.read(buffer, 0, CHUNK_SIZE)
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            streamEnded = true
            track.sampleMetadata(0, C.BUFFER_FLAG_END_OF_STREAM, 0, 0, null)
            return Extractor.RESULT_END_OF_INPUT
        }
        if (bytesRead == 0) return Extractor.RESULT_CONTINUE

        track.sampleData(ParsableByteArray(buffer), bytesRead)
        track.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, bytesRead, 0, null)
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        streamEnded = false
    }

    override fun release() {
        extractorOutput = null
        trackOutput = null
    }
}
