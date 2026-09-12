package com.shiyinplayer.data.metadata

import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.inject.Inject

/**
 * Genius 数据源（西方兜底）：搜索 + 歌词（embed.js）。
 * 中文覆盖低、embed.js 解析脆弱，作兜底源使用。实现 [MetadataSource]。
 */
class GeniusApi @Inject constructor(
    client: OkHttpClient,
    zt: ZeroTierManager
) : MetadataSource {

    override val id: String = "genius"
    override val displayName: String = "Genius"
    override val capabilities: Set<MetaCapability> =
        setOf(MetaCapability.LYRIC, MetaCapability.COVER, MetaCapability.ARTIST)

    private val http = HttpApiClient(client, zt)

    override suspend fun search(title: String, artist: String?): List<SongMatch> {
        val kw = listOf(title.trim(), artist?.trim()).filter { !it.isNullOrEmpty() }.joinToString(" ")
        if (kw.isBlank()) return emptyList()
        val raw = http.get(
            "https://genius.com/api/search/song?q=${urlEncode(kw)}",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://genius.com/")
        ) ?: return emptyList()
        return try {
            val sections = JSONObject(raw).optJSONObject("response")?.optJSONArray("sections") ?: return emptyList()
            val out = ArrayList<SongMatch>()
            for (i in 0 until sections.length()) {
                val hits = sections.getJSONObject(i).optJSONArray("hits") ?: continue
                for (j in 0 until hits.length()) {
                    val result = hits.getJSONObject(j).optJSONObject("result") ?: continue
                    val id = result.optLong("id")
                    if (id <= 0) continue
                    val artist = result.optJSONObject("primary_artist")
                    out.add(
                        SongMatch(
                            source = "genius",
                            id = id.toString(),
                            title = result.optString("title", ""),
                            artist = artist?.optString("name", "").orEmpty(),
                            album = null,
                            coverUrl = result.optString("song_art_image_url").takeIf { it.isNotBlank() },
                            artistAvatarUrl = artist?.optString("image_url")
                            ?.takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun lyric(match: SongMatch): LyricDocument? {
        val raw = http.get(
            "https://genius.com/songs/${match.id}/embed.js",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://genius.com/")
        ) ?: return null
        val text = extractLyrics(raw) ?: return null
        return LyricDocument(lrcText = text, translatedText = null, source = "genius")
    }

    override suspend fun artist(name: String): ArtistMetadata? = artistLookup(name)

    private fun artistLookup(artistName: String): ArtistMetadata? {
        val raw = http.get(
            "https://genius.com/api/artists?q=${urlEncode(artistName)}",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://genius.com/")
        ) ?: return null
        return try {
            val sections = JSONObject(raw).optJSONObject("response")?.optJSONArray("sections") ?: return null
            for (i in 0 until sections.length()) {
                val hits = sections.getJSONObject(i).optJSONArray("hits") ?: continue
                for (j in 0 until hits.length()) {
                    val r = hits.getJSONObject(j).optJSONObject("result") ?: continue
                    if (r.optString("name", "").equals(artistName, ignoreCase = true)) {
                        return ArtistMetadata(
                            name = r.optString("name", ""),
                            avatarUrl = r.optString("image_url").takeIf { it.isNotBlank() },
                            bio = r.optString("url").takeIf { it.isNotBlank() }
                        )
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractLyrics(raw: String): String? {
        return try {
            // embed.js 内嵌 JSON：取 "lyrics":{"text": "..."} 字段
            val start = raw.indexOf("\"text\":\"")
            if (start < 0) return null
            val sb = StringBuilder()
            var i = start + "\"text\":\"".length
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\') {
                    when (raw.getOrNull(i + 1)) {
                        'n' -> { sb.append('\n'); i += 2; continue }
                        '"' -> { sb.append('"'); i += 2; continue }
                        '\\' -> { sb.append('\\'); i += 2; continue }
                        else -> { sb.append('\\'); i += 1; continue }
                    }
                } else if (c == '"') {
                    break
                } else {
                    sb.append(c)
                    i += 1
                }
            }
            val text = sb.toString().replace(Regex("<br/?>"), "\n")
                .replace(Regex("<[^>]*>"), "")
                .replace(Regex("\\[Verse \\d+:?.*?\\]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\[Chorus.*?\\]", RegexOption.IGNORE_CASE), "")
                .trim()
            text.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}