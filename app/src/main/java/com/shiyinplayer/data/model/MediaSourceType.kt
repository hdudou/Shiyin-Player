package com.shiyinplayer.data.model

/**
 * 音乐库来源类型（架构 §3.1，扩展 LOCAL/SMB/WEBDAV/HTTP）。
 * HTTP 与 WEBDAV 底层都走 DefaultHttpDataSource，仅在浏览/凭据来源上区分。
 */
enum class MediaSourceType {
    LOCAL,   // 本地 SAF DocumentTree 目录
    SMB,     // SMB 共享目录
    WEBDAV,  // WebDAV 服务器目录

    /** P1-10 收口结论：HTTP 直链来源自 2026-08-24 起支持录入与扫描——
     *  录入直链 URL（m3u/换行列表或 HTML 自动索引页），扫描枚举音频直链入库；播放复用 SmartDataSource。 */
    HTTP
}
