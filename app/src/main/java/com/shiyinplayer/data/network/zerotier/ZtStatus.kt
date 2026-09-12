package com.shiyinplayer.data.network.zerotier

/** ZeroTier 连接状态（架构 §3.1）。 */
enum class ZtStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}
