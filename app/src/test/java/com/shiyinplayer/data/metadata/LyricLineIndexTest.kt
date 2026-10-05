package com.shiyinplayer.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * B4-7：歌词行定位从"每次全量线性扫描"改成"升序时二分 + 乱序时回退扫描"，
 * 这个改动唯一不能出错的地方就是**语义必须完全一致** ——
 * 二分算错一行，用户看到的就是整首歌的歌词错位。
 *
 * 所以这里不测"我认为对"的期望值，而是**直接拿原实现当参照物逐点比对**：
 * 同一条位置输入，线性扫描的结果是唯一真相。
 */
class LyricLineIndexTest {

    /** 优化前的实现（保留一份作参照物，别删）。 */
    private fun referenceIndex(lines: List<MergedLine>, positionMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (positionMs >= lines[i].timeMs) idx = i
        }
        return idx
    }

    private fun line(timeMs: Long, text: String = "t") = MergedLine(timeMs, text, null)

    private fun assertSameAsReference(lines: List<MergedLine>, positionMs: Long, label: String) {
        val sorted = LyricLineIndex.isSortedAscending(lines)
        val actual = LyricLineIndex.indexOf(lines, positionMs, sorted)
        val expected = referenceIndex(lines, positionMs)
        assertEquals("$label（position=$positionMs, sorted=$sorted）", expected, actual)
    }

    @Test
    fun `空列表返回 -1`() {
        assertEquals(-1, LyricLineIndex.indexOf(emptyList(), 0L, true))
        assertEquals(-1, LyricLineIndex.indexOf(emptyList(), 999L, false))
    }

    @Test
    fun `升序列表逐点与线性扫描一致`() {
        val lines = listOf(line(0), line(1000), line(2000), line(3500), line(9000))
        for (p in -500L..10000L step 250) {
            assertSameAsReference(lines, p, "升序")
        }
    }

    @Test
    fun `时间戳重复时取最后一个同时间戳的行`() {
        // 对唱/重复段落的 LRC 常见写法：同一毫秒多行
        val lines = listOf(line(0, "a"), line(1000, "b"), line(1000, "c"), line(1000, "d"), line(2000, "e"))
        assertSameAsReference(lines, 1000, "重复时间戳")
        assertEquals(3, LyricLineIndex.indexOf(lines, 1000, true))
    }

    @Test
    fun `位置早于第一行返回 -1`() {
        val lines = listOf(line(5000), line(6000))
        assertSameAsReference(lines, 0, "早于首行")
        assertEquals(-1, LyricLineIndex.indexOf(lines, 0, true))
        assertEquals(-1, LyricLineIndex.indexOf(lines, 4999, true))
        assertEquals(0, LyricLineIndex.indexOf(lines, 5000, true))
    }

    @Test
    fun `乱序列表回退扫描且结果与参照物一致`() {
        val lines = listOf(line(5000, "x"), line(1000, "y"), line(8000, "z"), line(3000, "w"))
        assertFalse("应判定为乱序", LyricLineIndex.isSortedAscending(lines))
        for (p in -100L..9000L step 137) {
            assertSameAsReference(lines, p, "乱序")
        }
    }

    @Test
    fun `单行与两行边界`() {
        assertSameAsReference(listOf(line(1000)), 999, "单行-前")
        assertSameAsReference(listOf(line(1000)), 1000, "单行-等")
        assertSameAsReference(listOf(line(1000)), 1001, "单行-后")
        val two = listOf(line(0), line(1000))
        assertSameAsReference(two, 0, "两行")
        assertSameAsReference(two, 999, "两行")
        assertSameAsReference(two, 1000, "两行")
    }

    @Test
    fun `排序性判定`() {
        assertTrue(LyricLineIndex.isSortedAscending(emptyList()))
        assertTrue(LyricLineIndex.isSortedAscending(listOf(line(1))))
        assertTrue(LyricLineIndex.isSortedAscending(listOf(line(1), line(1), line(2))))
        assertFalse(LyricLineIndex.isSortedAscending(listOf(line(2), line(1))))
    }

    /** 随机模糊：把参照物当唯一真相，随机行数/随机时间戳/随机位置逐点比对。 */
    @Test
    fun `随机模糊与参照物一致`() {
        val rnd = Random(20260923)
        repeat(300) { round ->
            val count = rnd.nextInt(0, 40)
            val sortedWanted = rnd.nextBoolean()
            val raw = List(count) { rnd.nextInt(0, 5000) }
            val times = if (sortedWanted) raw.sorted() else raw
            val lines = times.mapIndexed { i, t -> line(t.toLong(), "L$i") }

            repeat(20) {
                val position = rnd.nextInt(-500, 6000).toLong()
                assertSameAsReference(lines, position, "模糊#$round(${if (sortedWanted) "有序" else "乱序"})")
            }
        }
    }

    /**
     * 量化：500 行歌词 × 20 万次定位。不断言耗时（CI 上会抖），只打印对照数字，
     * 让"这次改动到底省了多少"留下可复现的证据。
     */
    @Test
    fun `性能对照（仅打印）`() {
        val lines = List(500) { line(it * 400L, "line $it") }
        val positions = LongArray(200_000) { (it * 7L) % (500 * 400L) }

        val linearStart = System.nanoTime()
        var acc = 0L
        for (p in positions) acc += referenceIndex(lines, p)
        val linearMs = (System.nanoTime() - linearStart) / 1_000_000.0

        val binaryStart = System.nanoTime()
        var acc2 = 0L
        for (p in positions) acc2 += LyricLineIndex.indexOf(lines, p, true)
        val binaryMs = (System.nanoTime() - binaryStart) / 1_000_000.0

        println(
            "B4-7 歌词定位 500 行 × ${positions.size} 次：线性 %.1fms → 二分 %.1fms（校验和 %d/%d）"
                .format(linearMs, binaryMs, acc, acc2)
        )
        assertEquals("两次遍历校验和必须一致", acc, acc2)
    }
}
