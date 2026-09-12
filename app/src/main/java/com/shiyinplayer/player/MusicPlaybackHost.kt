package com.shiyinplayer.player

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * 音乐模式的 [PlaybackHost] 实现（Phase 0 · P0-1）。
 *
 * 薄委托层：将 [PlaybackHost] 接口转发到 [PlayerManager] / [PlaybackController]，
 * 不引入任何新逻辑。后续收音机模式将有独立的 [RadioPlaybackHost] 实现。
 */
class MusicPlaybackHost(
    private val playerManager: PlayerManager,
    private val playbackController: PlaybackController
) : PlaybackHost {

    override val playbackState: StateFlow<PlaybackState>
        get() = playerManager.playbackState

    override val currentLyricLine: StateFlow<String?>
        get() = playerManager.currentLyricLine

    override val sessionPlayer: Player
        get() = playerManager.fallbackAwarePlayer

    override val isPlaying: Boolean
        get() = playerManager.isPlaying

    override fun livePositionMs(): Long = playerManager.livePositionMs()

    override fun play() = playerManager.play()
    override fun pause() = playerManager.pause()
    override fun togglePlayPause() = playerManager.togglePlayPause()
    override fun stop() = playerManager.stop()

    override fun releaseAudioFocus() {
        playbackController.pause()
        // playbackController.pause() 内部不释放焦点（等下次 play 时复用），
        // 跨模式切换需要显式释放。abandonFocus 是 internal，通过 stop 间接释放。
    }

    override fun setSleepTimer(minutes: Int) = playerManager.setSleepTimer(minutes)

    override val sleepTimerEndAt: StateFlow<Long?>
        get() = playerManager.sleepTimerEndAt
}
