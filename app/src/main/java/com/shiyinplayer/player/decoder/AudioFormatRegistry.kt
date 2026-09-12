package com.shiyinplayer.player.decoder

/**
 * 音频格式注册表（单一来源）。登记当前已交付格式（12 原生 + 8 P0 + 16 P2A），
 * 按 [CURRENT_PHASE] 过滤出扫描/解码范围。详见 specs/audio_decoder_opensource/spec.md。
 *
 * 2026-08-24 收口：移除模块音乐（P1A：mod/xm/s3m/it/mtm/umx，原 XmpRenderer）、
 * MIDI（P2B：mid/midi/rmi，原 MidiRenderer）与 DSD（P1B）支持项；
 * ATRAC（aa3/at3/oma）保留（FFmpeg 软解）。
 * 玩法阶段仅保留 NATIVE/P0/P2A（ffmpeg 软解），枚举随之精简。
 *
 * - NATIVE：ExoPlayer 原生 Extractor 可解码
 * - P0：系统直通，ExoPlayer 内置 Extractor + Android 系统 MediaCodec，零新增依赖
 * - P2A：自研 libffmpeg 子进程方案已废弃（.so 为 DYN、无 JNI 符号，不可 PIE 运行）；ape/wv/tta/mpc/spx/aa3/at3/oma/wma/tak/ofr
 *        经 exotic 透传 + FfmpegRenderer 软解（自编译 libffmpeg 6.1.1 + ffmpeg_jni），flac 走官方 FfmpegAudioRenderer
 */
object AudioFormatRegistry {

    /** 交付阶段。ordinal 顺序保证 NATIVE < P0 < P2A。P1A(模块音乐)/P1B(DSD)/P2B(MIDI) 已于 2026-08-24 移除。 */
    enum class Phase { NATIVE, P0, P2A }

    /** 解码路径。 */
    enum class DecodePath { SYSTEM, SOFTWARE, NDK }

    /** 时长探测策略。 */
    enum class ProbeStrategy { DURATION_RETRIEVER, FORMAT_SPECIFIC, SKIP }

    /** 文件头魔数描述。 */
    sealed class MagicSpec {
        /** 按十六进制字节匹配（offset 默认 0）。 */
        data class HexBytes(val bytes: ByteArray, val offset: Int = 0) : MagicSpec()
        /** 按 ASCII 字符串匹配。 */
        data class Ascii(val text: String, val offset: Int = 0) : MagicSpec()
        /** 仅按扩展名判定（无可靠魔数或首版不深度校验）。 */
        object ByExtension : MagicSpec()
    }

    /** 单个格式声明。 */
    data class AudioFormatSpec(
        val extension: String,
        val mimeType: String,
        val phase: Phase,
        val decodePath: DecodePath,
        val magic: MagicSpec,
        val probeStrategy: ProbeStrategy,
        val description: String
    )

    /** 当前已交付阶段。 */
    val CURRENT_PHASE: Phase = Phase.P2A

    /**
     * 已降级（暂停交付）的阶段。P1B（DSD）已于 2026-08-24 移除支持项，暂无可降级阶段，保留为空机制。
     */
    val DEFERRED_PHASES: Set<Phase> = setOf()

    /**
     * 已降级的单独格式（2026-08-18 起）：无可用解码器、不参与扫描/播放注册。
     * 2026-08-22（R-B1）：原 9 个 P2A 格式（wv/tta/mpc/spx/aa3/at3/oma/tak/ofr）已由
     * 自编译静态 FFmpeg（ffmpeg_jni + FfmpegRenderer，软解）激活，故移除出本集合；
     * ape/wma 均归 FfmpegRenderer 软解。
     * 当前无降级格式，集合保留为空（isDeliverable 依赖其空值恒真）。
     */
    val DEFERRED_FORMATS: Set<String> = setOf()

    private fun spec(ext: String, mime: String, phase: Phase, path: DecodePath, magic: MagicSpec, probe: ProbeStrategy, desc: String): AudioFormatSpec =
        AudioFormatSpec(ext, mime, phase, path, magic, probe, desc)

