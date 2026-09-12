package com.shiyinplayer.player

import com.shiyinplayer.data.model.Song

/** 播放状态快照（供 UI 收集）。 */
data class PlaybackState(
    val currentSong: Song? = null,
    override val isPlaying: Boolean = false,
    override val positionMs: Long = 0,
    override val durationMs: Long = 0,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val buffering: Boolean = false,
    /** 播放速度（1.0 = 正常速度）。ExoPlayer 通过 PlaybackParameters 设置。 */
    val playbackSpeed: Float = 1.0f,
    /** AB 循环起点（毫秒），null = 未设置。 */
    val loopStartMs: Long? = null,
    /** AB 循环终点（毫秒），null = 未设置。 */
    val loopEndMs: Long? = null
) : CommonPlaybackState {
    override val title: String? get() = currentSong?.title
    override val artist: String? get() = currentSong?.artistName
    override val coverUrl: String? get() = currentSong?.albumArtUri

    /** AB 循环是否已激活（起点和终点都已设置） */
    val isLooping: Boolean get() = loopStartMs != null && loopEndMs != null
}
