package com.shiyinplayer.data.metadata

import android.net.Uri
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 轻量 HTTP 封装：供各歌词/元数据数据源使用（基于 OkHttp，超时收短）。
 * 直连模式（R2-04）：请求目标为虚拟网内主机（ZeroTier 私网地址）时，经 [ZeroTierManager.mapToLocal]
 * 把地址改写为 127.0.0.1:<转发端口>，由 LoopbackForwarder 直接走 libzt 私网路由；
 * 公网 / 局域网主机原样走系统网络栈。
 */
class HttpApiClient(
    private val client: OkHttpClient,
    private val zt: ZeroTierManager? = null
) {
    private val shortClient: OkHttpClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun route(url: String): String {
        val manager = zt ?: return url
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return url
            val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
            val mapped = manager.mapToLocal(host, port)
            if (mapped.port == port && mapped.hostString == host) return url
            // encodedAuthority 避免冒号被 %3A 编码（见 WebDavDataSource 同款修复）
            uri.buildUpon().encodedAuthority("127.0.0.1:${mapped.port}").build().toString()
        } catch (_: Throwable) {
            url
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val builder = Request.Builder().url(route(url))
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.get()
            shortClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: IOException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun post(url: String, body: String, contentType: String = "application/x-www-form-urlencoded", headers: Map<String, String> = emptyMap()): String? {
        return try {
            val builder = Request.Builder().url(route(url))
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.post(RequestBody.create(contentType.toMediaType(), body))
            shortClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: IOException) {
            null
        } catch (_: Throwable) {
            null
        }
    }
}