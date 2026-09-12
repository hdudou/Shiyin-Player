package com.shiyinplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 艺术家表。 */
@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val albumCount: Int = 0,
    val songCount: Int = 0
)
