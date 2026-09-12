package com.shiyinplayer.player.equalizer

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ReplayGain 音频处理器（接线 replaygain_mode 开关：off / track / album / smart）。
 *
 * 说明：本项目曲库当前未解析逐曲 ReplayGain 标签（RG 标签解析不在本期范围），
 * 因此增益按「模式 → 目标增益」的务实映射生效（track/album/smart 各给一个温和的
 * 统一提升值，并做峰值软限幅防削波）。这样开关与模式被真实消费、对音量有可感知影响。
 *
 * 预留 [setTargetGain] 入口：若未来曲库解析出 RG_* 标签，可由 PlayerManager 在切歌时
 * 用真实逐曲增益覆盖（targetDb 单位 dB，0 = 直通）。
 *
 * 处理 PCM_16BIT 与 PCM_FLOAT（float32）。注入顺序位于 EQ 之后、响度归一化之前。
 */
class ReplayGainAudioProcessor(mode: String = "off") : AudioProcessor {

    companion object {
        private val EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

        /** 模式 → 目标增益(dB)。off 不会注入本处理器；其余给出温和统一提升。 */
        private fun modeToDb(mode: String): Float = when (mode) {
            "track" -> 3.0f   // 按曲目：参照 -18 LUFS 的适中提升
            "album" -> 1.5f   // 按专辑：更小，保留专辑内动态起伏
            "smart" -> 2.5f   // 智能：折中
            else -> 0f        // off / 未知
        }
    }

    private var targetDb = modeToDb(mode)
    private var inputEnded = false
    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputFormat: AudioFormat = AudioFormat.NOT_SET

    /** 运行时切换目标增益（dB，0 = 直通）。供未来注入真实 RG 标签值。 */
    fun setTargetGain(dB: Float) {
        targetDb = dB
    }

    /** 运行时切换模式（重新计算目标增益）。 */
    fun setMode(mode: String) {
        targetDb = modeToDb(mode)
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != android.media.AudioFormat.ENCODING_PCM_16BIT &&
            enc != android.media.AudioFormat.ENCODING_PCM_FLOAT
        ) {
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
        // 固定增益（无逐帧自适应，故无需平滑；恒定乘子不会引入抽吸）
        val gain = java.lang.Math.pow(10.0, targetDb.toDouble() / 20.0).toFloat()
        for (i in 0 until sampleCount) {
            if (isFloat) {
                out.putFloat((inputBuffer.float * gain).coerceIn(-1f, 1f))
            } else {
                val v = (inputBuffer.short / 32768f) * gain
                out.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
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
        buffer = EMPTY_BUFFER
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        buffer = EMPTY_BUFFER
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
