package com.shiyinplayer.util

/**
 * 全局常量。音乐播放器项目清单见 SETTINGS_SPEC M-03 等；音频格式支持范围（对标 AIMP 播放器）保持一致。
 */
object Constants {
    const val DATABASE_NAME = "musicplayer.db"
    const val DATABASE_VERSION = 12
    const val DATASTORE_METADATA_DB = "metadata.db"

    // 全新通道 ID：IMPORTANCE_LOW 用于媒体控制卡片（下拉栏显示）。
    const val NOTIFICATION_CHANNEL_ID = "playback_channel_v2"
    // 占位通知通道：IMPORTANCE_MIN + 锁屏不可见，仅满足前台服务约束，用户端不可见。
    const val NOTIFICATION_CHANNEL_PLACEHOLDER_ID = "playback_placeholder_channel"
    const val NOTIFICATION_ID = 1001

    const val DATASTORE_SETTINGS = "settings"
    const val DATASTORE_ZEROTIER = "zerotier"

    const val ENC_PREF_SMB = "smb_credentials"
    const val ENC_PREF_WEBDAV = "webdav_credentials"

    /**
     * 可纳入扫描的音乐文件扩展名（派生自 AudioFormatRegistry，按当前已交付阶段过滤）。
     *
     * 历史：原硬编码全格式清单，因 media3-exoplayer-ffmpeg 依赖不可达而降级为仅原生格式（2026-08-17）；
     * 现引入 AudioFormatRegistry 单一来源，P0 阶段恢复 8 个系统直通格式（ac3/eac3/dts/mp1/mp2/alac/aiff/aif），
     * 共 17 个（9 原生 + 8 P0）。P1/P2 格式随交付递增自动纳入。详见 specs/audio_decoder_ext/spec.md。
     */
    val AUDIO_EXTENSIONS: Set<String> get() = com.shiyinplayer.player.decoder.AudioFormatRegistry.activeExtensions()

    const val CUE_EXTENSION = "cue"

    /**
     * ZeroTier 默认网络 ID。
     *
     * 开源版置空：不内嵌任何默认网络，由用户在 ZeroTier 页手动输入自己的 Network ID。
     */
    const val ZEROTIER_DEFAULT_NETWORK_ID = ""
}
