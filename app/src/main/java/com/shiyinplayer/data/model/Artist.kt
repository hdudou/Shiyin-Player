package com.shiyinplayer.data.model

/** 艺术家领域模型。 */
data class Artist(
    val id: Long = 0,
    val name: String,
    val albumCount: Int = 0,
    val songCount: Int = 0
)
