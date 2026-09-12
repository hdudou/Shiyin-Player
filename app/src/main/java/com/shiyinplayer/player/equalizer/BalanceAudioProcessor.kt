package com.shiyinplayer.player.equalizer

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 声道平衡（channel_balance）：以 [-1, 1] 的平衡值分别衰减左右声道——负值减弱右声道、正值减弱左声道，
 * 0 居中（旁路）。仅对双声道（stereo）生效，输入输出格式原样透传（PCM_16BIT / PCM_FLOAT）。
 *
 * 由 EqualizerManager 持有，并通过 DataStore `channel_balance` 流实时更新（无需重建播放器）。
 */
class BalanceAudioProcessor : AudioProcessor {

    companion object {
        private val EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    /** 平衡值 [-1,1]：0=居中，<0 减右，>0 减左。@Volatile 供运行时热更新。 */
    @Volatile
    private var balance = 0f

    private var inputEnded = false
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputFormat: AudioFormat = AudioFormat.NOT_SET

    fun setBalance(value: Float) {
        balance = value.coerceIn(-1f, 1f)
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != android.media.AudioFormat.ENCODING_PCM_16BIT &&
            enc != android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        inputFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        abs(balance) > 0.001f && inputFormat.channelCount == 2

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (isActive() && inputBuffer.hasRemaining()) {
            val remaining = inputBuffer.remaining()
            val out = replaceOutputBuffer(remaining)
            val isFloat = inputFormat.encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
            // 双声道：帧内 L/R 交错，偶数=左、奇数=右
            val leftGain = if (balance > 0f) 1f - balance else 1f
            val rightGain = if (balance < 0f) 1f + balance else 1f
            var sample = 0
            while (sample < remaining) {
                val ch = (sample / (if (isFloat) 4 else 2)) % 2
                if (isFloat) {
                    val s = inputBuffer.float
                    out.putFloat(s * (if (ch == 0) leftGain else rightGain))
                    sample += 4
                } else {
                    val s = inputBuffer.short
                    out.putShort((s * (if (ch == 0) leftGain else rightGain).toFloat()).toInt().toShort())
                    sample += 2
                }
            }
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
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
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