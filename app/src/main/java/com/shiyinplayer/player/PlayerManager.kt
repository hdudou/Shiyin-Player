package com.shiyinplayer.player

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.shiyinplayer.data.cache.MusicCacheManager
import com.shiyinplayer.R
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.mapper.EntityMappers.toModel
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.player.radio.RadioPlayer
import com.shiyinplayer.player.decoder.AudioFormatRegistry
import com.shiyinplayer.player.decoder.DeviceCodecProbe
import com.shiyinplayer.ui.settings.SettingsRepository
import com.shiyinplayer.util.toast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放核心：封装 ExoPlayer，暴露播放控制命令与 [PlaybackState] 流。
 * 队列逻辑委托 [QueueController] 计算 next/prev。开始播放时拉起前台 [PlaybackService]（通知/锁屏控制）。
 *
 * 首版补齐（对标 SETTINGS_SPEC / PRD）：
 * - R-P1-07 断点续播：随播放进度周期保存会话（队列主键/索引/位置），启动时 [restoreLastSession] 恢复。
 * - P-04 解码失败自动跳下一首（skip_on_error）。
 * - C-02 耳机拔出自动暂停（headset_pause）。
 * - C-04 睡眠定时（sleep_timer_min）。
 * - P-03 默认播放模式（default_repeat）随设置应用。
 * - P-09 队列编辑：seekToIndex / removeAt。
 */
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    initialExo: ExoPlayer,
    private val playerFactory: PlayerFactory,
    private val mediaItemBuilder: MediaItemBuilder,
    private val queueController: QueueController,
    private val equalizerManager: EqualizerManager,
    private val settings: SettingsRepository,
    private val songDao: SongDao,
    private val lyricLinesStore: LyricLinesStore,
    private val libraryRepository: LibraryRepository,
    private val audioRoutingController: AudioRoutingController,
    private val deviceCodecProbe: DeviceCodecProbe,
    // A1 步骤 1：断点续播会话持久化独立成类，本类仅保留门面方法
    private val sessionPersister: SessionPersister,
    // A1 步骤 2：窗口式队列与 ExoPlayer 装载同步独立成类，本类仅保留门面方法
    private val queueManager: QueueManager,
    // A1 步骤 3：播放命令与音频段状态（音量/淡入淡出/睡眠定时/耳机拔出）独立成类，本类仅保留门面
    val playbackController: PlaybackController,
    // A2：播放动作单线程串行执行器（PlaybackController 亦注入，统一收敛队列/窗口/位置变更）
    private val playbackActor: PlaybackActor,
    // 网络源本地缓存（离网播放，自动缓存触发点）
    private val musicCache: MusicCacheManager,
    // AZ-删除对账：收纳删曲/删源事件，剔除内存队列失效曲目（避免与 LibraryRepository 构造环）。
    private val queueReconciler: QueueReconciler,
    // 跨模式互斥：音乐起播时强制停电台流，杜绝两条独立 ExoPlayer 同时出声混音。
    private val radioPlayer: RadioPlayer
) {
    /** 当前生效的 ExoPlayer（音频链开关 #9-12 热重建时整体替换；@Volatile 保证各线程读一致）。 */
    @Volatile
    var exoPlayer: ExoPlayer = initialExo

    /** 播放器替换通知（MediaSessionManager 观察并 setPlayer 跟随，实现热重建后会话同步）。 */
    private val _activePlayer = MutableStateFlow<ExoPlayer>(exoPlayer)
    val activePlayer: StateFlow<ExoPlayer> = _activePlayer.asStateFlow()

    /** 播放器监听器（热重建时从旧播放器移除、挂到新播放器）。 */
    private val playerListener = PlayerListener()

    @Volatile
    private var rebuilding = false
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /** 是否正在播放音频（供 PlaybackHost 等公共层读取）。 */
    val isPlaying: Boolean get() = playbackState.value.isPlaying

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var skipOnError = true
    // 歌间停顿（自动切歌时暂停）；gapless 无缝播放时不生效。
    // 音量/淡入淡出/睡眠定时/耳机拔出 等音频段状态与命令已迁至 PlaybackController。
    private var gapless = false

    // P2 接线：autoEQ / 音频路由 / 监听文件夹 / 播放统计 / 时长时间写
    private var autoEqByGenre = false
    private var audioRoute = "auto"
    private var watchFolders = false
    private var mediaStoreObserver: ContentObserver? = null
    private var lastRescan = 0L

    private val _currentLyricLine = MutableStateFlow<String?>(null)
    val currentLyricLine: StateFlow<String?> = _currentLyricLine.asStateFlow()

    /** 已下发到媒体会话（车载 AVRCP）的歌词行，用于去重与开关关闭时清空。 */
    private var lastBroadcastCarLyric: String? = null

    // 2026-08-18：APE/WMA 系统 MediaPlayer 兜底（ExoPlayer 无容器解析器 + QTI ape MediaCodec 直通不可用）
    private val mediaPlayerFallback: MediaPlayerFallback = MediaPlayerFallback(context)

    /** 兜底播放是否活跃（MediaSession 桥接用）。 */
    val fallbackActive: Boolean get() = mediaPlayerFallback.active

    /** MediaSession 桥接播放器：平时透传 ExoPlayer，兜底时覆盖状态并路由命令（功能缺口 #1）。 */
    @Volatile
    var fallbackAwarePlayer: Player = FallbackAwarePlayer(exoPlayer, this)

    /** 睡眠定时剩余（委托 PlaybackController，C-04）。 */
    val sleepTimerEndAt: StateFlow<Long?> get() = playbackController.sleepTimerEndAt

    init {
        // A1 步骤 2：绑定窗口队列的运行时依赖（ExoPlayer 活引用 / UI 刷新 / 服务启动）
        queueManager.bind(
            playerRef = { exoPlayer },
            onChanged = { emitFull() },
            ensureServiceStarted = { ensureServiceStarted() }
        )
        // AZ-删除对账：监听删曲/删源事件，从内存队列剔除 DB 已不存在的曲目并重定位当前索引。
        scope.launch { queueReconciler.deletions.collect { pruneGhostsFromQueue() } }
        // A1 步骤 3：绑定播放控制器的运行时依赖（ExoPlayer 活引用 / 兜底 / 状态 / UI / 会话 / 前台服务 / 兜底判定）
        playbackController.bind(
            playerRef = { exoPlayer },
            mediaPlayerFallback = mediaPlayerFallback,
            state = _playbackState,
            onChanged = { emitFull() },
            persist = { persistSession() },
            ensureForeground = { ensureServiceStarted() },
            routeFallback = { fallbackRoute(it) },
            fallbackSourceFor = { fallbackDataSource(it) },
            startFallbackDownload = { launchRemoteFallback(it) },
            stopFallback = { stopFallbackIfNeeded() }
        )
        // 兜底播放完成 → 自动切下一首（与 ExoPlayer 自动连播对齐）
        mediaPlayerFallback.onCompletion = {
            val idx = queueController.nextIndex()
            if (idx >= 0) playbackController.navigateTo(idx) else {
                mediaPlayerFallback.stop()
                exoPlayer.pause()
            }
        }
        exoPlayer.addListener(playerListener)
        startProgressLoop()
        observeSettings()
        observeFallback()
        // 2026-09-12 跨模式互斥（方向二）：电台起播时若音乐链仍在出声，强制暂停音乐。
        // 与 playQueueInternal 里 `radioPlayer.suppressForMusicMode()` 形成双向闭环，
        // 保证任一时刻只有音乐或电台一条音频链在响（RadioPlayer.addPreemptionListener 原本是空转回调）。
        radioPlayer.addPreemptionListener {
            runCatching { playbackController.pause() }
            runCatching { mediaPlayerFallback.pause() }
        }
    }

    /**
     * 热重建播放器（音频链开关 #9-12 即时化）：按当前 DataStore 设置重建 ExoPlayer，
     * 保留队列/索引/进度/播放态后替换，并通知 [activePlayer] 订阅方（MediaSessionManager）。
     * 触发：low_latency / float32_processing / volume_normalize / silence_remover 任一变化。
     */
    private fun rebuildPlayer() {
        if (rebuilding) return
        rebuilding = true
        scope.launch {
            try {
                playbackActor.execute {
                    try {
                        val old = exoPlayer
                        val songs = queueController.queue.toList()
                        val index = queueController.currentIndex
                        val pos = runCatching { old.currentPosition.coerceAtLeast(0) }.getOrDefault(0L)
                        val wasPlaying = runCatching { old.isPlaying }.getOrDefault(false)
                        val fbActive = mediaPlayerFallback.active
                        runCatching { old.pause() }
                        old.removeListener(playerListener)
                        val fresh = playerFactory.create()
                        fresh.addListener(playerListener)
                        if (!fbActive && songs.isNotEmpty() && index in songs.indices) {
                            val (exoSongs, exoIndex) = queueManager.centerWindow(songs, index)
                            val items = withContext(Dispatchers.Default) { exoSongs.map { mediaItemBuilder.build(it) } }
                            fresh.setMediaItems(items, exoIndex, 0L)
                            fresh.prepare()
                            fresh.playWhenReady = wasPlaying
                            if (pos > 0) fresh.seekTo(pos)
                        }
                        exoPlayer = fresh
                        applyGapless()
                        fallbackAwarePlayer = FallbackAwarePlayer(fresh, this@PlayerManager)
                        _activePlayer.value = fresh
                        emitFull()
                        persistSession()
                        runCatching { old.release() }
                    } finally {
                        rebuilding = false
                    }
                }
            } catch (_: Exception) {
                rebuilding = false
            }
        }
    }

    private fun observeSettings() {
        scope.launch {
            settings.defaultRepeat.collect { value ->
                val mode = when (value) {
                    "one" -> RepeatMode.ONE
                    "all" -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                }
                queueController.setRepeatMode(mode)
                exoPlayer.repeatMode = when (mode) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                }
                emitFull()
            }
        }
        scope.launch { settings.skipOnError.collect { skipOnError = it } }
        // 音量曲线/淡入淡出/耳机拔出 等音频段设置由 PlaybackController 观察
        scope.launch { settings.gapless.collect { gapless = it; applyGapless() } }
        scope.launch { settings.autoEqByGenre.collect { autoEqByGenre = it } }
        scope.launch { settings.audioRoute.collect { audioRoute = it; applyAudioRoute() } }
        scope.launch { settings.watchFolders.collect { watchFolders = it; updateMediaStoreObserver() } }
        // 音频链开关即时化：low_latency / float32_processing / volume_normalize / silence_remover /
        // buffer_ms / replaygain_mode 任一实际变化 → 热重建播放器（构建期门控项经重建重新读取，无需重启）。
        // 每个流独立 collect：distinctUntilChanged 过滤无关 DataStore 写入（persistSession 每 5s 写会话），
        // drop(1) 跳过各自初始值，debounce(300) 合并连续切换；rebuilding 标志防并发重建。
        listOf(
            settings.lowLatency,
            settings.float32Processing,
            settings.volumeNormalize,
            settings.silenceRemover,
            settings.bufferMs,
            settings.replaygainMode
        ).forEach { flow ->
            scope.launch {
                flow.distinctUntilChanged().drop(1).debounce(300).collect {
                    rebuildPlayer()
                }
            }
        }
    }

    /** F3-1：Gapless 无缝连播——开启时对当前播放器强制采样精确 seek，切歌/回跳不再按同步帧对齐丢弃采样，保证音轨衔接连续。 */
    private fun applyGapless() {
        runCatching {
            exoPlayer.setSeekParameters(
                if (gapless) androidx.media3.exoplayer.SeekParameters.EXACT
                else androidx.media3.exoplayer.SeekParameters.DEFAULT
            )
        }
    }

    // ===== P2：监听文件夹变更（watch_folders） =====

    private fun updateMediaStoreObserver() {
        if (watchFolders) {
            if (mediaStoreObserver == null) {
                mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = rescanLocal()
                }
                runCatching {
                    context.contentResolver.registerContentObserver(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver!!
                    )
                }
            }
        } else {
            mediaStoreObserver?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
            mediaStoreObserver = null
        }
    }

    /** 去抖后重扫本地曲库（MediaStore 内容变更时）。 */
    private fun rescanLocal() {
        val now = System.currentTimeMillis()
        if (now - lastRescan < 3000) return
        lastRescan = now
        scope.launch(Dispatchers.IO) {
            val sources = runCatching { libraryRepository.getMusicSources().first() }.getOrDefault(emptyList())
            // P1-7：MediaStore 变化只与本地 SAF 曲库相关——仅重扫 LOCAL 来源，
            // 避免触发 SMB/WebDAV 逐文件 128KB 前缀探测（大曲库重扫网络开销大）。
            val localSources = sources.filter { it.type == MediaSourceType.LOCAL }
            // L：重扫前校验 LOCAL 源根可访问性，权限被撤销/目录被移除的源跳过，避免对其静默空扫清库
            val accessible = localSources.filter { libraryRepository.localSourceAccessible(it) }
            if (accessible.isEmpty()) return@launch
            runCatching { libraryRepository.scanAndPersist(accessible) }
        }
    }

    // ===== P2：音频输出路由（audio_route） =====

    private fun applyAudioRoute() {
        audioRoutingController.setRoute(audioRoute, context)
    }

    // ===== 播放命令 =====

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        scope.launch { playQueueInternal(songs, startIndex, seekToMs = 0L, seekTo = false) }
    }

    /** 断点续播专用：异步建队后按指定位置定位（避免 startIndex 逻辑与 seek 竞态）。 */
    suspend fun playQueueAndSeek(songs: List<Song>, startIndex: Int, positionMs: Long) {
        playQueueInternal(songs, startIndex, positionMs, seekTo = true)
    }

    private suspend fun playQueueInternal(songs: List<Song>, startIndex: Int, seekToMs: Long, seekTo: Boolean) {
        if (songs.isEmpty()) return
        // A2：队列切换（锁定 ExoPlayer + MediaPlayer、setQueue、中心窗口装载、定位）整体送入
        // PlaybackActor 单线程队列，与 navigateTo/rebuildPlayer 串行，避免「withContext(Default)
        // 建 MediaItem 挂起期间另一协程基于过期状态改写 player」的 TOCTOU 竞态。
        playbackActor.execute {
            // 2026-08-19 修复：切换队列前先把两个播放器都静音，杜绝「ExoPlayer 旧曲 + MediaPlayer 兜底新曲」
            // 同时出声的播放冲突。原实现仅在非兜底分支调用 stopFallbackIfNeeded()，漏了「ExoPlayer 播放中
            // 点击 APE/WMA」时未暂停 ExoPlayer 的路径（navigateTo 同分支有 pause，此处补齐统一）。
            runCatching { exoPlayer.pause() }
            stopFallbackIfNeeded()
            // 2026-09-12 跨模式互斥：音乐起播时强制停电台流（电台是独立 ExoPlayer，不与音乐共享实例），
            // 防止冷启动自动恢复与手动点歌时电台链仍在后台出声造成两条音频链混音为「两首歌」。
            runCatching { radioPlayer.suppressForMusicMode() }
            runCatching { radioPlayer.cancelSleepForMusicMode() }
            queueController.setQueue(songs, startIndex)
            queueManager.altAttempts.clear()
            ensureServiceStarted()
            // 起始曲目是 APE/WMA → 系统 MediaPlayer 兜底（本地 / 已缓存 / 下载后系统解码）
            val startSong = songs.getOrNull(startIndex)
            if (startSong != null) {
                when (fallbackRoute(startSong)) {
                    FallbackRoute.READY -> {
                        // T-铃声/焦点策略：兜底播放同样经受音频焦点门控，避免通话/其它播放器占焦时混音
                        if (!playbackController.ensureFocusForPlay()) {
                            Log.i("PlayerManager", "焦点未取得，拦截 APE/WMA 兜底播放：${startSong.title}")
                            _playbackState.value = _playbackState.value.copy(isPlaying = false, buffering = false)
                            return@execute
                        }
                        mediaPlayerFallback.play(startSong, fallbackDataSource(startSong))
                        _playbackState.value = _playbackState.value.copy(
                            currentSong = startSong,
                            isPlaying = true,
                            positionMs = 0,
                            durationMs = startSong.durationMs,
                            buffering = false
                        )
                        emitFull()
                        persistSession()
                        return@execute
                    }
                    FallbackRoute.DOWNLOAD -> {
                        // 远端 APE/WMA 未缓存：加载态，下载完成后系统解码
                        _playbackState.value = _playbackState.value.copy(
                            currentSong = startSong,
                            isPlaying = false,
                            positionMs = 0,
                            durationMs = startSong.durationMs,
                            buffering = true
                        )
                        emitFull()
                        persistSession()
                        launchRemoteFallback(startSong)
                        return@execute
                    }
                    FallbackRoute.NO -> Unit // 继续下方 ExoPlayer 主链路
                }
            }
            // 大曲库（网络源全量入库可达数万首）：MediaItem 构建在后台线程执行，
            // 且只装载当前窗口（避免全量时间线触发 MediaSessionCompat.setQueue OOM 与主线程 ANR）。
            // 2026-08-18：窗口内 APE/WMA 由系统 MediaPlayer 兜底，不放入 ExoPlayer 队列
            // （避免 MediaCodecAudioRenderer 解码 APE 报错）；映射表 fullToExo 记录 fullIdx→exoIdx。
            val (exoSongs, exoIndex) = queueManager.centerWindow(songs, startIndex)
            val items = withContext(Dispatchers.Default) { exoSongs.map { mediaItemBuilder.build(it) } }
            exoPlayer.setMediaItems(items, exoIndex, 0L)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            if (seekTo) exoPlayer.seekTo(seekToMs)
            emitFull()
            persistSession()
        }
    }

    fun play() = playbackController.play()
    fun pause() = playbackController.pause()
    fun togglePlayPause() = playbackController.togglePlayPause()
    /** 停止播放：保存当前播放歌曲不变，仅将播放进度归零并停止播放（需求：停止后仍显示当前歌曲）。 */
    fun stop() {
        // 记住停止前正在呈现的歌曲——emitFull() 会按 currentIndex 重算 currentSong，
        // 若停止瞬间 currentIndex 已被自动连播推进，或者当前曲走了兜底(fallback)播放未同步 currentIndex，
        // 都会取到别的歌。这里在停止后把 currentIndex 同步指回该曲并强制保持，保证后续刷新一致。
        val keep = _playbackState.value.currentSong
        playbackController.stop()
        // BG-停播歌词清理：复位当前歌词与车载 AVRCP，避免停播后残留歌词显示
        lyricLinesStore.clear()
        clearCarBtLyric()
        _currentLyricLine.value = null
        if (keep != null) {
            val keepIdx = queueController.queue.indexOfFirst { it.id == keep.id }
            if (keepIdx >= 0) queueController.setCurrentIndex(keepIdx)
            _playbackState.value = _playbackState.value.copy(
                currentSong = keep,
                isPlaying = false,
                positionMs = 0,
                durationMs = keep.durationMs,
                currentIndex = if (keepIdx >= 0) keepIdx else _playbackState.value.currentIndex
            )
        }
    }
    fun next() = playbackController.next()
    fun previous() = playbackController.previous()
    fun seekTo(ms: Long) = playbackController.seekTo(ms)

    /** §12 R5：返回播放位置的实时毫秒值（主线程访问 ExoPlayer/MediaPlayer）。 */
    fun livePositionMs(): Long = playbackController.livePositionMs()

    /** 跳转到队列指定项（P-09 队列编辑）。 */
    fun seekToIndex(index: Int) = playbackController.seekToIndex(index)

    /** 导航到全量队列索引（已迁至 PlaybackController）。 */
    fun navigateTo(fullIdx: Int) = playbackController.navigateTo(fullIdx)

    /** 兜底格式扩展名（APE/WMA）；非兜底格式返回 null。 */
    private fun fallbackExt(song: Song): String? {
        val ext = song.path?.substringAfterLast('.', "")
            ?: song.uri.substringAfterLast('.', "").substringBefore('?')
        return ext.takeIf { MediaPlayerFallback.supportsExtension(it) }
    }

    /**
     * 兜底播放路由（R-B5 远端 FFmpeg 解不动 → 下载后系统解码）。
     * - 本地(无鉴权) APE/WMA → [READY]（MediaPlayer 直接读原 uri）
     * - 远端 APE/WMA 且已本地缓存 → [READY]（用缓存文件路径）
     * - 远端 APE/WMA 未缓存 → [DOWNLOAD]（先整首下载再交系统 MediaPlayer 解码）
     * - 其余 → [NO]（ExoPlayer 主链路）
     */
    private fun fallbackRoute(song: Song): FallbackRoute {
        if (isLocalUri(song.uri)) {
            return if (fallbackExt(song) != null) FallbackRoute.READY else FallbackRoute.NO
        }
        if (fallbackExt(song) == null) return FallbackRoute.NO
        return if (musicCache.filePathFor(song.uri) != null) FallbackRoute.READY else FallbackRoute.DOWNLOAD
    }

    /** 兜底播放实际喂给系统 MediaPlayer 的数据源：远端已缓存 → 缓存绝对路径；本地 → 原 uri。 */
    private fun fallbackDataSource(song: Song): String =
        musicCache.filePathFor(song.uri) ?: song.uri

    /** 曲目 URI 是否为本地存储（无鉴权、MediaPlayer 可直接打开）。 */
    private fun isLocalUri(uri: String): Boolean {
        val scheme = uri.substringBefore("://").lowercase()
        return scheme == "file" || scheme == "content" || scheme.isBlank() || uri.startsWith("/")
    }

    /**
     * 远端 APE/WMA（FFmpeg 解不动）：先整首下载到本地缓存，再以本地 Uri 交给系统 MediaPlayer
     * 解码。下载期间置 [PlaybackState.buffering]，下载完成复核仍为当前曲目才切换
     * （避免下载期间用户切走导致串台），下载失败自动跳下一首。
     */
    private fun launchRemoteFallback(song: Song) {
        scope.launch {
            val path = withContext(Dispatchers.IO) { musicCache.ensureCached(song) }
            playbackActor.execute {
                if (path == null) {
                    _playbackState.value = _playbackState.value.copy(
                        currentSong = song, isPlaying = false, buffering = false
                    )
                    Log.w("PlayerManager", "${song.title} 下载失败，无法播放，跳下一首")
                    emitFull()
                    val idx = queueController.nextIndex()
                    if (idx >= 0) playbackController.navigateTo(idx) else exoPlayer.pause()
                } else if (queueController.current()?.id == song.id) {
                    // V-焦点复校：下载期间焦点可能被抢，续播前复校且未持焦则不让权（避免与通话/他播放器混音）
                    if (playbackController.ensureFocusForPlay()) {
                        stopFallbackIfNeeded()
                        mediaPlayerFallback.play(song, path)
                        _playbackState.value = _playbackState.value.copy(
                            currentSong = song, isPlaying = true, buffering = false,
                            positionMs = 0, durationMs = song.durationMs
                        )
                        emitFull()
                        persistSession()
                    } else {
                        Log.i("PlayerManager", "焦点未取得，拦截兜底续播：${song.title}")
                        _playbackState.value = _playbackState.value.copy(isPlaying = false, buffering = false)
                        emitFull()
                        val idx = queueController.nextIndex()
                        if (idx >= 0) playbackController.navigateTo(idx) else exoPlayer.pause()
                    }
                }
            }
        }
    }

    /** 若正在用 MediaPlayer 兜底播放，则停止（切回 ExoPlayer 前调用）。 */
    private fun stopFallbackIfNeeded() {
        if (mediaPlayerFallback.active) mediaPlayerFallback.stop()
    }

    /** 兜底播放的进度桥接（MediaPlayer 轮询 → 播放状态）。 */
    private fun observeFallback() {
        scope.launch {
            mediaPlayerFallback.state.collect { st ->
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = st.isPlaying,
                    positionMs = st.positionMs,
                    durationMs = st.durationMs.coerceAtLeast(_playbackState.value.durationMs),
                    currentSong = st.song ?: _playbackState.value.currentSong
                )
            }
        }
    }

    /** 当前曲目切换到下一个备用源重试（R2-01 多源回退，失败源整曲重播）。 */
    private fun retryCurrentWithAlt(uri: String) {
        val cur = queueController.current() ?: return
        val exoIdx = exoPlayer.currentMediaItemIndex
        val item = mediaItemBuilder.build(cur.copy(uri = uri))
        runCatching { exoPlayer.replaceMediaItem(exoIdx, item) }
        exoPlayer.prepare()
        exoPlayer.play()
    }

    /** 重读当前曲目最新状态（评分/播放统计等修改后刷新 UI 用）。 */
    fun refreshCurrentSong() {
        val cur = queueController.current() ?: return
        scope.launch(Dispatchers.IO) {
            val fresh = runCatching { songDao.getById(cur.id)?.toModel() }.getOrNull()
            if (fresh != null) {
                val idx = queueController.currentIndex
                queueController.updateAt(idx, fresh)
                // 重建当前 mediaItem 的 metadata：普通格式锁屏/下拉栏标题读自 ExoPlayer 的
                // mediaMetadata（非兜底），不重建则匹配/编辑后的新标题无法即时反映（见 FallbackAwarePlayer）。
                val item = runCatching { mediaItemBuilder.build(fresh) }.getOrNull()
                scope.launch {
                    if (item != null) {
                        val exoIdx = exoPlayer.currentMediaItemIndex
                        if (exoIdx >= 0) runCatching { exoPlayer.replaceMediaItem(exoIdx, item) }
                    }
                    emitFull()
                }
            }
        }
    }

    /** [9] 远程来源首次播放后回填真实时长：曲目切换后轮询 ExoPlayer 时长，
     *  就绪且仍在同一曲目时写入数据库，避免列表恒显 0:00 / 「—」。
     *  ExoPlayer 必须在主线程（Looper）访问，故时长读取在 scope(Dispatchers.Main) 上完成，
     *  仅数据库写入切到 IO。 */
    private fun persistDurationIfNeeded(song: Song) {

        val sid = song.id
        val suri = song.uri
        scope.launch {
            var dur = exoPlayer.duration
            var tries = 0
            while (dur <= 0 && tries < 20 && isActive) {
                delay(250)
                dur = exoPlayer.duration
                tries++
            }
            if (dur > 0 && queueController.current()?.let { it.id == sid && it.uri == suri } == true) {
                withContext(Dispatchers.IO) {
                    runCatching { songDao.setDurationMs(sid, dur) }
                }
                queueController.current()?.let { cur ->
                    if (cur.id == sid) queueController.updateAt(queueController.currentIndex, cur.copy(durationMs = dur))
                }
            }
        }
    }

    /** 音量（线性 0..1，按音量曲线映射后应用；已迁至 PlaybackController）。 */
    fun setVolume(linear: Float) = playbackController.setVolume(linear)

    /** 从队列移除指定项（P-09 队列编辑）。窗口同步与索引修正已迁至 QueueManager。 */
    fun removeAt(index: Int) = queueManager.removeAt(index)

    /** 清空整个队列并停止播放。 */
    fun clearQueue() = queueManager.clear()

    /** AZ-删除对账：把 DB 已不存在的队列曲目从内存队列剔除并重定位当前索引。主线程收集、DAO 查库在 Room 执行器。 */
    private suspend fun pruneGhostsFromQueue() {
        val q = queueController.queue
        if (q.isEmpty()) return
        val existing = runCatching { songDao.getExistingIds(q.map { it.id }) }.getOrElse { return }.toHashSet()
        if (existing.size == q.size && q.all { it.id in existing }) return
        // 从尾部往下删，保证已删元素上方的索引不受影响（removeAt 会同步窗口与当前索引）。
        var i = queueController.queue.size - 1
        while (i >= 0) {
            if (queueController.queue.getOrNull(i)?.id !in existing) queueManager.removeAt(i)
            i--
        }
        emitFull()
    }

    /** 睡眠定时（C-04）：minutes <= 0 取消。已迁至 PlaybackController。 */
    fun setSleepTimer(minutes: Int) = playbackController.setSleepTimer(minutes)

    /** 队列重排（P-09 队列编辑）：from -> to，含当前曲目索引修正。窗口同步已迁至 QueueManager。 */
    fun moveQueueItem(from: Int, to: Int) = queueManager.moveQueueItem(from, to)

    fun setRepeatMode(mode: RepeatMode) {
        queueController.setRepeatMode(mode)
        exoPlayer.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        emitFull()
    }

    fun toggleShuffle() {
        queueController.toggleShuffle()
        // 随机顺序完全由 QueueController 计算（避免 ExoPlayer 的 shuffleModeEnabled 改变索引空间，
        // 导致 seekTo(index) 与队列索引不一致）；ExoPlayer 保持线性。
        exoPlayer.shuffleModeEnabled = false
        emitFull()
    }

    fun enqueueNext(song: Song) = queueManager.enqueueNext(song)
    /** F2-1「稍后播放」：追加到队尾。 */
    fun enqueueTail(song: Song) = queueManager.enqueue(song)

    // ===== 断点续播（R-P1-07） =====

    // A1 步骤 1：节流与恢复标记已随逻辑迁入 SessionPersister；
    // 持久化实际写库由 SessionPersister 在独立 IO scope 执行（行为等价：同步节流判定 + 异步写）。

    /** 持久化当前会话快照（门面：转交 SessionPersister）。 */
    private fun persistSession() {
        val queue = queueController.queue
        val idx = queueController.currentIndex
        val pos = exoPlayer.currentPosition.coerceAtLeast(0)
        sessionPersister.persist(queue, idx, pos)
    }

    /** 启动时恢复上次播放会话（无弹窗，自动）。受 auto_resume / remember_position 设置约束。
     *  数据由 SessionPersister 构造，本方法仅做续播决策。仅进程首次执行。 */
    suspend fun restoreLastSession() {
        val snap = sessionPersister.buildRestoreSnapshot() ?: return
        val songs = snap.songs
        val startIndex = snap.startIndex
        val position = snap.positionMs
        val action = snap.action
        // 仅 resume 恢复播放位置，且受 remember_position 设置约束
        if (action == "resume") {
            val rememberPos = runCatching { settings.rememberPosition.first() }.getOrDefault(true)
            val target = songs.getOrNull(startIndex)?.durationMs ?: 0L
            if (rememberPos && position in 1 until target) {
                playQueueAndSeek(songs, startIndex, position)
            } else {
                playQueue(songs, startIndex)
            }
        } else {
            playQueue(songs, startIndex)
        }
        if (action == "play") exoPlayer.play()
    }

    /** 模式切换时恢复音乐队列（不受 restoreChecked 限制）。仅恢复队列和位置，不自动播放。 */
    suspend fun restoreSessionForModeSwitch() {
        val ids = runCatching { settings.lastQueueIds.first() }.getOrDefault(emptyList())
        if (ids.isEmpty()) return
        val index = runCatching { settings.lastIndex.first() }.getOrDefault(-1)
        val position = runCatching { settings.lastPosition.first() }.getOrDefault(0L)
        val songs = try {
            songDao.getByIds(ids).associateBy { it.id }.let { byId -> ids.mapNotNull { byId[it]?.toModel() } }
        } catch (e: Exception) { emptyList() }
        if (songs.isEmpty()) return
        val currentId = ids.getOrNull(index.coerceIn(0, ids.size - 1))
        val startIndex = if (currentId != null) {
            songs.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: index.coerceIn(0, songs.size - 1)
        } else {
            index.coerceIn(0, songs.size - 1)
        }
        playQueueAndSeek(songs, startIndex, position.coerceAtLeast(0))
    }

    // ===== 服务 =====

    private fun ensureServiceStarted() {
        runCatching {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }
    }

    // ===== P0 解码扩展：播放前设备能力与格式校验探测 =====

    /** 播放前检查当前曲目是否可播放：formatVerified=false 提示文件损坏；系统直通格式设备不支持则提示并跳下一首。
     *  返回 true 表示可播放，false 表示已触发跳下一首（调用方应中止后续操作）。 */
    private fun checkPlayableOrSkip(song: Song): Boolean {
        // 中危-E：远端 HTTP URI 可能带 ?query/签名串，须先掐掉再取扩展名，否则 ext 含
        // '?xxx' 落在空白/未知扩展，跳过下方格式校验（ext.isBlank()→直接放行）。
        val ext = (song.path?.substringAfterLast('.', "")
            ?: song.uri.substringAfterLast('.', ""))
            .substringBefore('?')
            .lowercase()
        if (ext.isBlank()) return true
        // 1. 文件头魔数校验失败 → 文件损坏或扩展名伪造
        if (!song.formatVerified) {
            Log.w("PlayerManager", "格式校验失败，文件损坏或扩展名伪造：${song.title}（ext=$ext）")
            context.toast(context.getString(R.string.toast_corrupt_file))
            if (skipOnError) advancePastBroken()
            return false
        }
        // 2. P0 系统直通格式：设备 MediaCodec 不支持 → 提示并跳下一首
        val decodePath = AudioFormatRegistry.decodePathOf(ext)
        val mime = song.mimeType ?: AudioFormatRegistry.mimeTypeOf(ext)
        if (decodePath == AudioFormatRegistry.DecodePath.SYSTEM && mime != null) {
            if (!deviceCodecProbe.supports(mime)) {
                val desc = AudioFormatRegistry.allFormats.firstOrNull { it.extension == ext }?.description ?: ext
                Log.w("PlayerManager", "设备不支持解码：$desc（mime=$mime）")
                context.toast(context.getString(R.string.toast_unsupported_decode, desc))
                if (skipOnError) advancePastBroken()
                return false
            }
        }
        return true
    }

    // AS-单曲循环护栏：自动跳过坏曲时，若下一目标仍是当前坏曲（RepeatMode.ONE），
    // 不再无限重试，改为暂停并提示，避免死循环。
    private fun advancePastBroken() {
        val idx = queueController.nextIndex()
        if (idx >= 0 && idx != queueController.currentIndex) {
            playbackController.navigateTo(idx)
        } else {
            runCatching { exoPlayer.pause() }
            context.toast(context.getString(R.string.toast_unplayable_retry))
        }
    }

    // ===== 监听 =====

    private inner class PlayerListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                // 音频焦点强制复核（2026-08-24）：即便某条路径绕过 play()/navigateTo 直接开播
                // （热重建 playWhenReady 恢复、自动续播等），也开始播放即确认已持焦点；
                // 未获焦（如其它播放器占用）则立即暂停，杜绝混音。
                if (!playbackController.ensureFocusForPlay()) {
                    Log.w("AudioFocus", "onIsPlayingChanged=true 但未持音频焦点，立即暂停")
                    runCatching { exoPlayer.pause() }
                } else {
                    persistSession()
                }
            }
            emitFull()
        }
        override fun onPlaybackStateChanged(state: Int) = emitFull()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 2026-08-18：ExoPlayer 窗口已剔除 APE/WMA，exoIdx → fullIdx 经 fullToExo 逆映射
            val exoIdx = exoPlayer.currentMediaItemIndex
            val fullIdx = queueManager.fullToExo.entries.firstOrNull { it.value == exoIdx }?.key
                ?: (queueManager.windowStart + exoIdx)
            queueController.setCurrentIndex(fullIdx)
            // BG-切歌歌词清理：清空歌词源与已下发车载歌词，避免上一首歌词串行到新曲
            lyricLinesStore.clear()
            clearCarBtLyric()
            _currentLyricLine.value = null
            persistSession()
            val current = queueController.current()
            if (current != null) {
                // 2026-08-18：APE/WMA 走系统 MediaPlayer 兜底（ExoPlayer 无容器解析器）；R-B5：远端未缓存先下载。
                when (fallbackRoute(current)) {
                    FallbackRoute.READY -> {
                        // 暂停 ExoPlayer（避免其尝试解码 APE 报错），转系统 MediaPlayer 播放
                        runCatching { exoPlayer.pause() }
                        stopFallbackIfNeeded()
                        // T-铃声/焦点策略：切到兜底同样经受音频焦点门控，避免通话/其它播放器占焦时混音
                        if (!playbackController.ensureFocusForPlay()) {
                            _playbackState.value = _playbackState.value.copy(isPlaying = false, buffering = false)
                            return
                        }
                        mediaPlayerFallback.play(current, fallbackDataSource(current))
                        _playbackState.value = _playbackState.value.copy(
                            currentSong = current,
                            isPlaying = true,
                            buffering = false,
                            positionMs = 0,
                            durationMs = current.durationMs
                        )
                        emitFull()
                        persistSession()
                        queueManager.maybeExtendWindow()
                        emitFull()
                        return
                    }
                    FallbackRoute.DOWNLOAD -> {
                        runCatching { exoPlayer.pause() }
                        stopFallbackIfNeeded()
                        _playbackState.value = _playbackState.value.copy(
                            currentSong = current,
                            isPlaying = false,
                            buffering = true,
                            positionMs = 0,
                            durationMs = current.durationMs
                        )
                        emitFull()
                        persistSession()
                        launchRemoteFallback(current)
                        queueManager.maybeExtendWindow()
                        emitFull()
                        return
                    }
                    FallbackRoute.NO -> stopFallbackIfNeeded()
                }
                // P0 解码扩展：播放前设备能力与格式校验探测，不可播放则跳下一首
                if (!checkPlayableOrSkip(current)) {
                    emitFull()
                    return
                }
                // 播放统计
                scope.launch(Dispatchers.IO) {
                    val prevCount = runCatching { songDao.getById(current.id)?.playCount ?: 0 }.getOrDefault(0)
                    runCatching { songDao.updatePlayStats(current.id, prevCount + 1, System.currentTimeMillis()) }
                }
                if (autoEqByGenre) equalizerManager.applyGenrePreset(current.genre)
                applyAudioRoute()
                // [9] 远程来源首次播放后回填真实时长，避免列表恒显 0:00 / 「—」
                persistDurationIfNeeded(current)
                // 网络源本地缓存：播放即自动缓存原格式到本机（离网也能播；内部分按 SMB/WebDAV 过滤）
                musicCache.requestCache(current)
            }
            queueManager.maybeExtendWindow()
            emitFull()
        }
        override fun onRepeatModeChanged(repeatMode: Int) = emitFull()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = emitFull()
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val curIdx = queueController.currentIndex
            val song = queueController.queue.getOrNull(curIdx)
            val alts = song?.altUris ?: emptyList()
            val tried = queueManager.altAttempts[curIdx] ?: 0
            Log.w("PlayerManager", "播放错误：${error.message}（skipOnError=$skipOnError curIdx=$curIdx winStart=${queueManager.windowStart} exoIdx=${exoPlayer.currentMediaItemIndex} 备用源剩余=${(alts.size - tried).coerceAtLeast(0)}）")
            if (skipOnError && tried < alts.size) {
                queueManager.altAttempts[curIdx] = tried + 1
                retryCurrentWithAlt(alts[tried])
            } else {
                queueManager.altAttempts[curIdx] = 0
                val idx = queueController.nextIndex()
                // AS-单曲循环护栏：RepeatMode.ONE 下 nextIndex 仍指向当前坏曲，若再 navigateTo
                // 会无限重试同一首；改为暂停并提示。正常态（idx 有值且非当前曲）保持自动跳下一首。
                if (idx >= 0 && idx != curIdx) {
                    playbackController.navigateTo(idx)
                } else {
                    runCatching { exoPlayer.pause() }
                    context.toast(context.getString(R.string.toast_play_failed_stopped))
                }
            }
            emitFull()
        }
    }

    private fun startProgressLoop() {
        scope.launch {
            while (isActive) {
                if (exoPlayer.isPlaying) {
                    checkAbLoopSeekBack()
                    emitProgress()
                    persistSession()
                    updateLyricLine()
                }
                delay(if (exoPlayer.isPlaying) 500 else 2000)
            }
        }
    }

    /** 更新当前歌词行（供通知 / 车载蓝牙显示）。 */
    private fun updateLyricLine() {
        val line = lyricLinesStore.lineAt(exoPlayer.currentPosition.coerceAtLeast(0))
        if (line != _currentLyricLine.value) {
            _currentLyricLine.value = line
            pushCarBtLyric(line)
        }
    }

    /**
     * 车载蓝牙歌词（AVRCP 3.0 scrobble）：歌词行变化时更新当前 mediaItem 的 DESCRIPTION，
     * 系统经蓝牙媒体下发到车载屏幕。开关关闭时直接跳过（已下发内容由 [clearCarBtLyric] 清空）。
     */
    private fun pushCarBtLyric(line: String?) {
        if (!settings.carBtLyricsSync()) return
        val finalLine = line?.takeIf { it.isNotBlank() }
        if (finalLine == lastBroadcastCarLyric) return
        lastBroadcastCarLyric = finalLine
        replaceLyricMetadata(finalLine)
    }

    /** 车载蓝牙歌词：开关关闭时立即清除已下发到媒体会话的歌词（避免车载屏残留最后一行）。 */
    fun clearCarBtLyric() {
        if (lastBroadcastCarLyric == null) return
        lastBroadcastCarLyric = null
        replaceLyricMetadata(null)
    }

    /** 基于当前 mediaItem 仅替换歌词 description（保持其余播放内容不变），再 replaceMediaItem 下发到车载端。 */
    private fun replaceLyricMetadata(description: String?) {
        val idx = exoPlayer.currentMediaItemIndex
        if (idx < 0) return
        val cur = exoPlayer.currentMediaItem ?: return
        val newMeta = runCatching {
            (cur.mediaMetadata ?: MediaMetadata.EMPTY).buildUpon().setDescription(description).build()
        }.getOrNull() ?: return
        val item = runCatching { cur.buildUpon().setMediaMetadata(newMeta).build() }.getOrNull() ?: return
        runCatching { exoPlayer.replaceMediaItem(idx, item) }
    }

    private fun emitProgress() {
        _playbackState.value = _playbackState.value.copy(
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0),
            durationMs = exoPlayer.duration.coerceAtLeast(0)
        )
    }

    private fun emitFull() {
        // 2026-08-18：兜底播放（APE/WMA 经系统 MediaPlayer）时，播放状态从 MediaPlayerFallback 读取，
        // 避免读到 ExoPlayer 停止状态导致 UI 显示异常。
        val fb = mediaPlayerFallback.state.value
        val fbActive = mediaPlayerFallback.active
        _playbackState.value = PlaybackState(
            currentSong = if (fbActive && fb.song != null) fb.song else queueController.current(),
            isPlaying = if (fbActive) fb.isPlaying else exoPlayer.isPlaying,
            positionMs = if (fbActive) fb.positionMs else exoPlayer.currentPosition.coerceAtLeast(0),
            durationMs = if (fbActive && fb.durationMs > 0) fb.durationMs else exoPlayer.duration.coerceAtLeast(0),
            repeatMode = queueController.repeatMode,
            shuffle = queueController.shuffle,
            // P2-8：toList() 拷贝快照，避免 UI 持有队列同一引用被后续 mutate 污染
            queue = queueController.queue.toList(),
            currentIndex = queueController.currentIndex,
            playbackSpeed = if (fbActive) fb.playbackSpeed else exoPlayer.playbackParameters.speed
        )
    }

    /**
     * 设置播放速度（仅作用于当前会话，重启后恢复 1.0）。
     * 0.5x-2.0x 范围；不在兜底播放时（fbActive）不生效（系统 MediaPlayer 不支持变速）。
     */
    fun setPlaybackSpeed(speed: Float) {
        if (mediaPlayerFallback.active) {
            // 兜底播放器不支持变速，忽略
            return
        }
        exoPlayer.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 2.0f))
        emitFull()
    }

    // ---- AB 循环 ----

    /**
     * 设置 AB 循环点。
     * @param startMs A 点（毫秒），null 清除起点
     * @param endMs B 点（毫秒），null 表示仅设置 A 点
     */
    fun setAbLoop(startMs: Long? = null, endMs: Long? = null) {
        _playbackState.value = _playbackState.value.copy(
            loopStartMs = startMs,
            loopEndMs = endMs
        )
        emitFull()
    }

    /** 清除 AB 循环。 */
    fun clearAbLoop() {
        _playbackState.value = _playbackState.value.copy(
            loopStartMs = null,
            loopEndMs = null
        )
        emitFull()
    }

    /**
     * 播放进度轮询中调用：如果 AB 循环已激活且 position >= B，seek 回 A。
     * 在 emitProgress / emitFull 之前调用。
     */
    private fun checkAbLoopSeekBack() {
        val s = _playbackState.value
        val start = s.loopStartMs ?: return
        val end = s.loopEndMs ?: return
        if (!s.isPlaying) return
        val pos = if (mediaPlayerFallback.active) s.positionMs else exoPlayer.currentPosition
        if (pos >= end) {
            if (mediaPlayerFallback.active) {
                mediaPlayerFallback.seekTo(start)
            } else {
                exoPlayer.seekTo(start)
            }
        }
    }
}