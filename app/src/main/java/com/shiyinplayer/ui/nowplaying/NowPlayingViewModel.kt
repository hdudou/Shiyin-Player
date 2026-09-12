package com.shiyinplayer.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.repository.PlaylistRepository
import com.shiyinplayer.player.PlayerManager
import com.shiyinplayer.player.PlaybackState
import com.shiyinplayer.player.RepeatMode
import com.shiyinplayer.ui.lyrics.DesktopLyricController
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import android.app.Application
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playerManager: PlayerManager,
    private val playlistRepo: PlaylistRepository,
    private val songDao: SongDao,
    private val desktopLyric: DesktopLyricController,
    private val settingsRepository: SettingsRepository,
    private val application: Application
) : ViewModel() {
    val state: StateFlow<PlaybackState> = playerManager.playbackState
    val sleepTimerEndAt: StateFlow<Long?> = playerManager.sleepTimerEndAt

    // 闹钟状态（与电台模式共享 SettingsRepository）
    val alarmEnabled: StateFlow<Boolean> = settingsRepository.radioAlarmEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val alarmHour: StateFlow<Int> = settingsRepository.radioAlarmHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 7)
    val alarmMinute: StateFlow<Int> = settingsRepository.radioAlarmMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // F2-4：桌面歌词悬浮窗
    val desktopLyricShown: StateFlow<Boolean> = desktopLyric.shown

    fun toggle() = playerManager.togglePlayPause()
    fun toggleDesktopLyric() {
        if (!desktopLyric.canDrawOverlays()) desktopLyric.openOverlaySettings()
        else desktopLyric.toggle()
    }
    fun next() = playerManager.next()
    fun prev() = playerManager.previous()
    fun stop() = playerManager.stop()
    fun seek(ms: Long) = playerManager.seekTo(ms)

    /** 循环模式切换：关 -> 全部 -> 单曲 -> 关。 */
    fun cycleRepeat() {
        val next = when (state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        playerManager.setRepeatMode(next)
    }

    fun toggleShuffle() = playerManager.toggleShuffle()
    fun setSleepTimer(minutes: Int) = playerManager.setSleepTimer(minutes)

    /** 闹钟启用/禁用，与电台模式共享 SettingsRepository。 */
    fun setAlarmEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRadioAlarmEnabled(enabled)
            val hour = settingsRepository.radioAlarmHourSync()
            val minute = settingsRepository.radioAlarmMinuteSync()
            com.shiyinplayer.player.radio.RadioAlarmReceiver.schedule(
                application, enabled, hour, minute
            )
        }
    }

    /** 设置闹钟时间，与电台模式共享 SettingsRepository。 */
    fun setAlarmTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setRadioAlarmHour(hour)
            settingsRepository.setRadioAlarmMinute(minute)
            if (settingsRepository.radioAlarmEnabledSync()) {
                com.shiyinplayer.player.radio.RadioAlarmReceiver.schedule(
                    application, true, hour, minute
                )
            }
        }
    }
}
