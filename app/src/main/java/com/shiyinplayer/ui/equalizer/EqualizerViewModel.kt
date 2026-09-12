package com.shiyinplayer.ui.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.player.EqualizerManager
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val equalizerManager: EqualizerManager,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _gains = MutableStateFlow(equalizerManager.getBandGains().toList())
    val gains: StateFlow<List<Float>> = _gains.asStateFlow()

    private val _preset = MutableStateFlow("Flat")
    val preset: StateFlow<String> = _preset.asStateFlow()

    init {
        viewModelScope.launch {
            // 恢复上次增益与预设（S-04 均衡器持久化）。
            val saved = runCatching { settings.eqGains.first() }.getOrDefault(equalizerManager.getBandGains())
            if (saved.isNotEmpty() && saved.any { it != 0f }) {
                equalizerManager.setBandGains(saved)
            }
            _gains.value = equalizerManager.getBandGains().toList()
            _preset.value = runCatching { settings.eqPreset.first() }.getOrDefault("Flat")
        }
    }

    fun setBand(band: Int, db: Float) {
        equalizerManager.setBandGain(band, db)
        _gains.value = equalizerManager.getBandGains().toList()
        _preset.value = "Custom"
        persist()
    }

    fun loadPreset(name: String) {
        equalizerManager.loadPreset(name)
        _gains.value = equalizerManager.getBandGains().toList()
        _preset.value = name
        persist()
    }

    fun reset() {
        equalizerManager.setBandGains(FloatArray(EqualizerManager.NUM_BANDS))
        _gains.value = equalizerManager.getBandGains().toList()
        _preset.value = "Flat"
        persist()
    }

    private fun persist() {
        viewModelScope.launch {
            runCatching { settings.setEqGains(_gains.value.toFloatArray()) }
            runCatching { settings.setEqPreset(_preset.value) }
        }
    }
}