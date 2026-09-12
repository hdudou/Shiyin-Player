package com.shiyinplayer.data.remote.webdav

import android.net.Uri
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 浏览（架构 §3.2 / T14）：连接前经 ZeroTierManager.mapToLocal() 把虚拟网地址
 * 换成 127.0.0.1:<转发端口>，PROPFIND 与 Range 播放都走本地转发到达 WebDAV 服务器。
 * 返回的 entry.path 统一为【原始完整 URL】（scheme://host:port + 解码路径），
 * 供扫描递归 / 播放 URL 直接使用（播放时再经 mapToLocal 路由，凭据按原始主机名注入）。
 */
@Singleton
class WebDavBrowser @Inject constructor(
    private val client: WebDavClient,
    private val zt: ZeroTierManager
) {
    fun listFiles(dirPath: String): List<WebDavEntry> {
        val u = Uri.parse(dirPath)
        val scheme = u.scheme ?: return emptyList()
        val host = u.host ?: return emptyList()
        val port = if (u.port != -1) u.port else if (scheme == "https") 443 else 80
        val mapped = zt.mapToLocal(host, port)
        val baseUrl = "$scheme://${mapped.hostName}:${mapped.port}"
        val origin = "$scheme://$host" + if (u.port != -1) ":${u.port}" else ""
        return client.propfind(baseUrl, u.path ?: "/", host).map { e ->
            val resolved = if (e.path.startsWith("http")) e.path
            else origin + e.path
            e.copy(path = resolved)
        }
    }

    fun getPlayableUrl(entry: WebDavEntry): String = entry.path

    /** 下载远程文件前缀字节（用于扫描时探测时长）。经 ZeroTier 映射 + 凭据按原始 host 查。 */
    fun readPrefix(path: String, maxBytes: Int): ByteArray? {
        val u = Uri.parse(path)
        val scheme = u.scheme ?: return null
        val host = u.host ?: return null
        val port = if (u.port != -1) u.port else if (scheme == "https") 443 else 80
        val mapped = zt.mapToLocal(host, port)
        val baseUrl = "$scheme://${mapped.hostName}:${mapped.port}"
        val url = (baseUrl.trimEnd('/') + "/" + (u.path ?: "/").trimStart('/')).trimEnd('/')
        return client.readRange(url, maxBytes, host)
    }

    /** 全量 GET 下载远程文件（文件夹附件封面/说明 txt 预览）。经 ZeroTier 映射 + 凭据按原始 host 查。 */
    fun readFull(path: String): ByteArray? {
        val u = Uri.parse(path)
        val scheme = u.scheme ?: return null
        val host = u.host ?: return null
        val port = if (u.port != -1) u.port else if (scheme == "https") 443 else 80
        val mapped = zt.mapToLocal(host, port)
        val baseUrl = "$scheme://${mapped.hostName}:${mapped.port}"
        val url = (baseUrl.trimEnd('/') + "/" + (u.path ?: "/").trimStart('/')).trimEnd('/')
        return client.download(url, host)
    }
}