    private val formats: List<AudioFormatSpec> = listOf(
        // ===== NATIVE（9，ExoPlayer 1.4.1 原生） =====
        spec("mp3", "audio/mpeg", Phase.NATIVE, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "MPEG-1/2 Audio Layer III"),
        spec("aac", "audio/aac", Phase.NATIVE, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "AAC"),
        spec("m4a", "audio/mp4", Phase.NATIVE, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "MPEG-4 Audio (M4A)"),
        spec("m4b", "audio/mp4", Phase.NATIVE, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "MPEG-4 Audio Book (M4B)"),
        spec("ogg", "audio/ogg", Phase.NATIVE, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "Ogg Vorbis"),
        spec("oga", "audio/ogg", Phase.NATIVE, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "Ogg Audio"),
        spec("opus", "audio/ogg", Phase.NATIVE, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "Opus"),
        spec("flac", "audio/flac", Phase.P2A, DecodePath.NDK, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "Free Lossless Audio Codec"),
        spec("wav", "audio/wav", Phase.NATIVE, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "RIFF WAVE"),
        // ===== 新增（2026-08）：ExoPlayer 内置提取器 + 系统解码器（defaultFactory 路由） =====
        spec("amr", "audio/amr", Phase.NATIVE, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "Adaptive Multi-Rate Narrowband (AMR-NB)"),
        spec("awb", "audio/amr", Phase.NATIVE, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "Adaptive Multi-Rate Wideband (AMR-WB)"),
        spec("weba", "audio/webm", Phase.NATIVE, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "WebM Audio (Opus/Vorbis)"),
        spec("webm", "audio/webm", Phase.NATIVE, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.DURATION_RETRIEVER, "WebM Audio (Opus/Vorbis)"),
        // ===== P0（8，系统直通） =====
        spec("ac3", "audio/ac3", Phase.P0, DecodePath.SYSTEM, MagicSpec.HexBytes(byteArrayOf(0x0B, 0x77)), ProbeStrategy.FORMAT_SPECIFIC, "Dolby Digital (AC-3)"),
        spec("eac3", "audio/eac3", Phase.P0, DecodePath.SYSTEM, MagicSpec.HexBytes(byteArrayOf(0x0B, 0x77)), ProbeStrategy.FORMAT_SPECIFIC, "Dolby Digital Plus (E-AC-3)"),
        spec("dts", "audio/dts", Phase.P0, DecodePath.SYSTEM, MagicSpec.HexBytes(byteArrayOf(0x7F, 0xFE.toByte(), 0x80.toByte(), 0x01)), ProbeStrategy.FORMAT_SPECIFIC, "DTS Coherent Acoustics"),
        spec("mp1", "audio/mpeg-L1", Phase.P0, DecodePath.SYSTEM, MagicSpec.HexBytes(byteArrayOf(0xFF.toByte(), 0xF8.toByte())), ProbeStrategy.FORMAT_SPECIFIC, "MPEG Audio Layer I"),
        spec("mp2", "audio/mpeg-L2", Phase.P0, DecodePath.SYSTEM, MagicSpec.HexBytes(byteArrayOf(0xFF.toByte(), 0xF8.toByte())), ProbeStrategy.FORMAT_SPECIFIC, "MPEG Audio Layer II"),
        spec("alac", "audio/alac", Phase.P0, DecodePath.SYSTEM, MagicSpec.ByExtension, ProbeStrategy.FORMAT_SPECIFIC, "Apple Lossless (纯 ALAC 文件)"),
        spec("aiff", "audio/aiff", Phase.P0, DecodePath.SYSTEM, MagicSpec.Ascii("FORM"), ProbeStrategy.FORMAT_SPECIFIC, "Audio Interchange File Format"),
        spec("aif", "audio/aiff", Phase.P0, DecodePath.SYSTEM, MagicSpec.Ascii("FORM"), ProbeStrategy.FORMAT_SPECIFIC, "Audio Interchange File Format (AIF)"),
        // ===== P2A（11，2026-08-22 R-B1 + L3：自编译静态 libffmpeg（ffmpeg_jni + FfmpegRenderer 软解）激活
        //       wv/tta/mpc/spx/aa3/at3/oma/wma/tak/ofr，走 exotic 透传 + FfmpegRenderer；
        //       ape 亦切 FFmpeg 软解（原 ApeExtractor → MediaCodec 整首累积方案废弃）；原 DYN 子进程方案已废弃） =====
        spec("ape", "audio/x-ape", Phase.P2A, DecodePath.NDK, MagicSpec.Ascii("MAC "), ProbeStrategy.FORMAT_SPECIFIC, "Monkey's Audio（FFmpeg 软解流式）"),
        spec("wv", "audio/x-wavpack", Phase.P2A, DecodePath.NDK, MagicSpec.Ascii("wvpk"), ProbeStrategy.FORMAT_SPECIFIC, "WavPack"),
        spec("tta", "audio/x-tta", Phase.P2A, DecodePath.NDK, MagicSpec.Ascii("TTA1"), ProbeStrategy.FORMAT_SPECIFIC, "True Audio"),
        spec("mpc", "audio/x-musepack", Phase.P2A, DecodePath.NDK, MagicSpec.Ascii("MP+"), ProbeStrategy.FORMAT_SPECIFIC, "Musepack SV8"),
        spec("spx", "audio/speex", Phase.P2A, DecodePath.NDK, MagicSpec.Ascii("Speex   "), ProbeStrategy.FORMAT_SPECIFIC, "Speex"),
        spec("aa3", "audio/x-atrac3", Phase.P2A, DecodePath.NDK, MagicSpec.ByExtension, ProbeStrategy.FORMAT_SPECIFIC, "ATRAC3 (AA3)"),
        spec("at3", "audio/x-atrac3", Phase.P2A, DecodePath.NDK, MagicSpec.ByExtension, ProbeStrategy.FORMAT_SPECIFIC, "ATRAC3 (AT3)"),
        spec("oma", "audio/x-oma", Phase.P2A, DecodePath.NDK, MagicSpec.ByExtension, ProbeStrategy.FORMAT_SPECIFIC, "OpenMG Audio (OMA)"),
        spec("wma", "audio/x-ms-wma", Phase.P2A, DecodePath.NDK, MagicSpec.ByExtension, ProbeStrategy.FORMAT_SPECIFIC, "Windows Media Audio"),
        spec("tak", "audio/x-tak", Phase.P2A, DecodePath.NDK, MagicSpec.Ascii("tBk"), ProbeStrategy.FORMAT_SPECIFIC, "TAK (Tom's Lossless Audio Kompressor)"),
        spec("ofr", "audio/x-ofr", Phase.P2A, DecodePath.NDK, MagicSpec.Ascii("OFR "), ProbeStrategy.FORMAT_SPECIFIC, "OptimFROG"),
        // ===== 新增（2026-08）：FFmpeg 软解（dsf/dff/caf/shn/ac4，走 exotic 透传 + FfmpegRenderer） =====
        spec("dsf", "audio/x-dsf", Phase.P2A, DecodePath.NDK, MagicSpec.ByExtension, ProbeStrategy.FORMAT_SPECIFIC, "DSD Stream File（FFmpeg 软解）"),
        spec("dff", "audio/x-dff", Phase.P2A, DecodePath.NDK, MagicSpec.ByExtension, ProbeStrategy.FORMAT_SPECIFIC, "DSD Interchange File Format（FFmpeg 软解）"),
        spec("caf", "audio/x-caf", Phase.P2A, DecodePath.NDK, MagicSpec.ByExtension, ProbeStrategy.FORMAT_SPECIFIC, "Apple Core Audio Format（FFmpeg 软解）"),
        spec("shn", "audio/x-shorten", Phase.P2A, DecodePath.NDK, MagicSpec.ByExtension, ProbeStrategy.FORMAT_SPECIFIC, "Shorten 无损（FFmpeg 软解）"),
        spec("ac4", "audio/ac4", Phase.P2A, DecodePath.NDK, MagicSpec.ByExtension, ProbeStrategy.FORMAT_SPECIFIC, "AC-4（FFmpeg 软解）")
    )

