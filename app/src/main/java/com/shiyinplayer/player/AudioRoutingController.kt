package com.shiyinplayer.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频输出路由（audio_route）：持有注入的 [AudioSink]，通过
 * [AudioSink.setPreferredDevice] 将输出导向扬声器 / 有线耳机 / 蓝牙（auto=默认）。
 * 与播放器同步构造（PlayerModule 渲染器工厂 attach），切歌/启动时应用。
 */
@Singleton
class AudioRoutingController @Inject constructor() {

    @Volatile
    var sink: AudioSink? = null
        private set

    fun attach(sink: AudioSink) {
        this.sink = sink
    }

    @OptIn(UnstableApi::class)
    fun setRoute(route: String, context: Context) {
        val s = sink ?: return
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val targetType = when (route) {
                "speaker" -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                "wired" -> AudioDeviceInfo.TYPE_WIRED_HEADSET
                "bt" -> AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                else -> null
            }
            if (targetType == null) {
                s.setPreferredDevice(null)
                return@runCatching
            }
            val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == targetType }
            s.setPreferredDevice(device) // 设备不可用传 null 即清除
        }
    }
}