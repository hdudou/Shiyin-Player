package com.shiyinplayer.data.model

/**
 * 歌曲领域模型（与 [com.shiyinplayer.data.local.entity.SongEntity] 分离）。
 * 含 CUE 分轨扩展字段（R-P1-15 / T15）：cueId / trackIndex / clipStartMs / clipEndMs。
 */
data class Song(
    val id: Long = 0,
    val title: String,
    val artistId: Long? = null,
    val albumId: Long? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val albumArtUri: String? = null,
    val durationMs: Long = 0,
    val trackNumber: Int = 0,
    val uri: String,
    val mimeType: String? = null,
    val source: MediaSourceType,
    val path: String? = null,
    val dateAdded: Long = 0,
    val sizeBytes: Long = 0,
    // ===== CUE 分轨（T15） =====
    val cueId: String? = null,        // 整轨音频文件标识（同文件多子曲目共享）
    val trackIndex: Int? = null,      // 第几轨（从 1 起）
    val clipStartMs: Long? = null,    // 裁剪起点（INDEX 01）
    val clipEndMs: Long? = null,      // 裁剪终点（下一曲 INDEX 01 或文件末尾）
    // ===== 去重键（T12）：普通曲目=uri；CUE 子曲目=uri#trackIndex，保证重扫描不重复且保留主键 =====
    val dedupKey: String = "",
    // ===== P2 增强字段（genre / 评分 / 播放统计） =====
    val genre: String? = null,
    val year: Int? = null,
    val rating: Int = 0,
    val playCount: Int = 0,
    val lastPlayedMs: Long = 0,
    // ===== 多源同曲合并（R2-01）：主源之外的备用源 URI（按优先级从高到低） =====
    val altUris: List<String> = emptyList(),
    // ===== 格式校验（P0 解码扩展） =====
    /** 文件头魔数校验结果（magic 校验失败设 false，提示文件损坏或扩展名伪造）。 */
    val formatVerified: Boolean = true,
    // ===== 歌词时间偏移（§12 手动校正，单位 ms，仅写 DB） =====
    /** 用户对歌词时间轴的手动校正量（+ 表示歌词整体推后显示）。 */
    val lyricOffsetMs: Long = 0
)
