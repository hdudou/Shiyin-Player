package com.musicplayer.player.decoder

import com.shiyinplayer.player.decoder.AudioFormatRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioFormatRegistry 单元测试。
 *
 * 验证格式登记表、Phase 枚举顺序、activeExtensions 过滤。
 *
 * 2026-09-16 同步：登记表已收敛为 **37 个格式 / 3 个阶段**（NATIVE 13 + P0 8 + P2A 16），
 * CURRENT_PHASE = P2A，故全部登记格式均 active。原 P1A 模块音乐（mod/xm/s3m/it/mtm/umx）、
 * P2B MIDI（mid/midi/rmi）与 P1B DSD 的旧断言随格式移除/回归一并重写：
 * 模块音乐与 MIDI 已彻底移除，DSD（dsf/dff）经自编译 FFmpeg 软解回归为 P2A 可用格式。
 */
class AudioFormatRegistryTest {

    @Test
    fun `Phase enum ordinal order NATIVE less than P0 less than P2A`() {
        val phases = AudioFormatRegistry.Phase.values()
        assertEquals(3, phases.size)
        assertEquals(AudioFormatRegistry.Phase.NATIVE, phases[0])
        assertEquals(AudioFormatRegistry.Phase.P0, phases[1])
        assertEquals(AudioFormatRegistry.Phase.P2A, phases[2])
    }

    @Test
    fun `allFormats contains 37 extensions`() {
        assertEquals(37, AudioFormatRegistry.allFormats.size)
    }

    @Test
    fun `allFormats has no duplicate extensions`() {
        val extensions = AudioFormatRegistry.allFormats.map { it.extension }
        assertEquals(extensions.size, extensions.toSet().size)
    }

    @Test
    fun `CURRENT_PHASE is P2A`() {
        assertEquals(AudioFormatRegistry.Phase.P2A, AudioFormatRegistry.CURRENT_PHASE)
    }

    @Test
    fun `activeExtensions contains all 37 registered formats at P2A`() {
        assertEquals(37, AudioFormatRegistry.activeExtensions().size)
    }

    @Test
    fun `activeExtensions includes NATIVE formats`() {
        val active = AudioFormatRegistry.activeExtensions()
        listOf("mp3", "aac", "m4a", "m4b", "ogg", "oga", "opus", "flac", "wav").forEach {
            assertTrue("NATIVE format $it should be active", it in active)
        }
    }

    @Test
    fun `activeExtensions includes P0 formats`() {
        val active = AudioFormatRegistry.activeExtensions()
        listOf("ac3", "eac3", "dts", "mp1", "mp2", "alac", "aiff", "aif").forEach {
            assertTrue("P0 format $it should be active", it in active)
        }
    }

    @Test
    fun `activeExtensions includes all P2A formats unlocked by ffmpeg soft decoding`() {
        val active = AudioFormatRegistry.activeExtensions()
        listOf("ape", "wv", "tta", "mpc", "spx", "aa3", "at3", "oma", "wma", "tak", "ofr").forEach {
            assertTrue("P2A format $it should be active", it in active)
        }
    }

    @Test
    fun `activeExtensions includes ffmpeg-backed DSD and extra formats`() {
        val active = AudioFormatRegistry.activeExtensions()
        listOf("dsf", "dff", "caf", "shn", "ac4").forEach {
            assertTrue("FFmpeg soft-decoded format $it should be active", it in active)
        }
    }

    @Test
    fun `removed module music and MIDI formats are not registered`() {
        listOf("mod", "xm", "s3m", "it", "mtm", "umx", "mid", "midi", "rmi").forEach {
            assertFalse("$it should have been removed from registry", it in AudioFormatRegistry.activeExtensions())
            assertNull("$it should have no phase entry", AudioFormatRegistry.phaseOf(it))
        }
    }

    @Test
    fun `decodePathOf ape is NDK`() {
        assertEquals(AudioFormatRegistry.DecodePath.NDK, AudioFormatRegistry.decodePathOf("ape"))
    }

