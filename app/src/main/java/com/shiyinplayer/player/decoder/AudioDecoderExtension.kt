package com.shiyinplayer.player.decoder

import androidx.media3.exoplayer.Renderer

/**
 * 音频解码扩展点（R-P1-16 / 架构 §1.2.2）。
 * 2026-08-24：DSD（DSF/DFF）支持项已移除，本接口当前无注册实现，保留为未来解码器插拔点。
 *
 * P1 扩展：[priority] 用于同 mime 多解码器时排序（高优先级先选）。
 */
@Deprecated(
    "占位扩展点：当前没有任何注册实现（DSD 支持项已于 2026-08-24 移除）。" +
        "接入新解码器时请实现本接口并删除此注解；仅作阅读参考时勿调用。"
)
interface AudioDecoderExtension {
    /** 同 mime 多解码器时的优先级（数值越大越优先）。 */
    val priority: Int
    fun supports(mimeType: String): Boolean
    fun createRenderer(): Renderer?
}
