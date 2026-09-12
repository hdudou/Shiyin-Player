package com.shiyinplayer.lockscreen

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.player.PlayerManager
import com.shiyinplayer.player.radio.RadioPlayer
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 锁屏叠加层 Composable 根。
 * - M3：2 种动态效果（0=光之呼吸 / 1=能量核心，LockScreenVisualEffectRouter）
 * - M4：手势 + TrackToast 切歌浮层（1.5s 显示 + 0.3s 淡出）
 * - M6：android.media.audiofx.Visualizer 生命周期管理
 */

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LockScreenEntryPoint {
    fun visualizer(): LockScreenVisualizer
    fun settingsRepository(): SettingsRepository
    fun playerManager(): PlayerManager
    fun radioPlayer(): RadioPlayer
}

@Composable
fun LockScreenOverlayScreen(
    activity: Activity,
    isManualMode: Boolean,
    hasRecordAudioPermission: Boolean = false
) {
    val context = activity.applicationContext
    val entry = remember {
        EntryPointAccessors.fromApplication(context, LockScreenEntryPoint::class.java)
    }
    val visualizer = remember { entry.visualizer() }
    val settings = remember { entry.settingsRepository() }
    val playerManager = remember { entry.playerManager() }
    val radioPlayer = remember { entry.radioPlayer() }
    val scope = rememberCoroutineScope()

    val effectIndex by settings.lockscreenVisualEffect
        .collectAsStateWithLifecycle(initialValue = 0)
    val musicState by playerManager.playbackState.collectAsStateWithLifecycle()
    val radioState by radioPlayer.playbackState.collectAsStateWithLifecycle()

    // ===== M6：Visualizer 生命周期管理 =====
    // 进入时 start，退出时 stop。用 DisposableEffect 管理。
    DisposableEffect(hasRecordAudioPermission) {
        if (hasRecordAudioPermission) {
            // 获取当前 audioSessionId（session 0 = master output，捕获所有音频）
            val sessionId = runCatching { playerManager.exoPlayer.audioSessionId }.getOrElse { 0 }
            Log.d("LockScreenOverlay", "Starting Visualizer with session=$sessionId")
            visualizer.start(sessionId)
        } else {
            Log.w("LockScreenOverlay", "No RECORD_AUDIO permission, Visualizer disabled")
        }
        onDispose {
            Log.d("LockScreenOverlay", "Stopping Visualizer")
            visualizer.stop()
        }
    }

    // 聚合频谱 + 帧时钟（时间演化 L1 的驱动源）
    var frame by remember { mutableStateOf(BandFrame.ZERO) }
    var timeMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { ns ->
                timeMs = ns / 1_000_000L
                frame = visualizer.getBandFrame()
            }
        }
    }

    val isMusicActive = musicState.currentSong != null && (musicState.isPlaying || musicState.buffering)
    val isRadioActive = !radioState.isIdle
    val isPlaying = isMusicActive || isRadioActive

    // ===== M4：TrackToast 浮层 =====
    // 监听曲目 ID 变化，触发 1.5s 显示 + 0.3s 淡出
    var toastVisible by remember { mutableStateOf(false) }
    var toastTitle by remember { mutableStateOf("") }
    var toastArtist by remember { mutableStateOf("") }

    // 音乐模式切歌检测：只需 song id 变化即触发，不依赖 isMusicActive（切歌瞬间播放状态可能尚未就绪）
    val currentMusicId = musicState.currentSong?.id
    LaunchedEffect(currentMusicId) {
        if (currentMusicId != null) {
            // 等待一帧确保 musicState 已更新到最新
            delay(50)
            toastTitle = musicState.currentSong?.title ?: ""
            toastArtist = musicState.currentSong?.artistName ?: ""
            toastVisible = true
            delay(1500)
            toastVisible = false
            delay(300)
        }
    }

    // 电台模式切歌检测
    val currentStationId = radioState.stationId
    LaunchedEffect(currentStationId) {
        if (currentStationId != null && isRadioActive) {
            toastTitle = radioState.stationName ?: ""
            toastArtist = (radioState.programName ?: "").ifEmpty { radioState.currentTrack ?: "" }
            toastVisible = true
            delay(1500)
            toastVisible = false
            delay(300)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 动态效果路由（0=光之呼吸 / 1=能量核心 / 2=3D 深空 / 3=流体万花筒 / 4=歌词呼吸 / 5=黑胶赛博）
        LockScreenVisualEffectRouter(
            effectIndex = effectIndex,
            frame = frame,
            timeMs = timeMs,
            isPlaying = isPlaying,
            title = toastTitle,
            modifier = Modifier.fillMaxSize()
        )

        // M4：TrackToast 浮层（底部居中，半透明黑底）
        AnimatedVisibility(
            visible = toastVisible,
            enter = fadeIn(initialAlpha = 0f),
            exit = fadeOut(targetAlpha = 0f),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 48.dp)
                    .background(Color.Black.copy(alpha = 0.65f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = toastTitle,
                        color = Color.White,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (toastArtist.isNotEmpty()) {
                        Text(
                            text = toastArtist,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        // 手势检测
        LockScreenGestureDetector(
            onTap = {
                scope.launch {
                    settings.setLockscreenVisualEffect((effectIndex + 1) % LOCKSCREEN_EFFECT_COUNT)
                }
            },
            onSwipeUp = {
                val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (km.isKeyguardLocked) {
                    // 系统锁屏确已激活：调系统凭据验证，成功后退出叠加层
                    LockScreenUnlockHelper.requestUnlock(
                        activity,
                        object : LockScreenUnlockHelper.UnlockCallback {
                            override fun onDismissSucceeded() {
                                activity.finish()
                            }
                            override fun onDismissError() {}
                            override fun onDismissCancelled() {}
                        }
                    )
                } else {
                    // 系统锁屏未激活（自绘锁屏手动唤起）：上滑即视为解锁，直接退出叠加层
                    activity.finish()
                }
            },
            onSwipeLeft = {
                if (isMusicActive) playerManager.next()
                else if (isRadioActive) radioPlayer.playNextFavorite()
            },
            onSwipeRight = {
                if (isMusicActive) playerManager.previous()
                else if (isRadioActive) radioPlayer.playPreviousFavorite()
            }
        )
    }
}
