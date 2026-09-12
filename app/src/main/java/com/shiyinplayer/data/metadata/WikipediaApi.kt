package com.shiyinplayer.data.metadata

import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.inject.Inject

/**
 * 维基百科数据源（兜底）：仅歌手简介（artist()），无歌词/封面。
 * 中文覆盖依赖条目存在性，作兜底源使用。实现 [MetadataSource]。
 */
class WikipediaApi @Inject constructor(
    client: OkHttpClient,
    zt: ZeroTierManager
) : MetadataSource {

    override val id: String = "wikipedia"
    override val displayName: String = "维基百科"
    override val capabilities: Set<MetaCapability> =
        setOf(MetaCapability.ARTIST)

    private val http = HttpApiClient(client, zt)

    override suspend fun search(title: String, artist: String?): List<SongMatch> = emptyList()

    override suspend fun lyric(match: SongMatch): LyricDocument? = null

    override suspend fun artist(name: String): ArtistMetadata? = artistLookup(name)

    private fun artistLookup(name: String): ArtistMetadata? {
        val clean = name.trim().ifBlank { return null }
        val raw = http.get(
            "https://zh.wikipedia.org/w/api.php?action=query&prop=extracts|pageimages&exintro&explaintext&format=json&redirects=1&pithumbsize=300&titles=${urlEncode(clean)}",
            headers = mapOf("User-Agent" to UA)
        ) ?: return null
        return try {
            val query = JSONObject(raw).optJSONObject("query") ?: return null
            val pages = query.optJSONObject("pages") ?: return null
            val keys = pages.keys()
            while (keys.hasNext()) {
                val page = pages.optJSONObject(keys.next()) ?: continue
                val title = page.optString("title", "")
                if (page.optInt("missing", 0) == 1 || title.isBlank()) continue
                val bio = page.optString("extract", "").takeIf { it.isNotBlank() }?.trim()?.take(600)
                val thumb = page.optJSONObject("thumbnail")?.optString("source")
                return ArtistMetadata(
                    name = title,
                    avatarUrl = thumb?.takeIf { it.isNotBlank() },
                    bio = bio
                )
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