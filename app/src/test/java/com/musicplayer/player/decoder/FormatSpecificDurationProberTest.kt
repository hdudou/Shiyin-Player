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

    @Test
    fun `probe ape with MAC header returns non-zero estimate`() {
        val header = ByteArray(100)
        "MAC ".toByteArray().copyInto(header, 0)
        val result = FormatSpecificDurationProber.probe("ape", header, 5_000_000L)
        assertTrue("APE should return non-zero estimate", result > 0)
    }

    @Test
    fun `probe ape with invalid header returns 0`() {
        val result = FormatSpecificDurationProber.probe("ape", ByteArray(100), 5_000_000L)
        assertEquals(0L, result)
    }

    @Test
    fun `probe ofr with OFR header returns non-zero estimate`() {
        val header = ByteArray(100)
        "OFR ".toByteArray().copyInto(header, 0)
        val result = FormatSpecificDurationProber.probe("ofr", header, 5_000_000L)
        assertTrue("OptimFROG should return non-zero estimate", result > 0)
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