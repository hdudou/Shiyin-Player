package com.shiyinplayer.di

import android.net.Uri
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * WebDAV 网络客户端按目标主机分流（2026-08-28 修复高危-02）：
 * - 内网（含 ZeroTier 虚拟网，即 RFC1918 私网 10/172.16-31/192.168、回环 127、链路本地 169.254）→
 *   信任自签 HTTPS 并跳过主机名校验（家庭/内网 WebDAV 常见自签证书）；
 * - 外链（公网 IP，或无法确认内网的主机）→ 走系统严格 TLS 校验（系统 CA + 主机名）。
 *
 * 取代原先「所有 WebDAV 一律信任所有证书」的 @Named("webdav") 单客户端，
 * 缩小中间人攻击面：只有明确的内网/零特网流量才放宽 TLS。
 */
@Singleton
class WebDavClientProvider @Inject constructor() {

    private val trustAllClient: OkHttpClient = buildClient(trustInternal = true)
    private val strictClient: OkHttpClient = buildClient(trustInternal = false)

    /** 按目标 URL 选择客户端：内网用信任自签的客户端，外链用严格校验的客户端。 */
    fun of(url: String): OkHttpClient =
        if (isInternalHost(hostOf(url))) trustAllClient else strictClient

    /**
     * 明文流量收敛（中危-D）：http:// 仅放行内网主机；外链明文一律拒绝，防止公网音源/凭证
     * 经明文 HTTP 泄露与篡改。https/其它 scheme 不受此限制。调用方在发起明文请求前先校验，
     * 不满足即不发起请求并给出可读错误。
     */
    internal fun cleartextAllowed(url: String): Boolean {
        val scheme = url.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "ws") return true
        return isInternalHost(hostOf(url))
    }

    private fun buildClient(trustInternal: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // 2026-08-21：播放读超时 60s→5s——libzt 转发存在"读一段后静默停滞"，
            // WebDavDataSource 依赖 read 超时触发内部 Range 续传以绕过停滞。
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (trustInternal) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
            b.sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
        }
        return b.build()
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host?.substringBefore(':')?.lowercase() }
            .getOrNull().orEmpty()

    /**
     * 是否内网主机：ZeroTier 虚拟网默认分配 RFC1918 私网地址（10/172.16-31/192.168），
     * 连同回环、链路本地一并视为内网；ZeroTier 映射后经回环（127.0.0.1）转发亦命中。
     * 解析失败或无法确认为内网一律按外链（严格）处理。
     */
    private val internalHostCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun isInternalHost(host: String): Boolean {
        if (host.isBlank()) return false
        return internalHostCache.getOrPut(host) {
            runCatching {
                InetAddress.getAllByName(host).any {
                    it.isSiteLocalAddress || it.isLoopbackAddress ||
                        it.isLinkLocalAddress || it.isAnyLocalAddress
                }
            }.getOrDefault(false)
        }
    }
}