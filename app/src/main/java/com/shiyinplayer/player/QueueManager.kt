package com.shiyinplayer.player

import androidx.media3.exoplayer.ExoPlayer
import com.shiyinplayer.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 窗口式队列 + ExoPlayer 装载同步（A1 步骤 2：从 PlayerManager 拆出的独立职责，行为等价）。
 *
 * 背景：逻辑队列（[QueueController]）可能是整个曲库（网络源可达数万首），ExoPlayer/MediaSession
 * 只装载当前窗口，避免全量时间线导致 MediaSessionCompat.setQueue OOM 与主线程 ANR。
 * 本类负责窗口定位与装载（[centerWindow] / [reloadWindowCenteredAt]）、尾部扩展（[maybeExtendWindow]）、
 * 全量索引→窗口 Exo 索引映射（[fullToExo]）、备用源尝试计数（[altAttempts]），
 * 以及三项队列编辑的窗口同步（[removeAt] / [moveQueueItem] / [enqueueNext]）。
 *
 * 运行时挂钩（ExoPlayer 活引用 / UI 刷新 / 前台服务启动）由 PlayerManager 在 init 绑定，
 * 避免构造期耦合可变 ExoPlayer 与回调。
 */
@Singleton
class QueueManager @Inject constructor(
    private val queueController: QueueController,
    private val mediaItemBuilder: MediaItemBuilder
) {
    companion object {
        private const val WINDOW = 500
        private const val EXTEND_THRESHOLD = 100
    }

    // ===== 运行时挂钩（PlayerManager.init 绑定，一次方法调用内保持同一 ExoPlayer 引用） =====
    /** 获取当前生效的 ExoPlayer（热重建后自动跟随 PlayerManager.exoPlayer 的 @Volatile 引用）。 */
    private var playerRef: () -> ExoPlayer = { error("QueueManager 未绑定 ExoPlayer") }
    /** 队列/窗口/索引变化后通知 UI 刷新（= PlayerManager.emitFull）。 */
    var onChanged: () -> Unit = {}
    /** 启动前台播放服务（窗口装载前调用，维持既有语义）。 */
    var ensureServiceStarted: () -> Unit = {}

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    /** 当前窗口在全量队列中的起始偏移。 */
    var windowStart = 0
    /** fullIdx → ExoPlayer 窗口内索引（仅收录窗口内非兜底格式曲目，Key 即全量队列索引）。 */
    val fullToExo = mutableMapOf<Int, Int>()
    /** 多源同曲（R2-01）：全量队列索引 → 已尝试的备用源数量（0=主源，未尝试备用）。 */
    val altAttempts = mutableMapOf<Int, Int>()

    /** 由 PlayerManager 绑定运行时依赖。 */
    fun bind(playerRef: () -> ExoPlayer, onChanged: () -> Unit, ensureServiceStarted: () -> Unit) {
        this.playerRef = playerRef
        this.onChanged = onChanged
        this.ensureServiceStarted = ensureServiceStarted
    }

    /**
     * 以 center 为中心定位窗口并重建 [fullToExo]。
     * 返回 (剔除 APE/WMA 后的 exo 歌曲, 起始 exo 索引)，调用方据此 setMediaItems/prepare。
     */
    fun centerWindow(songs: List<Song>, center: Int): Pair<List<Song>, Int> {
        val fullSize = songs.size
        val maxStart = (fullSize - WINDOW).coerceAtLeast(0)
        windowStart = (center - WINDOW / 2).coerceIn(0, maxStart)
        val window = songs.subList(windowStart, (windowStart + WINDOW).coerceAtMost(fullSize))
        return buildExoWindow(window, center)
    }

    /**
     * 以 center 为中心重载窗口（后台建 MediaItem，主线程 setMediaItems；不自动播放，由调用方决定 play）。
     *
     * EF：setMediaItems 直接定位到目标曲目在窗口内的索引，避免"先显示窗口首项 → 再 seek 到目标"
     * 的中间态闪烁（原实现丢弃了 centerWindow 返回的起始索引，硬编码 0）。
     */
    suspend fun reloadWindowCenteredAt(center: Int) {
        ensureServiceStarted()
        val exo = player()
        val (exoSongs, startIndex) = centerWindow(queueController.queue, center)
        val items = withContext(Dispatchers.Default) { exoSongs.map { mediaItemBuilder.build(it) } }
        exo.setMediaItems(items, startIndex, 0L)
        exo.prepare()
    }

    /** 靠近窗口尾部时向前追加（不收缩头部，保持 fullToExo 映射简单正确；窗口上限由播放进度自然推进）。 */
    fun maybeExtendWindow() {
        val fullSize = queueController.queue.size
        val count = player().mediaItemCount
        val exoIdx = player().currentMediaItemIndex
        if (exoIdx < count - EXTEND_THRESHOLD) return
        val end = windowStart + count
        if (end >= fullSize) return
        val grow = minOf(WINDOW, fullSize - end)
        val tail = queueController.queue.subList(end, end + grow)
        scope.launch {
            val currentExo = player()
            val tailExo = mutableListOf<Song>()
            tail.forEachIndexed { offset, song ->
                val fullIdx = end + offset
                if (!MediaPlayerFallback.supportsExtension(extensionOf(song))) {
                    fullToExo[fullIdx] = currentExo.mediaItemCount + tailExo.size
                    tailExo.add(song)
                }
            }
            if (tailExo.isEmpty()) return@launch
            val items = withContext(Dispatchers.Default) { tailExo.map { mediaItemBuilder.build(it) } }
            currentExo.addMediaItems(items)
            onChanged()
        }
    }

    /** 从队列移除指定项并同步窗口与当前索引（P-09 队列编辑）。 */
    fun removeAt(index: Int) {
        if (index !in queueController.queue.indices) return
        val current = queueController.currentIndex
        queueController.replaceQueue(queueController.queue.toMutableList().apply { removeAt(index) })
        val exo = player()
        val wCount = exo.mediaItemCount
        if (index < windowStart) {
            windowStart -= 1
        } else if (index < windowStart + wCount) {
            runCatching { exo.removeMediaItem(index - windowStart) }
            // EA：ExoPlayer 侧移除后 fullToExo 映射失配（删除点之后的 exo 索引全部错 1），
            // 重建当前窗口映射以保证 onMediaItemTransition 换算正确。
            rebuildFullToExoMap()
        }
        // EA：移除后 altAttempts 键（fullIdx）与实际曲目错位，整体丢弃（当前曲失败重试下一次自然归零）。
        altAttempts.clear()
        queueController.setCurrentIndex(when {
            index < current -> current - 1
            index == current ->
                if (queueController.queue.isEmpty()) 0
                else (windowStart + exo.currentMediaItemIndex).coerceIn(0, queueController.queue.size - 1)
            else -> current
        })
        onChanged()
    }

    /**
     * 清空整个队列（停止播放并移除所有项）。
     * - ExoPlayer 先 stop，再清空 mediaItems（避免流继续播）
     * - replaceQueue 把队列置空（currentIndex 被 coerce 到 -1）
     * - 重置 windowStart，准备下次 enqueue
     */
    fun clear() {
        val exo = player()
        exo.stop()
        exo.clearMediaItems()
        queueController.replaceQueue(emptyList())
        windowStart = 0
        fullToExo.clear()
        onChanged()
    }

    /** 队列重排：from -> to，含当前曲目索引与窗口同步（P-09 队列编辑）。 */
    fun moveQueueItem(from: Int, to: Int) {
        if (from !in queueController.queue.indices || to !in queueController.queue.indices || from == to) return
        val list = queueController.queue.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        queueController.replaceQueue(list)
        val cur = queueController.currentIndex
        queueController.setCurrentIndex(when {
            from == cur -> to
            from < cur && to >= cur -> cur - 1
            from > cur && to <= cur -> cur + 1
            else -> cur
        })
        runCatching {
            val exo = player()
            val wCount = exo.mediaItemCount
            if (from in windowStart until windowStart + wCount && to in windowStart until windowStart + wCount) {
                exo.moveMediaItem(from - windowStart, to - windowStart)
                // EA：移动后 fullToExo 映射失配，重建。
                rebuildFullToExoMap()
            }
        }
        // EA：重排后 altAttempts 键错位，整体丢弃。
        altAttempts.clear()
        onChanged()
    }

    /** 在“当前曲目后一位”插入歌曲并同步窗口与队列（P-09 队列编辑）。 */
    fun enqueueNext(song: Song) {
        // CC-去重：同一首曲（song.id>0）已在队列中则不重复插入，避免反复「下一首加入」堆积重复项
        if (song.id > 0 && queueController.queue.any { it.id == song.id }) return
        val exo = player()
        val items = mediaItemBuilder.build(song)
        val exoIdx = (queueController.currentIndex + 1 - windowStart).coerceIn(0, exo.mediaItemCount)
        val insertFullIdx = queueController.currentIndex + 1
        runCatching { exo.addMediaItem(exoIdx, items) }
        queueController.replaceQueue(queueController.queue.toMutableList().apply {
            add(insertFullIdx, song)
        })
        // EA：插入后 fullToExo 映射失配（插入点之后的 exo 索引全部错 1），重建。
        if (insertFullIdx >= windowStart && insertFullIdx < windowStart + exo.mediaItemCount) {
            rebuildFullToExoMap()
        }
        // EA：插入后插入点之后的 altAttempts 键错位，整体丢弃。
        altAttempts.clear()
        onChanged()
    }

    /** F2-1「稍后播放」：将歌曲追加到队尾，并同步窗口（若追加点仍在当前窗口内则追加 Exo 项）。 */
    fun enqueue(song: Song) {
        // CC-去重：同一首曲（song.id>0）已在队列中则不重复追加，避免「稍后播放」堆积重复项
        if (song.id > 0 && queueController.queue.any { it.id == song.id }) return
        val exo = player()
        val newFullIdx = queueController.queue.size
        queueController.replaceQueue(queueController.queue.toMutableList().apply { add(song) })
        if (!MediaPlayerFallback.supportsExtension(extensionOf(song))
            && newFullIdx < windowStart + exo.mediaItemCount) {
            val exoIdx = (newFullIdx - windowStart).coerceIn(0, exo.mediaItemCount)
            runCatching { exo.addMediaItem(exoIdx, mediaItemBuilder.build(song)) }
            fullToExo[newFullIdx] = exoIdx
        }
        // EA：追加后后续 altAttempts 键不变，无需清空（新增项键为新序号，本身无尝试记录）。
        onChanged()
    }

    /**
     * EA：按当前 windowStart + exo 媒体项重建 fullToExo 映射。
     * 窗口内队列条目顺序与 exo 顺序一一对应（剔除兜底格式），重新数一遍即可。
     */
    private fun rebuildFullToExoMap() {
        val exo = player()
        val exoCount = exo.mediaItemCount
        val queue = queueController.queue
        fullToExo.clear()
        var exoIdx = 0
        for (offset in 0 until minOf(exoCount, queue.size - windowStart)) {
            val fullIdx = windowStart + offset
            val song = queue.getOrNull(fullIdx) ?: continue
            if (!MediaPlayerFallback.supportsExtension(extensionOf(song))) {
                fullToExo[fullIdx] = exoIdx
                exoIdx++
            }
        }
    }

    /**
     * fullIdx → ExoPlayer 窗口内索引（-1 表示该 fullIdx 不在窗口或为兜底格式）。
     * 同时填充 [fullToExo] 映射（剔除 APE/WMA，剔除项不映射）。
     */
    private fun buildExoWindow(window: List<Song>, startFullIdx: Int): Pair<List<Song>, Int> {
        fullToExo.clear()
        val exoSongs = mutableListOf<Song>()
        window.forEachIndexed { offset, song ->
            val fullIdx = windowStart + offset
            if (!MediaPlayerFallback.supportsExtension(extensionOf(song))) {
                fullToExo[fullIdx] = exoSongs.size
                exoSongs.add(song)
            }
        }
        // 起始 fullIdx 对应的 exo 索引（若起始是兜底格式则从 0 开始，实际由 MediaPlayer 接管）
        val startExo = fullToExo[startFullIdx] ?: 0
        return Pair(exoSongs, startExo)
    }

    private fun extensionOf(song: Song): String =
        song.path?.substringAfterLast('.', "")
            ?: song.uri.substringAfterLast('.', "").substringBefore('?')

    private fun player(): ExoPlayer = playerRef()
}