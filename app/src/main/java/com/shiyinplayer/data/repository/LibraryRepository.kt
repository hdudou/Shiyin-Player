package com.shiyinplayer.data.repository

import androidx.room.withTransaction
import com.shiyinplayer.data.local.dao.AlbumDao
import com.shiyinplayer.data.local.dao.ArtistDao
import com.shiyinplayer.data.local.dao.DuplicateGroupRow
import com.shiyinplayer.data.local.dao.MergedAltRow
import com.shiyinplayer.data.local.dao.MergedPrimaryRow
import com.shiyinplayer.data.local.dao.MusicSourceDao
import com.shiyinplayer.data.local.dao.PlayStatsRow
import com.shiyinplayer.data.local.dao.PlaylistItemDao
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.local.entity.SongEntity
import com.shiyinplayer.data.cache.MusicCacheManager
import com.shiyinplayer.data.mapper.EntityMappers.toEntity
import com.shiyinplayer.data.mapper.EntityMappers.toModel
import com.shiyinplayer.data.util.SongSearchKey
import com.shiyinplayer.data.media.LibraryScanner
import com.shiyinplayer.data.media.FolderStructureBuilder
import com.shiyinplayer.data.media.ScanMode
import com.shiyinplayer.data.model.Album
import com.shiyinplayer.data.model.Artist
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.PlayStats
import com.shiyinplayer.data.model.SearchField
import com.shiyinplayer.data.model.MusicSource
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.util.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 曲库仓库：暴露各视图所需 Flow，并负责音乐源 CRUD 与扫描合并。
 * 多源扫描结果按 (sourceType, uri/path) 由 [LibraryScanner] 去重 upsert。
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val db: com.shiyinplayer.data.local.AppDatabase,
    private val songDao: SongDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val musicSourceDao: MusicSourceDao,
    private val playlistItemDao: PlaylistItemDao,
    private val scanner: LibraryScanner,
    private val folderStructure: FolderStructureBuilder,
    private val cacheManager: MusicCacheManager,
    private val dispatcher: DispatcherProvider,
    // AZ-删除对账：删曲/删源写库后通知播放器剔除内存队列失效曲目（解耦，避免依赖环）。
    private val queueReconciler: com.shiyinplayer.player.QueueReconciler,
    @ApplicationContext private val context: Context
) {
    /**
     * 最近一次成功加载的合并歌单快照（内存缓存）。切前/后台或同步写盘时用于「秒供列表」：
     * 订阅端重新开始收集时先立即发射快照（免去 15k 行全量分页重读的等待），后台再异步刷新，
     * 消除「同步任务存在时从后台切前台列表十几秒不显示」的卡顿。
     */
    @Volatile private var lastSongsSnapshot: List<Song>? = null

    /** 与 [lastSongsSnapshot] 对应的 songs 表行数。切回前台重收集时用于判定数据是否真的变化，
     *  未变化则复用同一快照实例（同一引用 → Compose 不重组，消除「后台切前台闪一下」）。 */
    @Volatile private var lastLoadedCount: Int? = null

    /** [getSongs] 的触发去抖：同步/扫描写盘常密集变更 songs 表，去抖合并突发重读，避免每写一次全量表读。 */
    private val SONG_TRIGGER_DEBOUNCE_MS = 600L

    /**
     * 曲库全部歌曲（多源合并）。[8] SQL 层合并：主行查询（每组一行，SQL 内选主）+ 备选行查询
     * （每组全部 uri/类型/id），Kotlin 仅对 ~15k 组做轻量拼装，替代原先 79k 行全量实体映射与
     * 逐行字符串建键，显著降低首屏与每次数据变更后的重组开销。
     *
     * 2026-08-19 修复（Room 2.6.1 CursorWindow 崩溃）：原实现 combine(observeMergedPrimaries,
     * observeMergedAlts) 在歌曲 >700 首时，两个 Flow 查询结果超过单个 CursorWindow 容量且并发
     * fillWindow 竞争 → 越界读（`Couldn't read row 719, col 11 from CursorWindow`）→ getString null
     * → Room 非空映射 NPE（MergedAltRow mergeKey）闪退。现改为 [observeSongsCount]（1 行触发器）
     * 驱动 + 顺序执行两个 suspend 合并查询（CoroutinesRoom.execute 路径，SQLiteCursor 正常翻页，
     * 且串行执行消除并发窗口竞争）。
     *
     * 2026-08-23：改为「内存快照首发 + 去抖重读」——订阅起 onStart 立刻发射 [lastSongsSnapshot]（秒供旧列表），
     * 其后数据变更经 600ms 去抖合并再由 mapLatest 全量重读并刷新快照，兼顾首屏秒开与数据新鲜度。
     * （首次无快照时 onStart 不发射则自然等待首读；mapLatest 为顶层运算符，避免在自定义 flow{} 内层嵌套 emit
     * 引发的 Flow-invariant 崩溃。）
     * 2026-08-23 闪烁修复：mapLatest 前比对 [lastLoadedCount]，表行数未变则直接复用同一快照实例
     * （同一引用 → Compose 不重新组合），避免后台→前台重收集时对同一份数据重读并替换为新实例导致「闪一下」。
     */
    fun getSongs(): Flow<List<Song>> =
        combine(songDao.observeSongsCount(), songsRefreshTick) { count, _ -> count }
            .debounce(SONG_TRIGGER_DEBOUNCE_MS)
            .mapLatest { loadSongsIfChanged(it) }
            .flowOn(dispatcher.io)
            .onStart { lastSongsSnapshot?.let { emit(it) } }

    /// [getSongs] 的就地元数据写回脏标记：在线匹配/手工编辑是 UPDATE（行数不变），
    // 若仅靠「行数比较」会在「行数未变即复用快照」处被短接、列表永不重读新数据；置脏后下轮 count 触发即强制重读。
    @Volatile private var songsDirty = false

    /** 强制刷新触发器：置脏后递增，驱动 [getSongs] 立即重读。UPDATE 不改表行数、observeSongsCount
     *  不会重发，仅靠脏标记会一直等不到触发（表现为「时好时坏」）；把该 tick 与 count 组合后，
     *  在线匹配/手工编辑写回多少条都能确定性触发一次全量重读。 */
    private val songsRefreshTick = MutableStateFlow(0)

    /** 表行数未变（且已有快照、未置脏）时复用原快照实例；置脏或行数变化则全量重读并更新快照与计数。 */
    private suspend fun loadSongsIfChanged(count: Int): List<Song> {
        val cached = lastSongsSnapshot
        if (!songsDirty && count == lastLoadedCount && cached != null) return cached
        songsDirty = false
        val list = loadAllMergedSongs()
        lastSongsSnapshot = list
        lastLoadedCount = count
        return list
    }

    /** 使歌曲列表快照失效并置脏：元数据就地写回（在线匹配/手工编辑等 UPDATE 不改变表行数）后必须调用，
     *  否则「行数未变即复用快照」会把就地更新冲掉、列表永不刷新新内容（含文件信息弹窗读到的旧 song 对象）。
     *  脏标记持久保留，直到下一轮 count 触发才被消费并强制重读，规避 Room 异步重发的时序竞态。
     *  注：不得在自动/批量同步的逐条写回中调用（放大曲库全量重读 IO），仅用于用户触发的单条匹配/编辑。 */
    fun invalidateSongsSnapshot() {
        songsDirty = true
        songsRefreshTick.value++
    }

    /**
     * 冷启动预热：后台一次性读取曲库快照，使后续任意页面订阅 [getSongs] 时
     * [lastSongsSnapshot] 已就绪，列表秒出（无白屏等待）。
     * 由 [MusicPlayerApplication.onCreate] 在 IO 协程中调用，不阻塞主线程。
     */
    suspend fun preloadSnapshot() {
        if (lastSongsSnapshot != null) return
        val count = songDao.observeSongsCount().first()
        if (count > 0) loadSongsIfChanged(count)
    }

    /**
     * 2026-08-22：大库分页修复。原实现一次 getMergedPrimariesOnce + getMergedAltsOnce，上万行×30列 +
     * GROUP_CONCAT 巨串在同一周期填进单个 CursorWindow（约 2MB）→ `Failed NO_MEMORY` → 列表/播放链路断裂
     * （真机特征：SongDao_Impl 生成的 Callable 读 row=17832 时 `Couldn't read row ... from CursorWindow`）。
     * 改为按 [MERGE_PAGE] 分块循环读取，每块独立 CursorWindow，逐块累积成完整 List，从根本上分散内存压力，
     * 同时保留播放所需的完整队列（playQueue 依赖整表 indexOfFirst + 下一首）。
     */
    private val MERGE_PAGE = 500
    private suspend fun loadAllMergedSongs(): List<Song> {
        val primaries = ArrayList<MergedPrimaryRow>()
        val alts = ArrayList<MergedAltRow>()
        var offset = 0
        while (true) {
            val p = songDao.getMergedPrimaries(offset, MERGE_PAGE)
            if (p.isEmpty()) break
            primaries.addAll(p)
            alts.addAll(songDao.getMergedAlts(offset, MERGE_PAGE))
            if (p.size < MERGE_PAGE) break
            offset += MERGE_PAGE
        }
        return buildMergedSongs(primaries, alts)
    }

    /** [getSongs] 的合并拼装（主行 + 备选来源 uri 列表，按来源优先级排序）。 */
    private fun buildMergedSongs(
        primaries: List<MergedPrimaryRow>,
        alts: List<MergedAltRow>
    ): List<Song> {
        val altMap = alts.associateBy { it.mergeKey }
        return primaries.map { row ->
            val primary = row.song
            val altUris = altMap[row.mergeKey]?.let { alt ->
                val uris = alt.uris.split('\u0001')
                val types = alt.types.split('\u0001')
                val ids = alt.ids.split('\u0001')
                buildList {
                    for (i in uris.indices) {
                        if (ids[i].toLongOrNull() != primary.id) {
                            add(uris[i] to MediaSourceType.valueOf(types[i]))
                        }
                    }
                }.sortedBy { sourcePriority(it.second) }.map { it.first }
            } ?: emptyList()
            primary.toModel().copy(altUris = altUris)
        }
    }

    fun getAlbums(): Flow<List<Album>> =
        combine(albumDao.observeAll(), songDao.observeAlbumCovers()) { albums, covers ->
            albums.map { e ->
                val cover = e.albumArtUri
                    ?: covers.firstOrNull { it.albumName == e.name && it.artistName == e.artistName }?.albumArtUri
                e.toModel().copy(albumArtUri = cover)
            }
        }.flowOn(dispatcher.io)

    fun getArtists(): Flow<List<Artist>> =
        artistDao.observeAll().map { it.map { e -> e.toModel() } }.flowOn(dispatcher.io)

    fun getSongsByAlbum(albumId: Long): Flow<List<Song>> =
        songDao.observeByAlbum(albumId).map { it.mergeSongs() }.flowOn(dispatcher.io)

    fun getSongsByArtist(artistId: Long): Flow<List<Song>> =
        songDao.observeByArtist(artistId).map { it.mergeSongs() }.flowOn(dispatcher.io)

    /** 按专辑名 + 艺术家取曲目（专辑详情；扫描聚合未写回 albumId 时也可用）。 */
    fun getSongsByAlbumName(albumName: String, artistName: String?): Flow<List<Song>> =
        songDao.observeByAlbumName(albumName, artistName).map { it.mergeSongs() }.flowOn(dispatcher.io)

    /** 专辑发行年份（专辑详情年份显示）。 */
    fun getAlbumYear(albumName: String, artistName: String?): Flow<Int?> =
        albumDao.observeByName(albumName, artistName)
            .map { it.firstOrNull()?.year }
            .flowOn(dispatcher.io)

    /** 艺术家作品年表（按发行年代排序的专辑，艺术家详情）。 */
    fun getAlbumsByArtistName(artistName: String): Flow<List<Album>> =
        albumDao.observeByArtistName(artistName)
            .map { list -> list.map { e -> e.toModel() } }
            .flowOn(dispatcher.io)

    /** 按艺术家名取曲目（艺术家详情）。 */
    fun getSongsByArtistName(artistName: String): Flow<List<Song>> =
        songDao.observeByArtistName(artistName).map { it.mergeSongs() }.flowOn(dispatcher.io)

    fun getSongById(id: Long): Flow<Song?> =
        kotlinx.coroutines.flow.flow { emit(songDao.getById(id)?.toModel()) }

    /** 来源优先级（R2-01）：本地 > SMB > WebDAV > HTTP，值越小优先级越高。 */
    private fun sourcePriority(type: MediaSourceType): Int = when (type) {
        MediaSourceType.LOCAL -> 0
        MediaSourceType.SMB -> 1
        MediaSourceType.WEBDAV -> 2
        MediaSourceType.HTTP -> 3
    }

    /** 多源同曲合并（R2-01）：按 标题+艺术家+专辑 分组，取优先级最高的来源为主，
     *  其余来源的 uri 按优先级降序填入 altUris，供播放失败时回退。 */
    private fun List<SongEntity>.mergeSongs(): List<Song> {
        val groups = LinkedHashMap<String, MutableList<SongEntity>>()
        for (e in this) {
            val key = buildString {
                append(e.title.trim())
                append('\u0000')
                append((e.artistName ?: "").trim())
                append('\u0000')
                append((e.albumName ?: "").trim())
            }.asciiLowercase()
            groups.getOrPut(key) { mutableListOf() }.add(e)
        }
        return groups.values.map { group ->
            // P1-5：主行 tie-break 与 SQL 层一致（同优先级取 id 最小），保证主列表与详情页合并结果相同
            val primary = group.minWithOrNull(compareBy({ sourcePriority(it.sourceType) }, { it.id }))!!
            val alts = group.asSequence()
                .filter { it.id != primary.id }
                .sortedBy { sourcePriority(it.sourceType) }
                .map { it.uri }
                .toList()
            primary.toModel().copy(altUris = alts)
        }
    }

    /** P1-5：ASCII 小写（仅 A-Z→a-z），与 SQLite lower() 行为一致；
     *  使 Kotlin 侧合并键与 SongDao.MERGE_KEY_EXPR 完全相同，避免含重音拉丁文时两处去重结果不一致。 */
    private fun String.asciiLowercase(): String {
        val sb = StringBuilder(length)
        for (c in this) {
            sb.append(if (c in 'A'..'Z') c + 32 else c)
        }
        return sb.toString()
    }

    /** 最近播放（智能列表，P3）。 */
    fun getRecentlyPlayed(): Flow<List<Song>> =
        songDao.observeRecentlyPlayed().map { it.map { e -> e.toModel() } }.flowOn(dispatcher.io)

    /** 最常播放（智能列表，P3）。 */
    fun getMostPlayed(): Flow<List<Song>> =
        songDao.observeMostPlayed().map { it.map { e -> e.toModel() } }.flowOn(dispatcher.io)

    /** F2-3：播放统计概览（总曲目 / 累计播放 / 有播放记录的曲目）。 */
    fun getPlayStats(): Flow<PlayStats> =
        songDao.observePlayStats().map { row ->
            PlayStats(
                totalSongs = row.totalSongs,
                totalPlays = row.totalPlays,
                playedSongs = row.playedSongs
            )
        }.flowOn(dispatcher.io)

    // ===== F3-4：重复曲目清理 =====
    /** 重复组流（标题+歌手+时长 2 秒桶 判定；成员 id 以 char(1) 分隔）。 */
    fun getDuplicateGroups(): Flow<List<DuplicateGroupRow>> = songDao.observeDuplicateGroups()

    /** 取重复组内的完整原始成员（不做多源合并折叠，便于用户逐条选择保留）。 */
    suspend fun getDuplicateGroupMembers(ids: List<Long>): List<Song> =
        songDao.getByIds(ids).map { it.toModel() }

    /** 合并一组重复曲：歌单引用重定向到保留曲，删除其余行（分块防 SQLite 变量上限）。 */
    suspend fun mergeDuplicateGroup(keepId: Long, deleteIds: List<Long>) {
        if (deleteIds.isEmpty()) return
        db.withTransaction {
            playlistItemDao.deleteConflictingForMerge(keepId, deleteIds)
            playlistItemDao.remapSongRefs(keepId, deleteIds)
            deleteIds.chunked(500).forEach { songDao.deleteByIds(it) }
        }
        invalidateSongsSnapshot()
    }

    private fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun search(query: String): Flow<List<Song>> {
        val trimmed = query.trim()
        val q = "%${escapeLike(trimmed)}%"
        val sk = "%${escapeLike(SongSearchKey.compact(trimmed))}%"
        return songDao.search(q, sk).map { it.mergeSongs() }.flowOn(dispatcher.io)
    }

    /** F2-2：按搜索类型定向匹配歌曲（全部字段 / 标题 / 艺术家 / 专辑 / 文件名）；F6-2 增补拼音/首字母匹配。 */
    fun searchSongsByField(query: String, field: SearchField): Flow<List<Song>> {
        val trimmed = query.trim()
        val q = "%${escapeLike(trimmed)}%"
        val sk = "%${escapeLike(SongSearchKey.compact(trimmed))}%"
        return when (field) {
            SearchField.TITLE -> songDao.searchByTitle(q, sk)
            SearchField.ARTIST -> songDao.searchByArtist(q, sk)
            SearchField.ALBUM -> songDao.searchByAlbum(q, sk)
            SearchField.FILENAME -> songDao.searchByFilename(q)
            SearchField.ALL -> songDao.search(q, sk)
        }.map { it.mergeSongs() }.flowOn(dispatcher.io)
    }

    /** 专辑搜索（R-P1-03 曲库内搜索）。 */
    fun searchAlbums(query: String): Flow<List<Album>> {
        val q = "%${query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        return albumDao.search(q).map { it.map { e -> e.toModel() } }.flowOn(dispatcher.io)
    }

    /** 艺术家搜索（R-P1-03 曲库内搜索）。 */
    fun searchArtists(query: String): Flow<List<Artist>> {
        val q = "%${query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        return artistDao.search(q).map { it.map { e -> e.toModel() } }.flowOn(dispatcher.io)
    }

    fun getMusicSources(): Flow<List<MusicSource>> =
        musicSourceDao.observeAll().map { it.map { e -> e.toModel() } }.flowOn(dispatcher.io)

    suspend fun addMusicSource(source: MusicSource): Long =
        musicSourceDao.insert(source.toEntity())

    suspend fun updateMusicSource(source: MusicSource) =
        musicSourceDao.update(source.toEntity())

    /** L-实时监控重扫前置：LOCAL 源根是否仍可访问（透传 LibraryScanner.localSourceAccessible）。 */
    fun localSourceAccessible(source: MusicSource): Boolean = scanner.localSourceAccessible(source)

    suspend fun removeMusicSource(id: Long) =
        musicSourceDao.deleteById(id)

    /**
     * 2026-08-19 需求5：删除网络源——删源行 + 删该源全部曲目条目（按 uri 前缀，仅该源的）
     * + 重建专辑/艺术家聚合。
     * 注：多源同曲（mergeKey 相同、uri 不同）只删本源的条目行；若该曲仅此一个源，行删除即整曲
     * 连同其元数据一起消失（符合需求：唯一源删除才删元数据与整个条目）。
     */
    suspend fun removeNetworkSourceWithSongs(source: MusicSource) {
        musicSourceDao.deleteById(source.id)
        val prefix = runCatching {
            org.json.JSONObject(source.configJson).optString("url").trimEnd('/')
        }.getOrNull().orEmpty()
        if (prefix.isNotBlank()) {
            val uriHit = runCatching { songDao.getIdsByUriPrefix(source.type.name, prefix) }
                .getOrDefault(emptyList())
            val dedupHit = runCatching { songDao.getIdsByDedupKeyPrefix(source.type.name, prefix) }
                .getOrDefault(emptyList())
            val affected = (uriHit + dedupHit).distinct()
            if (affected.isNotEmpty()) {
                db.withTransaction {
                    affected.forEach { playlistItemDao.removeAllForSong(it) }
                    songDao.deleteByIds(affected)
                }
            }
            scanningScope.launch { runCatching { cacheManager.purgeByUrlPrefix(prefix) } }
        }
        scanner.rebuildAlbumsArtists()
        folderStructure.rebuild()
        queueReconciler.notifyDeleted()
    }

    /**
     * 当前正在扫描的来源 id 集合（供 UI 显示"扫描中"状态；并发扫描时合并、完成后消退）。
     * 统一在本仓库集中维护，任意入口（单源同步 / 全部重扫 / 播放冷启动）都能反映。
     */
    private val _scanningIds = MutableStateFlow<Set<Long>>(emptySet())
    val scanningIds: StateFlow<Set<Long>> = _scanningIds.asStateFlow()

    /** 当前单源扫描实时进度（新增/更新计数），供来源列表「扫描状态」中显示计数提示；无扫描时置 null。 */
    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()

    /** 扫描运行在仓库独立作用域：不随页面/ViewModel 生命周期被取消，保证后台扫描与"扫描中"状态跨页持续。 */
    private val scanningScope = CoroutineScope(SupervisorJob())

    private fun scanningIdsFor(sources: List<MusicSource>, singleSourceId: Long?): Set<Long> =
        if (singleSourceId != null) setOf(singleSourceId)
        else sources.filter { it.enabled }.map { it.id }.toSet()

    /** 同步等待扫描结束并入库（供需要拿结果的调用方：播放冷启动、歌曲页单源刷新）。保留 await 语义。 */
    suspend fun scanAndPersist(sources: List<MusicSource>, singleSourceId: Long? = null): ScanResult {
        val ids = scanningIdsFor(sources, singleSourceId)
        if (ids.isNotEmpty()) _scanningIds.update { it + ids }
        return try {
            scanner.scan(sources, singleSourceId)
        } finally {
            if (ids.isNotEmpty()) _scanningIds.update { it - ids }
        }
    }

    /**
     * 在仓库独立作用域后台启动扫描（脱离页面生命周期）：返回 / 切页不会中断扫描，
     * 且 [scanningIds] 由本仓库持有，网络源页面重进后"扫描中"图标仍能如实反映在扫状态。
     * 扫描期间每落库一批即经 [scanProgress] 上报实时 added/updated 计数；结束（含失败）置 null。
     * [onDone] 在扫描结束时回调（线索线程），用于 UI 回填结果文案。
     */
    fun scanSources(
        sources: List<MusicSource>,
        singleSourceId: Long? = null,
        mode: ScanMode = ScanMode.NEW_ONLY,
        onDone: (Result<ScanResult>) -> Unit = {}
    ): Job {
        // 确定进度归属的源 id 与模式（单源取 singleSourceId；多源取首个在扫源，仅作展示定位）
        val targetId = singleSourceId ?: scanningIdsFor(sources, singleSourceId).firstOrNull() ?: 0L
        val ids = scanningIdsFor(sources, singleSourceId)
        _scanProgress.value = ScanProgress(targetId, mode, 0, 0)
        if (ids.isNotEmpty()) _scanningIds.update { it + ids }
        return scanningScope.launch {
            val result = try {
                Result.success(
                    scanner.scan(sources, singleSourceId, mode) { added, updated ->
                        _scanProgress.value = ScanProgress(targetId, mode, added, updated)
                    }
                )
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                if (ids.isNotEmpty()) _scanningIds.update { it - ids }
                _scanProgress.value = null
            }
            onDone(result)
            // AZ-删除对账：扫描（含 pruneMissingRoot 追删）写库完成，剔除播放队列中的失效曲目
            queueReconciler.notifyDeleted()
        }
    }

    /** 按当前歌曲表重建专辑/艺术家聚合（删除曲目等改动后调用，保证计数/年份一致）。 */
    suspend fun refreshAggregates() = scanner.rebuildAlbumsArtists()

    /** 清理失效曲目（P3）：本地文件已不存在则删除（含歌单引用清理）。分页处理避免全表加载。返回清理数量。 */
    suspend fun pruneMissingLocal(): Int {
        val pageSize = 500
        var offset = 0
        var removed = 0
        while (true) {
            val batch = songDao.getAllPaged(offset, pageSize)
            if (batch.isEmpty()) break
            for (song in batch) {
                if (song.sourceType != MediaSourceType.LOCAL) continue
                val exists = if (song.uri.startsWith("content://")) {
                    (runCatching { context.contentResolver.openInputStream(Uri.parse(song.uri))?.use { true } }
                        .getOrDefault(false)) ?: false
                } else {
                    runCatching { File(song.uri).exists() }.getOrDefault(false)
                }
                if (!exists) {
                    runCatching { playlistItemDao.removeAllForSong(song.id) }
                    runCatching { songDao.deleteById(song.id) }
                    removed++
                }
            }
            offset += pageSize
        }
        if (removed > 0) {
            scanner.rebuildAlbumsArtists()
            queueReconciler.notifyDeleted()
        }
        return removed
    }

    /**
     * F1-3：「清理失效曲目」设置项对网络源场景的兜底。
     * 清理「已删除网络源」遗留在库中的孤儿曲目：网络源曲目（SMB/WEBDAV/HTTP）若其 uri 与
     * dedupKey 前缀均不属于任一当前有效网络源（config.url / scheme://authority），即视为该源已
     * 删除遗留的脏数据，删除（含歌单引用、播放队列对账、聚合重建）。返回清理数量。
     * 与删源级联（F1-1）互补：删源负责移除时立即清理，本方法作为历史遗留 / 删源匹配死角的手动兜底。
     */
    suspend fun pruneMissingNetworkOrphans(): Int {
        val validPrefixes = getMusicSources().first()
            .filterNot { it.type == MediaSourceType.LOCAL }
            .mapNotNull { networkRootPrefixOf(it) }
            .map { it.trimEnd('/') }
            .distinct()
        val pageSize = 500
        var offset = 0
        var removed = 0
        while (true) {
            val batch = songDao.getAllPaged(offset, pageSize)
            if (batch.isEmpty()) break
            for (song in batch) {
                if (song.sourceType == MediaSourceType.LOCAL) continue
                val belongs = validPrefixes.any { p ->
                    song.uri.startsWith(p) || song.dedupKey.startsWith(p)
                }
                if (belongs) continue
                runCatching { playlistItemDao.removeAllForSong(song.id) }
                runCatching { songDao.deleteById(song.id) }
                removed++
            }
            offset += pageSize
        }
        if (removed > 0) {
            scanner.rebuildAlbumsArtists()
            queueReconciler.notifyDeleted()
        }
        return removed
    }

    /** 网络源根前缀：SMB/WEBDAV 取 config.url，HTTP 取 scheme://authority；用于孤儿归属判定。LOCAL 返回 null。 */
    private fun networkRootPrefixOf(src: MusicSource): String? {
        val j = runCatching { org.json.JSONObject(src.configJson) }.getOrNull() ?: return null
        return when (src.type) {
            MediaSourceType.SMB, MediaSourceType.WEBDAV ->
                j.optString("url").takeIf { it.isNotBlank() }?.trimEnd('/')
            MediaSourceType.HTTP ->
                runCatching { Uri.parse(j.optString("url")) }.getOrNull()?.let { u ->
                    val a = u.authority
                    if (u.scheme.isNullOrBlank() || a.isNullOrBlank()) null else "${u.scheme}://$a"
                }
            else -> null
        }
    }
}
