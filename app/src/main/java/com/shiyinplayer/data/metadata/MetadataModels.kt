package com.shiyinplayer.data.metadata

/** 单个带时间戳的歌词行。 */
data class TimedLyric(
    val timeMs: Long,
    val text: String
)

/** LRC 解析结果：原词 + 翻译（可空）。 */
data class ParsedLyric(
    val primary: List<TimedLyric>,
    val translated: List<TimedLyric>
)

/** 各数据源匹配到的歌曲条目（来自不同平台的歌曲 id 与封面）。 */
data class SongMatch(
    val source: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val artistAvatarUrl: String?,
    // ===== 决策 6：发行年份（best-effort，平台未提供则为 null） =====
    val year: Int? = null,
    // ===== 源私有透传字段（如酷狗 accesskey、TheAudioDB 内联歌词等），供 lyric(match) 使用 =====
    val extra: Map<String, String>? = null
)

/** 歌词文档。 */
data class LyricDocument(
    val lrcText: String,
    val translatedText: String?,
    val source: String
)

/** 歌手信息（简介 + 头像）。 */
data class ArtistMetadata(
    val name: String,
    val avatarUrl: String?,
    val bio: String?
)

/** 歌曲附加信息（专辑名 + 封面 + 年份）。 */
data class SongMetadata(
    val albumName: String?,
    val coverUrl: String?,
    val year: Int? = null
)