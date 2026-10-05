package com.shiyinplayer.player

import com.shiyinplayer.data.metadata.LyricLineIndex
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
    private var _lines: List<MergedLine> = emptyList()

    /**
     * 行列表是否升序。B4-7：随 [lines] 一起缓存 —— 定位当前行是每 ~200ms 一次的热调用，
     * 每次都重算排序性等于把省下的扫描又花回去。
     */
    @Volatile
    private var sortedAscending: Boolean = true

    var lines: List<MergedLine>
        get() = _lines
        set(value) {
            _lines = value
            sortedAscending = LyricLineIndex.isSortedAscending(value)
        }

    fun clear() {
        songKey = null
        lines = emptyList()
    }

    /** 取给定时间点命中的歌词行文本（副歌翻译合并显示）。 */
    fun lineAt(positionMs: Long): String? {
        val list = _lines
        if (list.isEmpty()) return null

        // §12 R4：取最后一个 timeMs <= position 的行（对乱序/相等时间戳稳健）。
        // B4-7：升序时走上界二分，乱序时回退全量扫描 —— 语义完全一致，见 LyricLineIndex。
        val idx = LyricLineIndex.indexOf(list, positionMs, sortedAscending)
        if (idx < 0) return null

        val line = list[idx]
        return line.translated?.takeIf { it.isNotBlank() && it != line.text }
            ?.let { "${line.text} / ${it}" }
            ?: line.text
    }
}