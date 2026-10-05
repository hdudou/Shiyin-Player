package com.shiyinplayer.data.repository

import com.shiyinplayer.data.local.cache.LyricCacheEntity
import com.shiyinplayer.data.local.cache.LyricCacheDao
import com.shiyinplayer.data.local.cache.MetadataCacheDao
import com.shiyinplayer.data.local.cache.MetadataCacheEntity
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.metadata.ArtistMetadata
import com.shiyinplayer.data.metadata.LocalLyricLoader
import com.shiyinplayer.data.metadata.LyricDocument
import com.shiyinplayer.data.metadata.MetaCapability
import com.shiyinplayer.data.metadata.MetadataCacheType
import com.shiyinplayer.data.metadata.SongMatch
import com.shiyinplayer.data.metadata.SongMetadata
import com.shiyinplayer.data.metadata.SourceRegistry
import com.shiyinplayer.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 各数据源返回值（repository 聚合层）。 */
data class LyricsFetchResult(
    val document: LyricDocument,
    val cached: Boolean
)

data class ArtistInfo(
    val artist: ArtistMetadata?,
    val cached: Boolean
)

data class SongInfo(
    val song: SongMetadata?,
    val cached: Boolean
)

/** 2026-08-19 需求4：歌词搜索候选（供用户手工匹配挑选）。 */
data class LyricCandidate(
    val title: String,
    val artist: String?,
    val source: String,
    val doc: LyricDocument
)

/**
 * 歌词与元数据聚合仓库（§3.3，重构自原 5 源硬编码）：
 * 经 [SourceRegistry] 按用户配置的统一顺序做多源兜底 + Room 缓存。
 * 源集合：网易云/QQ/酷我/咪咕/酷狗（中文）+ Genius/TheAudioDB/维基（西方兜底）。
 */
