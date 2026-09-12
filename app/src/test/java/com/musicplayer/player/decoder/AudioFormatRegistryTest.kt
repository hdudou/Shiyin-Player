package com.shiyinplayer.player.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioFormatRegistry 单元测试（P1A §1.1 / P1B §2.1）。
 *
 * 验证 37 格式登记、Phase 枚举顺序、activeExtensions 过滤、
 * CURRENT_PHASE = P2B（active 28 格式：NATIVE 9 + P0 8 + P1A 6 + P2A 可用 2 + P2B 3）。
 *
 * 注（R-A1 同步）：DSD（P1B）尽早搁置——registry 已不含 dsf/dff，DSD 相关断言已随之移除。
 */
class AudioFormatRegistryTest {

    @Test
    fun `Phase enum ordinal order NATIVE less than P0 less than P1A less than P1B less than P2A less than P2B`() {
        val phases = AudioFormatRegistry.Phase.values()
        assertEquals(AudioFormatRegistry.Phase.NATIVE, phases[0])
        assertEquals(AudioFormatRegistry.Phase.P0, phases[1])
        assertEquals(AudioFormatRegistry.Phase.P1A, phases[2])
        assertEquals(AudioFormatRegistry.Phase.P1B, phases[3])
        assertEquals(AudioFormatRegistry.Phase.P2A, phases[4])
        assertEquals(AudioFormatRegistry.Phase.P2B, phases[5])
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
    fun `CURRENT_PHASE is P2B`() {
        assertEquals(AudioFormatRegistry.Phase.P2B, AudioFormatRegistry.CURRENT_PHASE)
    }

    @Test
    fun `activeExtensions contains 28 formats at P2B (deliverable only)`() {
        val active = AudioFormatRegistry.activeExtensions()
        assertEquals(28, active.size)
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
    fun `activeExtensions includes P1A module music formats`() {
        val active = AudioFormatRegistry.activeExtensions()
        listOf("mod", "xm", "s3m", "it", "mtm", "umx").forEach {
            assertTrue("P1A format $it should be active", it in active)
        }
    }

    @Test
    fun `activeExtensions includes P2A ape and wma (rest deferred)`() {
        val active = AudioFormatRegistry.activeExtensions()
        listOf("ape", "wma").forEach {
            assertTrue("P2A format $it should be active at P2B", it in active)
        }
        listOf("wv", "tta", "mpc", "spx", "aa3", "at3", "oma", "tak", "ofr").forEach {
            assertFalse("deferred P2A format $it should NOT be active", it in active)
        }
    }

    @Test
    fun `activeExtensions includes P2B MIDI formats`() {
        val active = AudioFormatRegistry.activeExtensions()
        listOf("mid", "midi", "rmi").forEach {
            assertTrue("P2B format $it should be active at P2B", it in active)
        }
    }

    @Test
    fun `decodePathOf mod is NDK`() {
        assertEquals(AudioFormatRegistry.DecodePath.NDK, AudioFormatRegistry.decodePathOf("mod"))
    }

    @Test
    fun `decodePathOf mp3 is SYSTEM`() {
        assertEquals(AudioFormatRegistry.DecodePath.SYSTEM, AudioFormatRegistry.decodePathOf("mp3"))
    }

    @Test
    fun `mimeTypeOf mod is audio x-mod`() {
        assertEquals("audio/x-mod", AudioFormatRegistry.mimeTypeOf("mod"))
    }

    @Test
    fun `magicOf xm is Ascii Extended Module at offset 0`() {
        val magic = AudioFormatRegistry.magicOf("xm")
        assertNotNull(magic)
        assertTrue(magic is AudioFormatRegistry.MagicSpec.Ascii)
        assertEquals("Extended Module", (magic as AudioFormatRegistry.MagicSpec.Ascii).text)
        assertEquals(0, magic.offset)
    }

    @Test
    fun `magicOf s3m is Ascii SCRM at offset 44`() {
        val magic = AudioFormatRegistry.magicOf("s3m")
        assertNotNull(magic)
        assertTrue(magic is AudioFormatRegistry.MagicSpec.Ascii)
        assertEquals("SCRM", (magic as AudioFormatRegistry.MagicSpec.Ascii).text)
        assertEquals(44, magic.offset)
    }

    @Test
    fun `isFormatActive returns true for active formats`() {
        assertTrue(AudioFormatRegistry.isFormatActive("mp3"))
        assertTrue(AudioFormatRegistry.isFormatActive("mod"))
        assertTrue(AudioFormatRegistry.isFormatActive("wma"))
    }

    @Test
    fun `isFormatActive returns true for all registered formats at P2B`() {
        assertTrue(AudioFormatRegistry.isFormatActive("ape"))
        assertTrue(AudioFormatRegistry.isFormatActive("mid"))
    }

    @Test
    fun `isFormatActive returns false for unknown format`() {
        assertFalse(AudioFormatRegistry.isFormatActive("xyz"))
    }

    @Test
    fun `phaseOf returns correct phase`() {
        assertEquals(AudioFormatRegistry.Phase.NATIVE, AudioFormatRegistry.phaseOf("mp3"))
        assertEquals(AudioFormatRegistry.Phase.P0, AudioFormatRegistry.phaseOf("ac3"))
        assertEquals(AudioFormatRegistry.Phase.P1A, AudioFormatRegistry.phaseOf("mod"))
        assertEquals(AudioFormatRegistry.Phase.P2A, AudioFormatRegistry.phaseOf("ape"))
        assertEquals(AudioFormatRegistry.Phase.P2B, AudioFormatRegistry.phaseOf("mid"))
    }

    @Test
    fun `phaseOf returns null for unknown and removed DSD formats`() {
        assertNull(AudioFormatRegistry.phaseOf("xyz"))
        assertNull(AudioFormatRegistry.phaseOf("dsf"))
    }

    @Test
    fun `activeExtensions with empty filter returns all active`() {
        val active = AudioFormatRegistry.activeExtensions(emptySet())
        assertEquals(28, active.size)
    }

    @Test
    fun `activeExtensions with filter returns intersection`() {
        val filter = setOf("mp3", "mod", "ape", "xyz")
        val active = AudioFormatRegistry.activeExtensions(filter)
        assertEquals(setOf("mp3", "mod", "ape"), active)
    }

    @Test
    fun `probeStrategyOf returns correct strategy`() {
        assertEquals(
            AudioFormatRegistry.ProbeStrategy.DURATION_RETRIEVER,
            AudioFormatRegistry.probeStrategyOf("mp3")
        )
        assertEquals(
            AudioFormatRegistry.ProbeStrategy.FORMAT_SPECIFIC,
            AudioFormatRegistry.probeStrategyOf("mod")
        )
    }
}