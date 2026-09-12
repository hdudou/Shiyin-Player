package com.shiyinplayer.data.model

/**
 * 音乐库来源配置（领域模型）。
 * 每个来源可为本地 SAF 目录 / SMB 共享 / WebDAV 服务器；configJson 存类型相关配置。
 */
data class MusicSource(
    val id: Long = 0,
    val name: String,
    val type: MediaSourceType,
    val configJson: String = "{}",
    val enabled: Boolean = true,
    val lastScanTime: Long = 0
)
