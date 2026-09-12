package com.shiyinplayer.data.model

/** 播放列表项（关联 playlist 与 song，含排序）。 */
data class PlaylistItem(
    val id: Long = 0,
    val playlistId: Long,
    val songId: Long,
    val position: Int = 0
)
