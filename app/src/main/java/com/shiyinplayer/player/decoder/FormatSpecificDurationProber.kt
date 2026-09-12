package com.shiyinplayer.player.decoder

/**
 * 格式专用时长探测。当 MediaMetadataRetriever 失败时，按格式分支解析文件头估算时长。
 * 首版实现 AIFF/AIF 精确解析（COMM chunk）；AC3/EAC3/DTS/MP1/MP2/ALAC 首版返回 0，
 * 依赖 MediaMetadataRetriever 或播放后 persistDurationIfNeeded 回填。详见 tasks.md §1.5。
 * 2026-08-24：模块音乐（mod/xm/s3m/it/mtm/umx）与 MIDI（mid/midi/rmi）分支随支持项移除。
 */
object FormatSpecificDurationProber {

    /** 返回时长（毫秒），0 表示探测失败。 */
    fun probe(ext: String, prefixBytes: ByteArray, fileSize: Long): Long {
        return try {
            when (ext) {
                "aiff", "aif" -> probeAiff(prefixBytes)
                "wv" -> probeWavpack(prefixBytes)
                "tta" -> probeTta(prefixBytes)
                "mpc" -> probeMpc(prefixBytes)
                "spx" -> probeSpeex(prefixBytes)
                "ape" -> probeApe(prefixBytes, fileSize)
                "ofr" -> probeOptimFrog(prefixBytes, fileSize)
                "dsf" -> probeDsf(prefixBytes)
                // dff/caf/shn/ac4 当前返回 0（依赖 MediaMetadataRetriever 或播放后回填）
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    /** AIFF/AIF：解析 COMM chunk 的 numSampleFrames / sampleRate。 */
    private fun probeAiff(b: ByteArray): Long {
        if (b.size < 12) return 0
        if (String(b, 0, 4, Charsets.US_ASCII) != "FORM") return 0
        val formType = String(b, 8, 4, Charsets.US_ASCII)
        if (formType != "AIFF" && formType != "AIFC") return 0
        var pos = 12
        while (pos + 8 <= b.size) {
            val chunkId = String(b, pos, 4, Charsets.US_ASCII)
            val chunkSize = readBE32(b, pos + 4)
            pos += 8
            if (chunkId == "COMM") {
                if (pos + 18 > b.size) return 0
                val numSampleFrames = readBE32(b, pos + 2).toLong() and 0xFFFFFFFFL
                val sampleRate = parseExtended80(b, pos + 8)
                if (sampleRate <= 0.0) return 0
                return (numSampleFrames / sampleRate * 1000).toLong()
            }
            // 奇数 chunk size 加 1 字节对齐
            pos += chunkSize + (chunkSize and 1)
        }
        return 0
    }

    private fun readBE32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    /** 解析 80-bit IEEE 754 extended float（AIFF sampleRate 编码）。 */
    private fun parseExtended80(b: ByteArray, off: Int): Double {
        if (off + 10 > b.size) return 0.0
        val exponent = ((b[off].toInt() and 0x7F) shl 8) or (b[off + 1].toInt() and 0xFF)
        var mantissa = 0L
        for (i in 2..8) {
            mantissa = (mantissa shl 8) or (b[off + i].toLong() and 0xFF)
        }
        mantissa = (mantissa shl 8) or (b[off + 9].toLong() and 0xFF)
        if (exponent == 0 || mantissa == 0L) return 0.0
        return mantissa.toDouble() * Math.pow(2.0, (exponent - 16383 - 63).toDouble())
    }

    private fun readLE32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF)) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun readLE24(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF)) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16)

    private fun readLE64(b: ByteArray, off: Int): Long {
        if (off + 8 > b.size) return 0L
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /**
     * DSF 时长探测（P2A，FFmpeg 软解）。
     * DSF 头（小端）：
     *   0  "DSD "；40 采样率(LE32)；36 声道数(LE32)；44 位深(LE32)；48 每声道采样总数(LE64)。
     * 时长 = sampleCount / sampleRate * 1000。
     */
    private fun probeDsf(b: ByteArray): Long {
        if (b.size < 56) return 0L
        if (String(b, 0, 4, Charsets.US_ASCII) != "DSD ") return 0L
        val sampleRate = readLE32(b, 40).toInt()
        val sampleCount = readLE64(b, 48)
        if (sampleRate <= 0 || sampleCount <= 0) return 0L
        return sampleCount * 1000L / sampleRate
    }

    /**
     * WavPack 时长探测（P2A）。解析 "wvpk" 头，total_samples 在 offset 12（LE32），
     * sample_rate 在 offset 24（LE24）。
     */
    private fun probeWavpack(b: ByteArray): Long {
        if (b.size < 28) return 0L
        if (String(b, 0, 4, Charsets.US_ASCII) != "wvpk") return 0L
        val totalSamples = readLE32(b, 12)
        if (totalSamples == 0L || totalSamples == 0xFFFFFFFFL) return 0L
        val sampleRate = readLE24(b, 24).toInt()
        if (sampleRate <= 0) return 0L
        return totalSamples * 1000L / sampleRate
    }

    /**
     * TTA 时长探测（P2A）。"TTA1"/"TTA2" 头，num_samples 在 offset 4（LE32），
     * sample_rate 在 offset 10（LE32）。
     */
    private fun probeTta(b: ByteArray): Long {
        if (b.size < 18) return 0L
        val magic = String(b, 0, 4, Charsets.US_ASCII)
        if (magic != "TTA1" && magic != "TTA2") return 0L
        val numSamples = readLE32(b, 4)
        val sampleRate = readLE32(b, 10).toInt()
        if (numSamples == 0L || sampleRate <= 0) return 0L
        return numSamples * 1000L / sampleRate
    }

    /**
     * MPC 时长探测（P2A）。"MP+" 头（SV8），按文件大小 + 典型码率估算。
     */
    private fun probeMpc(b: ByteArray): Long {
        if (b.size < 4) return 0L
        val magic = String(b, 0, 3, Charsets.US_ASCII)
        if (magic != "MP+") return 0L
        return 0L
    }

    /**
     * Speex 时长探测（P2A）。Ogg Speex 头含 granule 信息，简化版返回 0 依赖播放回填。
     */
    private fun probeSpeex(b: ByteArray): Long {
        if (b.size < 28) return 0L
        if (String(b, 0, 4, Charsets.US_ASCII) != "OggS") return 0L
        return 0L
    }

    /**
     * APE 时长探测（P2A）。"MAC " 头，按文件大小 + 典型码率估算。
     */
    private fun probeApe(b: ByteArray, fileSize: Long): Long {
        if (b.size < 4) return 0L
        if (String(b, 0, 4, Charsets.US_ASCII) != "MAC ") return 0L
        if (fileSize <= 0) return 0L
        val estimatedBitrate = 800L
        return fileSize * 8L / estimatedBitrate * 1000L
    }

    /**
     * OptimFROG 时长探测（P2A）。"OFR " 头，按文件大小估算。
     */
    private fun probeOptimFrog(b: ByteArray, fileSize: Long): Long {
        if (b.size < 4) return 0L
        if (String(b, 0, 4, Charsets.US_ASCII) != "OFR ") return 0L
        if (fileSize <= 0) return 0L
        val estimatedBitrate = 900L
        return fileSize * 8L / estimatedBitrate * 1000L
    }
}