package com.shiyinplayer.data.remote.webdav

/** WebDAV 目录项（架构 §3.1）。 */
data class WebDavEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val contentType: String?,
    val path: String
)
