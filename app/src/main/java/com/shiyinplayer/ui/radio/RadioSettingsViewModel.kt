package com.shiyinplayer.ui.radio

import android.app.Application
import com.shiyinplayer.player.radio.RadioAlarmReceiver
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 电台设置 ViewModel：管理电台专用设置项。
 */
@HiltViewModel
class RadioSettingsViewModel @Inject constructor(
    private val application: Application,
    private val settings: SettingsRepository
) : ViewModel() {

    val maxRetryCount = settings.radioMaxRetryCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 5)

    val retryDelaySeconds = settings.radioRetryDelaySeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 2)

    val wifiOnly = settings.radioWifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val showProgramInfo = settings.radioShowProgramInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val autoReconnect = settings.radioAutoReconnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val sleepTimerEnabled = settings.radioSleepTimerEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val sleepTimerMinutes = settings.radioSleepTimerMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    // ---- 闹钟定时播放 ----
    val alarmEnabled = settings.radioAlarmEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val alarmHour = settings.radioAlarmHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 7)

    val alarmMinute = settings.radioAlarmMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setMaxRetryCount(value: Int) {
        viewModelScope.launch { settings.setRadioMaxRetryCount(value) }
    }

    fun setRetryDelaySeconds(value: Int) {
        viewModelScope.launch { settings.setRadioRetryDelaySeconds(value) }
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch { settings.setRadioWifiOnly(value) }
    }

    fun setShowProgramInfo(value: Boolean) {
        viewModelScope.launch { settings.setRadioShowProgramInfo(value) }
    }

    fun setAutoReconnect(value: Boolean) {
        viewModelScope.launch { settings.setRadioAutoReconnect(value) }
    }

    fun setSleepTimerEnabled(value: Boolean) {
        viewModelScope.launch { settings.setRadioSleepTimerEnabled(value) }
    }

    fun setSleepTimerMinutes(value: Int) {
        viewModelScope.launch { settings.setRadioSleepTimerMinutes(value) }
    }

    // ---- 闹钟 ----

    fun setAlarmEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setRadioAlarmEnabled(enabled)
            val hour = settings.radioAlarmHourSync()
            val minute = settings.radioAlarmMinuteSync()
            RadioAlarmReceiver.schedule(application, enabled, hour, minute)
        }
    }

    fun setAlarmTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settings.setRadioAlarmHour(hour)
            settings.setRadioAlarmMinute(minute)
            if (settings.radioAlarmEnabledSync()) {
                RadioAlarmReceiver.schedule(application, true, hour, minute)
            }
        }
    }

    // ---- 共享设置项（与音乐模式共用 DataStore） ----

    // 界面设计
    val themeStyle = settings.themeStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val themeMode = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val dynamicColors = settings.dynamicColors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val accent = settings.accent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val displayMode = settings.displayMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "auto")
    val keepScreenOn = settings.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val miniBarEnabled = settings.miniBarEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val notifyEnabled = settings.notifyEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val focusLossAutoSkip = settings.focusLossAutoSkip
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // 耳机/蓝牙控制
    val headsetPause = settings.headsetPause
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val headsetButtonControl = settings.headsetButtonControl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val btDisconnectPause = settings.btDisconnectPause
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val btReconnectResume = settings.btReconnectResume
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val lockscreenControl = settings.lockscreenControl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // 声音引擎
    val audioRoute = settings.audioRoute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "auto")
    val lowLatency = settings.lowLatency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val bufferMs = settings.bufferMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 200)
    val float32Processing = settings.float32Processing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val volumeCurve = settings.volumeCurve
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "log")
    val channelBalance = settings.channelBalance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    // 界面设计 setter
    fun setThemeStyle(v: Int) { viewModelScope.launch { settings.setThemeStyle(v) } }
    fun setThemeMode(v: Int) { viewModelScope.launch { settings.setThemeMode(v) } }
    fun setDynamicColors(v: Boolean) { viewModelScope.launch { settings.setDynamicColors(v) } }
    fun setAccent(v: Int) { viewModelScope.launch { settings.setAccent(v) } }
    fun setDisplayMode(v: String) { viewModelScope.launch { settings.setDisplayMode(v) } }
    fun setKeepScreenOn(v: Boolean) { viewModelScope.launch { settings.setKeepScreenOn(v) } }
    fun setMiniBarEnabled(v: Boolean) { viewModelScope.launch { settings.setMiniBarEnabled(v) } }
    fun setNotifyEnabled(v: Boolean) { viewModelScope.launch { settings.setNotifyEnabled(v) } }
    fun setLockscreenControl(v: Boolean) { viewModelScope.launch { settings.setLockscreenControl(v) } }
    fun setFocusLossAutoSkip(v: Boolean) { viewModelScope.launch { settings.setFocusLossAutoSkip(v) } }

    // 耳机/蓝牙控制
    fun setHeadsetPause(v: Boolean) { viewModelScope.launch { settings.setHeadsetPause(v) } }
    fun setHeadsetButtonControl(v: Boolean) { viewModelScope.launch { settings.setHeadsetButtonControl(v) } }
    fun setBtDisconnectPause(v: Boolean) { viewModelScope.launch { settings.setBtDisconnectPause(v) } }
    fun setBtReconnectResume(v: Boolean) { viewModelScope.launch { settings.setBtReconnectResume(v) } }

    // 声音引擎 setter
    fun setAudioRoute(v: String) { viewModelScope.launch { settings.setAudioRoute(v) } }
    fun setLowLatency(v: Boolean) { viewModelScope.launch { settings.setLowLatency(v) } }
    fun setBufferMs(v: Int) { viewModelScope.launch { settings.setBufferMs(v) } }
    fun setFloat32Processing(v: Boolean) { viewModelScope.launch { settings.setFloat32Processing(v) } }
    fun setVolumeCurve(v: String) { viewModelScope.launch { settings.setVolumeCurve(v) } }
    fun setChannelBalance(v: Float) { viewModelScope.launch { settings.setChannelBalance(v) } }
}
