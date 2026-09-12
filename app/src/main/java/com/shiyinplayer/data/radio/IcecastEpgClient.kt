package com.shiyinplayer.data.radio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Icecast status-json.xsl EPG 客户端。
 *
 * 从 Icecast 2 服务器的 `/status-json.xsl` 端点获取当前播放信息。
 * 返回 `null` 表示该服务器不是 Icecast 或请求失败。
 *
 * 注意：请求频率由调用方控制（建议 ≥ 5 分钟一次）。
 */
object IcecastEpgClient {

    private const val TAG = "IcecastEpgClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * 查询结果。
     */
    data class EpgInfo(
        /** 当前曲目（如 "Some Song - Some Artist"） */
        val currentTrack: String? = null,
        /** 节目/流名称（如 "Classic FM"） */
        val streamName: String? = null,
        /** 首页 URL */
        val listenUrl: String? = null
    )

    /**
     * 从给定流 URL 推断 Icecast status-json.xsl 地址并查询。
     *
     * 典型 URL 映射：
     * - 流地址: `http://host:port/stream`
     * - status: `http://host:port/status-json.xsl`
     *
     * @return [EpgInfo] 或 null（非 Icecast / 请求失败）
     */
    suspend fun fetchEpg(streamUrl: String): EpgInfo? = withContext(Dispatchers.IO) {
        try {
            val url = java.net.URL(streamUrl)
            val baseUrl = "${url.protocol}://${url.host}"
            val port = if (url.port > 0 && url.port != 80 && url.port != 443) ":${url.port}" else ""
            val statusUrl = "$baseUrl$port/status-json.xsl"

            Log.d(TAG, "Fetching EPG from: $statusUrl")

            val request = Request.Builder()
                .url(statusUrl)
                .header("User-Agent", "ShiyinPlayer/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "status-json.xsl returned ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            return@withContext parseStatusJson(body, streamUrl)
        } catch (e: Exception) {
            Log.d(TAG, "fetchEpg failed for $streamUrl: ${e.message}")
            return@withContext null
        }
    }

    /**
     * 解析 Icecast status-json.xsl 响应。
     *
     * JSON 结构示例：
     * ```json
     * {
     *   "icestats": {
     *     "source": [
     *       {
     *         "listenurl": "/stream",
     *         "server_name": "Classic FM",
     *         "artist": "Artist",
     *         "title": "Song"
     *       }
     *     ]
     *   }
     * }
     * ```
     * 或者当只有一个源时，`source` 是对象而非数组。
     */
    private fun parseStatusJson(json: String, streamUrl: String): EpgInfo? {
        try {
            val root = JSONObject(json)
            val icestats = root.optJSONObject("icestats") ?: return null

            val streamPath = try {
                java.net.URL(streamUrl).path
            } catch (_: Exception) {
                streamUrl
            }

            // source 可能是数组或单个对象
            val sources = icestats.opt("source")
            val matchingSource = when (sources) {
                is org.json.JSONArray -> {
                    var found: JSONObject? = null
                    for (i in 0 until sources.length()) {
                        val src = sources.optJSONObject(i) ?: continue
                        val listenUrl = src.optString("listenurl", "")
                        if (listenUrl.contains(streamPath) || streamPath.contains(listenUrl)) {
                            found = src
                            break
                        }
                    }
                    // 没有精确匹配就取第一个
                    found ?: sources.optJSONObject(0)
                }
                is JSONObject -> sources
                else -> null
            } ?: return null

            val artist = matchingSource.optString("artist", "").ifBlank { null }
            val title = matchingSource.optString("title", "").ifBlank { null }
            val serverName = matchingSource.optString("server_name", "").ifBlank { null }
            val listenUrl = matchingSource.optString("listenurl", "").ifBlank { null }

            // 组合当前曲目
            val currentTrack = when {
                artist != null && title != null -> "$artist - $title"
                title != null -> title
                artist != null -> artist
                else -> null
            }

            if (currentTrack == null && serverName == null) return null

            return EpgInfo(
                currentTrack = currentTrack,
                streamName = serverName,
                listenUrl = listenUrl
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseStatusJson failed: ${e.message}")
            return null
        }
    }
}
