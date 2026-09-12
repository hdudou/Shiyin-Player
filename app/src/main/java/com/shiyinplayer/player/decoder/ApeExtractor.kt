package com.shiyinplayer.player.decoder

import android.util.Log
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
 * Monkey's Audio（APE）容器解析器（2026-08-18 新增，P2A 播放修复）。
 *
 * 背景：ExoPlayer 无内置 APE demuxer，真机 MediaCodec 提供 QTI ape 解码器
 * （c2.qti.ape.sw/hw.decoder）。本 Extractor 解析 APE 容器，把每个压缩帧输出为
 * sample 交给 MediaCodecAudioRenderer（系统解码器）→ 均衡器等下游链路完整保留。
 *
 * APE v3.99 容器布局（实测画心.ape 验证：16bit/2ch/44100Hz，259 帧）：
 * - 0..3 "MAC "  4..5 version  6..7 padding
 * - 8..11 nDescriptorBytes(52)  12..15 nHeaderBytes(24)  16..19 nSeekTableBytes(1036)
 * - 20..23 nWavHeaderBytes(44)  24..27 nAudioDataBytes  28..31 nAudioCRC  32..35 nSeekCRC
 * - 36..59 其余 descriptor 字段（版本差异大，本解析器不依赖其中 wav 参数）
 * - 60..83  header 块（压缩配置；偏移 68=bits、70=channels、72..75=rate，实测 16/2/44100）
 * - 84..127 wavHeader 块（部分编码器含帧索引前段，本解析器不依赖）
 * - 128..    seektable（1036B / 4 = 259 项，每项 u32 = 该帧在音频数据区的相对偏移）
 * - 1164..   音频数据（audioDataOffset = 60+header+wavHeader+seektable；总长 nAudioDataBytes）
 */
@UnstableApi
class ApeExtractor : Extractor {

    companion object {
        private const val TAG = "ApeExtractor"
        private const val MAX_FRAMES = 200_000
        private const val MAX_HEADER_REGION = 4 * 1024 * 1024 // 头部区上限（seektable 巨大时）
    }

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var headerParsed = false

    private var headerBytes = 0
    private var seekTableBytes = 0
    private var wavHeaderBytes = 0
    private var audioDataOffset = 0L
    private var audioDataBytes = 0L
    private var frameSizeHint = 0 // 每帧样本数（从 descriptor 取，用于时长估算）

    private var sampleRate = 44100
    private var channelCount = 2
    private var bitsPerSample = 16

    /** header 块（压缩配置，作为 codec-specific data 传给解码器）。 */
    private var csdHeader: ByteArray? = null

    private var frameOffsets: LongArray = LongArray(0)
    private var frameCount = 0

    private var currentFrame = 0
    private var streamEnded = false

