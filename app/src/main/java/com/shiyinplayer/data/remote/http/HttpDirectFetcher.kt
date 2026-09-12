package com.shiyinplayer.data.remote.http

import com.shiyinplayer.di.WebDavClientProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP 直链来源抓取器（无凭据，公开直链 / 播放列表 / HTML 自动索引页）。
 *
 * - [fetchText]：GET 全文（供解析 m3u / 换行列表 / HTML 索引页里的音频直链）。
 * - [readRange]：Range GET 下载前缀字节（供扫描时探测格式/时长，见 LibraryScanner#scanHttp）。
 *
 * 仅用于「扫描枚举」；实际播放复用 [com.shiyinplayer.player.SmartDataSourceFactory]（Media3 DefaultHttpDataSource 严格校验）。
 * 中危-D：明文收敛——http:// 直链目标非内网一律拒绝（防公网明文抓取/泄漏）。
 */
@Singleton
class HttpDirectFetcher @Inject constructor(
    private val clientProvider: WebDavClientProvider
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** GET 全文（文本）。失败/超时/明文外链返回 null，不抛。瞬时 IO 错误自动重试（O）。 */
    fun fetchText(url: String): String? {
        if (!clientProvider.cleartextAllowed(url)) return null
        repeat(RETRY_ATTEMPTS) { attempt ->
            try {
                val result = client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    when {
                        resp.isSuccessful -> resp.body?.string()
                        resp.code == 404 -> NOT_FOUND_SENTINEL     // 明确不存在，无需重试
                        else -> null                               // 瞬时 5xx/超时态：重试
                    }
                }
                if (result === NOT_FOUND_SENTINEL) return null
                if (result != null) return result as? String
            } catch (_: IOException) {
                // 落到下方统一背退重试
            } catch (e: Exception) {
                return null
            }
            if (attempt < RETRY_ATTEMPTS - 1) sleepBackoff()
        }
        return null
    }

    /** Range GET 返回前 [maxBytes] 字节。失败/明文外链返回 null。瞬时 IO 错误自动重试（O）。 */
    fun readRange(url: String, maxBytes: Int): ByteArray? {
        if (!clientProvider.cleartextAllowed(url)) return null
        repeat(RETRY_ATTEMPTS) { attempt ->
            try {
                val req = Request.Builder().url(url)
                    .header("Range", "bytes=0-${maxBytes - 1}")
                    .get().build()
                val result = client.newCall(req).execute().use { resp ->
                    when {
                        resp.isSuccessful || resp.code == 206 -> resp.body?.bytes()?.takeIf { it.isNotEmpty() }
                        resp.code == 404 -> NOT_FOUND_SENTINEL
                        else -> null
                    }
                }
                if (result === NOT_FOUND_SENTINEL) return null
                if (result != null) return result as? ByteArray
            } catch (_: IOException) {
            } catch (e: Exception) {
                return null
            }
            if (attempt < RETRY_ATTEMPTS - 1) sleepBackoff()
        }
        return null
    }

    private fun sleepBackoff() {
        try { Thread.sleep(RETRY_BACKOFF_MS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 250L
        private val NOT_FOUND_SENTINEL = Any()

        /** 相对 URL 以 [base] 为基准解析为绝对 URL；解析失败返回 null。 */
        fun resolve(base: String, ref: String): String? = try {
            URI(base).resolve(ref).toString()
        } catch (e: Exception) {
            null
        }
    }
}