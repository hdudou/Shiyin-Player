package com.shiyinplayer.player

import com.shiyinplayer.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 队列控制：维护当前播放队列、索引、随机与循环逻辑。
 * PlayerManager 委托此类计算 next/prev。
 *
 * P2-1：公开可变状态（queue / currentIndex / shuffle / repeatMode）收敛为 `private set`，
 * 状态突变必须经显式方法（[setQueue] / [replaceQueue] / [updateAt] / [setCurrentIndex] /
 * [setRepeatMode] / [toggleShuffle] / [setShuffle]）完成，避免外部散落裸写导致状态不一致。
 *
 * DD：随机播放由「每次 nextIndex() 独立取随机」改为 Fisher-Yates 洗牌序列 + 指针：
 *  - 保证每轮完整遍历所有曲目一次，不重复也不遗漏（除列表循环 ALL 外）；
 *  - prev 沿随机序列向前回退（而非沿原索引回退）；
 *  - 队列/索引变化时同步重建洗牌序列，保证映射稳定。
 */
@Singleton
class QueueController @Inject constructor() {
    var queue: List<Song> = emptyList()
        private set
    var currentIndex = -1
        private set
    var shuffle = false
        private set
    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    /** Fisher-Yates 洗牌后的索引序列（shuffle=true 时使用）。 */
    private var shuffleOrder: IntArray = IntArray(0)
    /** 当前在 [shuffleOrder] 中的指针位置；shuffleOrder[shufflePointer] == currentIndex。 */
    private var shufflePointer = -1

    fun setQueue(songs: List<Song>, startIndex: Int) {
        queue = songs
        currentIndex = if (songs.isEmpty()) -1 else startIndex.coerceIn(0, songs.size - 1)
        if (shuffle && currentIndex >= 0) {
            rebuildShuffleOrder(currentIndex)
        }
    }

    /** 整体替换队列（不动索引；removeAt / moveQueueItem / enqueueNext / refreshCurrentSong 用）。 */
    fun replaceQueue(songs: List<Song>) {
        queue = songs
        if (currentIndex !in songs.indices) currentIndex = (songs.size - 1).coerceAtLeast(-1)
        if (shuffle && currentIndex >= 0) {
            rebuildShuffleOrder(currentIndex)
        }
    }

    /** 更新队列中单项（保持同一索引，刷新元数据后回写用）。 */
    fun updateAt(index: Int, song: Song) {
        if (index in queue.indices) {
            queue = queue.toMutableList().apply { this[index] = song }
        }
    }

    fun setCurrentIndex(index: Int) {
        currentIndex = index
        if (shuffle && index >= 0) {
            // 在洗牌序列中定位指针；找不到说明队列/索引有变化，重建并以新位置为首项
            val pos = shuffleOrder.indexOf(index)
            if (pos >= 0) {
                shufflePointer = pos
            } else {
                rebuildShuffleOrder(index)
            }
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
    }

    fun toggleShuffle() {
        setShuffle(!shuffle)
    }

    fun setShuffle(value: Boolean) {
        if (shuffle == value) return
        shuffle = value
        if (value && queue.isNotEmpty() && currentIndex >= 0) {
            rebuildShuffleOrder(currentIndex)
        }
    }

    fun nextIndex(): Int {
        if (queue.isEmpty()) return -1
        if (shuffle) {
            if (queue.size <= 1) return currentIndex
            val nextPtr = shufflePointer + 1
            return if (nextPtr < shuffleOrder.size) {
                shuffleOrder[nextPtr]
            } else {
                if (repeatMode == RepeatMode.ALL) {
                    // 新循环：洗牌后确保首项 ≠ 刚播完的末项，避免立即重复
                    val lastPlayed = shuffleOrder.lastOrNull()
                    var arr = fisherYates(queue.size)
                    if (lastPlayed != null && queue.size > 1) {
                        while (arr[0] == lastPlayed) arr = fisherYates(queue.size)
                    }
                    shuffleOrder = arr
                    shufflePointer = 0
                    shuffleOrder[0]
                } else {
                    -1
                }
            }
        }
        val n = currentIndex + 1
        return if (n >= queue.size) if (repeatMode == RepeatMode.ALL) 0 else -1 else n
    }

    fun prevIndex(): Int {
        if (queue.isEmpty()) return -1
        // CG-同 nextIndex：ONE 下单曲循环仍允许主动切到上一首；自然单曲循环由 ExoPlayer 处理
        if (shuffle) {
            if (queue.size <= 1) return currentIndex
            val prevPtr = shufflePointer - 1
            return if (prevPtr >= 0) {
                shuffleOrder[prevPtr]
            } else {
                // 指针已在首位：ALL 循环 → 回跳到序列末尾；否则无更多
                if (repeatMode == RepeatMode.ALL && shuffleOrder.isNotEmpty()) {
                    shuffleOrder[shuffleOrder.size - 1]
                } else {
                    -1
                }
            }
        }
        val p = currentIndex - 1
        return if (p < 0) if (repeatMode == RepeatMode.ALL) queue.size - 1 else -1 else p
    }

    fun current(): Song? = queue.getOrNull(currentIndex)

    /** 对 0..n-1 做 Fisher-Yates 洗牌。 */
    private fun fisherYates(n: Int): IntArray {
        val arr = IntArray(n) { it }
        val rnd = Random.Default
        for (i in n - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp
        }
        return arr
    }

    /**
     * 重建洗牌序列：Fisher-Yates 洗牌后，把 [startIdx] 交换到序列首位，指针置 0。
     * 保证开启 shuffle / 外部跳转时，当前曲就是洗牌序列首项，不从当前曲跳走。
     */
    private fun rebuildShuffleOrder(startIdx: Int) {
        val n = queue.size
        val arr = fisherYates(n)
        if (startIdx in 0 until n) {
            val pos = arr.indexOf(startIdx)
            if (pos > 0) {
                val tmp = arr[0]; arr[0] = arr[pos]; arr[pos] = tmp
            }
            shufflePointer = 0
        } else {
            shufflePointer = -1
        }
        shuffleOrder = arr
    }
}
