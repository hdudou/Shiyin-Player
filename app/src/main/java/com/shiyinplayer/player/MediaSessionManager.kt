package com.shiyinplayer.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaSession 封装（架构 §1.2.3）：绑定同一 ExoPlayer，
 * 通知与锁屏控制经此同源同步。
 *
 * 会话采用「首次 onGetSession 时惰性创建」而非进程启动即建：
 * MediaSessionService 需要其 MediaNotification.Provider 在服务创建后才注册；
 * 若会话在服务之前已激活播放（如冷启动断点续播），服务可能因错过状态变更
 * 而迟迟不 startForeground，触发 ForegroundServiceDidNotStartInTimeException。
 *
 * 锁屏媒体控制接线（修复占位 lockscreen_control）：
 * Media3 1.4 的传输命令暴露通过 [MediaSession.Callback.onConnect] 返回的
 * [MediaSession.ConnectionResult]（per-controller 的 Player.Commands）控制。
 * 关闭时移除 播放/暂停/上下首/seek 等命令，锁屏（及系统媒体控制）不再显示控制条；
 * 通知内容的展示与否由 PlaybackService 的 notify_enabled 独立控制（二者正交）。
 * 注：命令在 controller 连接时确定，运行时切换于下次连接/冷启生效（Media3 API 限制）。
 */
@OptIn(UnstableApi::class)
@Singleton
class MediaSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerManager: PlayerManager,
    private val settings: SettingsRepository
) {
    private val player get() = playerManager.fallbackAwarePlayer
    /** 播放命令转由 PlaybackController 执行（经 PlayerManager 取得，避免 Hilt 依赖环）。 */
    private val playbackController get() = playerManager.playbackController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** onPlayerCommandRequest 返回非 0 表示「已由 App 接管」，Media3 不再执行默认 player 命令。 */
    private val HANDLED = 1

    @Volatile
    private var session: MediaSession? = null

    @Volatile
    private var lockscreen = true

    /** 惰性创建（线程安全）：返回当前会话实例，并同步应用锁屏控制初始值与监听。 */
    fun getOrCreate(): MediaSession = session ?: synchronized(this) {
        session ?: MediaSession.Builder(context, player)
            .setId("MusicPlayerSession")
            .setCallback(object : MediaSession.Callback {
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int = routeSessionCommand(playerCommand)

                /**
                 * 拦截蓝牙 AVRCP / 有线耳机等「硬件媒体按键」：系统把它们作为
                 * ACTION_MEDIA_BUTTON 的 KeyEvent 投递到此回调（onMediaButtonEvent 通道）；
                 * 而 [onPlayerCommandRequest] 只覆盖通知/锁屏/系统媒体面板的按钮。
                 * Media3 默认会把该 KeyEvent 转成「窗口级」切歌命令（与 App 的
                 * 全量队列 + fullToExo 映射不符，导致蓝牙切歌无效），故在此接管，
                 * 统一下发 [PlaybackController.handleMediaButton] 走队列切歌。
                 * 返回 true 表示已消费，Media3 不再执行默认 player 命令。
                 */
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                        Log.i("MediaSession", "onMediaButtonEvent keyCode=${keyEvent.keyCode}")
                        playbackController.handleMediaButton(keyEvent.keyCode)
                    }
                    // 消费 DOWN/UP/重复，杜绝 Media3 默认命令与队列逻辑双触发。
                    return true
                }

                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    return MediaSession.ConnectionResult.accept(
                        SessionCommands.EMPTY,
                        computeCommands(lockscreen)
                    )
                }
            })
            .build()
            .also { session = it }
            .also { s ->
                // P0-4：改读内存快照，消除主线程 runBlocking 阻塞（ANR）；运行中变更由下方 collect 监听。
                lockscreen = settings.lockscreenControlSync()
                // 后续切换更新字段（供后续连接/重连反映；已连接 controller 随重连生效）
                scope.launch {
                    settings.lockscreenControl.collect { v ->
                        lockscreen = v
                        // CE-即时刷新：media3 重绑 player 即触发所有已连接 controller（含锁屏/下拉栏）重新
                        // onConnect → computeCommands(lockscreen)，开关即时生效，无需等下次会话重连
                        runCatching { session?.setPlayer(playerManager.fallbackAwarePlayer) }
                    }
                }
                // 播放器热重建（音频链开关 #9-12 即时化）：跟随 PlayerManager 的当前桥接播放器，
                // 会话即时切换（Media3 1.4 支持 setPlayer）。
                scope.launch {
                    playerManager.activePlayer.collect { p ->
                        session?.setPlayer(playerManager.fallbackAwarePlayer)
                    }
                }
            }
    }

    /** 按开关决定向系统暴露的传输命令集合（影响锁屏/系统媒体面板的按钮显示）。
     *  只要锁屏开关闭合，就始终暴露 play/pause/next/prev 等基础命令，
     *  确保系统 PlaybackState.actions 包含对应位（锁屏/下拉栏才显示控制按钮）。
     *  之前因 queueEmpty=true 导致返回 EMPTY，系统看到 actions=128（仅 SET_REPEAT_MODE），锁屏无按钮。 */
    private fun computeCommands(enabled: Boolean): Player.Commands {
        if (!enabled) return Player.Commands.EMPTY
        return Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_WINDOW)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_WINDOW)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .build()
    }

    /**
     * 拦截系统/蓝牙/通知下发的媒体命令，统一转给 [PlaybackController]：
     *  - 播放命令先申请音频焦点（获焦才能播），避免与其它播放器混音；
     *  - 上下首走基于队列的切歌（而非 ExoPlayer 原生窗口切换，后者与队列映射不一致，曾有蓝牙切歌无效）。
     * 返回非 0 表示已由本回调接管（Media3 默认返回 0=走默认），Media3 不再执行默认 player 命令。
     */
    private fun routeSessionCommand(playerCommand: Int): Int = try {
        when (playerCommand) {
            Player.COMMAND_PLAY_PAUSE -> {
                Log.i("MediaSession", "routeSessionCommand COMMAND_PLAY_PAUSE")
                playbackController.togglePlayPause()
                HANDLED
            }
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_WINDOW,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                Log.i("MediaSession", "routeSessionCommand COMMAND_NEXT")
                playbackController.next()
                HANDLED
            }
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_WINDOW,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                Log.i("MediaSession", "routeSessionCommand COMMAND_PREVIOUS")
                playbackController.previous()
                HANDLED
            }
            else -> 0
        }
    } catch (t: Throwable) {
        Log.w("MediaSession", "routeSessionCommand 失败，回退默认处理", t)
        0
    }

    fun release() {
        synchronized(this) {
            session?.release()
            session = null
        }
    }
}
