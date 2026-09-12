package com.shiyinplayer.data.metadata

import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.inject.Inject

/**
 * 网易云音乐数据源（163MusicLyrics 内置的网易云接口）：
 * 搜索、歌词（lrc + 翻译）、歌手信息。无需登录的公开接口。实现 [MetadataSource]。
 */
class NeteaseApi @Inject constructor(
    client: OkHttpClient,
    zt: ZeroTierManager
) : MetadataSource {

    override val id: String = "netease"
    override val displayName: String = "网易云"
    override val capabilities: Set<MetaCapability> =
        setOf(MetaCapability.LYRIC, MetaCapability.COVER, MetaCapability.ARTIST)

    private val http = HttpApiClient(client, zt)

    override suspend fun search(title: String, artist: String?): List<SongMatch> {
        val kw = listOf(title.trim(), artist?.trim()).filter { !it.isNullOrEmpty() }.joinToString(" ")
        if (kw.isBlank()) return emptyList()
        val body = "s=${urlEncode(kw)}&type=1&offset=0&limit=10"
        val raw = http.post(
            "https://music.163.com/api/cloudsearch/pc",
            body,
            headers = mapOf("User-Agent" to UA, "Referer" to "https://music.163.com/")
        ) ?: return emptyList()
        return try {
            val result = JSONObject(raw).optJSONObject("result") ?: return emptyList()
            val songs = result.optJSONArray("songs") ?: return emptyList()
            val list = ArrayList<SongMatch>()
            for (i in 0 until songs.length()) {
                val s = songs.getJSONObject(i)
                val id = s.optLong("id")
                if (id <= 0) continue
                val album = s.optJSONObject("album")
                val artists = s.optJSONArray("artists")
                val artistName = if (artists != null && artists.length() > 0) {
                    artists.getJSONObject(0).optString("name", "")
                } else ""
                list.add(
                    SongMatch(
                        source = "netease",
                        id = id.toString(),
                        title = s.optString("name", ""),
                        artist = artistName,
                        album = album?.optString("name"),
                        coverUrl = album?.optString("pic")
                            ?.takeIf { it.isNotBlank() }
                            ?: album?.optString("picUrl")?.takeIf { it.isNotBlank() },
                        artistAvatarUrl = null
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun lyric(match: SongMatch): LyricDocument? = lyricById(match.id)

    private fun lyricById(songId: String): LyricDocument? {
        val raw = http.get(
            "https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1&csrf_token=",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://music.163.com/")
        ) ?: return null
        return try {
            val json = JSONObject(raw)
            if (json.optInt("code", -1) != 200) return null
            val lrc = json.optJSONObject("lrc")?.optString("lyric", "").orEmpty()
            if (lrc.isBlank()) return null
            val tlyric = json.optJSONObject("tlyric")?.optString("lyric", "")
            LyricDocument(lrcText = lrc, translatedText = tlyric?.takeIf { it.isNotBlank() }, source = "netease")
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun artist(name: String): ArtistMetadata? = artistSearch(name)

    private fun artistSearch(artistName: String): ArtistMetadata? {
        val body = "s=${urlEncode(artistName)}&type=100&offset=0&limit=5"
        val raw = http.post(
            "https://music.163.com/api/cloudsearch/pc",
            body,
            headers = mapOf("User-Agent" to UA, "Referer" to "https://music.163.com/")
        ) ?: return null
        return try {
            val result = JSONObject(raw).optJSONObject("result") ?: return null
            val artists = result.optJSONArray("artists") ?: return null
            for (i in 0 until artists.length()) {
                val a = artists.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.equals(artistName, ignoreCase = true)) {
                    val detail = artistDetail(a.optLong("id").toString())
                    if (detail != null) return detail
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun artistDetail(id: String): ArtistMetadata? {
        val raw = http.get(
            "https://music.163.com/api/artist/$id",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://music.163.com/")
        ) ?: return null
        return try {
            val artist = JSONObject(raw).optJSONObject("artist") ?: return null
            ArtistMetadata(
                name = artist.optString("name", ""),
                avatarUrl = artist.optString("picUrl").takeIf { it.isNotBlank() },
                bio = artist.optString("briefDesc").takeIf { it.isNotBlank() }?.take(600)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
