package com.shiyinplayer.data.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.shiyinplayer.data.cache.MusicCacheManager
import com.shiyinplayer.data.remote.webdav.WebDavCredentialStore
import com.shiyinplayer.data.remote.webdav.WebDavDataSourceFactory
import com.shiyinplayer.di.WebDavClientProvider
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * 统一数据源工厂（T14）：按 URI scheme 把读取请求路由到正确的底层实现，
 * 使 SMB / WebDAV / 本地 / 公网直链都能经同一套 ExoPlayer 管线播放。
 *
 * - `smb://`      → [SmbDataSourceFactory]（jcifs-ng + 加密凭据）
 * - `http(s)://`  → 仅当目标 host 命中用户显式配置的 WebDAV 源（凭据库中存在该主机，
 *                   即用户在"网络来源"中保存过凭据的 WebDAV 服务器）时，才使用
 *                   [WebDavDataSourceFactory]（信任自签 HTTPS + ZeroTier 映射 + Authorization 头）；
 *                   其余公网/局域网直链一律走默认严格校验客户端（Media3 DefaultHttpDataSource，
 *                   完整 TLS 证书与主机名校验），杜绝公网 HTTPS 流被中间人替换/窃听（P0-1 修复）。
 * - 其它          → [DefaultDataSource]（file / content / …）
 *
 * 本地缓存优先：若目标 URI 已在 [MusicCacheManager] 有本地副本（播放过的 SMB/WebDAV 曲目被自动缓存），
 * 直接读本地文件（原格式保真、零网络依赖），离网也能播；否则走上述远端路由。
 *
 * 接入方式见 [com.shiyinplayer.di.PlayerModule]，经 [androidx.media3.exoplayer.source.DefaultMediaSourceFactory] 注入。
 */
class SmartDataSourceFactory @Inject constructor(
    private val context: Context,
    private val smbFactory: SmbDataSourceFactory,
    private val webDavFactory: WebDavDataSourceFactory,
    private val webDavCredStore: WebDavCredentialStore,
    private val musicCache: MusicCacheManager,
    private val clientProvider: WebDavClientProvider
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        SmartDataSource(context, smbFactory, webDavFactory, webDavCredStore, musicCache, clientProvider)
}

@UnstableApi
private class SmartDataSource(
    private val context: Context,
    private val smbFactory: SmbDataSourceFactory,
    private val webDavFactory: WebDavDataSourceFactory,
    private val webDavCredStore: WebDavCredentialStore,
    private val musicCache: MusicCacheManager,
    private val clientProvider: WebDavClientProvider
) : DataSource {

    private var delegate: DataSource? = null

    // P2-12：Media3 在 createDataSource() 后、open() 前调用 addTransferListener，
    // 此时 delegate 尚为 null，监听器先缓存，open 时转发给实际数据源（否则带宽统计/进度回调丢失）。
    private val pendingListeners = mutableListOf<TransferListener>()

    @UnstableApi
    override fun open(dataSpec: DataSpec): Long {
        val cached = musicCache.filePathFor(dataSpec.uri.toString())
        val spec = if (cached != null) dataSpec.withUri(Uri.fromFile(File(cached))) else dataSpec
        delegate = if (cached != null) {
            // 本地已有缓存副本 → 直接读本地文件（原格式保真，离网可播）
            DefaultDataSource.Factory(context).createDataSource()
        } else {
            when (dataSpec.uri.scheme?.lowercase()) {
                "smb" -> smbFactory.createDataSource()
                "http", "https" -> if (isConfiguredWebDavHost(dataSpec.uri)) {
                    // 用户显式配置的 WebDAV 源（有凭据）：允许自签 HTTPS / ZeroTier 映射 / 鉴权头
                    webDavFactory.createDataSource()
                } else {
                    // 中危-D：明文收敛——无凭据直链若为 http:// 且目标非内网，一律拒绝（防公网明文播放）。
                    if (!clientProvider.cleartextAllowed(dataSpec.uri.toString())) {
                        throw IOException("明文 HTTP 音频源已被安全策略拦截（仅内网允许）：${dataSpec.uri.host}")
                    }
                    // 无凭据的公网直链：默认客户端（DefaultHttpDataSource 严格校验 TLS 证书与主机名）
                    DefaultDataSource.Factory(context).createDataSource()
                }
                else -> DefaultDataSource.Factory(context).createDataSource()
            }
        }
        if (pendingListeners.isNotEmpty()) {
            pendingListeners.forEach { listener -> delegate?.addTransferListener(listener) }
            pendingListeners.clear()
        }
        return delegate!!.open(spec)
    }

    /**
     * 仅当目标主机在 WebDAV 凭据库中存在（用户显式保存过凭据）时才视为 WebDAV 源。
     * 先精确匹配、再小写兜底（凭据库按原始主机名存储，兼容大小写不一致的配置）。
     */
    private fun isConfiguredWebDavHost(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (host.isBlank()) return false
        return webDavCredStore.getForHost(host) != null ||
            webDavCredStore.getForHost(host.lowercase()) != null
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate!!.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun close() {
        delegate?.close()
        delegate = null
    }

    override fun addTransferListener(transferListener: TransferListener) {
        val d = delegate
        if (d != null) d.addTransferListener(transferListener) else pendingListeners.add(transferListener)
    }

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
}
