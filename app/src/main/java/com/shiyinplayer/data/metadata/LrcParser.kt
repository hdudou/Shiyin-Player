package com.shiyinplayer.data.metadata

import java.util.regex.Pattern

/**
 * LRC 歌词解析（对标 163MusicLyrics 的歌词处理能力）：
 * 支持 [mm:ss.xx] / [mm:ss] 时间戳、一行多个时间戳、[ar:/ti:/al:/by:/offset:] 元信息。
 */
object LrcParser {

    private val TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:\\.(\\d{1,3}))?]")

    fun parse(raw: String): ParsedLyric {
        var offsetMs = 0L
        val primary = ArrayList<TimedLyric>()
        val translated = ArrayList<TimedLyric>()
        if (raw.isBlank()) return ParsedLyric(primary, translated)

        raw.lineSequence().forEach { line ->
            val tagMatcher = TAG.matcher(line)
            val times = ArrayList<Long>()
            while (tagMatcher.find()) {
                val mm = tagMatcher.group(1).toLongOrNull() ?: 0
                val ss = tagMatcher.group(2).toLongOrNull() ?: 0
                val fracRaw = tagMatcher.group(3)
                val frac = if (fracRaw != null) {
                    when (fracRaw.length) {
                        1 -> fracRaw.toLong() * 100
                        2 -> fracRaw.toLong() * 10
                        else -> fracRaw.toLong()
                    }
                } else 0
                times.add(mm * 60_000 + ss * 1000 + frac)
            }
            if (times.isEmpty()) {
                // 元信息
                val body = line.trim()
                if (body.startsWith("[offset:")) {
                    body.removePrefix("[offset:").removeSuffix("]").trim().toLongOrNull()?.let {
                        offsetMs = it
                    }
                }
                return@forEach
            }
            val text = tagMatcher.replaceAll("").trim()
            if (text.isBlank()) return@forEach
            for (t in times) {
                // §12 R2：整体平移后保留负值时间戳，不截断为 0——靠排序 + 展示侧对 timeMs<=0 视作命中，
                // 保留相对间隔，避免含负偏移/早期行的 LRC 整曲偏前。
                primary.add(TimedLyric(t + offsetMs, text))
            }
        }

        primary.sortBy { it.timeMs }
        translated.sortBy { it.timeMs }
        return ParsedLyric(primary, translated)
    }

    /**
     * 将翻译歌词与原词按行对齐（时间相近则合并）。
     * 返回已合并好的行：timeMs / text（原词）/ translated（可空）。
     */
    fun merge(primary: List<TimedLyric>, translated: List<TimedLyric>): List<MergedLine> {
        if (translated.isEmpty()) return primary.map { MergedLine(it.timeMs, it.text, null) }
        val result = ArrayList<MergedLine>(primary.size)
        var tIdx = 0
        for (line in primary) {
            var matched: String? = null
            while (tIdx < translated.size && translated[tIdx].timeMs < line.timeMs - 1500) tIdx++
            val candidate = translated.getOrNull(tIdx)
            if (candidate != null && kotlin.math.abs(candidate.timeMs - line.timeMs) <= 1500) {
                matched = candidate.text
            }
            result.add(MergedLine(line.timeMs, line.text, matched))
        }
        return result
    }
}

data class MergedLine(
    val timeMs: Long,
    val text: String,
    val translated: String?
)