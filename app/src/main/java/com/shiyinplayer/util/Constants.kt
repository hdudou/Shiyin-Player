package com.shiyinplayer.util

/**
 * 全局常量。音乐播放器项目清单见 SETTINGS_SPEC M-03 等；音频格式支持范围（对标 AIMP 播放器）保持一致。
 */
object Constants {
    const val DATABASE_NAME = "musicplayer.db"
    const val DATABASE_VERSION = 16
    const val DATASTORE_METADATA_DB = "metadata.db"

/**
 * 缓存库（歌词/元数据）的当前版本号，**必须与 [com.shiyinplayer.data.local.cache.MetadataDatabase]
 * 注解里的 `version` 一致**。
 *
 * 迁移安全管线要用它判断"是否需要升级"，写死在别处会逐渐漂移。
 */
const val DATASTORE_METADATA_VERSION = 3

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
     * 开源版**留空**：每个使用者必须在自己的 ZeroTier 页填入自己的 Network ID。
     *
     * 为什么不内置默认值：内嵌任何具体网络 ID 都会让**所有安装者静默加入作者的网络** ——
     * 既泄露私人网络信息，使用者也拿不到任何服务。留空时
     * [com.shiyinplayer.data.network.zerotier.ZeroTierConfig.ensureDefaultNetwork] 什么都不写，
     * 用户自行输入后持久化，之后手动清空也不会被再次覆盖。
     */
    const val ZEROTIER_DEFAULT_NETWORK_ID = ""

    /**
     * 版本更新仓库（新版本安装包 + latest.json 清单所在处）。
     *
     * 开源版**留空**：不内嵌任何更新服务器。填上自己的 WebDAV / HTTP 地址后，
     * 「关于 › 检查更新」才会去该地址拉取清单；留空即等于**关闭应用内更新检查**。
     *
     * 经 ZeroTier 托管时可用 ZT 虚拟地址，运行时由 ZeroTierManager.mapToLocal
     * 解析到可达地址（系统 ZT 网卡直连原地址，或内嵌 libzt 回环映射）。
     */
    const val UPDATE_BASE_URL = ""

    /**
     * 默认 ZeroTier WebDAV 源（首启播种，见 DefaultSourceSeeder）。
     *
     * 开源版**留空**：不预置任何默认音乐源与凭据（地址、账号、口令都属于部署者的私人信息）。
     * URL 留空时 [DefaultSourceSeeder] 直接跳过播种，使用者在「音乐来源」页自行添加即可。
     */
    const val ZT_WEBDAV_DEFAULT_NAME = "WebDAV-ZT"
    const val ZT_WEBDAV_DEFAULT_URL = ""
    const val ZT_WEBDAV_DEFAULT_USER = ""
    const val ZT_WEBDAV_DEFAULT_PASS = ""
}
