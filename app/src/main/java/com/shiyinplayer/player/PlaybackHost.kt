package com.shiyinplayer.player

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放宿主公共能力接口（Phase 0 · P0-1）。
 *
 * 音乐模式 [PlayerManager] 与收音机模式（未来 RadioPlayer）共同实现此接口，
 * 供 [PlaybackService] / [MediaSessionManager] / 睡眠定时 / 音频焦点门控
 * 等公共模块统一消费，不感知当前是哪种播放模式。
 *
 * 设计原则：仅抽象「两端都需要的最小公共面」，不强求统一队列/曲目等语义差异。
 */
interface PlaybackHost {

    /** 可观察的播放状态快照。 */
    val playbackState: StateFlow<out CommonPlaybackState>

    /** 当前歌词行（通知 / 车载蓝牙显示）；收音机端可返回节目名或 null。 */
    val currentLyricLine: StateFlow<String?>

    /** 桥接给 MediaSession 的 Player 实例。 */
    val sessionPlayer: Player

    /** 是否正在播放音频。 */
    val isPlaying: Boolean

    /** 实时播放位置（ms）。 */
    fun livePositionMs(): Long

    // ---- 播放控制（内含音频焦点管理） ----

    fun play()
    fun pause()
    fun togglePlayPause()
    fun stop()

    // ---- 跨模式切换 ----

    /** 释放音频焦点并暂停（模式切换时对方调用）。 */
    fun releaseAudioFocus()

    // ---- 睡眠定时 ----

    fun setSleepTimer(minutes: Int)
    val sleepTimerEndAt: StateFlow<Long?>
}

/**
 * 公共播放状态接口，两端各自实现具体 data class 并继承此类。
 * 公共层（通知/焦点门控）仅使用此处定义的通用字段。
 */
interface CommonPlaybackState {
    val isPlaying: Boolean
    val positionMs: Long
    val durationMs: Long
    val title: String?
    val artist: String?
    val coverUrl: String?
}
