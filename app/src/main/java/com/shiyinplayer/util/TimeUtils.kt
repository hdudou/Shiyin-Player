package com.shiyinplayer.util

/**
 * 时间 / 时长格式化工具。
 */
object TimeUtils {
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "—" // 未知时长诚实显示占位，不谎报 0:00（R：远程来源首次播放后回填真实时长）
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    fun formatClock(ms: Long): String = formatDuration(ms)
}
