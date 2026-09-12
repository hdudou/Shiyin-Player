package com.shiyinplayer.lockscreen

import android.media.audiofx.Visualizer
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 频谱分层（驱动视觉分层 L2）。
 * 把 64 段对数频谱聚合为：低频/中频/高频/总能量，统一归一化到 0..1。
 */
data class BandFrame(
    val bass: Float = 0f,   // 低频 0..1
    val mid: Float = 0f,    // 中频 0..1
    val treble: Float = 0f, // 高频 0..1
    val energy: Float = 0f  // 总体能量 0..1
) {
    companion object {
        val ZERO = BandFrame()
    }
}

/**
 * 锁屏叠加层音频可视化器（android.media.audiofx.Visualizer 方案）。
 *
 * 根因：Media3 1.4.1 的 DefaultAudioSink 在 Float 输出路径中跳过 AudioProcessor，
 * 因此 AudioProcessor 方案在 float32=true 时不可用。改用 Android 原生 Visualizer API，
 * 直接从 AudioTrack 输出获取 FFT 数据。
 *
 * - 需要 RECORD_AUDIO 权限
 * - 64 段对数频段映射 + 指数平滑 (α=0.3)
 * - 线程安全频谱缓冲 AtomicReference<FloatArray>
 * - UI 端以帧时钟调用 getSpectrum() / getBandFrame() 驱动时间演化
 */
