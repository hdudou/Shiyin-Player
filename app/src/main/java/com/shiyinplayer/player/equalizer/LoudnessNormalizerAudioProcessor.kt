package com.shiyinplayer.player.equalizer

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 响度归一化（volume_normalize）：逐帧按 RMS 向目标响度归一，增益平滑（防抽吸）并限制峰值。
 * 处理 PCM_16BIT 与 PCM_FLOAT（float32）。注入 ExoPlayer 音频链（PlayerModule 构建时，随 volume_normalize 开关）。
 */
class LoudnessNormalizerAudioProcessor : AudioProcessor {

    companion object {
        private val EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        private const val TARGET_RMS = 0.25f // ≈ -12 dBFS
        private const val MAX_GAIN = 8f
        private const val SMOOTH = 0.5f // 增益平滑系数（慢速跟随，避免音量抽动）
    }

    private var gain = 1f
    private var inputEnded = false
    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputFormat: AudioFormat = AudioFormat.NOT_SET

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != android.media.AudioFormat.ENCODING_PCM_16BIT &&
            enc != android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        inputFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val remaining = inputBuffer.remaining()
        val out = replaceOutputBuffer(remaining)
        val isFloat = inputFormat.encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
        val sampleCount = if (isFloat) remaining / 4 else remaining / 2
        var sum = 0.0
        val probe = inputBuffer.duplicate()
        for (i in 0 until sampleCount) {
            val s = if (isFloat) probe.float.toDouble() else probe.short / 32768.0
            sum += s * s
        }
        val rms = sqrt(sum / sampleCount.coerceAtLeast(1)).toFloat()
        val desired = if (rms > 0.0001f) (TARGET_RMS / rms).coerceIn(1f / MAX_GAIN, MAX_GAIN) else 1f
        gain += (desired - gain) * SMOOTH
        for (i in 0 until sampleCount) {
            val s = if (isFloat) inputBuffer.float * gain else (inputBuffer.short / 32768f) * gain
            val clamped = s.coerceIn(-1f, 1f)
            if (isFloat) out.putFloat(clamped) else out.putShort((clamped * 32767f).toInt().toShort())
        }
        inputBuffer.position(inputBuffer.limit())
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return out
    }

    override fun queueEndOfStream() {
        inputEnded = true
        buffer = EMPTY_BUFFER
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        buffer = EMPTY_BUFFER
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        gain = 1f
    }

    override fun reset() {
        flush()
        inputFormat = AudioFormat.NOT_SET
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }
}
