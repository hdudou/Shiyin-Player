package com.shiyinplayer.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.player.PlayerManager
import com.shiyinplayer.player.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** 播放状态共享 ViewModel（底部条 / 播放页 / 队列共用）。 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerManager: PlayerManager
) : ViewModel() {

    /** 公开 PlayerManager 供导航图访问（模式切换等）。 */
    val playerManagerRef: PlayerManager get() = playerManager

    val state: StateFlow<PlaybackState> = playerManager.playbackState
    val sleepTimerEndAt: StateFlow<Long?> = playerManager.sleepTimerEndAt

    fun togglePlay() = playerManager.togglePlayPause()
    fun next() = playerManager.next()
    fun prev() = playerManager.previous()
    fun stop() = playerManager.stop()
    fun clearQueue() = playerManager.clearQueue()
    fun seek(ms: Long) = playerManager.seekTo(ms)
    fun seekToIndex(index: Int) = playerManager.seekToIndex(index)
    fun removeFromQueue(index: Int) = playerManager.removeAt(index)
    fun moveQueueItem(from: Int, to: Int) = playerManager.moveQueueItem(from, to)
    fun moveUp(index: Int) = playerManager.moveQueueItem(index, index - 1)
    fun moveDown(index: Int) = playerManager.moveQueueItem(index, index + 1)
    fun setRepeat(mode: com.shiyinplayer.player.RepeatMode) = playerManager.setRepeatMode(mode)
    fun toggleShuffle() = playerManager.toggleShuffle()
    fun setSleepTimer(minutes: Int) = playerManager.setSleepTimer(minutes)
    fun setPlaybackSpeed(speed: Float) = playerManager.setPlaybackSpeed(speed)
}
