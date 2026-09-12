package com.shiyinplayer.data.radio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * RadioBrowser API 客户端（Phase 2 · 2-1）。
 * 使用公开 REST API 搜索/浏览网络电台目录。
 */
class RadioBrowserClient {

    companion object {
        private const val TAG = "RadioBrowserClient"
        private const val BASE_URL = "https://de1.api.radio-browser.info"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * RadioBrowser 电台数据类。
     */
    data class RadioBrowserStation(
        val stationuuid: String = "",
        val name: String = "",
        val url: String = "",
        val homepage: String = "",
        val favicon: String = "",
        val tags: String = "",
        val country: String = "",
        val countrycode: String = "",
        val state: String = "",
        val language: String = "",
        val codec: String = "",
        val bitrate: Int = 0,
        val votes: Int = 0,
        val clickcount: Int = 0,
        val clicktrend: Int = 0,
        val lastcheckok: Int = 0
    )

    /**
     * 搜索电台。
     */
    suspend fun searchStations(
        query: String? = null,
        tag: String? = null,
        country: String? = null,
        limit: Int = 50,
        offset: Int = 0,
        order: String = "votes"
    ): List<RadioBrowserStation> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = StringBuilder("$BASE_URL/json/stations/search")
            urlBuilder.append("?limit=$limit&offset=$offset&order=$order&hidebroken=true")

            if (!query.isNullOrBlank()) {
                urlBuilder.append("&name=${java.net.URLEncoder.encode(query, "UTF-8")}")
            }
            if (!tag.isNullOrBlank()) {
                urlBuilder.append("&tag=${java.net.URLEncoder.encode(tag, "UTF-8")}")
            }
            if (!country.isNullOrBlank()) {
                urlBuilder.append("&country=${java.net.URLEncoder.encode(country, "UTF-8")}")
            }

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .addHeader("User-Agent", "ShiyinPlayer/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "[]"
                parseStations(body)
            } else {
                Log.e(TAG, "Search failed: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 获取热门电台（按点击量排序）。
     */
    suspend fun getTopStations(limit: Int = 30): List<RadioBrowserStation> {
        return searchStations(limit = limit, order = "clickcount")
    }

    /**
     * 按标签获取电台。
     */
    suspend fun getStationsByTag(tag: String, limit: Int = 50): List<RadioBrowserStation> {
        return searchStations(tag = tag, limit = limit)
    }

    /**
     * 按国家获取电台。
     */
    suspend fun getStationsByCountry(country: String, limit: Int = 50): List<RadioBrowserStation> {
        return searchStations(country = country, limit = limit)
    }

    /**
     * 关闭 OkHttp 连接池和线程池，释放资源。
     */
    fun shutdown() {
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } catch (_: Exception) {}
    }

    private fun parseStations(json: String): List<RadioBrowserStation> {
        val stations = mutableListOf<RadioBrowserStation>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                stations.add(
                    RadioBrowserStation(
                        stationuuid = obj.optString("stationuuid", ""),
                        name = obj.optString("name", ""),
                        url = obj.optString("url", ""),
                        homepage = obj.optString("homepage", ""),
                        favicon = obj.optString("favicon", ""),
                        tags = obj.optString("tags", ""),
                        country = obj.optString("country", ""),
                        countrycode = obj.optString("countrycode", ""),
                        state = obj.optString("state", ""),
                        language = obj.optString("language", ""),
                        codec = obj.optString("codec", ""),
                        bitrate = obj.optInt("bitrate", 0),
                        votes = obj.optInt("votes", 0),
                        clickcount = obj.optInt("clickcount", 0),
                        clicktrend = obj.optInt("clicktrend", 0),
                        lastcheckok = obj.optInt("lastcheckok", 0)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}", e)
        }
        return stations
    }

    /**
     * 解析 M3U/PLS/TXT 文件内容，提取电台列表。
     */
    fun parsePlaylist(content: String, fileName: String): List<Pair<String, String>> {
        val stations = mutableListOf<Pair<String, String>>()
        val lowerName = fileName.lowercase()

        when {
            lowerName.endsWith(".m3u") || lowerName.endsWith(".m3u8") -> {
                parseM3U(content, stations)
            }
            lowerName.endsWith(".pls") -> {
                parsePLS(content, stations)
            }
            else -> {
                parseTXT(content, stations)
            }
        }
        return stations
    }

    private fun parseM3U(content: String, stations: MutableList<Pair<String, String>>) {
        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                val name = line.substringAfter(",", "").trim().ifEmpty { "未知电台" }
                if (i + 1 < lines.size) {
                    val url = lines[i + 1].trim()
                    if (url.isNotEmpty() && !url.startsWith("#")) {
                        stations.add(name to url)
                    }
                }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                stations.add("未知电台" to line)
            }
            i++
        }
    }

    private fun parsePLS(content: String, stations: MutableList<Pair<String, String>>) {
        val urlMap = mutableMapOf<String, String>()
        val nameMap = mutableMapOf<String, String>()

        for (line in content.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("File", ignoreCase = true) && trimmed.contains("=") -> {
                    val key = trimmed.substringBefore("=").trim()
                    val url = trimmed.substringAfter("=").trim()
                    urlMap[key] = url
                }
                trimmed.startsWith("Title", ignoreCase = true) && trimmed.contains("=") -> {
                    val key = trimmed.substringBefore("=").trim()
                    val name = trimmed.substringAfter("=").trim()
                    nameMap[key] = name
                }
            }
        }

        for ((key, url) in urlMap) {
            val name = nameMap[key] ?: "未知电台"
            stations.add(name to url)
        }
    }

    private fun parseTXT(content: String, stations: MutableList<Pair<String, String>>) {
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val parts = trimmed.split(Regex("[,|\t]"), limit = 2)
            if (parts.size == 2 && parts[1].contains("://")) {
                stations.add(parts[0].trim() to parts[1].trim())
            } else if (trimmed.contains("://")) {
                stations.add("未知电台" to trimmed)
            }
        }
    }
}
