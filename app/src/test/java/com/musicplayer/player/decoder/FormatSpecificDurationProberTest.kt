package com.shiyinplayer.player.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FormatSpecificDurationProber 单元测试（P1A §1.8 / P0 §1.5）。
 * 2026-08-24：模块音乐 / MIDI 支持项已移除，对应用例一并删除。
 *
 * 验证 AIFF 精确解析、异常返回 0。
 */
class FormatSpecificDurationProberTest {

    @Test
    fun `probe unknown format returns 0`() {
        val result = FormatSpecificDurationProber.probe("xyz", ByteArray(100), 1000L)
        assertEquals(0L, result)
    }

    @Test
    fun `probe exception returns 0 gracefully`() {
        val result = FormatSpecificDurationProber.probe("aiff", ByteArray(5), 100L)
        assertEquals(0L, result)
    }

    @Test
    fun `probe wv with wvpk header parses duration`() {
        val header = ByteArray(28)
        "wvpk".toByteArray().copyInto(header, 0)
        header[12] = 0x40.toByte(); header[13] = 0x0F.toByte()
        header[24] = 0x44.toByte(); header[25] = 0xAC.toByte()
        val result = FormatSpecificDurationProber.probe("wv", header, 10000L)
        assertTrue("WavPack should return non-zero", result > 0)
    }

    @Test
    fun `probe wv with invalid header returns 0`() {
        val result = FormatSpecificDurationProber.probe("wv", ByteArray(28), 10000L)
        assertEquals(0L, result)
    }

    @Test
    fun `probe tta with TTA1 header parses duration`() {
        val header = ByteArray(18)
        "TTA1".toByteArray().copyInto(header, 0)
        header[4] = 0x40.toByte(); header[5] = 0x0F.toByte()
        header[10] = 0x44.toByte(); header[11] = 0xAC.toByte()
        val result = FormatSpecificDurationProber.probe("tta", header, 10000L)
        assertTrue("TTA should return non-zero", result > 0)
    }

    @Test
    fun `probe tta with invalid header returns 0`() {
        val result = FormatSpecificDurationProber.probe("tta", ByteArray(18), 10000L)
        assertEquals(0L, result)
    }

    /**
     * 5 MB 的 APE 按 800 kbps 估算 ≈ 50 秒。
     * 断言"量级合理"而不是仅"非 0"：曾出现过把 kbps 当 bps 用（`fileSize*8/800*1000`）
     * 导致结果放大 1000 倍的缺陷（5 MB 算成约 14 小时），而 `result > 0` 完全放过了它。
     * 区间取 20 秒 ~ 5 分钟：容纳真实码率差异，但任何 1000× 级错误都会被拒。
     */
    @Test
    fun `probe ape with MAC header returns plausible duration`() {
        val header = ByteArray(100)
        "MAC ".toByteArray().copyInto(header, 0)
        val result = FormatSpecificDurationProber.probe("ape", header, 5_000_000L)
        assertTrue(
            "APE 5MB 估算应落在 20s~5min，实际 ${result}ms（若约 5000万说明码率单位用了 kbps 而非 bps）",
            result in 20_000L..300_000L
        )
    }

    @Test
    fun `probe ape duration scales linearly with file size`() {
        val header = ByteArray(100)
        "MAC ".toByteArray().copyInto(header, 0)
        val one = FormatSpecificDurationProber.probe("ape", header, 1_000_000L)
        val five = FormatSpecificDurationProber.probe("ape", header, 5_000_000L)
        // 估算公式对文件大小线性，5 倍大小应得 5 倍时长（允许 ±10% 取整误差）
        assertTrue("时长应随文件大小线性增长：1MB=$one, 5MB=$five", five in (one * 4.5).toLong()..(one * 5.5).toLong())
    }

    @Test
    fun `probe ape with invalid header returns 0`() {
        val result = FormatSpecificDurationProber.probe("ape", ByteArray(100), 5_000_000L)
        assertEquals(0L, result)
    }

    @Test
    fun `probe ofr with OFR header returns plausible duration`() {
        val header = ByteArray(100)
        "OFR ".toByteArray().copyInto(header, 0)
        val result = FormatSpecificDurationProber.probe("ofr", header, 5_000_000L)
        assertTrue(
            "OptimFROG 5MB 估算应落在 20s~5min，实际 ${result}ms",
            result in 20_000L..300_000L
        )
    }

    @Test
    fun `probe mpc with invalid header returns 0`() {
        val result = FormatSpecificDurationProber.probe("mpc", ByteArray(100), 10000L)
        assertEquals(0L, result)
    }

    @Test
    fun `probe spx with invalid header returns 0`() {
        val result = FormatSpecificDurationProber.probe("spx", ByteArray(100), 10000L)
        assertEquals(0L, result)
    }

    @Test
    fun `probe all P2A formats do not crash`() {
        val formats = listOf("ape", "wv", "tta", "mpc", "spx", "aa3", "at3", "oma", "wma", "tak", "ofr")
        formats.forEach { ext ->
            FormatSpecificDurationProber.probe(ext, ByteArray(100), 10000L)
        }
    }
}