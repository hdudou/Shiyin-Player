package com.shiyinplayer.data.metadata

import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.inject.Inject

/**
 * 咪咕音乐数据源（music-tag-web 同款中文源）：搜索 + 歌词（getLyric 按 copyrightId）。
 * 实现 [MetadataSource]。
 */
class MiguApi @Inject constructor(
    client: OkHttpClient,
    zt: ZeroTierManager
) : MetadataSource {

    override val id: String = "migu"
    override val displayName: String = "咪咕"
    override val capabilities: Set<MetaCapability> =
        setOf(MetaCapability.LYRIC, MetaCapability.COVER)

    private val http = HttpApiClient(client, zt)

    override suspend fun search(title: String, artist: String?): List<SongMatch> {
        val kw = listOf(title.trim(), artist?.trim()).filter { !it.isNullOrEmpty() }.joinToString(" ")
        if (kw.isBlank()) return emptyList()
        val raw = http.get(
            "https://m.music.migu.cn/migu/remoting/scr_search_tag?rows=10&type=2&keyword=${urlEncode(kw)}&pgc=1",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://m.music.migu.cn/")
        ) ?: return emptyList()
        return try {
            val musics = JSONObject(raw).optJSONArray("musics") ?: return emptyList()
            val out = ArrayList<SongMatch>()
            for (i in 0 until musics.length()) {
                val m = musics.getJSONObject(i)
                val cid = m.optString("copyrightId")
                if (cid.isEmpty()) continue
                val imgs = m.optJSONArray("albumImgs")
                val cover = if (imgs != null && imgs.length() > 0) imgs.getJSONObject(0).optString("img") else null
                out.add(
                    SongMatch(
                        source = "migu",
                        id = cid,
                        title = m.optString("songName", ""),
                        artist = m.optString("singerName", ""),
                        album = m.optString("albumName").takeIf { it.isNotBlank() },
                        coverUrl = cover?.takeIf { it.isNotBlank() },
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
            "https://music.migu.cn/v3/api/music/audioPlayer/getLyric?copyrightId=${match.id}",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://music.migu.cn/")
        ) ?: return null
        return try {
            val lrc = JSONObject(raw).optString("lyric", "").takeIf { it.isNotBlank() } ?: return null
            LyricDocument(lrcText = lrc, translatedText = null, source = "migu")
        } catch (_: Exception) {
            null
        }
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}