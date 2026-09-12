package com.shiyinplayer.player.decoder

import android.media.MediaCodecList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备解码能力探测器。构造时遍历系统 MediaCodec 收集支持的解码 mime，
 * 供 PlayerManager 在播放前判断设备是否支持某格式（P0 系统直通格式）。
 * MediaCodecList 异常时保守降级（所有查询返回 false）。详见 tasks.md §1.4。
 */
@Singleton
class DeviceCodecProbe @Inject constructor() {

    private val supportedMimes: Set<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { it.supportedTypes.asList() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** 设备是否支持给定 mime。精确匹配优先，回退到 top-level type 匹配（如 audio/mpeg-L1 → audio/mpeg）。 */
    fun supports(mimeType: String): Boolean {
        if (supportedMimes.isEmpty()) return false
        if (supportedMimes.contains(mimeType)) return true
        // 别名回退：MPEG Layer I/II 归入 audio/mpeg
        val alias = when (mimeType) {
            "audio/mpeg-L1", "audio/mpeg-L2" -> "audio/mpeg"
            else -> mimeType
        }
        if (supportedMimes.contains(alias)) return true
        // 同 top-level type 下任一匹配（宽松，用于 audio/x-* 自定义 mime）
        val top = mimeType.substringBefore("/")
        return supportedMimes.any { it.startsWith("$top/") }
    }

    /** 调试用：设备支持的解码 mime 集合。 */
    fun supportedFormats(): Set<String> = supportedMimes
}