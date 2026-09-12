package com.shiyinplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shiyinplayer.data.model.MediaSourceType

/** 歌曲表（含 CUE 分轨扩展字段，可空）。dedupKey 唯一，重扫描按 IGNORE 覆盖而非重复插入、且不改变主键。 */
@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index(value = ["title"]),
        Index(value = ["artistName"]),
        Index(value = ["albumName"]),
        Index(value = ["sourceType"]),
        Index(value = ["albumId"]),
        Index(value = ["artistId"])
    ]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val sourceType: MediaSourceType,
    val path: String? = null,
    val dateAdded: Long = 0,
    val sizeBytes: Long = 0,
    // ===== CUE 分轨（T15） =====
    val cueId: String? = null,
    val trackIndex: Int? = null,
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
    // ===== 去重键（T12） =====
    val dedupKey: String = "",
    // ===== P2 增强列（genre / 评分 / 播放统计） =====
    val genre: String? = null,
    val year: Int? = null,
    val rating: Int = 0,
    val playCount: Int = 0,
    val lastPlayedMs: Long = 0,
    // ===== 格式校验（P0 解码扩展） =====
    /** 文件头魔数校验结果（magic 校验失败设 false，提示文件损坏或扩展名伪造）。 */
    val formatVerified: Boolean = true,
    // ===== 歌词时间偏移（§12 手动校正，单位 ms，仅写 DB） =====
    /** 用户对歌词时间轴的手动校正量（+ 表示歌词整体推后显示）。 */
    val lyricOffsetMs: Long = 0,
    // ===== 搜索拼音索引（F6-2，DB v12） =====
    /** 归一化搜索键：原文去空白 + 全拼 + 中文首字母（全小写），供拼音/首字母/原文匹配；扫描入录时计算。 */
    val searchKey: String = ""
)
