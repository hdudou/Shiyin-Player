package com.shiyinplayer.ui.zerotier

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.network.zerotier.ZeroTierConfig
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ZeroTierViewModel @Inject constructor(
    private val zt: ZeroTierManager,
    private val config: ZeroTierConfig
) : ViewModel() {
    val status = zt.status
    val virtualIp = zt.virtualIp
    val error = zt.error
    val networkId: StateFlow<String?> = config.networkId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val autoReconnect: StateFlow<Boolean> = config.autoReconnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun join(id: String) {
        viewModelScope.launch { config.setNetworkId(id) }
        zt.joinNetwork(id)
    }

    fun leave() = zt.leaveNetwork()

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { config.setAutoReconnect(enabled) }
    }
}
