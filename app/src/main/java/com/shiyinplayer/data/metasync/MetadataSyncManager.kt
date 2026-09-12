package com.shiyinplayer.data.metasync

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.shiyinplayer.MusicPlayerApplication
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.local.entity.SongEntity
import com.shiyinplayer.data.media.parseFileNameTitle
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.network.NetworkKind
import com.shiyinplayer.data.network.NetworkMonitor
import com.shiyinplayer.data.repository.MetadataRepository
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动同步曲库文件元数据（2026-08-19 新增，独立后台线程）。
 *
 * 对曲库中本地（sourceType=LOCAL）歌曲，用 [MediaMetadataRetriever] 读取音频文件内嵌标签
 * （艺术家 / 专辑 / 年份 / 流派 / 音轨号），按 **fill-in 策略** 回填 DB 中缺失的字段——
 * 不覆盖已有值（含用户手工匹配 / 在线元数据的结果），只补空。
 *
 * 2026-08-19 需求3：网络策略——去掉 24h 节流，改为流量保护判定：
 *  - 流量保护开启 + 移动网络 → 停止联网同步（本地标签 fill-in 仍执行，在线匹配跳过）；
 *  - WiFi 或流量保护关闭 → 正常联网同步；连接 WiFi 时自动触发一次后台同步。
 *
 * 2026-08-19 需求4：网络源在线匹配成功后同步获取歌词并缓存到本地库（播放时优先本地）。
 *
 * 线程模型：独立 [CoroutineScope]（Dispatchers.IO），不与 UI / 播放（主线程）抢占，
 * 也不阻塞播放器其它功能；[AtomicBoolean] 防重入。
 *
 * 触发：应用启动 [maybeRunSync]；连接 WiFi 自动触发 [start] 监听；设置开关打开时立即 [syncNow]。
 */
