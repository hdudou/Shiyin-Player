package com.shiyinplayer.data.model

/** 专辑领域模型。 */
data class Album(
    val id: Long = 0,
    val name: String,
    val artistName: String? = null,
    val albumArtUri: String? = null,
    val year: Int? = null,
    val songCount: Int = 0
)
