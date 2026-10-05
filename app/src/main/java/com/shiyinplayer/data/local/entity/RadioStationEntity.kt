package com.shiyinplayer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 网络电台（Phase 0 · P0-4）。来源：builtin（内置清单）、user（手动添加）、browsing（RadioBrowser 目录）。 */
@Entity(tableName = "radio_station")
data class RadioStationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    @ColumnInfo(name = "logoUrl") val logoUrl: String? = null,
    val genre: String? = null,
    val country: String? = null,
    /** builtin | user | browsing */
    val source: String = "user",
    @ColumnInfo(name = "isFavorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "createdAt") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updatedAt") val updatedAt: Long = System.currentTimeMillis(),
    // ===== 局域网同步（PC 主控推送，DB v13） =====
    /** 备用流地址数组（契约 alternateUrls，存 JSON 数组文本；与封面 logoUrl 语义无关，勿混用）。 */
    val alternateUrls: String? = null,
    /** 码率（kbps，契约 bitrate）。 */
    val bitrate: Int = 0,
    /** 收听次数（契约 listenCount）。 */
    val listenCount: Int = 0,
    /** 最后播放时间（Unix ms，契约 lastPlayedAt）。 */
    val lastPlayedAt: Long = 0
)