    @Test
    fun `decodePathOf mp3 is SYSTEM`() {
        assertEquals(AudioFormatRegistry.DecodePath.SYSTEM, AudioFormatRegistry.decodePathOf("mp3"))
    }

    @Test
    fun `mimeTypeOf ape is audio x-ape`() {
        assertEquals("audio/x-ape", AudioFormatRegistry.mimeTypeOf("ape"))
    }

    @Test
    fun `magicOf ape is Ascii MAC at offset 0`() {
        val magic = AudioFormatRegistry.magicOf("ape")
        assertNotNull(magic)
        assertTrue(magic is AudioFormatRegistry.MagicSpec.Ascii)
        assertEquals("MAC ", (magic as AudioFormatRegistry.MagicSpec.Ascii).text)
        assertEquals(0, magic.offset)
    }

    @Test
    fun `magicOf wv is Ascii wvpk at offset 0`() {
        val magic = AudioFormatRegistry.magicOf("wv")
        assertNotNull(magic)
        assertTrue(magic is AudioFormatRegistry.MagicSpec.Ascii)
        assertEquals("wvpk", (magic as AudioFormatRegistry.MagicSpec.Ascii).text)
        assertEquals(0, magic.offset)
    }

    @Test
    fun `magicOf ac3 is HexBytes 0B77 at offset 0`() {
        val magic = AudioFormatRegistry.magicOf("ac3")
        assertNotNull(magic)
        assertTrue(magic is AudioFormatRegistry.MagicSpec.HexBytes)
        val bytes = (magic as AudioFormatRegistry.MagicSpec.HexBytes).bytes
        assertEquals(2, bytes.size)
        assertEquals(0x0B.toByte(), bytes[0])
        assertEquals(0x77.toByte(), bytes[1])
    }

    @Test
    fun `isFormatActive returns true for active formats`() {
        assertTrue(AudioFormatRegistry.isFormatActive("mp3"))
        assertTrue(AudioFormatRegistry.isFormatActive("ape"))
        assertTrue(AudioFormatRegistry.isFormatActive("wma"))
    }

    @Test
    fun `isFormatActive returns true for all registered formats at P2A`() {
        assertTrue(AudioFormatRegistry.isFormatActive("ape"))
        assertTrue(AudioFormatRegistry.isFormatActive("dsf"))
    }

    @Test
    fun `isFormatActive returns false for unknown format`() {
        assertFalse(AudioFormatRegistry.isFormatActive("xyz"))
    }

    @Test
    fun `phaseOf returns correct phase`() {
        assertEquals(AudioFormatRegistry.Phase.NATIVE, AudioFormatRegistry.phaseOf("mp3"))
        assertEquals(AudioFormatRegistry.Phase.P0, AudioFormatRegistry.phaseOf("ac3"))
        assertEquals(AudioFormatRegistry.Phase.P2A, AudioFormatRegistry.phaseOf("ape"))
        assertEquals(AudioFormatRegistry.Phase.P2A, AudioFormatRegistry.phaseOf("dsf"))
    }

    @Test
    fun `phaseOf returns null for unknown format`() {
        assertNull(AudioFormatRegistry.phaseOf("xyz"))
    }

    @Test
    fun `activeExtensions with empty filter returns all active`() {
        val active = AudioFormatRegistry.activeExtensions(emptySet())
        assertEquals(37, active.size)
    }

    @Test
    fun `activeExtensions with filter returns intersection`() {
        val filter = setOf("mp3", "ape", "xyz")
        val active = AudioFormatRegistry.activeExtensions(filter)
        assertEquals(setOf("mp3", "ape"), active)
    }

    @Test
    fun `probeStrategyOf returns correct strategy`() {
        assertEquals(
            AudioFormatRegistry.ProbeStrategy.DURATION_RETRIEVER,
            AudioFormatRegistry.probeStrategyOf("mp3")
        )
        assertEquals(
            AudioFormatRegistry.ProbeStrategy.FORMAT_SPECIFIC,
            AudioFormatRegistry.probeStrategyOf("ape")
        )
    }
}
