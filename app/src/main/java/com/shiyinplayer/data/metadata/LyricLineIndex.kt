package com.shiyinplayer.data.metadata

/**
 * 歌词行定位（Batch 4 / B4-7）。
 *
 * 播放页每 ~200ms 要问一次"当前该高亮哪一行"，原先两处实现都是**全量线性扫描**
 * （`LyricLinesStore.lineAt` 与 `LyricsViewModel.indexForPosition`）——
 * 一首歌几百行歌词、每秒问 5 次，长期播放时这部分开销完全是白烧的。
 *
 * 这里统一成一个入口，并按**列表是否已升序**选择算法：
 * - 已升序（LRC 的正常形态）→ 二分查找；
 * - 未升序 / 有时间戳相等的乱序行 → 保留原来的全量扫描。
 *
 * 为什么不无脑二分：原实现的注释明确写了"对乱序/相等时间戳稳健，不提前 break"，
 * 说明它见过乱序歌词。二分在乱序数据上会给出**不同的行**，那就不是优化而是改行为。
 * 所以排序性做一次缓存，两种情况都保持原有的"取最后一个 timeMs <= position 的行"语义。
 */
object LyricLineIndex {

    /** 列表是否按 timeMs 非递减排列（相等视为有序）。 */
    fun isSortedAscending(lines: List<MergedLine>): Boolean {
        for (i in 1 until lines.size) {
            if (lines[i - 1].timeMs > lines[i].timeMs) return false
        }
        return true
    }

    /**
     * 返回"最后一个 timeMs <= [positionMs] 的行"的下标；没有这样的行返回 -1。
     *
     * @param sortedAscending 由 [isSortedAscending] 预先算好并缓存 —— 每次调用都重算等于把
     *   省下的扫描又花回去，别在这里现算。
     */
    fun indexOf(lines: List<MergedLine>, positionMs: Long, sortedAscending: Boolean): Int {
        if (lines.isEmpty()) return -1
        return if (sortedAscending) binarySearch(lines, positionMs) else linearScan(lines, positionMs)
    }

    /**
     * 升序列表上的上界查找：找到第一个 timeMs > position 的位置再减一。
     * 与线性扫描语义等价（含时间戳相等的情况，因为上界会跳过所有相等项）。
     */
    private fun binarySearch(lines: List<MergedLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size      // [lo, hi) 内所有行的 timeMs <= position
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }

    private fun linearScan(lines: List<MergedLine>, positionMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (positionMs >= lines[i].timeMs) idx = i
        }
        return idx
    }
}
