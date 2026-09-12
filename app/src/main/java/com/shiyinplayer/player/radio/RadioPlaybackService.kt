package com.shiyinplayer.player.radio

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import com.shiyinplayer.ui.MainActivity
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
import javax.inject.Inject

/**
 * 收音机模式前台服务：MediaSession + MediaStyle 通知。
 * 与音乐 PlaybackService 共用通知通道，复用 MediaSession 架构实现锁屏/下拉栏控制。
 *
 * 通知/锁屏控制：上一首 | 播放/暂停 | 下一首（与播放页和迷你条按钮布局一致）
 * 媒体按键（耳机/蓝牙上一首下一首播放暂停）：通过 MediaSession.Callback.onMediaButtonEvent 路由
 */
@AndroidEntryPoint
class RadioPlaybackService : android.app.Service() {

    companion object {
        private const val TAG = "RadioPlaybackService"
        const val ACTION_PLAY_PAUSE = "com.shiyinplayer.radio.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.shiyinplayer.radio.ACTION_NEXT"
        const val ACTION_PREV = "com.shiyinplayer.radio.ACTION_PREV"
        const val ACTION_STOP = "com.shiyinplayer.radio.ACTION_STOP"
    }

    @Inject
    lateinit var radioPlayer: RadioPlayer

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var foregroundStarted = false
    private var mediaSession: MediaSession? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        // 监听电台状态变化，更新通知
        stateJob = scope.launch {
            radioPlayer.playbackState.collect { state ->
                if (state.isPlaying || state.isBuffering) {
                    refreshNotification(state)
                } else if (state.streamUrl != null) {
                    // 已暂停但保留内容（典型：切到音乐模式前 pause 留下的）→ 仍要保持通知/锁屏
                    // 状态可显示（不显示播放控制）让用户能感知到"上次听的是这个"
                    refreshNotification(state)
                } else if (foregroundStarted) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        // M5：锁屏叠加层自动唤起 —— 电台播放开始时若 lockscreen_overlay_enabled 开关为 true，则自动启动叠加层
        var overlayAutoLaunched = false
        fun launchOverlay() {
            val intent = android.content.Intent(
                this@RadioPlaybackService,
                com.shiyinplayer.lockscreen.LockScreenOverlayActivity::class.java
            ).apply {
                putExtra(com.shiyinplayer.lockscreen.LockScreenOverlayActivity.EXTRA_MANUAL_MODE, false)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(intent) }
        }
        scope.launch {
            radioPlayer.playbackState.collect { state ->
                if ((state.isPlaying || state.isBuffering) && state.streamUrl != null && !overlayAutoLaunched) {
                    overlayAutoLaunched = true
                    if (settingsRepository.lockscreenOverlayEnabledSync()) launchOverlay()
                }
                if (!state.isPlaying && !state.isBuffering) {
                    overlayAutoLaunched = false
                }
            }
        }
        // 补漏重建：叠加层/进程被系统回收后，若仍在播放且系统锁屏仍激活、叠加层已不在，则自动重建拉起。
        scope.launch {
            while (true) {
                delay(3_000)
                val s = radioPlayer.playbackState.value
                if ((s.isPlaying || s.isBuffering) && s.streamUrl != null
                    && settingsRepository.lockscreenOverlayEnabledSync()
                    && !com.shiyinplayer.lockscreen.LockScreenOverlayActivity.isShowing
                ) {
                    val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    if (km.isKeyguardLocked) launchOverlay()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> radioPlayer.togglePlayPause()
            ACTION_NEXT -> radioPlayer.playNextFavorite()
            ACTION_PREV -> radioPlayer.playPreviousFavorite()
            ACTION_STOP -> {
                radioPlayer.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mediaSession?.run {
            release()
            mediaSession = null
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun setupMediaSession() {
        try {
            mediaSession = MediaSession.Builder(this, radioPlayer.sessionPlayer)
                .setCallback(object : MediaSession.Callback {
                    /**
                     * 决定向系统暴露哪些传输命令。锁屏/下拉栏要看这里接受
                     * COMMAND_SEEK_TO_NEXT/PREVIOUS 才会显示上一首/下一首按钮。
                     * （MusicPlayerSession 也是同样的处理方式，参考 MediaSessionManager.kt。）
                     */
                    override fun onConnect(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo
                    ): MediaSession.ConnectionResult {
                        // 锁屏控制关闭时只暴露最小可用命令
                        if (!settingsRepository.lockscreenControlSync()) {
                            return MediaSession.ConnectionResult.accept(
                                SessionCommands.EMPTY,
                                Player.Commands.EMPTY
                            )
                        }
                        return MediaSession.ConnectionResult.accept(
                            SessionCommands.EMPTY,
                            Player.Commands.Builder()
                                .add(Player.COMMAND_PLAY_PAUSE)
                                .add(Player.COMMAND_SEEK_TO_NEXT)
                                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                                .add(Player.COMMAND_STOP)
                                .build()
                        )
                    }

                    /**
                     * 拦截有线耳机/蓝牙的硬件媒体按键（AVRCP MediaButton）。
                     * 与 MediaSessionManager.kt 相同处理：消费后 Media3 不再下默认命令。
                     * 注意：
                     *  - 播控蓝牙耳机上一首/下一首/播放暂停都通过这里分发
                     *  - 受 [SettingsRepository.headsetButtonControl] 控制
                     */
                    override fun onMediaButtonEvent(
                        session: MediaSession,
                        controllerInfo: MediaSession.ControllerInfo,
                        intent: Intent
                    ): Boolean {
                        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
                        if (!settingsRepository.headsetButtonControlSync()) return false
                        val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                        }
                        if (keyEvent?.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_PLAY -> radioPlayer.play()
                                KeyEvent.KEYCODE_MEDIA_PAUSE -> radioPlayer.pause()
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                KeyEvent.KEYCODE_HEADSETHOOK -> radioPlayer.togglePlayPause()
                                KeyEvent.KEYCODE_MEDIA_NEXT -> radioPlayer.playNextFavorite()
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> radioPlayer.playPreviousFavorite()
                                KeyEvent.KEYCODE_MEDIA_STOP -> {
                                    radioPlayer.stop()
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }
                            }
                        }
                        return true
                    }
                })
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "MediaSession 创建失败", e)
        }
    }

    private fun refreshNotification(state: RadioState) {
        val notification = buildNotification(state)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, Constants.NOTIFICATION_ID + 1, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(Constants.NOTIFICATION_ID + 1, notification)
            }
            foregroundStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun buildNotification(state: RadioState): Notification {
        val title = state.stationName ?: "收音机"
        val subtitle = state.programName ?: state.genre ?: ""

        val builder = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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

        // 锁屏控制关闭时只显示一个停止按钮（保持最低可用性）
        if (!settingsRepository.lockscreenControlSync()) {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "停止",
                    pendingService(ACTION_STOP, requestCode = 99)
                )
            )
        } else {
            // 上一首 | 播放/暂停 | 下一首（与播放页、迷你条布局一致）
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_previous,
                    "上一首",
                    pendingService(ACTION_PREV, requestCode = 10)
                )
            )
            builder.addAction(
                NotificationCompat.Action(
                    if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (state.isPlaying) "暂停" else "播放",
                    pendingService(ACTION_PLAY_PAUSE, requestCode = 11)
                )
            )
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_next,
                    "下一首",
                    pendingService(ACTION_NEXT, requestCode = 12)
                )
            )
        }

        // MediaStyle + 链接到 MediaSession（锁屏/通知栏媒体控件的关键）
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
        try {
            mediaSession?.let { session ->
                mediaStyle.setMediaSession(session.sessionCompatToken)
            }
        } catch (e: Exception) {
            Log.w(TAG, "setMediaSession 失败", e)
        }
        // 折叠态显示前 3 个 action（prev/play/next）
        if (settingsRepository.lockscreenControlSync()) {
            mediaStyle.setShowActionsInCompactView(0, 1, 2)
        } else {
            mediaStyle.setShowActionsInCompactView(0)
        }
        builder.setStyle(mediaStyle)

        return builder.build()
    }

    private fun pendingService(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, RadioPlaybackService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotificationChannel() {
        try {
            NotificationManagerCompat.from(this).createNotificationChannel(
                NotificationChannelCompat.Builder(
                    Constants.NOTIFICATION_CHANNEL_ID,
                    NotificationManager.IMPORTANCE_LOW
                )
                    .setName("收音机播放")
                    .build()
            )
        } catch (_: Exception) {}
    }
}
