package com.shiyinplayer.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 内嵌歌词解析（需求⑦：安卓对齐 PC 的 TagLib —— ID3v2 USLT / FLAC LYRICS / MP4 ©lyr）。
 *
 * 为什么值得单测：改造前只支持 ID3，FLAC/MP4 一律读不到 —— 同一批文件在 PC 有歌词、在手机没有。
 * 而字节解析里全是**不会在编译期报错**的细节：
 *   · ID3 的长度是「同步安全整数」（每字节只用 7 位）；
 *   · FLAC 自身的块长度是**大端**，而 VORBIS_COMMENT 内部的字段长度是**小端**（最容易写反）；
 *   · MP4 的 `©lyr` 是**单字节 0xA9** + "lyr"（不是 UTF-8 的 C2 A9），用字符串比较永远匹配不上；
 *   · `meta` 是 full box，子 atom 要从 +4 开始。
 * 所以这些用例全部用手工构造的最小容器来做，不依赖真实音频文件。
 */
class EmbeddedLyricsTest {

    private val lrc = "[00:01.00]第一行\n[00:05.50]第二行"

    // ---------------------------------------------------------------- ID3v2

    private fun syncSafe(n: Int) = byteArrayOf(
        ((n shr 21) and 0x7f).toByte(), ((n shr 14) and 0x7f).toByte(),
        ((n shr 7) and 0x7f).toByte(), (n and 0x7f).toByte()
    )