@Singleton
class MetadataRepository @Inject constructor(
    private val registry: SourceRegistry,
    private val localLyrics: LocalLyricLoader,
    private val lyricCacheDao: LyricCacheDao,
    private val metadataCacheDao: MetadataCacheDao,
    private val songDao: SongDao
) {
    /**
     * 缓存新鲜度 —— **只剩曲目标签缓存（`getSongInfo`）还在用**。
     *
     * 需求⑤ / D7=A 点名的「永不过期」是**图片与歌词**：歌词、`getAlbumCover`、`getArtistInfo`
     * 现在都不再看时间（见各自的注释）。`getSongInfo` 是**曲目标签**（专辑名 / 年份），
     * 不在其列 —— 用户可能在别处改过标签，留着 TTL 才有机会自动刷新一次。
     */
    private val cacheTtlMs = 7 * 24 * 60 * 60 * 1000L

    /** 源可靠度权重（中文源偏高，西方源兜底偏低），用于 pick 评分。 */
    private val reliability = mapOf(
        "netease" to 3, "qq" to 3, "kuwo" to 2, "migu" to 2, "kugou" to 2,
        "theaudiodb" to 1, "genius" to 1, "wikipedia" to 0
    )

    /**
     * 2026-08-19 需求：取歌词（本地/缓存/在线）。
     * - song 非空时**优先按 songId 查绑定缓存**——命中即用，**永不过期**（需求：同一首歌的元数据不会过期）；
     * - 未绑定时回退按 key（title|artist）查（旧数据，保留 TTL）；
     * - 在线获取成功后**同时写 songId + key**（绑定 + 兼容 title|artist 匹配场景）。
     */
    suspend fun getLyrics(title: String, artist: String?, song: Song? = null, force: Boolean = false, online: Boolean = true): LyricsFetchResult? =
        withContext(Dispatchers.IO) {
            val key = songKey(title, artist)
            // 1) 优先：与歌曲条目绑定（songId 命中即用，不过期）
            if (song != null && song.id > 0 && !force) {
                val bound = lyricCacheDao.getBySongId(song.id)
                if (bound != null) {
                    return@withContext LyricsFetchResult(
                        LyricDocument(bound.lrcText, bound.translatedText, bound.source),
                        cached = true
                    )
                }
            }
            // 2) 回退：按 title|artist key（旧缓存）
            //    需求⑤/D7：**歌词永不过期** —— 原来这里有 7 天 TTL，到点就删条目、逼得重新联网取一次；
            //    现在命中即用（`force` 仍可强制重取）。下架时机只有两个：曲目被删（MediaCacheCleaner）、
            //    以及用户主动重新匹配。
            val cached = lyricCacheDao.get(key)
            if (!force && cached != null) {
                // 2026-08-19 需求：命中旧缓存时若当前歌曲未绑定则补绑 songId（此后按 songId 命中）
                if (song != null && song.id > 0 && cached.songId == null) {
                    lyricCacheDao.bindSong(key, song.id)
                }
                return@withContext LyricsFetchResult(
                    LyricDocument(cached.lrcText, cached.translatedText, cached.source),
                    cached = true
                )
            }
            // 3) 本地文件内嵌/侧车 .lrc 优先
            // 来源标识如实区分 embedded / sidecar（需求⑧：与 PC 端 OnlineSourceIds 同一套取值）。
            // 改造前这里写死 "local"，同步到 PC 后会被当成未知来源 —— 两端对不上就是来源漂移。
            if (song != null && !force) {
                val local = localLyrics.loadWithSource(song)
                if (local != null && local.text.isNotBlank()) {
                    lyricCacheDao.upsert(
                        LyricCacheEntity(key, local.text, null, local.source, System.currentTimeMillis(), song.id)
                    )
                    return@withContext LyricsFetchResult(LyricDocument(local.text, null, local.source), cached = false)
                }
            }
            // 4) 在线多源（统一顺序兜底）；online=false（如手动批量同步期间）时不联网，只走本地/缓存
            if (online) {
                val doc = fetchFromSources(title, artist) ?: return@withContext null
                val songId = song?.takeIf { it.id > 0 }?.id
                lyricCacheDao.upsert(LyricCacheEntity(key, doc.lrcText, doc.translatedText, doc.source, System.currentTimeMillis(), songId))
                LyricsFetchResult(doc, cached = false)
            } else {
                null
            }
        }

    /**
     * 2026-08-19 需求4：保存指定歌词到当前歌曲（手动匹配结果）——同时写 songId 绑定 + key 兜底，
     * 绑定后播放该曲目直接命中（永不过期）。
     */
    suspend fun saveLyricForSong(song: Song, doc: LyricDocument) {
        withContext(Dispatchers.IO) {
            val key = songKey(song.title, song.artistName)
            lyricCacheDao.upsert(
                LyricCacheEntity(key, doc.lrcText, doc.translatedText, doc.source, System.currentTimeMillis(), song.id)
            )
            if (song.id > 0) lyricCacheDao.bindSong(key, song.id)
        }
    }

    /** 2026-08-19 需求4：按标题/歌手搜索歌词候选（供用户手工挑选）。
     *  只查具备歌词能力的源，且**并发**执行——避免海外源（Genius 等）串行超时拖慢整个搜索。 */
    suspend fun searchLyricCandidates(title: String, artist: String?): List<LyricCandidate> =
        withContext(Dispatchers.IO) {
            val sources = registry.orderedEnabled().filter { MetaCapability.LYRIC in it.capabilities }
            sources.map { src ->
                async {
                    try {
                        val candidates = src.search(title, artist)
                        val match = pick(candidates, title, artist) ?: return@async null
                        val doc = src.lyric(match) ?: return@async null
                        if (doc.lrcText.isNotBlank()) {
                            LyricCandidate(title = match.title, artist = match.artist, source = doc.source, doc = doc)
                        } else null
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

    private suspend fun fetchFromSources(title: String, artist: String?): LyricDocument? {
        val sources = registry.orderedEnabled()
        for (src in sources) {
            try {
                val candidates = src.search(title, artist)
                val match = pick(candidates, title, artist) ?: continue
                val doc = src.lyric(match) ?: continue
                if (doc.lrcText.isNotBlank()) return doc
            } catch (_: Exception) {
                // 单源失败不影响其它源
            }
        }
        return null
    }

    /** 歌曲附加信息（封面 + 专辑名 + 年份）。 */
    suspend fun getSongInfo(title: String, artist: String?): SongInfo = withContext(Dispatchers.IO) {
        val key = "song|" + songKey(title, artist)
        val cached = metadataCacheDao.get(key, MetadataCacheType.SONG)
        if (cached != null && System.currentTimeMillis() - cached.updatedAt < cacheTtlMs) {
            val meta = MetadataCacheType.decodeSong(cached.payload)
            return@withContext SongInfo(meta, cached = true)
        } else if (cached != null) {
            // DC：读到期即删，防止过期缓存永久堆积
            metadataCacheDao.deleteByKey(key, MetadataCacheType.SONG)
        }
        var meta: SongMetadata? = null
        val all = ArrayList<SongMatch>()
        for (src in registry.orderedEnabled()) {
            try { all += src.search(title, artist) } catch (_: Exception) { }
        }
        val best = pick(all, title, artist)
        if (best != null) {
            meta = SongMetadata(best.album, best.coverUrl, best.year)
            metadataCacheDao.upsert(
                MetadataCacheEntity(key, MetadataCacheType.SONG, MetadataCacheType.encodeSong(meta), System.currentTimeMillis())
            )
        }
        SongInfo(meta, cached = false)
    }

    /** 专辑封面（专辑详情页）：按专辑名 + 歌手搜索，取封面。 */
    suspend fun getAlbumCover(albumName: String, artist: String?): SongMetadata? = withContext(Dispatchers.IO) {
        val key = "album|" + songKey(albumName, artist)
        val cached = metadataCacheDao.get(key, MetadataCacheType.ALBUM)
        // 需求⑤/D7：**封面永不过期**，命中即用（不再按 cacheTtlMs 判新鲜度）
        if (cached != null) {
            return@withContext MetadataCacheType.decodeSong(cached.payload)
        }
        val album = albumName.trim()
        var bestCover: String? = null
        val all = ArrayList<SongMatch>()
        for (src in registry.orderedEnabled()) {
            try { all += src.search(albumName, artist) } catch (_: Exception) { }
        }
        val match = all.firstNotNullOfOrNull { m ->
            val albumOk = m.album?.contains(album, ignoreCase = true) == true || album.contains(m.album.orEmpty(), ignoreCase = true)
            m.coverUrl?.takeIf { albumOk }
        }
        if (match != null) {
            bestCover = match
            val meta = SongMetadata(albumName, bestCover)
            metadataCacheDao.upsert(MetadataCacheEntity(key, MetadataCacheType.ALBUM, MetadataCacheType.encodeSong(meta), System.currentTimeMillis()))
            meta
        } else null
    }

    /**
     * 歌手信息（头像 + 简介）。
     *
     * 取源规则（1.0.16 与 PC 端 `OnlineMetadataService.LookupArtistAsync` **对齐**）：
     * **按字段分别取首家**，而不是"整条取首家" —— 头像取第一家能出图的，
     * 简介单独取第一家能出简介的（**可以来自不同源**）。
     *
     * 为什么必须拆开（实测结论，不是设计洁癖）：各源分布是**互补**的 ——
     * 网易云 / QQ 有中文歌手的**头像**但**没有简介**（网易云 `briefDesc` 恒为空串、
     * QQ 压根没这个字段），TheAudioDB 恰好相反（有 `strBiographyCN` 中文简介，
     * 但对中文歌手命中率仅约 17%）。
     * 若仍按"整条取首家"，网易云一旦给出头像就会**短路掉 TheAudioDB 的简介** ——
     * 就成了「明明接了 TheAudioDB 却永远拿不到它的中文简介」，直接违背需求①。
     */
    suspend fun getArtistInfo(artistName: String): ArtistInfo = withContext(Dispatchers.IO) {
        val key = "artist|" + songKey(artistName, null)
        val cached = metadataCacheDao.get(key, MetadataCacheType.ARTIST)
        // 需求⑤/D7：**歌手头像/简介永不过期**，命中即用
        if (cached != null) {
            val artist = MetadataCacheType.decodeArtist(cached.payload)
            return@withContext ArtistInfo(artist, cached = true)
        }

        var avatar: String? = null
        var bio: String? = null
        for (src in registry.orderedEnabled()) {
            if (!src.capabilities.contains(com.shiyinplayer.data.metadata.MetaCapability.ARTIST)) continue
            if (avatar != null && bio != null) break      // 两个字段都齐了就不用再问
            val one = try {
                src.artist(artistName)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue

            if (avatar == null && !one.avatarUrl.isNullOrBlank()) avatar = one.avatarUrl
            if (bio == null && !one.bio.isNullOrBlank()) bio = one.bio
        }

        // 一个字段都没拿到 = 本次没结果（落 null，下次再试），不要往缓存里写空壳
        val artist = if (avatar == null && bio == null) null else ArtistMetadata(artistName, avatar, bio)
        if (artist != null) {
            metadataCacheDao.upsert(
                MetadataCacheEntity(key, MetadataCacheType.ARTIST, MetadataCacheType.encodeArtist(artist), System.currentTimeMillis())
            )
        }
        ArtistInfo(artist, cached = false)
    }

    /** 将封面写回主库曲目（供列表封面使用）。 */
    suspend fun applyAlbumArt(songId: Long, coverUrl: String) {
        if (songId > 0 && coverUrl.isNotBlank()) {
            try {
                songDao.setAlbumArt(songId, coverUrl)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // 封面回写失败不阻断
            }
        }
    }

    /** §12 R3：持久化某曲目的歌词时间偏移（仅写 DB，叠加到所有歌词行展示时间）。 */
    suspend fun setLyricOffset(songId: Long, ms: Long) {
        if (songId <= 0) return
        try {
            songDao.setLyricOffset(songId, ms)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { }
    }

    /** 清理过期缓存（P3：按天数）。 */
    suspend fun pruneCache(days: Int) {
        val before = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000L
        try {
            lyricCacheDao.prune(before)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { }
        try {
            metadataCacheDao.prune(before)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { }
    }

    /** 清空歌词与元数据缓存（P3：缓存清理入口）。 */
    suspend fun clearCache() {
        try {
            lyricCacheDao.clearAll()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { }
        try {
            metadataCacheDao.clearAll()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { }
    }

    /** 挑选与「标题+歌手」匹配度最高的条目，优先带封面，并按源可靠度加权；透传 year。 */
    private fun pick(matches: List<SongMatch>, title: String, artist: String?): SongMatch? {
        val t = title.trim()
        val a = artist?.trim()
        val scored = matches.mapNotNull { m ->
            val titleOk = t.isBlank() || m.title.contains(t, ignoreCase = true) || t.contains(m.title, ignoreCase = true)
            if (!titleOk) return@mapNotNull null
            var score = reliability[m.source] ?: 0
            if (a != null && a.isNotBlank()) {
                val artistOk = m.artist.contains(a, ignoreCase = true) || a.contains(m.artist, ignoreCase = true)
                if (!artistOk) score -= 3
            }
            if (m.title.equals(t, ignoreCase = true)) score += 2
            if (m.artist.equals(a, ignoreCase = true)) score += 2
            if (m.coverUrl != null) score += 1
            // 需求优化：同一曲目多候选时，字段（名称/歌手/专辑/年份/封面）更齐全的项优先。
            val completeness = listOfNotNull(
                m.title.takeIf { it.isNotBlank() },
                m.artist.takeIf { it.isNotBlank() },
                m.album?.takeIf { it.isNotBlank() },
                m.year,
                m.coverUrl?.takeIf { it.isNotBlank() }
            ).size
            score += completeness * 2
            score to m
        }
        return scored.maxByOrNull { it.first }?.second
    }

    /**
     * [7] 在线候选搜索：聚合多源匹配结果，去重后返回供用户挑选（手工匹配）。
     * 搜索按「标题 + 歌手」双维度（§0.1 A）。
     */
    suspend fun searchCandidates(title: String, artist: String?): List<SongMatch> = withContext(Dispatchers.IO) {
        val t = title.trim()
        if (t.isBlank()) return@withContext emptyList()
        val all = ArrayList<SongMatch>()
        for (src in registry.orderedEnabled()) {
            try { all += src.search(t, artist?.trim()) } catch (_: Exception) { }
        }
        val seen = LinkedHashSet<String>()
        all.filter { it.title.isNotBlank() }.mapNotNull { m ->
            val key = "${m.source}|${m.title.lowercase()}|${m.artist.lowercase()}"
            if (!seen.add(key)) null else m
        }
    }

    /** [8] 自动同步用：在线搜索 + 自动评分选最佳（不需用户挑选）。无候选/无匹配返回 null。 */
    suspend fun autoMatchBest(title: String, artist: String?): SongMatch? {
        val candidates = searchCandidates(title, artist)
        return pick(candidates, title, artist)
    }

    /** [7] 将用户选定的在线匹配写回主库曲目（标题/艺术家/专辑 + 封面 + 年份，仅写 DB）。 */
    suspend fun applyMatch(songId: Long, match: SongMatch) {
        if (songId <= 0) return
        try {
            songDao.updateMetadataWithYear(songId, match.title, match.artist, match.album, match.year)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { }
        val cover = try {
            getAlbumCover(match.album ?: "", match.artist)?.coverUrl
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (!cover.isNullOrBlank()) {
            try {
                songDao.setAlbumArt(songId, cover)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }

    companion object {
        fun songKey(title: String?, artist: String?): String =
            "${title?.trim().orEmpty()}|${artist?.trim().orEmpty()}".lowercase()
    }
}
