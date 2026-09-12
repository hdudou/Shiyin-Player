package com.shiyinplayer.data.metadata

import android.util.Base64
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.inject.Inject

/**
 * 酷狗音乐数据源（music-tag-web 同款中文源）：搜索 + 歌词（两步：search → download）。
 * 无需签名；download 的 content 为 base64 编码的 LRC。实现 [MetadataSource]。
 */
class KugouApi @Inject constructor(
    client: OkHttpClient,
    zt: ZeroTierManager
) : MetadataSource {

    override val id: String = "kugou"
    override val displayName: String = "酷狗"
    override val capabilities: Set<MetaCapability> =
        setOf(MetaCapability.LYRIC, MetaCapability.COVER)

    private val http = HttpApiClient(client, zt)

    override suspend fun search(title: String, artist: String?): List<SongMatch> {
        val kw = listOf(title.trim(), artist?.trim()).filter { !it.isNullOrEmpty() }.joinToString(" ")
        if (kw.isBlank()) return emptyList()
        val raw = http.get(
            "https://songsearch.kugou.com/song_search_v2?keyword=${urlEncode(kw)}&page=1&pagesize=10&platform=WebFilter",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://www.kugou.com/")
        ) ?: return emptyList()
        return try {
            val lists = JSONObject(raw).optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()
            val out = ArrayList<SongMatch>()
            for (i in 0 until lists.length()) {
                val s = lists.getJSONObject(i)
                val hash = s.optString("FileHash")
                if (hash.isEmpty()) continue
                out.add(
                    SongMatch(
                        source = "kugou",
                        id = hash,
                        title = s.optString("SongName", ""),
                        artist = s.optString("SingerName", ""),
                        album = s.optString("AlbumName").takeIf { it.isNotBlank() },
                        coverUrl = s.optString("ImgUrl").takeIf { it.isNotBlank() },
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
        val kw = listOf(match.artist.trim(), match.title.trim())
            .filter { it.isNotBlank() }.joinToString("-")
        if (kw.isBlank()) return null
        val searchRaw = http.get(
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${urlEncode(kw)}&duration=0",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://www.kugou.com/")
        ) ?: return null
        val candidate = try {
            JSONObject(searchRaw).optJSONArray("candidates")
                ?.takeIf { it.length() > 0 }?.getJSONObject(0) ?: return null
        } catch (_: Exception) {
            return null
        }
        val id = candidate.optString("id")
        val accessKey = candidate.optString("accesskey")
        if (id.isEmpty() || accessKey.isEmpty()) return null
        val downRaw = http.get(
            "https://lyrics.kugou.com/download?ver=1&client=pc&id=${urlEncode(id)}&accesskey=${urlEncode(accessKey)}&fmt=lrc",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://www.kugou.com/")
        ) ?: return null
        val content = try {
            JSONObject(downRaw).optString("content", "").takeIf { it.isNotBlank() } ?: return null
        } catch (_: Exception) {
            return null
        }
        val lrc = decodeB64(content) ?: return null
        if (lrc.isBlank()) return null
        return LyricDocument(lrcText = lrc, translatedText = null, source = "kugou")
    }

    private fun decodeB64(s: String): String? = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}