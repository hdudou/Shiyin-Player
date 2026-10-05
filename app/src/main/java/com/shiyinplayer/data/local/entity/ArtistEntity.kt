package com.shiyinplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 艺术家表。 */
@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val albumCount: Int = 0,
    val songCount: Int = 0,
    // ===== 局域网同步（PC 主控推送，DB v13） =====
    /** PC 侧记录最后修改时间（Unix ms，契约 updatedAt）。 */
    val updatedAt: Long = 0
)
