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
                        artistAvatarUrl = qqArtistAvatarUrlByMid(singerMid)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun lyric(match: SongMatch): LyricDocument? = lyricById(match.id)

    /**
     * 歌手资料（需求③「能力按实际标记」）。
     *
     * 改造前本类声明了 [MetaCapability.ARTIST] 却**没有实现这个方法** —— 与 TheAudioDB 同一类能力虚标
     * （那是 B1 修的）：设置页的能力矩阵会显示它有「歌手」，`getArtistInfo` 也会按优先级白跑一轮。
     * 注意 `SongMatch.artistAvatarUrl` **顶不上这个入口** —— 它全仓**只写不读**，界面上的歌手头像
     * 只来自 `MetadataRepository.getArtistInfo`。
     *
     * QQ 没有独立的歌手资料接口，但歌曲搜索响应里的 `singer[].mid` 能直接拼出歌手图，
     * 所以按「歌手名当关键词搜歌 → 取名字对得上的那个 singer → 拼头像」实现。
     * 与 PC 端 `QqMusicMetadataProvider.LookupArtistAsync` **同一套规则**，两端刮到的头像一致。
     * QQ 不提供简介，`bio` 恒为 null（有头像就够，编排层只要求二者之一非空）。
     */
    override suspend fun artist(name: String): ArtistMetadata? {
        val clean = name.trim().ifBlank { return null }
        val raw = http.get(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${urlEncode(clean)}&format=json&n=10&p=1&t=0&cr=1",
            headers = mapOf("User-Agent" to UA, "Referer" to "https://y.qq.com/")
        ) ?: return null
        return try {
            val list = JSONObject(raw).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                ?: return null
            val candidates = ArrayList<Pair<String, String>>()
            for (i in 0 until list.length()) {
                val singers = list.getJSONObject(i).optJSONArray("singer") ?: continue
                for (j in 0 until singers.length()) {
                    val s = singers.getJSONObject(j)
                    candidates.add(s.optString("name", "") to s.optString("mid", ""))
                }
            }
            val avatar = qqArtistAvatarUrl(candidates, clean) ?: return null
            ArtistMetadata(name = clean, avatarUrl = avatar, bio = null)
        } catch (_: Exception) {
            null
        }
    }

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

/**
 * 歌手图地址（`T001` = 歌手图、`T002` = 专辑图）。
 * 与 PC 端 `QqMusicMetadataProvider.ArtistAvatarTemplate` **必须逐字一致** ——
 * 拼法一旦不同，同一个歌手在两端就会刮到两张不同的图。
 */
internal fun qqArtistAvatarUrlByMid(mid: String): String? =
    mid.trim().takeIf { it.isNotEmpty() }
        ?.let { "https://y.gtimg.cn/music/photo_new/T001R300x300M000$it.jpg" }

/**
 * 在「(歌手名, mid)」候选里挑出**名字对得上**的那一个并拼出头像地址；找不到返回 null。
 *
 * 独立成纯函数是为了能进 JVM 单测：provider 本体依赖 OkHttp 与 `org.json`（在 JVM 单测里是 stub），
 * 而真正容易出错的是「该挑哪一个」这条规则。
 * 必须逐个比对、**不能只看第一个**：合唱曲目（「周杰伦 / 费玉清」）的第一位未必是目标歌手，
 * 取错就是把人家的头像挂到这个歌手名下。
 */
internal fun qqArtistAvatarUrl(candidates: List<Pair<String, String>>, expectedName: String): String? {
    val want = expectedName.trim()
    if (want.isEmpty()) return null
    for ((name, mid) in candidates) {
        if (name.trim().equals(want, ignoreCase = true)) {
            qqArtistAvatarUrlByMid(mid)?.let { return it }
        }
    }
    return null
}
