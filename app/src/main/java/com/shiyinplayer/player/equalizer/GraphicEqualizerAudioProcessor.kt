package com.shiyinplayer.player.equalizer

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 自研 10 段图示均衡器（架构 §1.2.6，R-P1-09）。
 * 10 个串联 peaking biquad（中心频率 31/62/125/250/500/1k/2k/4k/8k/16k Hz），
 * 增益 [-15,+15] dB。注入 ExoPlayer 音频链（PlayerModule 构建时 setAudioProcessors）。
 * 仅处理 PCM_16BIT 与 PCM_FLOAT（float32）；其他编码抛出 UnhandledAudioFormatException。
 */
class GraphicEqualizerAudioProcessor : AudioProcessor {

    companion object {
        val BAND_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val NUM_BANDS = 10
        private const val Q = 1.0f // 峰值滤波器品质因数（适中带宽）
        /** P2-4 软膝限幅：|x|≤ 此值线性透传，峰值之上 tanh 软饱和。0.7071≈-3dB 头。 */
        private const val SOFT_KNEE = 0.7071f
        /** EH：EQ 开关淡入淡出时长（毫秒），避免瞬间切换产生的咔哒/突变感。 */
        private const val FADE_MS = 10
        private val EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    private val gains = FloatArray(NUM_BANDS) // dB，默认 0（Flat）
    private val biquads = Array(NUM_BANDS) { Biquad() }

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputFormat: AudioFormat = AudioFormat.NOT_SET
    /** 主开关目标状态（eq_enabled）。 */
    @Volatile private var targetEnabled = true
    /** 当前实际生效的 EQ 增益（0=完全旁路，1=完全生效），用于开关淡入淡出。 */
    @Volatile private var currentMix = 1f
    /** 淡入淡出方向：+1=正在淡入，-1=正在淡出，0=稳态。 */
    @Volatile private var fadeDirection = 0
    /** 稳态下是否处于激活状态（有非零增益且开关打开）。 */
    @Volatile private var active = false

    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    fun setBandGain(band: Int, gainDb: Float) {
        if (band in 0 until NUM_BANDS) {
            gains[band] = gainDb
            recompute(band)
            active = targetEnabled && gains.any { it != 0f }
        }
    }

    /**
     * 主开关（eq_enabled）运行时切换：带淡入淡出过渡，避免瞬间旁路/恢复产生咔哒声。
     * EH：通过 currentMix 在 FADE_MS 内从 0→1 或 1→0 平滑过渡。
     */
    fun setEnabled(on: Boolean) {
        if (on == targetEnabled) return
        targetEnabled = on
        fadeDirection = if (on) 1 else -1
        // 淡入需要 active=true 以便处理器参与音频链（isActive 返回 true）
        if (on) active = gains.any { it != 0f }
    }

    /** 用当前 outputFormat 采样率重算所有频段系数（修正 configure 前用默认 44.1k 计算的系数偏差）。 */
    private fun recomputeAll() {
        for (b in 0 until NUM_BANDS) recompute(b)
    }

    fun getGains(): FloatArray = gains.clone()

    fun setGains(array: FloatArray) {
        for (i in 0 until minOf(array.size, NUM_BANDS)) setBandGain(i, array[i])
    }

    private fun recompute(band: Int) {
        val fs = outputFormat.sampleRate.toDouble().takeIf { it > 0 } ?: 44100.0
        val w0 = 2.0 * Math.PI * BAND_FREQS[band] / fs
        val a = 10.0.pow(gains[band] / 40.0)
        val alpha = sin(w0) / (2.0 * Q)
        val cw = cos(w0)
        val b0 = (1 + alpha * a).toFloat()
        val b1 = (-2 * cw).toFloat()
        val b2 = (1 - alpha * a).toFloat()
        val a0 = (1 + alpha / a).toFloat()
        val a1 = (-2 * cw).toFloat()
        val a2 = (1 - alpha / a).toFloat()
        biquads[band].set(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != android.media.AudioFormat.ENCODING_PCM_16BIT &&
            enc != android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        inputFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        recomputeAll() // 用真实采样率重算（configure 前可能是默认 44.1k）
        active = targetEnabled && gains.any { it != 0f }
        // 配置完成后从稳态开始
        currentMix = if (targetEnabled && gains.any { it != 0f }) 1f else 0f
        fadeDirection = 0
        return outputFormat
    }

    override fun isActive(): Boolean = active || fadeDirection != 0

    fun getOutputAudioFormat(): AudioFormat = outputFormat

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val remaining = inputBuffer.remaining()
        val out = replaceOutputBuffer(remaining)
        val isFloat = inputFormat.encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
        val sampleCount = if (isFloat) remaining / 4 else remaining / 2
        val channels = inputFormat.channelCount.coerceAtLeast(1)
        val fadeDir = fadeDirection

        when {
            // 稳态旁路：直接复制
            fadeDir == 0 && !active -> {
                out.put(inputBuffer)
            }
            // 稳态激活：正常 EQ 处理
            fadeDir == 0 -> {
                for (i in 0 until sampleCount) {
                    var s = if (isFloat) inputBuffer.float else (inputBuffer.short.toInt() / 32768f)
                    for (b in 0 until NUM_BANDS) s = biquads[b].process(s)
                    s = softLimit(s)
                    if (isFloat) out.putFloat(s) else out.putShort((s * 32767f).toInt().toShort())
                }
                inputBuffer.position(inputBuffer.limit())
            }
            // 淡入淡出过渡：EQ 输出与干信号按 currentMix 交叉混合
            else -> {
                val sampleRate = outputFormat.sampleRate.toFloat().takeIf { it > 0 } ?: 44100f
                // EH：淡入淡出按帧数（不是单声道采样数）计算步进，确保多声道下时长准确
                val fadeFrames = (sampleRate * FADE_MS / 1000f).toInt().coerceAtLeast(1)
                val step = 1f / fadeFrames
                var mix = currentMix
                var frameCount = 0
                for (i in 0 until sampleCount) {
                    val raw = if (isFloat) inputBuffer.float else (inputBuffer.short.toInt() / 32768f)
                    // 计算 EQ 处理后的信号
                    var eqS = raw
                    for (b in 0 until NUM_BANDS) eqS = biquads[b].process(eqS)
                    eqS = softLimit(eqS)
                    // 交叉混合：mix=1 全 EQ，mix=0 全干信号
                    val mixed = raw * (1f - mix) + eqS * mix
                    if (isFloat) out.putFloat(mixed) else out.putShort((mixed * 32767f).toInt().toShort())
                    // 每帧（所有声道）更新一次 mix，确保多声道下淡入淡出时长正确
                    if ((i + 1) % channels == 0) {
                        frameCount++
                        mix += step * fadeDir
                        if (fadeDir > 0 && mix >= 1f) {
                            mix = 1f
                            currentMix = 1f
                            fadeDirection = 0
                            active = targetEnabled && gains.any { it != 0f }
                        } else if (fadeDir < 0 && mix <= 0f) {
                            mix = 0f
                            currentMix = 0f
                            fadeDirection = 0
                            active = false
                            // 淡出完成后重置 biquad 状态，避免下次淡入时有历史残留
                            biquads.forEach { it.reset() }
                        }
                    }
                }
                currentMix = mix
                inputBuffer.position(inputBuffer.limit())
            }
        }
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
        biquads.forEach { it.reset() }
        // EH：flush 时复位淡入淡出到当前目标稳态（seek/切歌后不应残留过渡）
        currentMix = if (targetEnabled && gains.any { it != 0f }) 1f else 0f
        fadeDirection = 0
    }

    override fun reset() {
        flush()
        inputFormat = AudioFormat.NOT_SET
        outputFormat = AudioFormat.NOT_SET
        active = false
        targetEnabled = true
        currentMix = 1f
        fadeDirection = 0
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    /**
     * 软膝限幅（P2-4：均衡器硬削波问题）。
     * |x|≤SOFT_KNEE 时线性透传（正常聆听细节零失真）；峰值之上以 tanh 软饱和逼近 ±1，
     * 消除多频段高增益叠加导致的方块削波与其引入的高频混叠/谐波。
     */
    private inline fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= SOFT_KNEE) return x
        val sign = if (x < 0f) -1f else 1f
        val t = (a - SOFT_KNEE) / (1f - SOFT_KNEE)
        return sign * (SOFT_KNEE + (1f - SOFT_KNEE) * tanh(t))
    }

    /** 单个双二阶峰值滤波器状态机。 */
    private class Biquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var x1 = 0f; private var x2 = 0f
        private var y1 = 0f; private var y2 = 0f

        fun set(b0: Float, b1: Float, b2: Float, a1: Float, a2: Float) {
            this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2
            reset()
        }

        fun reset() {
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }
    }
}
