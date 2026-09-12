package com.shiyinplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 播放列表项表（playlist 与 song 的关联，含排序）。 */
@Entity(
    tableName = "playlist_items",
    indices = [Index(value = ["playlistId", "songId"], unique = true)]
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: Long,
    val position: Int = 0
)
