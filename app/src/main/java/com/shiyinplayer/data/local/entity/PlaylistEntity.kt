package com.shiyinplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 播放列表表。 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val dateCreated: Long = 0,
    val dateModified: Long = 0
)