    private fun be32(n: Int) = byteArrayOf(
        (n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()
    )

    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** ID3v2.3/2.4：帧头 4 字节标识 + 4 字节大端长度 + 2 字节 flags；正文 = 编码[1] + 语言[3] + 描述 + 正文。 */
    private fun id3(version: Int, frameId: String, encoding: Int, text: String, desc: String = ""): ByteArray {
        val utf16 = encoding == 1 || encoding == 2
        val descBytes = if (utf16) {
            cat(desc.toByteArray(Charsets.UTF_16LE), byteArrayOf(0, 0))
        } else {
            cat(desc.toByteArray(Charsets.UTF_8), byteArrayOf(0))
        }
        val body = cat(
            byteArrayOf(encoding.toByte()),
            "eng".toByteArray(),
            descBytes,
            if (utf16) text.toByteArray(Charsets.UTF_16LE) else text.toByteArray(Charsets.UTF_8)
        )
        val frame = cat(frameId.toByteArray(Charsets.ISO_8859_1), be32(body.size), byteArrayOf(0, 0), body)
        return cat("ID3".toByteArray(), byteArrayOf(version.toByte(), 0, 0), syncSafe(frame.size), frame)
    }

    /** ID3v2.2：帧头 3 字节标识 + 3 字节大端长度。 */
    private fun id3v22(text: String): ByteArray {
        val body = cat(byteArrayOf(3), "eng".toByteArray(), byteArrayOf(0), text.toByteArray(Charsets.UTF_8))
        val frame = cat("ULT".toByteArray(), byteArrayOf(
            (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()
        ), body)
        return cat("ID3".toByteArray(), byteArrayOf(2, 0, 0), syncSafe(frame.size), frame)
    }

    @Test
    fun `ID3v2_4 的 USLT 仍能解析（原有行为不能回退）`() {
        assertEquals(lrc, EmbeddedLyrics.parse(id3(4, "USLT", 3, lrc)))
    }

    /**
     * 描述为空的帧是**绝大多数真实文件**（正文紧跟语言之后）。
     * 这条与 [ID3v2_4 的 USLT 仍能解析] 一起，把修掉的那个 off-by-one 两头都锁住：
     * 原来只有「描述非空」的罕见帧能过，描述为空一律返回 null。
     */
    @Test
    fun `ID3 的 USLT 描述非空时也能解析`() {
        assertEquals(lrc, EmbeddedLyrics.parse(id3(4, "USLT", 3, lrc, desc = "歌词")))
    }

    @Test
    fun `ID3 的 USLT 用 UTF-16LE 编码时能解析`() {
        assertEquals(lrc, EmbeddedLyrics.parse(id3(3, "USLT", 1, lrc)))
    }

    @Test
    fun `ID3v2_2 的 ULT 仍能解析`() {
        assertEquals(lrc, EmbeddedLyrics.parse(id3v22(lrc)))
    }

    @Test
    fun `ID3 里没有歌词帧时返回 null`() {
        val onlyTitle = cat("ID3".toByteArray(), byteArrayOf(3, 0, 0), syncSafe(0))
        assertNull(EmbeddedLyrics.parse(onlyTitle))
    }

    // ---------------------------------------------------------------- FLAC

    private fun le32(n: Int) = byteArrayOf(
        n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()
    )

    /** FLAC 元数据块：1 字节头（最高位=最后一块，低 7 位=类型）+ 3 字节大端长度。 */
    private fun flacBlock(type: Int, body: ByteArray, last: Boolean): ByteArray =
        cat(byteArrayOf(((if (last) 0x80 else 0) or type).toByte(),
            (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()), body)

    private fun vorbisComment(vararg entries: String): ByteArray {
        val vendor = "test".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(le32(vendor.size)); out.write(vendor)
        out.write(le32(entries.size))
        entries.forEach { e ->
            val b = e.toByteArray(Charsets.UTF_8)
            out.write(le32(b.size)); out.write(b)     // ⚠️ 小端
        }
        return out.toByteArray()
    }

    private fun flac(vararg blocks: ByteArray) =
        cat("fLaC".toByteArray(), *blocks)

    @Test
    fun `FLAC 的 VORBIS_COMMENT LYRICS 能解析`() {
        val file = flac(
            flacBlock(0, ByteArray(34), last = false),                        // STREAMINFO
            flacBlock(4, vorbisComment("TITLE=测试", "LYRICS=$lrc"), last = true)
        )
        assertEquals(lrc, EmbeddedLyrics.parse(file))
    }

    @Test
    fun `FLAC 的 UNSYNCEDLYRICS 也能解析（键名大小写不敏感）`() {
        val file = flac(
            flacBlock(4, vorbisComment("unsyncedlyrics=$lrc"), last = true)
        )
        assertEquals(lrc, EmbeddedLyrics.parse(file))
    }

    @Test
    fun `FLAC 没有歌词字段时返回 null`() {
        val file = flac(flacBlock(4, vorbisComment("TITLE=测试", "ARTIST=某人"), last = true))
        assertNull(EmbeddedLyrics.parse(file))
    }

    @Test
    fun `FLAC 的纯文本歌词（无时间轴）按统一口径返回 null`() {
        val file = flac(flacBlock(4, vorbisComment("LYRICS=这只是普通文本，没有时间轴"), last = true))
        assertNull(EmbeddedLyrics.parse(file))
    }

    @Test
    fun `FLAC 头部被截断时不崩、返回 null`() {
        val full = flac(
            flacBlock(0, ByteArray(34), last = false),
            flacBlock(4, vorbisComment("LYRICS=$lrc"), last = true)
        )
        assertNull(EmbeddedLyrics.parse(full.copyOf(full.size / 2)))
    }

    // ---------------------------------------------------------------- MP4

    private fun atom(type: ByteArray, body: ByteArray) = cat(be32(body.size + 8), type, body)

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** `©lyr` 的类型是**单字节 0xA9** + "lyr" —— 不是 UTF-8 的 C2 A9，这是本节的关键点。 */
    private val lyrType = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())

    private fun mp4Moov(lyric: String?): ByteArray {
        val ilstBody = if (lyric == null) ByteArray(0) else {
            val dataBody = cat(be32(1), be32(0), lyric.toByteArray(Charsets.UTF_8)) // type=1(UTF-8) + locale
            atom(lyrType, atom(ascii("data"), dataBody))
        }
        val ilst = atom(ascii("ilst"), ilstBody)
        val meta = atom(ascii("meta"), cat(be32(0), ilst))       // meta 是 full box：version+flags
        val udta = atom(ascii("udta"), meta)
        return atom(ascii("moov"), udta)
    }

    private fun mp4File(moov: ByteArray, leadingMdatBytes: Int = 0) = cat(
        atom(ascii("ftyp"), cat(ascii("M4A "), be32(0), ascii("M4A "))),
        if (leadingMdatBytes > 0) atom(ascii("mdat"), ByteArray(leadingMdatBytes)) else ByteArray(0),
        moov
    )

    @Test
    fun `MP4 的 ©lyr 能解析（moov 在文件头）`() {
        assertEquals(lrc, EmbeddedLyrics.parse(mp4File(mp4Moov(lrc))))
    }

    @Test
    fun `MP4 没有 ©lyr 时返回 null`() {
        assertNull(EmbeddedLyrics.parse(mp4File(mp4Moov(null))))
    }

    @Test
    fun `MP4 的 moov 在文件末尾时头部窗口取不到、尾部窗口能取到`() {
        val file = mp4File(mp4Moov(lrc), leadingMdatBytes = 4096)
        val headWindowSize = 512                       // 模拟只读文件头 512 字节
        assertNull("头部窗口里不该有 moov", EmbeddedLyrics.parse(file.copyOf(headWindowSize)))

        val tailWindow = file.copyOfRange(file.size - 1024, file.size)
        assertEquals(lrc, EmbeddedLyrics.parseMp4Tail(tailWindow, file.size.toLong()))
    }

    @Test
    fun `尾部窗口里恰好出现 moov 字样但结束位置不是文件末尾时判为 null`() {
        val fake = cat(be32(16), ascii("moov"), ByteArray(8))     // 一个不结束在末尾的 moov
        val tail = cat(fake, ByteArray(64))
        assertNull(EmbeddedLyrics.parseMp4Tail(tail, tail.size.toLong()))
    }

    // ---------------------------------------------------------------- 容器嗅探

    @Test
    fun `非音频字节一律不认`() {
        assertEquals(EmbeddedLyrics.Container.UNKNOWN, EmbeddedLyrics.sniff("not audio".toByteArray()))
        assertNull(EmbeddedLyrics.parse(ByteArray(0)))
        assertNull(EmbeddedLyrics.parse("RIFFxxxxWAVE".toByteArray()))
    }

    @Test
    fun `容器嗅探能区分三种格式`() {
        assertEquals(EmbeddedLyrics.Container.ID3, EmbeddedLyrics.sniff(id3(3, "USLT", 3, lrc)))
        assertEquals(EmbeddedLyrics.Container.FLAC, EmbeddedLyrics.sniff(flac(flacBlock(0, ByteArray(34), true))))
        assertEquals(EmbeddedLyrics.Container.MP4, EmbeddedLyrics.sniff(mp4File(mp4Moov(lrc))))
    }
}
