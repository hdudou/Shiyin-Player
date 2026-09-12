package com.shiyinplayer.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shiyinplayer.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 系统 MediaPlayer 兜底播放器（2026-08-18 新增，P2A 播放修复）。
 *
 * 背景：ExoPlayer 无 APE/WMA 容器解析器，且 QTI ape MediaCodec 解码器在 ExoPlayer
 * 直通输入下报 UNKNOWN_ERROR（已实测）。而系统 MediaPlayer 框架内置完整 Monkey's Audio
 * 支持（/vendor/lib64/libApeSwDec.so + libmonkey_jni.so），可完整播放 APE 文件。
 *
 * 职责：对 APE/WMA 曲目用系统 MediaPlayer 播放（支持本地文件 / content:// / http(s)://），
 * 状态经 [state] 暴露给 PlayerManager 桥接到现有通知与进度体系。
 * ExoPlayer 仍负责其余格式；本兜底仅在 [supports] 返回 true 时启用。
 */
class MediaPlayerFallback(private val context: Context) {

    companion object {
        private const val TAG = "MediaPlayerFallback"
        private const val PROGRESS_INTERVAL_MS = 500L

        /** 系统 MediaPlayer 支持的扩展（有系统组件保证）。 */
        val SUPPORTED_EXTENSIONS: Set<String> = setOf("ape", "wma")

        fun supportsExtension(ext: String): Boolean = ext.lowercase() in SUPPORTED_EXTENSIONS
    }

    data class State(
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val song: Song? = null,
        val error: String? = null,
        /** 播放速度（系统 MediaPlayer 不支持变速，固定 1.0f）。 */
        val playbackSpeed: Float = 1.0f
    )

    val state = MutableStateFlow(State())

    private var mediaPlayer: MediaPlayer? = null
    /** X-音量：当前兜底音量（与 ExoPlayer 同步的 mappedVolume），新 MediaPlayer 就绪时应用；存储以便无活动实例时保留。 */
    @Volatile private var fallbackVolume = 1f
    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            state.value = state.value.copy(
                positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(0),
                durationMs = runCatching { mp.duration.toLong() }.getOrDefault(0)
            )
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    /** 播放完成回调（由 PlayerManager 注入自动下一首逻辑）。 */
    @Volatile var onCompletion: (() -> Unit)? = null

    /** 当前是否在播（供 PlayerManager 判断兜底激活）。 */
    val active: Boolean get() = mediaPlayer != null

    /** X-音量：设置兜底音量并即时应用到当前 MediaPlayer（若有）；无活动实例时仅存储待下次播放应用。 */
    fun setVolume(linear: Float) {
        fallbackVolume = linear.coerceIn(0f, 1f)
        runCatching { mediaPlayer?.setVolume(fallbackVolume, fallbackVolume) }
    }

    /** 播放 APE/WMA 曲目；[dataSourceUri] 覆盖实际喂给 MediaPlayer 的源（默认原 uri）。 */
    fun play(song: Song, dataSourceUri: String = song.uri) {
        stop()
        val mp = MediaPlayer()
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            mp.setAudioAttributes(attrs)
            // 远端 APE/WMA 先下载到本地缓存：此处喂本地缓存文件绝对路径，系统 MediaPlayer 不提携
            // 网络鉴权头，但可直接读本地文件完成 Monkey's Audio 解码。
            // 无 scheme 的绝对路径必须走 setDataSource(String)：交给 context+Uri 时会被 ContentResolver
            // 当 content:// 解析而抛 "No content provider"，令兜底链路畸形（2026-08-22 缓存播放根因之一）。
            if (dataSourceUri.startsWith("/")) {
                mp.setDataSource(dataSourceUri)
            } else {
                mp.setDataSource(context, Uri.parse(dataSourceUri))
            }
            mp.setOnPreparedListener { p ->
                if (mediaPlayer !== p) return@setOnPreparedListener
                try {
                    p.setVolume(fallbackVolume, fallbackVolume)
                    p.start()
                    state.value = State(
                        isPlaying = true,
                        positionMs = 0,
                        durationMs = p.duration.toLong().coerceAtLeast(0),
                        song = song
                    )
                    handler.post(progressRunnable)
                    Log.i(TAG, "系统 MediaPlayer 开始播放: ${song.title} (${p.duration}ms)")
                } catch (e: Exception) {
                    Log.e(TAG, "onPrepared 启动失败", e)
                }
            }
            mp.setOnCompletionListener {
                state.value = state.value.copy(isPlaying = false)
                handler.removeCallbacks(progressRunnable)
                Log.i(TAG, "播放完成: ${song.title}")
                // 自动下一首（兜底播放结束后由 PlayerManager 接管队列推进）
                onCompletion?.invoke()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "系统 MediaPlayer 错误: what=$what extra=$extra song=${song.title}")
                state.value = state.value.copy(
                    isPlaying = false,
                    error = "MediaPlayer error what=$what extra=$extra"
                )
                handler.removeCallbacks(progressRunnable)
                true
            }
            mediaPlayer = mp
            state.value = State(song = song)
            // 异步 prepare（本地/网络源均适用；http 直链带鉴权需 Authorization 头——
            // 本兜底暂不注入鉴权头，SMB/WebDAV 走 ExoPlayer 数据源，故仅限本地/无鉴权 URI）
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer 创建失败: ${e.message}")
            state.value = state.value.copy(error = e.message)
            runCatching { mp.release() }
            mediaPlayer = null
        }
    }

    /** 暂停/继续。 */
    fun pause() {
        runCatching { mediaPlayer?.pause() }
        state.value = state.value.copy(isPlaying = false)
        handler.removeCallbacks(progressRunnable)
    }

    fun resume() {
        runCatching { mediaPlayer?.start() }
        state.value = state.value.copy(isPlaying = true)
        handler.post(progressRunnable)
    }

    fun seekTo(ms: Long) {
        runCatching { mediaPlayer?.seekTo(ms.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        state.value = state.value.copy(positionMs = ms)
    }

    /** 停止并释放。 */
    fun stop() {
        handler.removeCallbacks(progressRunnable)
        val mp = mediaPlayer
        mediaPlayer = null
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        state.value = State()
    }
}
