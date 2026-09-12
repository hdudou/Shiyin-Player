package com.shiyinplayer.data.metadata

import android.util.Base64
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.inject.Inject

/**
 * QQ 音乐数据源（163MusicLyrics 内置的 QQ 音乐接口）：
 * 搜索、歌词（base64 解码）、封面/歌手图 URL 构建。实现 [MetadataSource]。
 */
class QQMusicApi @Inject constructor(
    client: OkHttpClient,
    zt: ZeroTierManager
) : MetadataSource {

    override val id: String = "qq"
    override val displayName: String = "QQ音乐"
    override val capabilities: Set<MetaCapability> =
        setOf(MetaCapability.LYRIC, MetaCapability.COVER, MetaCapability.ARTIST)

    private val http = HttpApiClient(client, zt)

    override suspend fun search(title: String, artist: String?): List<SongMatch> {
        val kw = listOf(title.trim(), artist?.trim()).filter { !it.isNullOrEmpty() }.joinToString(" ")
        if (kw.isBlank()) return emptyList()
        val raw = http.get(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${urlEncode(kw)}&format=json&n=10&p=1&t=0&cr=1",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://y.qq.com/")
        ) ?: return emptyList()
        return try {
            val song = JSONObject(raw).optJSONObject("data")?.optJSONObject("song") ?: return emptyList()
            val list = song.optJSONArray("list") ?: return emptyList()
            val out = ArrayList<SongMatch>()
            for (i in 0 until list.length()) {
                val s = list.getJSONObject(i)
                val mid = s.optString("songmid")
                if (mid.isEmpty()) continue
                val singer = s.optJSONArray("singer")
                val artistName = if (singer != null && singer.length() > 0) singer.getJSONObject(0).optString("name", "") else ""
                val singerMid = if (singer != null && singer.length() > 0) singer.getJSONObject(0).optString("mid", "") else ""
                val albumMid = s.optString("albummid")
                out.add(
                    SongMatch(
                        source = "qq",
                        id = mid,
                        title = s.optString("songname", ""),
                        artist = artistName,
                        album = s.optString("albumname").takeIf { it.isNotBlank() },
                        coverUrl = if (albumMid.isNotBlank()) "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg" else null,
                        artistAvatarUrl = if (singerMid.isNotBlank()) "https://y.gtimg.cn/music/photo_new/T001R300x300M000$singerMid.jpg" else null
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun lyric(match: SongMatch): LyricDocument? = lyricById(match.id)

    private fun lyricById(songMid: String): LyricDocument? {
        val raw = http.get(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songMid&format=json&nobase64=1&g_tk=5381",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://y.qq.com/portal/player.html")
        ) ?: return null
        return try {
            val json = JSONObject(raw)
            if (json.optInt("retcode", -1) != 0) return null
            val lyricB64 = json.optString("lyric", "")
            val transB64 = json.optString("trans", "")
            val lrc = decodeB64(lyricB64) ?: return null
            if (lrc.isBlank()) return null
            LyricDocument(
                lrcText = lrc,
                translatedText = decodeB64(transB64)?.takeIf { it.isNotBlank() },
                source = "qq"
            )
        } catch (_: Exception) {
            null
        }
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
