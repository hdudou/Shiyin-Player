package com.shiyinplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 专辑表。 */
@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val artistName: String? = null,
    val albumArtUri: String? = null,
    val year: Int? = null,
    val songCount: Int = 0,
    // ===== 局域网同步（PC 主控推送，DB v13） =====
    /** 艺术家主键（契约 artistId，供 song 外键补全）。 */
    val artistId: Long? = null,
    /** PC 侧记录最后修改时间（Unix ms，契约 updatedAt）。 */
    val updatedAt: Long = 0
)