class LockScreenVisualizer(
    private val smoothingAlpha: Float = 0.3f
) {
    companion object {
        private const val TAG = "LockScreenVisualizer"
        private const val CAPTURE_SIZE = 256 // Visualizer 捕获大小（FFT 点数）
        private const val BANDS = 64 // 输出频段数
    }

    private val spectrumBuffer = AtomicReference<FloatArray>(FloatArray(BANDS) { 0f })

    private var visualizer: Visualizer? = null
    private var isRunning = false

    // 诊断
    var debugFftCount = 0L; private set
    var debugLastSpectrumMax = 0f; private set
    var debugLastError: String? = null; private set

    /** 线程安全读取：UI 端每次帧时钟调用一次 */
    fun getSpectrum(): FloatArray = spectrumBuffer.get()

    /** 频谱分层快照：低频/中频/高频/总能量，均归一化到 0..1 */
    fun getBandFrame(): BandFrame {
        val s = spectrumBuffer.get()
        var bass = 0f; var mid = 0f; var treble = 0f; var energy = 0f
        for (i in 0 until BANDS) {
            val v = s[i]
            energy += v
            when {
                i < 20 -> bass += v
                i < 48 -> mid += v
                else -> treble += v
            }
        }
        val n = BANDS.toFloat()
        return BandFrame(
            bass = (bass / 20f).coerceIn(0f, 1f),
            mid = (mid / 28f).coerceIn(0f, 1f),
            treble = (treble / 16f).coerceIn(0f, 1f),
            energy = (energy / n).coerceIn(0f, 1f)
        )
    }

    /**
     * 启动 Visualizer，绑定到指定的 audioSessionId。
     * 如果 audioSessionId <= 0，则捕获所有音频输出（master output）。
     */
    fun start(audioSessionId: Int = 0) {
        stop() // 确保先释放旧的
        try {
            val viz = Visualizer(audioSessionId)
            val captureSizeRange = Visualizer.getCaptureSizeRange()
            val captureSize = min(CAPTURE_SIZE, captureSizeRange[1])
            viz.captureSize = captureSize
            viz.enabled = false

            viz.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer,
                    waveform: ByteArray,
                    samplingRate: Int
                ) {
                    // 不使用波形数据
                }

                override fun onFftDataCapture(
                    visualizer: Visualizer,
                    fft: ByteArray,
                    samplingRate: Int
                ) {
                    processFftData(fft, samplingRate)
                }
            }, samplingRateToMilliHz(), false, true) // wave=false, fft=true

            viz.enabled = true
            visualizer = viz
            isRunning = true
            Log.w(TAG, "Visualizer started: session=$audioSessionId, captureSize=$captureSize, samplingRateHz=${samplingRateToMilliHz() / 1000}")
        } catch (e: Exception) {
            debugLastError = e.message
            Log.e(TAG, "Failed to start Visualizer: ${e.message}")
            // 降级：输出零频谱，保持界面不崩溃
            spectrumBuffer.set(FloatArray(BANDS) { 0f })
        }
    }

    /** 停止并释放 Visualizer */
    fun stop() {
        isRunning = false
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        spectrumBuffer.set(FloatArray(BANDS) { 0f })
        Log.d(TAG, "Visualizer stopped")
    }

    fun isRunning(): Boolean = isRunning

    /**
     * 回调采样率（毫赫兹）。
     * setDataCaptureListener 的 samplingRate 上限为 Visualizer.getMaxCaptureRate()，
     * 典型值为 20000 mHz（20 Hz）。传入超限值会导致回调永远不触发！
     */
    private fun samplingRateToMilliHz(): Int {
        val maxRate = runCatching { Visualizer.getMaxCaptureRate() }.getOrElse { 20000 }
        return maxRate // 使用系统允许的最大值（通常 20000 mHz = 20 Hz）
    }

    /**
     * 处理 Visualizer 返回的 FFT 数据。
     *
     * Android Visualizer FFT 字节格式（ByteArray 大小 = captureSize）：
     * - 相邻两个 byte 组成一个频率 bin 的 (实部, 虚部)
     * - fft[0], fft[1] = bin 0（DC 分量）的实部、虚部
     * - fft[2], fft[3] = bin 1 的实部、虚部
     * - ...
     * - fft[2*n], fft[2*n+1] = bin n 的实部、虚部
     * - 共 captureSize/2 个 bin（bin 0 到 bin captureSize/2-1）
     * - 每个值是有符号 byte（-128..127），表示线性振幅
     */
    private fun processFftData(fft: ByteArray, samplingRate: Int) {
        debugFftCount++
        val captureSize = fft.size
        val freqBins = captureSize / 2

        // 将 FFT 数据转为幅度谱（相邻 Re/Im 对）
        val magnitudes = FloatArray(freqBins)
        for (i in 0 until freqBins) {
            val real = fft[2 * i].toInt().toFloat()
            val imag = fft[2 * i + 1].toInt().toFloat()
            magnitudes[i] = sqrt(real * real + imag * imag)
        }

        // 调试：首次 + 每 50 次打印 FFT 摘要
        if (debugFftCount <= 3 || debugFftCount % 50 == 0L) {
            val first5 = (0 until min(5, freqBins)).joinToString { b ->
                "[$b]=${String.format("%.1f", magnitudes[b])}"
            }
            Log.w(TAG, "FFT #$debugFftCount: captureSize=$captureSize, bins=$freqBins, max=${String.format("%.3f", debugLastSpectrumMax)}, $first5")
        }

        // 映射到 64 段对数频段
        val actualSampleRate = if (samplingRate > 0) samplingRate else 44100
        val newSpectrum = FloatArray(BANDS)
        var maxVal = 0f
        for (band in 0 until BANDS) {
            // 对数映射：低频段更精细，高频段更粗糙
            val lowFreq = band * actualSampleRate / (2 * BANDS)
            val highFreq = (band + 1) * actualSampleRate / (2 * BANDS)
            val lowBin = max(0, (lowFreq.toLong() * captureSize / actualSampleRate).toInt())
            val highBin = min(freqBins - 1, (highFreq.toLong() * captureSize / actualSampleRate).toInt())

            // 聚合频段内所有 bin 的能量
            var sum = 0f
            var count = 0
            for (bin in lowBin..highBin) {
                sum += magnitudes[bin]
                count++
            }
            val avg = if (count > 0) sum / count else 0f

            // dB 归一化：Visualizer 输出范围约 -128..127 → 0..~3600 幅度
            // 归一化到 0-1
            val db = 20f * log10(max(avg, 1f) / 127f)
            newSpectrum[band] = ((db + 60f) / 60f).coerceIn(0f, 1f)

            if (newSpectrum[band] > maxVal) maxVal = newSpectrum[band]
        }

        // 指数平滑（避免抖动）
        val oldSpectrum = spectrumBuffer.get()
        val smoothed = FloatArray(BANDS)
        for (i in 0 until BANDS) {
            smoothed[i] = oldSpectrum[i] * (1f - smoothingAlpha) + newSpectrum[i] * smoothingAlpha
            if (smoothed[i] > maxVal) maxVal = smoothed[i]
        }
        debugLastSpectrumMax = maxVal
        spectrumBuffer.set(smoothed)
    }
}
