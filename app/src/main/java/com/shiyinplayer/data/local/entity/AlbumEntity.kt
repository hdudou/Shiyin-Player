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
    val songCount: Int = 0
)
