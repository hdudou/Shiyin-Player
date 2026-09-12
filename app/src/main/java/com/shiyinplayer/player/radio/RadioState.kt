package com.shiyinplayer.player.radio

import com.shiyinplayer.player.CommonPlaybackState

/**
 * 电台播放状态（独立于音乐模式的 PlaybackState）。
 */
data class RadioState(
    override val isPlaying: Boolean = false,
    override val positionMs: Long = 0L,
    override val durationMs: Long = 0L,
    override val coverUrl: String? = null,
    /** 当前电台名 */
    val stationName: String? = null,
    /** 当前节目名（ICY 元数据 / Icecast EPG） */
    val programName: String? = null,
    /** 当前正在播出的曲目（Icecast status-json.xsl 或 ICY StreamTitle） */
    val currentTrack: String? = null,
    /** 电台类型 */
    val genre: String? = null,
    /** 流比特率 */
    val bitrate: Int = 0,
    /** 是否正在缓冲 */
    val isBuffering: Boolean = false,
    /** 错误信息（null = 无错误） */
    val error: String? = null,
    /** 当前电台 URL */
    val streamUrl: String? = null,
    /** 电台 ID（Room 主键） */
    val stationId: Long? = null,
    /** 是否已收藏 */
    val isFavorite: Boolean = false,
    /** 该电台的可用线路（流）总数，>1 时播放页展示手动切换入口 */
    val lineCount: Int = 0,
    /** 当前播放线路在所有线路中的下标（0 起），用于播放页高亮/显示 */
    val lineIndex: Int = 0
) : CommonPlaybackState {
    override val title: String? get() = stationName
    override val artist: String? get() = currentTrack ?: programName

    /** 字幕优先级：currentTrack → programName → null */
    val subtitle: String? get() = currentTrack ?: programName

    /** 是否空闲（未播放、未缓冲、无流地址）：用于判断是否需要冷启动恢复 */
    val isIdle: Boolean get() = !isPlaying && !isBuffering && streamUrl == null
}
