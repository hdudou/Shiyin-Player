package com.shiyinplayer.data.model

/** 播放列表领域模型。 */
data class Playlist(
    val id: Long = 0,
    val name: String,
    val dateCreated: Long = 0,
    val dateModified: Long = 0,
    val songCount: Int = 0
)
