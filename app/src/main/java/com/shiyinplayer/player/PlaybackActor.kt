package com.shiyinplayer.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放动作串行执行器（A2：ExoPlayer TOCTOU 竞态统一收敛）。
 *
 * ExoPlayer 要求在单一 Looper 上访问（本 App 使用 Main）。真正的 TOCTOU 来自：一个协程进入
 * [kotlinx.coroutines.withContext]（构建 MediaItem）后挂起、让出主线程，期间另一协程基于**过期**
 * 播放状态改写 player（`setMediaItems`/`prepare`/`seekTo` 交错）——恢复后第一个协程用旧状态继续写，
 * 造成「读-挂起-写」竞态（如骨架窗口劫持）。
 *
 * [dispatch]/[execute] 将所有会改变「播放位置 / 队列 / 窗口」的动作送入单线程（Main）队列顺序执行：
 * 每个动作都从前序动作**已完成后**的最新状态开始，从根上消除该竞态。
 *
 * 播放/暂停开关、音量/淡入淡出为非竞态敏感控制（不与队列/索引耦合），有意**不在**此队列，
 * 避免长时间渐变阻塞命令通道。
 */
@Singleton
class PlaybackActor @Inject constructor() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (block in channel) {
                try {
                    block()
                } catch (e: Exception) {
                    Log.e("PlaybackActor", "Action failed in actor loop", e)
                }
            }
        }
    }

    /** 入队并立即返回（fire-and-forget）。 */
    fun dispatch(block: suspend () -> Unit) {
        channel.trySend(block)
    }

    /** 入队并挂起等待本次动作执行完成后返回（需要拿到"装载已完成"语义的调用方）。 */
    suspend fun execute(block: suspend () -> Unit) {
        val done = Channel<Unit>(1)
        channel.send {
            try {
                block()
            } finally {
                done.trySend(Unit)
            }
        }
        done.receive()
    }
}