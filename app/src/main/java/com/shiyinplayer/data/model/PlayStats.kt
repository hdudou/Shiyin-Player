package com.shiyinplayer.data.model

/** F2-3：播放统计概览（来自 songs 表的 playCount / lastPlayedMs）。 */
data class PlayStats(
    val totalSongs: Int,
    val totalPlays: Long,
    val playedSongs: Int
)