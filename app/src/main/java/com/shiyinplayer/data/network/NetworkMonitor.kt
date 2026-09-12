package com.shiyinplayer.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 当前网络类型（2026-08-19：流量保护 / WiFi 自动同步用）。 */
enum class NetworkKind { WIFI, CELLULAR, OTHER, NONE }

/**
 * 网络状态侦测（2026-08-19 新增，需求：流量保护 + WiFi 自动同步）。
 * 用 ConnectivityManager.registerDefaultNetworkCallback 实时监听当前活跃网络类型，
 * 供元数据同步决策：流量保护开启且为移动网络时停止联网获取。
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _network = MutableStateFlow(NetworkKind.NONE)
    val network: StateFlow<NetworkKind> = _network.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _network.value = kindOf(network)
            bindIfWifi(network)
        }

        override fun onLost(network: Network) {
            _network.value = NetworkKind.NONE
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val kind = kindOf(network)
            _network.value = kind
            if (kind == NetworkKind.WIFI) bindIfWifi(network)
        }
    }

    /**
     * 需求3：WiFi 时把本进程绑定到该 WiFi 网络（bindProcessToNetwork）——
     * 确保需要联网的操作（在线元数据/歌词/网络源访问）都走 WiFi 通道，
     * 即使设备同时连着移动数据也优先 WiFi。
     */
    private fun bindIfWifi(network: Network) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        runCatching { cm.bindProcessToNetwork(network) }
    }

    /** 当前是否为 WiFi（联网走 WiFi 通道的最简单判据）。 */
    fun isWifi(): Boolean = _network.value == NetworkKind.WIFI

    /** 当前是否为移动网络（流量保护关心的场景）。 */
    fun isCellular(): Boolean = _network.value == NetworkKind.CELLULAR

    /** 启动监听（Application onCreate 调用一次）。 */
    fun start() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
        }
        // 启动时同步一次当前状态（registerDefaultNetworkCallback 的 onAvailable 不一定立即回调）
        _network.value = kindOf(cm.activeNetwork)
    }

    private fun kindOf(network: Network?): NetworkKind {
        if (network == null) return NetworkKind.NONE
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetworkKind.NONE
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return NetworkKind.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
            else -> NetworkKind.OTHER
        }
    }
}
