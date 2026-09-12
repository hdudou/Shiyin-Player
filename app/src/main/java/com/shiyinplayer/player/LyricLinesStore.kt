package com.shiyinplayer.player

import com.shiyinplayer.data.metadata.MergedLine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局歌词行暂存（跨 UI / Service 共享）：
 * LyricsViewModel 解析后写入，PlaybackService 通知据此显示当前行。
 * volatile 保证跨线程可见性。
 */
@Singleton
class LyricLinesStore @Inject constructor() {
    @Volatile
    var songKey: String? = null

    @Volatile
    var lines: List<MergedLine> = emptyList()

    fun clear() {
        songKey = null
        lines = emptyList()
    }

    /** 取给定时间点命中的歌词行文本（副歌翻译合并显示）。 */
    fun lineAt(positionMs: Long): String? {
        val list = lines
        if (list.isEmpty()) return null
        // §12 R4：全量扫描取最后一个 timeMs <= position 的行（对乱序/相等时间戳稳健，不提前 break）。
        var idx = -1
        for (i in list.indices) {
            if (positionMs >= list[i].timeMs) idx = i
        }
        if (idx < 0) return null
        val line = list[idx]
        return line.translated?.takeIf { it.isNotBlank() && it != line.text }
            ?.let { "${line.text} / ${it}" }
            ?: line.text
    }
}