@Singleton
class MetadataSyncManager @Inject constructor(
    private val settings: SettingsRepository,
    private val songDao: SongDao,
    private val metadataRepo: MetadataRepository,
    private val networkMonitor: NetworkMonitor,
    private val playerManager: com.shiyinplayer.player.PlayerManager,
    @ApplicationContext private val context: Context
) {
    /** 独立后台线程（IO 池），与播放/UI 隔离。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    /** 周期自动同步任务（开关开启时运行，后续轮次自动重启并保证同步线程活跃）。 */
    private var continuousJob: kotlinx.coroutines.Job? = null
    /** 同步运行状态：true 表示正在同步（设置页显示「运行中」，并伴随保活前台服务）。 */
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    // ===== 手动批量同步元数据 =====
    /** 手动批量同步运行状态：true 期间后台自动同步暂停、播放时在线歌词匹配被抑制。 */
    private val _manualSyncActive = MutableStateFlow(false)
    val manualSyncActive: StateFlow<Boolean> = _manualSyncActive.asStateFlow()

    /** 手动批量同步进度（已处理 / 总数）。 */
    private val _manualProgress = MutableStateFlow(ManualSyncProgress(0, 0))
    val manualProgress: StateFlow<ManualSyncProgress> = _manualProgress.asStateFlow()

    /** 手动批量同步任务引用（stopManualSync 取消）。 */
    private var manualJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val TAG = "MetadataSyncManager"
        /** 2026-08-23 需求：自动同步周期（分钟）。开关打开时后台每至此间隔自动重新触发一轮同步。 */
        const val AUTO_SYNC_INTERVAL_MINUTES = 5
        /** 每轮同步单批在线匹配的网络源歌曲数（后台一次处理的身量，避免长时间占用 IO/打爆在线 API）。 */
        const val NETWORK_MATCH_BATCH = 10

        /** CX：在线匹配无结果后的重试冷却（≈6 轮 5 分钟同步 = 30 分钟），冷却期内跳过重复在线命中。 */
        private const val NEGATIVE_COOLDOWN_MS = 30 * 60_000L

        /** CX：负缓存上限，超过则按访问序淘汰最旧（防长期无结果歌曲累积占内存）。 */
        private const val MAX_NEGATIVE_HITS = 2000
        /** 自动同步周期（毫秒），由分钟换算。 */
        private const val AUTO_SYNC_INTERVAL_MS = AUTO_SYNC_INTERVAL_MINUTES * 60_000L
        /** 2026-08-19 需求2：预取下一首的最小间隔（防切歌过快时重复打在线 API）。 */
        private const val PREFETCH_MIN_INTERVAL_MS = 5_000L
        /** 2026-08-23 需求4：周期自动同步首轮延迟——先让曲库首屏正常加载，再启动整库同步，避免冷启争抢 IO。 */
        private const val AUTO_SYNC_FIRST_DELAY_MS = 3_000L
        /** 手动批量同步：并发在线匹配的曲目数（多线程多源同时采集，限流不打挂在线服务器）。 */
        const val MANUAL_SYNC_CONCURRENCY = 4
        /** 手动批量同步：每次在线请求之间的最小间隔（毫秒），整体限速防打爆服务器。 */
        private const val MANUAL_SYNC_GAP_MS = 120L
        /** 手动批量同步：分页查询每页曲目数（避免 Room CursorWindow 溢出）。 */
        private const val MANUAL_SYNC_PAGE = 500

        /** 手动批量同步：每处理 [MANUAL_SYNC_PROGRESS_STEP] 首刷新一次进度状态（入库按每首即时落库）。 */
        private const val MANUAL_SYNC_PROGRESS_STEP = 10
    }

    /** CX：在线匹配「无结果」的负命中冷却（songId→最近尝试的 elapsedRealtime）。上限内按访问序淘汰最旧。 */
    private val negativeHits = object : LinkedHashMap<Long, Long>(MAX_NEGATIVE_HITS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>): Boolean = size > MAX_NEGATIVE_HITS
    }

    /** 应用启动时调用：开关开启且当前网络允许（WiFi 或流量保护关闭）才执行（不阻塞调用方）。 */
    fun maybeRunSync() {
        scope.launch {
            val enabled = runCatching { settings.autoSyncMetadata.first() }.getOrDefault(true)
            if (!enabled) return@launch
            if (!canOnline()) return@launch
            syncNow()
        }
    }

    /** 连接 WiFi 时自动触发后台同步（需求3）。Application onCreate 调用一次即可常驻监听。 */
    fun start() {
        scope.launch {
            networkMonitor.network.collect { kind ->
                if (kind == NetworkKind.WIFI) {
                    val enabled = runCatching { settings.autoSyncMetadata.first() }.getOrDefault(true)
                    if (enabled) syncNow()
                }
            }
        }
    }

    // ===== 2026-08-19 需求2：连续播放时预取下一首元数据 =====

    /** 最近一次预取时间（限流）与歌曲 id（防重复）。 */
    @Volatile private var lastPrefetchAt = 0L
    @Volatile private var lastPrefetchSongId = -1L

    /**
     * 播放中监听：切歌时提前检查「当前播放的下一首」元数据是否完整，
     * 不完整（缺歌手/专辑/年份）则在线匹配并写回主库 + 缓存歌词，方便后续播放直接加载。
     * Application onCreate 调用一次常驻。网络不允许（移动网络+流量保护）时跳过在线部分。
     */
    fun startPlaybackPrefetch() {
        scope.launch {
            playerManager.playbackState.collect { st ->
                val idx = st.currentIndex
                val queue = st.queue
                if (idx < 0 || queue.isEmpty()) return@collect
                // 下一首（repeat OFF 时 index+1；越界/单曲则取队首）
                val next = queue.getOrNull(idx + 1) ?: queue.firstOrNull() ?: return@collect
                // 限流 + 防重复（同一首歌只预取一次）
                val now = System.currentTimeMillis()
                if (next.id == lastPrefetchSongId && now - lastPrefetchAt < PREFETCH_MIN_INTERVAL_MS) return@collect
                if (now - lastPrefetchAt < PREFETCH_MIN_INTERVAL_MS) return@collect
                if (next.id <= 0) return@collect
                if (!needsMetadata(next)) {
                    // 元数据已完整：仍顺带缓存歌词（绑定 songId，播放时免在线）
                    lastPrefetchSongId = next.id
                    lastPrefetchAt = now
                    scope.launch {
                        runCatching { metadataRepo.getLyrics(next.title, next.artistName, next) }
                    }
                    return@collect
                }
                lastPrefetchSongId = next.id
                lastPrefetchAt = now
                prefetchSong(next)
            }
        }
    }

    /** 元数据是否缺失（歌手/专辑/年份任一为空）。 */
    private fun needsMetadata(song: com.shiyinplayer.data.model.Song): Boolean =
        song.artistName.isNullOrBlank() ||
            song.albumName.isNullOrBlank() ||
            (song.year == null || song.year == 0)

    /** 预取：在线匹配下一首 → 写回主库（标题/歌手/专辑/年份 + 封面）→ 缓存歌词。 */
    private suspend fun prefetchSong(song: com.shiyinplayer.data.model.Song) {
        try {
            if (!canOnline()) return
            val (parsedArtist, parsedTitle) = parseFileNameTitle(song.title)
            val searchTitle = parsedTitle ?: song.title
            val searchArtist = parsedArtist ?: song.artistName
            if (searchTitle.isBlank()) return
            val best = metadataRepo.autoMatchBest(searchTitle, searchArtist) ?: return
            metadataRepo.applyMatch(song.id, best)
            Log.i(TAG, "预取下一首「${song.title}」元数据：${best.title} / ${best.artist} / ${best.album}")
            // 顺带缓存歌词（绑定 songId）
            runCatching { metadataRepo.getLyrics(best.title, best.artist, song) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // 预取失败不影响播放
        }
    }

    /** 是否允许联网同步：流量保护开启且当前为移动网络 → false；WiFi / 其它 / 保护关闭 → true。 */
    private suspend fun canOnline(): Boolean {
        val dataSaver = runCatching { settings.dataSaver.first() }.getOrDefault(true)
        if (!dataSaver) return true
        return !networkMonitor.isCellular()
    }

    /** 立即同步一轮（设置开关打开时触发；运行中则忽略，防重入；移动网络+流量保护时在线部分跳过）。 */
    fun syncNow() {
        scope.launch { syncRound() }
    }

    // ===== 手动批量同步元数据 =====

    /**
     * 启动手动批量同步：自动连续更新全库（任意来源）曲目缺失的元数据。
     * - 多线程并发（Semaphore 限流 [MANUAL_SYNC_CONCURRENCY]）+ 每次在线请求最小间隔限速，保证不打挂在线服务器；
     * - 运行期间 [manualSyncActive] 置 true，后台自动同步暂停、播放时在线歌词匹配被抑制（互斥）；
     * - 结束（含取消）自动复位 [manualSyncActive] 并清空进度。
     */
    fun startManualSync() {
        if (_manualSyncActive.value) return
        _manualSyncActive.value = true
        _manualProgress.value = ManualSyncProgress(0, 0)
        manualJob = scope.launch {
            try {
                runManualSync()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "手动批量同步失败: ${e.message}")
            } finally {
                _manualSyncActive.value = false
                _manualProgress.value = ManualSyncProgress(0, 0)
            }
        }
    }

    /** 停止手动批量同步（取消任务；finally 里自动复位状态）。 */
    fun stopManualSync() {
        manualJob?.cancel()
    }

    private suspend fun runManualSync() {
        val online = canOnline()
        if (!online) Log.i(TAG, "流量保护：移动网络，手动在线匹配已跳过（仅本地标签回填）")

        val total = runCatching { songDao.countSongsMissingMetadata() }.getOrDefault(0L).toInt()
        _manualProgress.value = ManualSyncProgress(0, total.coerceAtLeast(0))
        if (total <= 0) {
            Log.i(TAG, "手动批量同步：无缺失元数据的曲目")
            return
        }

        val semaphore = Semaphore(MANUAL_SYNC_CONCURRENCY)
        val processed = AtomicInteger(0)
        var page = 0
        while (currentCoroutineContext().isActive) {
            val offset = page * MANUAL_SYNC_PAGE
            if (offset >= total) break
            val batch = songDao.getSongsMissingMetadataPaged(MANUAL_SYNC_PAGE, offset)
            if (batch.isEmpty()) break
            coroutineScope {
                batch.forEach { song ->
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                processMissing(song, online)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // 单曲失败不影响其它曲目
                            } finally {
                                // 每同步 MANUAL_SYNC_PROGRESS_STEP 首（或收官）刷新一次进度，保证状态显示及时
                                val n = processed.incrementAndGet()
                                if (n % MANUAL_SYNC_PROGRESS_STEP == 0 || n >= total) {
                                    _manualProgress.value = ManualSyncProgress(n.coerceAtMost(total), total)
                                }
                            }
                        }
                    }
                }
            }
            page++
        }
    }

    /** 单曲缺失元数据补全：本地曲目读内嵌标签回填，联网时再做多源在线匹配（写回 + 缓存歌词）。 */
    private suspend fun processMissing(song: SongEntity, online: Boolean) {
        if (song.sourceType == MediaSourceType.LOCAL) {
            val tags = readTags(song)
            if (tags != null) {
                // fill-in：只补空字段
                songDao.fillTagsFromFile(song.id, tags.artist, tags.album, tags.year, tags.genre, tags.track)
                if (song.title.isNotBlank() && looksLikeFileNameTitle(song.title)) {
                    val (parsedArtist, parsedTitle) = parseFileNameTitle(song.title)
                    val newTitle = tags.title ?: parsedTitle ?: song.title
                    val newArtist = tags.artist ?: parsedArtist ?: song.artistName
                    if (newTitle != song.title || newArtist != song.artistName) {
                        songDao.updateTitleArtist(song.id, newTitle, newArtist)
                    }
                }
            }
        }
        if (online) {
            val (parsedArtist, parsedTitle) = parseFileNameTitle(song.title)
            val searchTitle = parsedTitle ?: song.title
            val searchArtist = parsedArtist ?: song.artistName
            if (searchTitle.isNotBlank()) {
                val best = metadataRepo.autoMatchBest(searchTitle, searchArtist)
                if (best != null) {
                    metadataRepo.applyMatch(song.id, best)
                    runCatching { metadataRepo.getLyrics(best.title, best.artist) }
                }
                // 限速：每次在线请求之间最小间隔，防打爆服务器（并发曲目由 Semaphore 控制）
                delay(MANUAL_SYNC_GAP_MS)
            }
        }
    }

    /**
     * 2026-08-23 需求2：自动同步改为「周期循环」——开关开启时后台持续工作。
     * 监听 [settings.autoSyncMetadata]：开启则循环执行同步（每轮完成由 [AUTO_SYNC_INTERVAL_MS] 后再自动触发下一轮，
     * 保证同步线程不活动时能自动重新启动）；关闭则取消循环。应用启动调用一次即可常驻。
     */
    fun startContinuousSync() {
        scope.launch {
            settings.autoSyncMetadata.collect { enabled ->
                continuousJob?.cancel()
                if (enabled) {
                    Log.i(TAG, "自动同步已开启：进入周期自动同步（每 ${AUTO_SYNC_INTERVAL_MS / 60_000} 分钟一轮）")
                    continuousJob = scope.launch {
                        // 需求4：首轮同步延迟，先让曲库首屏正常加载，再开启后台自动同步
                        delay(AUTO_SYNC_FIRST_DELAY_MS)
                        while (isActive) {
                            // 手动批量同步期间暂停后台自动同步；手动关闭后自动恢复周期同步（互斥）
                            if (_manualSyncActive.value) {
                                delay(2_000)
                                continue
                            }
                            syncRound()
                            delay(AUTO_SYNC_INTERVAL_MS)
                        }
                    }
                } else {
                    Log.i(TAG, "自动同步已关闭：停止周期同步")
                }
            }
        }
    }

    /** 执行一轮同步（挂起直到完成）；防重入；同步期间拉起保活前台服务。 */
    private suspend fun syncRound() {
        // 手动批量同步期间后台自动同步无条件让路（互斥）
        if (_manualSyncActive.value) return
        if (!running.compareAndSet(false, true)) return
        // 2026-08-23 修复：App 在前台时跳过「完整同步」——自动同步集中到退后台后执行，
        // 避免逐个读音频文件标签 / 网络在线匹配写回（会使 songs 表 Room Flow 反复失效重查并占满 IO）
        // 在用户浏览曲库/播放时压住首屏加载，导致「曲库内容十几秒不显示」。
        Log.i(TAG, "检查前台态 isAppInForeground=${MusicPlayerApplication.isAppInForeground}")
        if (MusicPlayerApplication.isAppInForeground) {
            Log.i(TAG, "应用在前台：本轮自动同步暂缓，退到后台后继续")
            running.set(false)
            return
        }
        try {
            // 更新「运行中」状态，并拉起保活前台服务（目标进程不被系统杀死，后台也能在线同步）
            _isSyncing.value = true
            MetadataSyncService.start(context)
            val online = canOnline()
            if (!online) Log.i(TAG, "流量保护：移动网络，在线元数据/歌词获取已跳过")

            // 需求3：只同步库中「元数据缺失」的本地曲目（getLocalSongsForSync 过滤），跳过已有完整元数据的。
            val localSongs = songDao.getLocalSongsForSync()
                var updated = 0
                var scanned = 0
                if (localSongs.isNotEmpty()) {
                    localSongs.forEach { song ->
                        val tags = readTags(song) ?: return@forEach
                        scanned++
                        try {
                            var changed = false
                            // 2026-08-19 标题清洗：DB title 疑似文件名回退（含 " - "/"-" 等分隔符）
                            // → 用内嵌元数据 title 覆盖；元数据也没有时从文件名解析「歌手 - 歌名」。
                            if (looksLikeFileNameTitle(song.title)) {
                                val (parsedArtist, parsedTitle) = parseFileNameTitle(song.title)
                                val newTitle = tags.title ?: parsedTitle ?: song.title
                                val newArtist = tags.artist ?: parsedArtist ?: song.artistName
                                if (newTitle != song.title || newArtist != song.artistName) {
                                    changed = songDao.updateTitleArtist(song.id, newTitle, newArtist) > 0 || changed
                                }
                            }
                            // fill-in：只补空字段（不覆盖在线匹配/手工结果）
                            changed = songDao.fillTagsFromFile(
                                song.id, tags.artist, tags.album, tags.year, tags.genre, tags.track
                            ) > 0 || changed
                            if (changed) updated++
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // 单曲更新失败不影响其它曲目
                        }
                    }
                    Log.i(TAG, "本地标签同步：本地 ${localSongs.size} 首，读取标签 $scanned 首，更新（补缺）$updated 首")
                }

                // 2026-08-19：网络源（WEBDAV/SMB）歌曲在线匹配元数据（标题/歌手/专辑/年份/封面），
                // 保存到本机库。网络源入库时只有文件名，经多源在线搜索自动评分写回。
                // 移动网络 + 流量保护时跳过（在线部分）。
                if (online) {
                    val missing = runCatching { songDao.countNetworkSongsMissingMetadata() }.getOrDefault(0)
                    if (missing > 0) {
                        val netMatched = matchNetworkSongs()
                        val stillMissing = runCatching { songDao.countNetworkSongsMissingMetadata() }.getOrDefault(missing)
                        Log.i(TAG, "网络源在线匹配：本轮匹配成功 $netMatched 首（缺元数据 $missing 首，剩余 $stillMissing 首）")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "同步失败: ${e.message}")
            } finally {
                running.set(false)
                // 同步结束：停止保活服务并复位「运行中」状态
                MetadataSyncService.stop(context)
                _isSyncing.value = false
            }
    }

    /** 网络源歌曲在线匹配：批量取缺元数据的歌曲，多源搜索 + 自动评分 → 写回主库。
     *  并发 4（Semaphore 限流），避免一次打爆在线 API。匹配成功后顺带缓存歌词（需求4）。
     *  返回成功匹配数。 */
    private suspend fun matchNetworkSongs(): Int {
        var batch = songDao.getNetworkSongsForSync(NETWORK_MATCH_BATCH)
        if (batch.isEmpty()) return 0
        // CX：负命中冷却。在线匹配无结果的歌曲仍是「缺元数据」，若不干预会每轮重新打在线 API；
        // 用内存负缓存跳过冷却期内的重复命中（命中写回成功即移除，异常/无结果则记录重试冷却）。
        val now = SystemClock.elapsedRealtime()
        batch = synchronized(negativeHits) {
            batch.filterNot { song -> negativeHits[song.id]?.let { now - it < NEGATIVE_COOLDOWN_MS } ?: false }
        }
        if (batch.isEmpty()) return 0
        val matched = java.util.concurrent.atomic.AtomicInteger(0)
        val semaphore = Semaphore(4)
        coroutineScope {
            batch.forEach { song ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        var hit = false
                        try {
                            // 网络源 title 是文件名（可能含 歌手-歌名 / [歌手]歌名 / (序号) 等），先解析出干净标题与歌手
                            val (parsedArtist, parsedTitle) = parseFileNameTitle(song.title)
                            val searchTitle = parsedTitle ?: song.title
                            val searchArtist = parsedArtist ?: song.artistName
                            val best = searchTitle.takeIf { it.isNotBlank() }?.let { metadataRepo.autoMatchBest(it, searchArtist) }
                            if (best != null) {
                                hit = true
                                // 自动匹配成功后写回（标题/歌手/专辑/年份 + 封面）
                                metadataRepo.applyMatch(song.id, best)
                                // 需求4：同步缓存歌词到本地库（getLyrics 内部：本地/缓存优先，在线获取后写缓存）。
                                // 用匹配后的标准标题/歌手作为 key，保证播放时命中。
                                runCatching {
                                    metadataRepo.getLyrics(best.title, best.artist)
                                }
                                matched.incrementAndGet()
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // 单曲匹配失败不影响其它曲目；按未命中记冷却，交由 finally 处理
                        } finally {
                            // 命中写回成功 → 移出负缓存（此后不再缺元数据，自然不出现在查询中）；
                            // 未命中/异常 → 记录冷却时间，冷却期内跳过在线重试。
                            // LinkedHashMap 非线程安全，写路径统一加锁（读在 coroutineScope 前单线程执行，安全）。
                            synchronized(negativeHits) {
                                if (hit) negativeHits.remove(song.id) else negativeHits[song.id] = now
                            }
                        }
                    }
                }
            }
        }
        return matched.get()
    }

    private data class FileTags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val year: Int?,
        val genre: String?,
        val track: Int?
    )

    /** DB title 疑似「文件名/未清洗」：含常见分隔符或括注符号（可能是 歌手-歌名 或 [歌手]歌名 文件名）。 */
    private fun looksLikeFileNameTitle(title: String): Boolean =
        title.contains(" - ") || title.contains("-") || title.contains("_") ||
            title.contains(".") || title.contains("[") || title.contains("]") ||
            title.contains("(") || title.contains(")") ||
            title.contains("（") || title.contains("）") ||
            title.contains("《") || title.contains("》") ||
            title.contains("/") || title.contains("：") || title.contains(":")

    /** 读取单个本地文件的内嵌标签；非本地/读取失败返回 null。 */
    private fun readTags(song: SongEntity): FileTags? {
        if (song.sourceType != MediaSourceType.LOCAL) return null
        // 本地歌曲 path/uri 可能是 SAF content://（如 externalstorage.documents/tree）、file:// 或绝对路径
        val pathOrUri = song.path ?: song.uri
        if (pathOrUri.isBlank()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
                retriever.setDataSource(context, Uri.parse(pathOrUri))
            } else {
                retriever.setDataSource(pathOrUri)
            }
            FileTags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() },
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() },
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull(),
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() },
                track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.toIntOrNull()
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** 手动批量同步进度（已处理曲目数 / 库中缺失元数据总曲目数）。 */
data class ManualSyncProgress(val done: Int, val total: Int)
