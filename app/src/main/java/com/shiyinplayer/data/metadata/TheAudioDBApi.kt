package com.shiyinplayer.data.metadata

import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.inject.Inject

/**
 * TheAudioDB 数据源（西方兜底）：搜索 + 歌词 + 封面 + 年份（searchtrack.php 一次返回全部）。
 * 中文覆盖低，作兜底源使用。实现 [MetadataSource]。
 */
class TheAudioDBApi @Inject constructor(
    client: OkHttpClient,
    zt: ZeroTierManager
) : MetadataSource {

    override val id: String = "theaudiodb"
    override val displayName: String = "TheAudioDB"
    override val capabilities: Set<MetaCapability> =
        setOf(MetaCapability.LYRIC, MetaCapability.COVER, MetaCapability.YEAR, MetaCapability.ARTIST)

    private val http = HttpApiClient(client, zt)

    override suspend fun search(title: String, artist: String?): List<SongMatch> {
        val t = title.trim().ifBlank { return emptyList() }
        val a = artist?.trim().orEmpty()
        if (t.isBlank()) return emptyList()
        val raw = http.get(
            "https://theaudiodb.com/api/v1/json/2/searchtrack.php?s=${urlEncode(a)}&t=${urlEncode(t)}",
            headers = mapOf("User-Agent" to UA)
        ) ?: return emptyList()
        return try {
            val track = JSONObject(raw).optJSONArray("track") ?: return emptyList()
            val out = ArrayList<SongMatch>()
            for (i in 0 until track.length()) {
                val s = track.getJSONObject(i)
                out.add(
                    SongMatch(
                        source = "theaudiodb",
                        id = "",
                        title = s.optString("strTrack", ""),
                        artist = s.optString("strArtist", ""),
                        album = s.optString("strAlbum").takeIf { it.isNotBlank() },
                        coverUrl = s.optString("strTrackThumb").takeIf { it.isNotBlank() },
                        artistAvatarUrl = null,
                        year = s.optString("intYearReleased").toIntOrNull(),

                        // lyric 复用本接口（同曲搜索后取 strLyrics）
                        extra = mapOf("track" to s.optString("strTrack", ""), "artist" to s.optString("strArtist", ""))
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun lyric(match: SongMatch): LyricDocument? {
        val t = (match.extra?.get("track")?.takeIf { it.isNotBlank() } ?: match.title).trim()
        val a = (match.extra?.get("artist")?.takeIf { it.isNotBlank() } ?: match.artist).trim()
        if (t.isBlank()) return null
        val raw = http.get(
            "https://theaudiodb.com/api/v1/json/2/searchtrack.php?s=${urlEncode(a)}&t=${urlEncode(t)}",
            headers = mapOf("User-Agent" to UA)
        ) ?: return null
        return try {
            val track = JSONObject(raw).optJSONArray("track") ?: return null
            if (track.length() == 0) return null
            val lyric = track.getJSONObject(0).optString("strLyrics", "").takeIf { it.isNotBlank() } ?: return null
            LyricDocument(lrcText = lyric, translatedText = null, source = "theaudiodb")
        } catch (_: Exception) {
            null
        }
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}