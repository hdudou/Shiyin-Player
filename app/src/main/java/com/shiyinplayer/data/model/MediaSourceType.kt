package com.shiyinplayer.data.model

/**
 * 音乐库来源类型。
 */
enum class MediaSourceType {
    LOCAL,   // 本地 SAF DocumentTree 目录
    SMB,     // SMB 共享目录
    WEBDAV   // WebDAV 服务器目录
}
