package com.shiyinplayer.data.media

/**
 * CUE 分轨解析器（架构 R-P1-15 / T15）。
 *
 * 支持指令：`FILE`（整轨音频文件名）、`TRACK`、`TITLE`、`PERFORMER`、`INDEX 01`（分轨起点，MM:SS:FF）。
 * 时间换算：1 秒 = 75 帧，故 `(MM*60 + SS)*75 + FF` 帧 → `*1000/75` 毫秒。
 */
data class CueTrack(
    val title: String,
    val performer: String?,
    val index01Ms: Long
)

data class CueSheet(
    val file: String,        // 引用的整轨音频文件名（不含路径）
    val tracks: List<CueTrack>,
    val albumName: String? = null,   // REM ALBUM（P2-7：CUE 子曲目回填专辑名）
    val artistName: String? = null   // REM ARTIST（P2-7：CUE 子曲目回填艺术家名）
)

object CueParser {

    private val INDEX_RE = Regex("""INDEX\s+(\d+)\s+(\d+):(\d+):(\d+)""", RegexOption.IGNORE_CASE)
    private val QUOTED_RE = Regex(""""([^"]*)"""")
    private const val FRAME_PER_SEC = 75L

    /** 解析 CUE 文本；文件或分轨缺失时返回 null。 */
    fun parse(text: String): CueSheet? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        var file: String? = null
        val tracks = mutableListOf<CueTrack>()
        var title: String? = null
        var performer: String? = null
        var indexMs: Long? = null
        var albumName: String? = null
        var artistName: String? = null
        var trackCount = 0

        fun flush() {
            val idx = indexMs
            if (idx != null) {
                trackCount++
                tracks += CueTrack(
                    title = title ?: "Track $trackCount",
                    performer = performer,
                    index01Ms = idx
                )
            }
            title = null
            performer = null
            indexMs = null
        }

        for (line in lines) {
            when {
                line.startsWith("FILE", ignoreCase = true) -> {
                    file = QUOTED_RE.find(line)?.groupValues?.get(1)
                        ?: line.substringAfter("FILE").trim().substringBefore(" ").takeIf { it.isNotEmpty() }
                }
                line.startsWith("TRACK", ignoreCase = true) -> {
                    flush()
                }
                line.startsWith("TITLE", ignoreCase = true) -> {
                    title = QUOTED_RE.find(line)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                        ?: line.substringAfter("TITLE").trim().takeIf { it.isNotEmpty() }
                }
                line.startsWith("PERFORMER", ignoreCase = true) -> {
                    performer = QUOTED_RE.find(line)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                        ?: line.substringAfter("PERFORMER").trim().takeIf { it.isNotEmpty() }
                }
                line.startsWith("REM", ignoreCase = true) -> {
                    // P2-7：REM ALBUM / REM ARTIST（专辑级元数据，回填子曲目 albumName/artistName）
                    val rem = line.substringAfter("REM").trim()
                    when {
                        rem.startsWith("ALBUM", ignoreCase = true) ->
                            albumName = QUOTED_RE.find(rem)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                                ?: rem.substringAfter("ALBUM").trim().takeIf { it.isNotEmpty() }
                        rem.startsWith("ARTIST", ignoreCase = true) ->
                            artistName = QUOTED_RE.find(rem)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                                ?: rem.substringAfter("ARTIST").trim().takeIf { it.isNotEmpty() }
                    }
                }
                line.startsWith("INDEX", ignoreCase = true) -> {
                    val m = INDEX_RE.find(line) ?: continue
                    val (idx, mm, ss, ff) = m.destructured
                    val ms = ((mm.toLong() * 60 + ss.toLong()) * FRAME_PER_SEC + ff.toLong()) * 1000 / FRAME_PER_SEC
                    // 优先采用 01 号索引作为分轨起点；无 01 时退化为首个索引
                    if (idx == "01" || indexMs == null) indexMs = ms
                }
            }
        }
        flush()

        if (file == null || tracks.isEmpty()) return null
        return CueSheet(file, tracks, albumName, artistName)
    }
}
