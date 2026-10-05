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

    /**
     * 歌手资料（需求③「能力按实际标记」）。
     *
     * 改造前本类声明了 [MetaCapability.ARTIST] 却**没有实现这个方法** —— 属于能力虚标：
     * 设置页的能力矩阵会显示它有「歌手」，getArtistInfo 也会按优先级白跑一轮。
     * 本次顺手补实现（而不是撤声明），因为需求①本来就要用 TheAudioDB 的中文简介。
     *
     * 注意与 searchtrack.php 的区别：那个接口只返回曲目、**不含**歌手头像/简介；
     * 歌手资料必须走 search.php（s=歌手名）→ strArtistThumb / strBiographyCN。
     * strBiographyCN 是现成的中文简介（实测有值），其它西方源很少给，故优先取它。
     */
    override suspend fun artist(name: String): ArtistMetadata? {
        val clean = name.trim().ifBlank { return null }
        val raw = http.get(
            "https://theaudiodb.com/api/v1/json/2/search.php?s=${urlEncode(clean)}",
            headers = mapOf("User-Agent" to UA)
        ) ?: return null
        return try {
            val artists = JSONObject(raw).optJSONArray("artists") ?: return null
            for (i in 0 until artists.length()) {
                val a = artists.getJSONObject(i)
                val thumb = a.optString("strArtistThumb").takeIf { it.isNotBlank() }
                val bio = a.optString("strBiographyCN").takeIf { it.isNotBlank() }
                    ?: a.optString("strBiographyEN").takeIf { it.isNotBlank() }
                if (thumb == null && bio == null) continue
                return ArtistMetadata(name = clean, avatarUrl = thumb, bio = bio)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}