    /** 全部格式声明。 */
    val allFormats: List<AudioFormatSpec> = formats

    private val byExt: Map<String, AudioFormatSpec> = formats.associateBy { it.extension }

    /** 格式是否处于可交付状态（phase <= CURRENT_PHASE 且未降级，且扩展名未单独降级）。 */
    fun isDeliverable(phase: Phase, extension: String? = null): Boolean =
        phase.ordinal <= CURRENT_PHASE.ordinal &&
            phase !in DEFERRED_PHASES &&
            (extension == null || extension !in DEFERRED_FORMATS)

    /** 当前已交付阶段的扩展名集合（deliverable 格式）。 */
    fun activeExtensions(): Set<String> =
        formats.filter { isDeliverable(it.phase, it.extension) }.map { it.extension }.toSet()

    /** 与用户白名单取交集派生最终扫描清单（空集合取全部 active，向后兼容）。 */
    fun activeExtensions(filter: Set<String>): Set<String> {
        val all = activeExtensions()
        return if (filter.isEmpty()) all else all.intersect(filter)
    }

    fun mimeTypeOf(ext: String): String? = byExt[ext]?.mimeType
    fun magicOf(ext: String): MagicSpec? = byExt[ext]?.magic
    fun probeStrategyOf(ext: String): ProbeStrategy? = byExt[ext]?.probeStrategy
    fun decodePathOf(ext: String): DecodePath? = byExt[ext]?.decodePath
    fun phaseOf(ext: String): Phase? = byExt[ext]?.phase
    fun isFormatActive(ext: String): Boolean = byExt[ext]?.let { isDeliverable(it.phase) } ?: false
}
