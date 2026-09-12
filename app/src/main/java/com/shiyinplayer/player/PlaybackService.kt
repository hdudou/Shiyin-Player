package com.shiyinplayer.player

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import com.shiyinplayer.ui.MainActivity
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.Player
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.CommandButton
import coil.Coil
import coil.request.ImageRequest
import com.google.common.collect.ImmutableList
import com.shiyinplayer.ui.settings.SettingsRepository
import com.shiyinplayer.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 媒体播放前台服务（架构 §1.2.3）：MediaSessionService + MediaStyle 通知。
 * foregroundServiceType=mediaPlayback（已在 Manifest 声明）。无 VpnService（v2.1）。
 * 通知副文本显示实时歌词行（由 PlayerManager.currentLyricLine 驱动刷新）。
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    private val TAG = "PlaybackService"

    @Inject
    lateinit var mediaSessionManager: MediaSessionManager

    @Inject
    lateinit var playerManager: PlayerManager

    @Inject
    lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lyricRefreshJob: Job? = null
    private var progressRefreshJob: Job? = null
    private var currentLyric: String? = null
    private var lastProgressSec = -1L
    private var notifyCallback: MediaNotification.Provider.Callback? = null
    private var lastSession: MediaSession? = null
    private var lastCommandButtons: ImmutableList<CommandButton> = ImmutableList.of()
    private var lastActionFactory: MediaNotification.ActionFactory? = null
    private var largeIcon: Bitmap? = null
    // BO-封面位图 LRU 淘汰（access-order），仅保留最近 MAX_COVER_CACHE 首；全部访问在 Main 协程作用域。
    private val coverCache: MutableMap<String, Bitmap> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
                size > MAX_COVER_CACHE
        }
    )
    // AR-加载失败标记：在线封面下载/解码失败或 404 时记录，避免通知卡每帧对其重复发起加载。
    // BO-加上限，防失败 URL 只增；满时淘汰最旧失败项，给长期失效 URL 留重试机会。
    private val failedCovers = java.util.Collections.synchronizedSet(object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= MAX_FAILED_COVERS) remove(first())
            return super.add(element)
        }
    })
    private var foregroundStarted = false

    /** 播放通知开关（notify_enabled）：false 时不展示媒体控制卡片，仅保留满足前台服务约束的极简占位通知。 */
    private var notifyEnabled = true

    private val notificationProvider = object : MediaNotification.Provider {
        override fun createNotification(
            session: MediaSession,
            commandButtons: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationUpdated: MediaNotification.Provider.Callback
        ): MediaNotification {
            notifyCallback = onNotificationUpdated
            lastSession = session
            lastCommandButtons = commandButtons
            lastActionFactory = actionFactory
            // 始终构建完整媒体控制卡（含 MediaStyle + setMediaSession），确保锁屏/下拉栏显示控制按钮。
            return MediaNotification(Constants.NOTIFICATION_ID, buildFullCard(session))
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle
        ): Boolean = false
    }

    /** 满足前台服务约束的极简占位通知（无控制/封面），notify 关闭或前台保活时使用。
     *  使用 IMPORTANCE_MIN 通道，用户端不可见。 */
    private fun placeholderNotification(): android.app.Notification =
        NotificationCompat.Builder(this@PlaybackService, Constants.NOTIFICATION_CHANNEL_PLACEHOLDER_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(com.shiyinplayer.R.string.app_name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    /** 异步加载封面位图并刷新通知（仅加载一次，缓存于内存）。 */
    private fun loadCoverAsync(url: String) {
        if (coverCache[url] != null || failedCovers.contains(url)) return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val request = ImageRequest.Builder(this@PlaybackService)
                    .data(url)
                    .allowHardware(false)
                    // N-内存上限：通知封面仅需小图，限制解码尺寸，避免大封面（在线封面包）全分辨率软位图 OOM
                    .size(512, 512)
                    .build()
                val drawable = runCatching { Coil.imageLoader(this@PlaybackService).execute(request).drawable }.getOrNull()
                (drawable as? BitmapDrawable)?.bitmap ?: drawable?.toBitmap()
            }
            if (bitmap != null) {
                coverCache[url] = bitmap
                largeIcon = bitmap
                refreshNotification()
            } else {
                // AR-失败标记：避免通知卡每秒对同一失效 URL 重复发起加载（网络/404/解码失败）。
                failedCovers.add(url)
            }
        }
    }

    /**
     * 按当前歌词行重建并推送通知。AC-去重：以卡片关键内容（标题/歌词/歌手/播放态/封面是否
     * 已加载/进度）聚合签名，内容未变化时不重复 startForeground，避免下拉栏媒体卡片反复重建闪烁。
     */
    @Volatile private var lastNotifiedSig: String? = null
    private fun refreshNotification() {
        val session = lastSession ?: runCatching { mediaSessionManager.getOrCreate() }.getOrNull() ?: return
        val meta = session.player.mediaMetadata
        val coverUrl = playerManager.playbackState.value.currentSong?.albumArtUri
        val pState = playerManager.playbackState.value
        val sig = listOf(
            notifyEnabled,
            meta?.title?.toString(),
            currentLyric,
            meta?.artist?.toString(),
            session.player.isPlaying,
            coverUrl,
            coverUrl != null && coverCache.containsKey(coverUrl),
            pState.positionMs / 1000,
            pState.durationMs / 1000
        ).joinToString("|")
        if (sig == lastNotifiedSig) return
        lastNotifiedSig = sig
        // 始终构建完整媒体控制卡（含 MediaStyle + setMediaSession），确保锁屏/下拉栏显示控制按钮。
        // 之前的 placeholderNotification 使用占位通道，导致系统不渲染媒体控件。
        val card = buildFullCard(session)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, Constants.NOTIFICATION_ID, card,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(Constants.NOTIFICATION_ID, card)
            }
            foregroundStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed (foregroundStartAllowed=${foregroundStarted})", e)
        }
    }

    /** 构建完整的播放控制通知卡：标题/歌词/封面 + 上一首 → 播放暂停 → 下一首 + 进度。
     *  VISIBILITY_PUBLIC：锁屏显示完整媒体控制（按钮 + 歌曲信息），下拉栏显示控制卡片。 */
    private fun buildFullCard(session: MediaSession): Notification {
        val meta = session.player.mediaMetadata
        val title = meta?.title?.toString() ?: getString(com.shiyinplayer.R.string.app_name)
        // 歌词行显示在通知副文本（未播放到歌词前回退为歌手名）。
        val lyricLine = currentLyric?.takeIf { it.isNotBlank() }
        // 封面大图：当前曲目 albumArtUri（内嵌/在线）
        val coverUrl = playerManager.playbackState.value.currentSong?.albumArtUri
        val icon = coverUrl?.let { coverCache[it] } ?: largeIcon
        if (coverUrl != null && !coverCache.containsKey(coverUrl)) {
            loadCoverAsync(coverUrl)
        }
        val builder = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(lyricLine ?: meta?.artist?.toString())
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // PUBLIC：锁屏显示完整媒体控制 + 歌曲信息（锁屏媒体控制由 MediaSession 提供）。
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        // 媒体控制按钮（下拉栏）：渲染 上一首 → 播放/暂停 → 下一首 三个按钮，
        // 顺序与播放器内控制行一致，由显式服务 Intent 派发到 PlaybackController
        // （走基于队列的切歌语义），不依赖 media3 自动按钮计算。
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
        // 关键：把媒体通知链接到 MediaSession，SystemUI 才能据此在锁屏/通知栏渲染媒体控件。
        try {
            mediaStyle.setMediaSession(session.sessionCompatToken)
        } catch (e: Exception) {
            Log.w(TAG, "setMediaSession 失败，锁屏/通知栏媒体控件可能不显示", e)
        }
        val compactIndices = ArrayList<Int>(3)
        val isPlaying = session.player.isPlaying
        builder.addAction(
            controlAction(ACTION_PREV, android.R.drawable.ic_media_previous, getString(com.shiyinplayer.R.string.notification_action_prev), 1)
        ); compactIndices.add(0)
        builder.addAction(
            controlAction(
                ACTION_PLAY_PAUSE,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (isPlaying) com.shiyinplayer.R.string.notification_action_pause else com.shiyinplayer.R.string.notification_action_play), 2
            )
        ); compactIndices.add(1)
        builder.addAction(
            controlAction(ACTION_NEXT, android.R.drawable.ic_media_next, getString(com.shiyinplayer.R.string.notification_action_next), 3)
        ); compactIndices.add(2)
        if (compactIndices.isNotEmpty()) mediaStyle.setShowActionsInCompactView(*compactIndices.toIntArray())
        builder.setStyle(mediaStyle)
        // 进度条（通知栏进度显示；拖动依赖系统媒体控制，其经 MediaSession 自动启用 seek 命令）
        val posMs = playerManager.playbackState.value.positionMs
        val durMs = playerManager.playbackState.value.durationMs
        if (durMs > 0) {
            builder.setProgress(
                (durMs / 1000).toInt().coerceAtLeast(1),
                (posMs / 1000).toInt().coerceIn(0, (durMs / 1000).toInt()),
                false
            )
        }
        if (icon != null) builder.setLargeIcon(icon)
        return builder.build()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setMediaNotificationProvider(notificationProvider)
        // 同步读取通知开关初始值，避免 addSession 触发的首次通知闪烁（与 MediaSessionManager 同模式）。
        // P0-4：改读内存快照，消除主线程 runBlocking 阻塞（ANR）。运行中变更由下方 collect 实时监听。
        notifyEnabled = settings.notifyEnabledSync()
        // 主动把会话挂到服务上（media3 仅在媒体按钮 Intent 或 Controller 连接时才
        // 回调 onGetSession；本应用由 PlayerManager 直驱同一 ExoPlayer，无外部
        // Controller，若不 addSession 则通知管理器拿不到会话、永不 startForeground）。
        addSession(mediaSessionManager.getOrCreate())
        // 歌词行变化时刷新通知（仅当通知已创建）。
        lyricRefreshJob = scope.launch {
            playerManager.currentLyricLine.collect { line ->
                if (currentLyric != line) {
                    currentLyric = line
                    refreshNotification()
                }
            }
        }
        // 播放通知开关（notify_enabled）：切换时重建通知（媒体控制卡片 ↔ 极简占位）。
        scope.launch {
            settings.notifyEnabled.collect { enabled ->
                if (notifyEnabled != enabled) {
                    notifyEnabled = enabled
                    refreshNotification()
                }
            }
        }
        // 车载蓝牙歌词开关：关闭时立即清除已下发歌词（避免车载屏残留最后一行）。
        scope.launch {
            settings.carBtLyrics.collect { enabled ->
                if (!enabled) playerManager.clearCarBtLyric()
            }
        }
        // 播放中每秒刷新进度（位置变化 >=1s 时才重建，避免频繁刷新）。
        progressRefreshJob = scope.launch {
            while (true) {
                delay(1000)
                val state = playerManager.playbackState.value
                val sec = state.positionMs / 1000
                if (state.isPlaying && sec != lastProgressSec) {
                    lastProgressSec = sec
                    refreshNotification()
                }
            }
        }

        // M5：锁屏叠加层自动唤起 —— 播放开始时若 lockscreen_overlay_enabled 开关为 true，则自动启动叠加层
        var overlayAutoLaunched = false
        fun launchOverlay() {
            val intent = android.content.Intent(
                this@PlaybackService,
                com.shiyinplayer.lockscreen.LockScreenOverlayActivity::class.java
            ).apply {
                putExtra(com.shiyinplayer.lockscreen.LockScreenOverlayActivity.EXTRA_MANUAL_MODE, false)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(intent) }
        }
        scope.launch {
            playerManager.playbackState.collect { state ->
                if (state.isPlaying && state.currentSong != null && !overlayAutoLaunched) {
                    overlayAutoLaunched = true
                    if (settings.lockscreenOverlayEnabledSync()) launchOverlay()
                }
                if (!state.isPlaying) {
                    overlayAutoLaunched = false
                }
            }
        }
        // 补漏重建：叠加层/进程被系统回收后，若仍在播放且系统锁屏仍激活、叠加层已不在，则自动重建拉起。
        // 仅在系统锁屏激活时重建，避免用户在解锁后误把叠加层重新弹回。
        scope.launch {
            while (true) {
                delay(3_000)
                val s = playerManager.playbackState.value
                if (s.isPlaying && s.currentSong != null
                    && settings.lockscreenOverlayEnabledSync()
                    && !com.shiyinplayer.lockscreen.LockScreenOverlayActivity.isShowing
                ) {
                    val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    if (km.isKeyguardLocked) launchOverlay()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        mediaSessionManager.getOrCreate()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台约束兜底：Android 会因 startForegroundService() 后 5~10s 内未调用
        // startForeground() 而抛 ForegroundServiceDidNotStartInTimeException 崩溃。
        // 始终推送完整媒体控制卡（含 MediaStyle + setMediaSession），确保锁屏/下拉栏渲染控制按钮。
        runCatching {
            val session = mediaSessionManager.getOrCreate()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, Constants.NOTIFICATION_ID, buildFullCard(session),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(Constants.NOTIFICATION_ID, buildFullCard(session))
            }
            foregroundStarted = true
        }.onFailure { Log.w(TAG, "startForeground 兜底失败", it) }
        // 通知控制按钮（上一首/播放暂停/下一首）经由显式动作派发到播放控制器。
        // BW-分发兜底 try-catch，避免极端时序（服务重启中播放器未就绪）崩溃。
        try {
            when (intent?.action) {
                ACTION_PREV -> playerManager.playbackController.previous()
                ACTION_PLAY_PAUSE -> playerManager.playbackController.togglePlayPause()
                ACTION_NEXT -> playerManager.playbackController.next()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "通知控制动作派发失败", t)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        const val ACTION_PREV = "com.shiyinplayer.player.action.PREV"
        const val ACTION_PLAY_PAUSE = "com.shiyinplayer.player.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.shiyinplayer.player.action.NEXT"
        // BO-封面/失败 URL 缓存容量上限：防长时间会话位图与失败集合累积导致内存增长/永不重试。
        private const val MAX_COVER_CACHE = 40
        private const val MAX_FAILED_COVERS = 200
    }

    /** 构造一个跳转到 [PlaybackService]、派发指定控制动作的 [NotificationCompat.Action]。 */
    private fun controlAction(action: String, icon: Int, label: String, requestCode: Int): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action(icon, label, pi)
    }

    /** 极简占位通知：仅用于满足前台服务约束，无需封面/歌词（真正卡片由 Provider 随后刷新）。
     *  使用 IMPORTANCE_MIN 通道，用户端不可见。 */
    private fun foregroundPlaceholder() =
        NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_PLACEHOLDER_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(com.shiyinplayer.R.string.app_name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    /** EM/P1-11：划掉最近任务/通知时保活——播放中不停止服务（前台通知常驻，后台播放不被系统回收），
     *  停播才走默认清理。不调用 super 的 stopSelf 分支即可保持前台服务，无 Android 12+ 启动时序问题。
     *  EM-增强：用 playbackState.isPlaying 统一判断（含兜底 MediaPlayerFallback 播放场景），
     *  避免只看 exoPlayer 导致兜底格式播放时被误杀。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (playerManager.playbackState.value.isPlaying) return
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        lyricRefreshJob?.cancel()
        progressRefreshJob?.cancel()
        // J-通知清理：服务彻底销毁时移除前台状态与播放通知，避免下拉栏/通知栏残留
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else stopForeground(true)
        } catch (_: Exception) {
            runCatching { stopForeground(true) }
        }
        runCatching { NotificationManagerCompat.from(this).cancel(Constants.NOTIFICATION_ID) }
        // BO-释放服务销毁时缓存的封面位图与失败集合，避免位图强引用残留至 GC。
        coverCache.clear()
        failedCovers.clear()
        largeIcon = null
        mediaSessionManager.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val name = getString(com.shiyinplayer.R.string.notification_channel_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 媒体控制通道：IMPORTANCE_LOW 下拉栏显示控制卡片，VISIBILITY_PUBLIC 锁屏显示控制按钮。
            val mediaChannel = android.app.NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW
            )
            mediaChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            mediaChannel.setShowBadge(false)
            NotificationManagerCompat.from(this).createNotificationChannel(mediaChannel)
            // 占位通道：IMPORTANCE_MIN + VISIBILITY_SECRET，仅满足前台服务约束，用户端不可见。
            val placeholderChannel = android.app.NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_PLACEHOLDER_ID,
                getString(com.shiyinplayer.R.string.notification_channel_placeholder_name),
                NotificationManager.IMPORTANCE_MIN
            )
            placeholderChannel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            placeholderChannel.setShowBadge(false)
            placeholderChannel.enableVibration(false)
            placeholderChannel.setSound(null, null)
            NotificationManagerCompat.from(this).createNotificationChannel(placeholderChannel)
        } else {
            val mediaChannel = NotificationChannelCompat.Builder(
                Constants.NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
                .setName(name)
                .setShowBadge(false)
                .build()
            NotificationManagerCompat.from(this).createNotificationChannel(mediaChannel)
            val placeholderChannel = NotificationChannelCompat.Builder(
                Constants.NOTIFICATION_CHANNEL_PLACEHOLDER_ID,
                NotificationManagerCompat.IMPORTANCE_MIN
            )
                .setName(getString(com.shiyinplayer.R.string.notification_channel_placeholder_name))
                .setShowBadge(false)
                .build()
            NotificationManagerCompat.from(this).createNotificationChannel(placeholderChannel)
        }
    }
}
