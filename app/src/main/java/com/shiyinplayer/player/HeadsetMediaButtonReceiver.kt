package com.shiyinplayer.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 有线/蓝牙耳机媒体按键接收器（2026-08-24）。
 *
 * 接收系统派发的 ACTION_MEDIA_BUTTON（有线 HEADSETHOOK / 蓝牙 AVRCP 播放·暂停·上下首等），
 * 把按键码转交给 [PlaybackController.handleMediaButton]，由其对播放/暂停/切歌统一分发，
 * 并受「耳机按键控制」设置开关门控。仅在按键按下（ACTION_DOWN）且非自动重复时处理。
 */
@AndroidEntryPoint
class HeadsetMediaButtonReceiver : BroadcastReceiver() {

    @Inject
    lateinit var playbackController: PlaybackController

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        } ?: return
        if (keyEvent.action != KeyEvent.ACTION_DOWN) return
        if (keyEvent.repeatCount != 0) return
        playbackController.handleMediaButton(keyEvent.keyCode)
    }
}