    override fun sniff(input: ExtractorInput): Boolean {
        val magic = ByteArray(4)
        input.peekFully(magic, 0, 4, true)
        return magic[0] == 'M'.code.toByte() && magic[1] == 'A'.code.toByte() &&
            magic[2] == 'C'.code.toByte() && magic[3] == ' '.code.toByte()
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
        headerParsed = false
        currentFrame = 0
        streamEnded = false
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val output = extractorOutput ?: return Extractor.RESULT_END_OF_INPUT
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT

        // ---- 首次：解析容器头 ----
        if (!headerParsed) {
            if (!parseHeader(input)) return Extractor.RESULT_END_OF_INPUT
            headerParsed = true
            val builder = Format.Builder()
                .setSampleMimeType("audio/x-ape")
                .setSampleRate(sampleRate)
                .setChannelCount(channelCount)
                .setPcmEncoding(encodingForBits(bitsPerSample))
                .setMaxInputSize(16 * 1024 * 1024)
            // codec-specific data：header 块（APE 压缩配置），QTI ape 解码器初始化必需
            csdHeader?.let { builder.setInitializationData(listOf(it)) }
            track.format(builder.build())
            output.seekMap(
                ApeSeekMap(frameOffsets, audioDataOffset, frameCount, frameSizeHint, sampleRate)
            )
            // 定位到音频数据起点
            seekPosition.position = audioDataOffset
            currentFrame = 0
            return Extractor.RESULT_SEEK
        }

        // ---- 按 seektable 帧边界逐帧输出 ----
        if (currentFrame >= frameCount) {
            if (!streamEnded) {
                streamEnded = true
                track.sampleMetadata(0, C.BUFFER_FLAG_END_OF_STREAM, 0, 0, null)
            }
            return Extractor.RESULT_END_OF_INPUT
        }

        // seektable 语义（实测）：每项 u32 = 该帧在音频数据区的【累积结束偏移】，
        // 即帧 i 的区间 = [prevOffset, offsets[i])，首帧从 0 开始。
        val frameStart = audioDataOffset + if (currentFrame == 0) 0L else frameOffsets[currentFrame - 1]
        val frameEnd = audioDataOffset + frameOffsets[currentFrame]
        val frameSize = (frameEnd - frameStart).toInt()
        if (frameSize <= 0 || frameSize > 32 * 1024 * 1024) {
            Log.w(TAG, "帧大小异常 skip: idx=$currentFrame size=$frameSize start=$frameStart end=$frameEnd")
            currentFrame++
            return Extractor.RESULT_CONTINUE
        }

        // 定位到帧起点
        if (input.position != frameStart) {
            seekPosition.position = frameStart
            currentFrame++ // 定位后由下一次 read 读取该帧（避免本 read 重复）
            return Extractor.RESULT_SEEK
        }

        val buf = ByteArray(frameSize)
        val bytesRead = input.read(buf, 0, frameSize)
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            streamEnded = true
            track.sampleMetadata(0, C.BUFFER_FLAG_END_OF_STREAM, 0, 0, null)
            return Extractor.RESULT_END_OF_INPUT
        }
        if (bytesRead > 0) {
            track.sampleData(ParsableByteArray(buf, bytesRead), bytesRead)
            val tsUs = currentFrame * frameSizeHint.toLong() * 1_000_000L / sampleRate
            track.sampleMetadata(tsUs, C.BUFFER_FLAG_KEY_FRAME, bytesRead, 0, null)
        }
        currentFrame++
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        // 重新从头解析（简化；seek 后 read 会先定位音频数据起点）
        headerParsed = false
        currentFrame = 0
        streamEnded = false
    }

    override fun release() {
        extractorOutput = null
        trackOutput = null
    }

    // ===== 容器头解析 =====

    /** 解析容器头。返回 false 表示无效。 */
    private fun parseHeader(input: ExtractorInput): Boolean {
        return runCatching {
            val head = ByteArray(128)
            input.peekFully(head, 0, 128, true)

            // 0..3: "MAC "
            if (head[0] != 'M'.code.toByte() || head[1] != 'A'.code.toByte() ||
                head[2] != 'C'.code.toByte() || head[3] != ' '.code.toByte()
            ) return false

            headerBytes = le32(head, 12)
            seekTableBytes = le32(head, 16)
            wavHeaderBytes = le32(head, 20)
            audioDataBytes = le32(head, 24).toLong() and 0xFFFFFFFFL

            // 布局：header@60 → wavHeader@(60+headerBytes) → seektable → audio
            // （实测 wavHeader 仅作占位，seektable 紧随 header 块；audio 在最后）
            audioDataOffset = 60L + headerBytes + wavHeaderBytes + seekTableBytes
            if (audioDataOffset > MAX_HEADER_REGION || seekTableBytes < 0 ||
                seekTableBytes / 4 > MAX_FRAMES || headerBytes < 0 || wavHeaderBytes < 0
            ) return false

            // wav 参数：header 块偏移 68=bits、70=channels、72..75=rate
            parseWavParams(head)

            // 帧大小提示：descriptor 偏移 56..59 = 每帧样本数（实测 73728）
            runCatching {
                val fs = le32(head, 56)
                if (fs in 1024..1_000_000) frameSizeHint = fs
            }

            // ---- 读取完整头部区（含 header 块 + seektable），从 peek 位置继续 ----
            val totalRegion = audioDataOffset.toInt()
            val region = ByteArray(totalRegion)
            // peekFully 从当前 peek position 继续（已 peek 128）
            input.peekFully(region, 0, totalRegion, true)

            // csd：header 块（offset 60 起，nHeaderBytes 长）——APE 压缩配置，解码器初始化必需
            if (headerBytes > 0 && headerBytes <= 64 * 1024) {
                csdHeader = region.copyOfRange(60, 60 + headerBytes)
            }

            // seektable 位于 header+wavHeader 之后
            val seekAbs = 60 + headerBytes + wavHeaderBytes
            val count = seekTableBytes / 4
            val offs = LongArray(count)
            for (i in 0 until count) {
                offs[i] = le32(region, seekAbs + i * 4).toLong()
            }
            frameOffsets = offs
            frameCount = count
            true
        }.getOrDefault(false)
    }

    private fun parseWavParams(head: ByteArray) {
        runCatching {
            val bits = le16(head, 68)
            if (bits in 8..32) bitsPerSample = bits
            val ch = le16(head, 70)
            if (ch in 1..8) channelCount = ch
            val rate = le32(head, 72)
            if (rate in 8000..192000) sampleRate = rate
        }
    }

    private fun encodingForBits(bits: Int): Int = when (bits) {
        8 -> C.ENCODING_PCM_8BIT
        24 -> C.ENCODING_PCM_24BIT
        32 -> C.ENCODING_PCM_32BIT
        else -> C.ENCODING_PCM_16BIT
    }

    private fun le16(buf: ByteArray, off: Int): Int {
        val b0 = buf[off].toInt() and 0xFF
        val b1 = buf[off + 1].toInt() and 0xFF
        return b0 or (b1 shl 8)
    }

    private fun le32(buf: ByteArray, off: Int): Int {
        val b0 = buf[off].toInt() and 0xFF
        val b1 = buf[off + 1].toInt() and 0xFF
        val b2 = buf[off + 2].toInt() and 0xFF
        val b3 = buf[off + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    /** 基于帧偏移表的 SeekMap。 */
    private class ApeSeekMap(
        private val frameOffsets: LongArray,
        private val audioDataOffset: Long,
        private val frameCount: Int,
        private val frameSizeHint: Int,
        private val sampleRate: Int
    ) : SeekMap {
        private val samplesPerFrame = if (frameSizeHint > 0) frameSizeHint.toLong() else 73728L

        override fun isSeekable(): Boolean = frameCount > 0
        override fun getDurationUs(): Long =
            if (frameCount > 0) frameCount * samplesPerFrame * 1_000_000L / sampleRate
            else C.TIME_UNSET

        override fun getSeekPoints(positionUs: Long): SeekMap.SeekPoints {
            if (frameCount <= 0) return SeekMap.SeekPoints(SeekPoint(0, audioDataOffset))
            val idx = (positionUs * sampleRate / samplesPerFrame / 1_000_000L).toInt()
                .coerceIn(0, frameCount - 1)
            // 帧 i 起点 = 前一项结束偏移（累积偏移语义），首帧从 0 开始
            val startOffset = if (idx == 0) 0L else frameOffsets[idx - 1]
            val pos = audioDataOffset + startOffset
            val timeUs = idx * samplesPerFrame * 1_000_000L / sampleRate
            return SeekMap.SeekPoints(SeekPoint(timeUs, pos))
        }
    }
}
