package com.shiyinplayer.data.metadata

import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.inject.Inject

/**
 * 酷我音乐数据源（music-tag-web 同款中文源）：搜索 + 歌词（songinfoandlrc 含信息+歌词）。
 * 需携带一致的 csrf 请求头与 kw_token cookie。实现 [MetadataSource]。
 */
class KuwoApi @Inject constructor(
    client: OkHttpClient,
    zt: ZeroTierManager
) : MetadataSource {

    override val id: String = "kuwo"
    override val displayName: String = "酷我"
    override val capabilities: Set<MetaCapability> =
        setOf(MetaCapability.LYRIC, MetaCapability.COVER)

    private val http = HttpApiClient(client, zt)

    /** 生成一致的 csrf / kw_token（任意值但二者须相同）。 */
    private fun token(): String {
        val chars = "abcdef0123456789"
        return (1..16).joinToString("") { chars.random().toString() }
    }

    override suspend fun search(title: String, artist: String?): List<SongMatch> {
        val kw = listOf(title.trim(), artist?.trim()).filter { !it.isNullOrEmpty() }.joinToString(" ")
        if (kw.isBlank()) return emptyList()
        val url = "https://kuwo.cn/api/www/search/searchMusicBykeyWord?key=${urlEncode(kw)}&pn=0&rn=10&httpsStatus=1"
        val tok = token()
        val raw = http.get(url, headers = mapOf("User-Agent" to UA, "Referer" to "https://kuwo.cn/", "csrf" to tok, "Cookie" to "kw_token=$tok"))
            ?: return emptyList()
        return try {
            val data = JSONObject(raw).optJSONObject("data") ?: return emptyList()
            val list = data.optJSONArray("list") ?: return emptyList()
            val out = ArrayList<SongMatch>()
            for (i in 0 until list.length()) {
                val s = list.getJSONObject(i)
                val rid = s.optLong("rid")
                if (rid <= 0) continue
                out.add(
                    SongMatch(
                        source = "kuwo",
                        id = rid.toString(),
                        title = s.optString("name", ""),
                        artist = s.optString("artist", ""),
                        album = s.optString("album").takeIf { it.isNotBlank() },
                        coverUrl = s.optString("pic").takeIf { it.isNotBlank() },
                        artistAvatarUrl = null
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun lyric(match: SongMatch): LyricDocument? {
        val raw = http.get(
            "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=${match.id}",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://m.kuwo.cn/")
        ) ?: return null
        return try {
            val data = JSONObject(raw).optJSONObject("data") ?: return null
            val lrc = data.optString("lrc", "").takeIf { it.isNotBlank() }
                ?: run {
                    val lrclist = data.optJSONArray("lrclist") ?: return null
                    buildLrcFromList(lrclist)
                }
            if (lrc.isBlank()) return null
            LyricDocument(lrcText = lrc, translatedText = null, source = "kuwo")
        } catch (_: Exception) {
            null
        }
    }

    private fun buildLrcFromList(lrclist: org.json.JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until lrclist.length()) {
            val line = lrclist.getJSONObject(i)
            val time = line.optDouble("time", -1.0)
            val text = line.optString("lineLyric", "").takeIf { it.isNotBlank() } ?: continue
            if (time < 0) continue
            sb.append(formatLrcTime(time)).append(text).append("\n")
        }
        return sb.toString()
    }

    private fun formatLrcTime(sec: Double): String {
        val totalMs = (sec * 1000).toLong()
        val mm = totalMs / 60000
        val ss = (totalMs % 60000) / 1000
        val xx = (totalMs % 1000) / 10
        return String.format("[%02d:%02d.%02d]", mm, ss, xx)
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
