package com.shiyinplayer.data.metadata

import java.nio.charset.Charset

/**
 * 音频文件**内嵌歌词**的纯解析器（需求⑦：安卓对齐 PC 的 TagLib 能力）。
 *
 * 改造前只有 `LocalLyricLoader` 里那段私有 ID3 解析，只能读 MP3；PC 用 TagLib 覆盖
 * **ID3v2 USLT / FLAC LYRICS / MP4 ©lyr** 三处 ⇒ 同一批文件在两端表现不一致
 * （FLAC/MP4 在 PC 有歌词、在手机没有）。本文件把三种容器都补齐。
 *
 * 为什么抽成独立 object（而不是继续留在 Loader 里）：Loader 依赖 Context / SmbBrowser /
 * WebDavBrowser，在 JVM 单测里全是 stub；而**真正容易写错的是字节解析**（同步安全整数、
 * atom 尺寸、`©lyr` 那个 0xA9 单字节、VORBIS_COMMENT 的小端长度…），
 * 抽出来才能用手工构造的最小容器做离线单测。
 *
 * 统一口径：歌词正文必须含 LRC 时间轴（`[mm:ss]`）才认，与原有 ID3 行为一致 ——
 * 纯文本歌词没有时间轴，播放器按时间轴高亮会直接失效。
 */
internal object EmbeddedLyrics {

    /** 容器类型（按文件头嗅探）。 */
    enum class Container { ID3, FLAC, MP4, UNKNOWN }

    /** 内嵌歌词只出现在文件头部（ID3v2 / FLAC 元数据块）或 MP4 的 `moov` 里。 */
    fun sniff(bytes: ByteArray): Container = when {
        isId3(bytes) -> Container.ID3
        isFlac(bytes) -> Container.FLAC
        isMp4(bytes) -> Container.MP4
        else -> Container.UNKNOWN
    }

    /** 解析头部窗口里的内嵌歌词；读不到返回 null。 */
    fun parse(bytes: ByteArray): String? = when (sniff(bytes)) {
        Container.ID3 -> parseId3(bytes)
        Container.FLAC -> parseFlac(bytes)
        Container.MP4 -> parseMp4(bytes)
        Container.UNKNOWN -> null
    }

    /**
     * 解析**文件尾部窗口**里的 MP4 `moov`（`moov` 在 MP4 里可能在文件末尾 —— ffmpeg 默认
     * 不加 `-movflags faststart` 时就是这样，此时头部窗口里根本没有 `moov`）。
     *
     * 入参是尾部窗口 `tail` 与**整个文件的长度** `fileLength`：
     * 在窗口里从后往前找 `moov`，要求「它的 size 正好把窗口里剩下的字节数补成到文件末尾」
     * （即该 atom 的结束位置 == 文件长度）—— 这个判据很强，能避开正文里恰好出现的 "moov" 字样。
     *
     * 只对**本地可定位**的文件用（远程源没法只取尾部）。
     */
    fun parseMp4Tail(tail: ByteArray, fileLength: Long): String? {
        if (tail.size < 8 || fileLength < tail.size) return null
        val tailStart = fileLength - tail.size          // 该窗口在文件里的绝对起点
        val type = moovType
        for (p in tail.size - 8 downTo 4) {
            if (!matchesAscii(tail, p, type)) continue
            val size = readU32(tail, p - 4)
            val atomStart = p - 4
            if (size < 8L) continue
            // 该 moov 的结束位置必须正好是文件末尾
            if (tailStart + atomStart + size != fileLength) continue
            val found = parseMp4Atoms(tail, atomStart, tail.size)
            if (found != null) return found
        }
        return null
    }

    /** 统一过滤：去空白后必须含 LRC 时间轴（`[mm:ss]`）才算有效歌词。 */
    fun normalize(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        return t.takeIf { it.startsWith("[") && it.contains("]") }
    }

    // ---------------------------------------------------------------- ID3v2

    private fun isId3(b: ByteArray): Boolean =
        b.size >= 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()

    /**
     * ID3v2：v2.2 帧标识 3 字节（ULT），v2.3/v2.4 帧标识 4 字节（USLT）。
     *
     * ⚠️ 2026-09-21 修：原先这两处调用传的是 `decodeUslt(b, pos + 1, fs - 1)`，
     * 而 [decodeUslt] 要的 `start` 正是**帧体首字节（编码字节）** —— 差 1 字节的结果是
     * 「描述为空」的帧（绝大多数真实文件）会把语言首字母当编码、再把正文首字吃掉，
     * 最终一律返回 null；只有「描述非空」的罕见帧才碰巧能过。
     * 本批新增的单测（描述为空的 v2.3/v2.2 夹具）正是这样把它照出来的。
     */
    private fun parseId3(b: ByteArray): String? {
        val major = b[3].toInt() and 0xff
        if (major !in 2..4) return null
        var pos = 10
        val tagEnd = pos + minOf(syncSafe(b, 6), b.size - 10)
        if (major == 2) {
            while (pos + 6 <= tagEnd) {
                val id = String(b, pos, 3, Charsets.ISO_8859_1); pos += 3
                val fs = ((b[pos].toInt() and 0xff) shl 16) or
                    ((b[pos + 1].toInt() and 0xff) shl 8) or (b[pos + 2].toInt() and 0xff); pos += 3
                if (fs <= 0) break
                if (id == "ULT" && fs > 1 && pos + fs <= b.size) return normalize(decodeUslt(b, pos, fs))
                pos += fs
            }
        } else {
            while (pos + 10 <= tagEnd) {
                val id = String(b, pos, 4, Charsets.ISO_8859_1); pos += 4
                val fs = ((b[pos].toInt() and 0xff) shl 24) or
                    ((b[pos + 1].toInt() and 0xff) shl 16) or
                    ((b[pos + 2].toInt() and 0xff) shl 8) or (b[pos + 3].toInt() and 0xff); pos += 4
                pos += 2 // frame flags
                if (fs <= 0) break
                if (id == "USLT" && fs > 1 && pos + fs <= b.size) return normalize(decodeUslt(b, pos, fs))
                pos += fs
            }
        }
        return null
    }

    /** ID3 的长度字段是「同步安全整数」：每字节只用 7 位。 */
    private fun syncSafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7f) shl 21) or
            ((b[off + 1].toInt() and 0x7f) shl 14) or
            ((b[off + 2].toInt() and 0x7f) shl 7) or
            (b[off + 3].toInt() and 0x7f)

    /** USLT/ULT 正文：编码[1] + 语言[3] + 描述（以 NUL 结束）+ 正文。 */
    private fun decodeUslt(b: ByteArray, start: Int, len: Int): String? {
        if (len < 4) return null
        val encoding = b[start].toInt() and 0xff
        var idx = start + 4 // 跳过编码字节 + 语言[3]
        val end = start + len
        if (encoding == 1 || encoding == 2) {
            while (idx + 1 < end) {
                if (b[idx] == 0.toByte() && b[idx + 1] == 0.toByte()) { idx += 2; break }
                idx += 1
            }
        } else {
            while (idx < end && b[idx] != 0.toByte()) idx += 1
            idx += 1
        }
        if (idx >= end) return null
        return when (encoding) {
            1 -> String(b, idx, end - idx, Charsets.UTF_16LE)
            2 -> String(b, idx, end - idx, Charsets.UTF_16BE)
            3 -> String(b, idx, end - idx, Charsets.UTF_8)
            else -> String(b, idx, end - idx, Charsets.ISO_8859_1)
        }
    }

    // ---------------------------------------------------------------- FLAC

    private fun isFlac(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() &&
            b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()

    private const val FLAC_VORBIS_COMMENT = 4

    /**
     * FLAC：`fLaC` 之后是一串元数据块 —— 每块 1 字节头（最高位=最后一块，低 7 位=块类型）
     * + 3 字节大端长度。歌词在 **VORBIS_COMMENT（类型 4）** 里的 `LYRICS` / `UNSYNCEDLYRICS` 字段。
     *
     * ⚠️ VORBIS_COMMENT 的**字段长度是小端**（与 FLAC 自身的块长度大端相反），
     * 这是最容易写错的地方 —— 单测里专门有对应用例。
     */
    private fun parseFlac(b: ByteArray): String? {
        var pos = 4
        while (pos + 4 <= b.size) {
            val header = b[pos].toInt() and 0xff
            val isLast = (header and 0x80) != 0
            val type = header and 0x7f
            val len = ((b[pos + 1].toInt() and 0xff) shl 16) or
                ((b[pos + 2].toInt() and 0xff) shl 8) or (b[pos + 3].toInt() and 0xff)
            val body = pos + 4
            if (len < 0 || body + len > b.size) return null
            if (type == FLAC_VORBIS_COMMENT) {
                val lyric = readVorbisLyric(b, body, len)
                if (lyric != null) return normalize(lyric)
            }
            if (isLast) return null      // 已经到最后一块还没找到
            pos = body + len
        }
        return null
    }

    /** VORBIS_COMMENT：厂商串（4 字节小端长度 + 内容），随后是条目数（4 字节小端）与各条目。 */
    private fun readVorbisLyric(b: ByteArray, start: Int, len: Int): String? {
        val end = start + len
        if (start + 4 > end) return null
        val vendorLen = readU32Le(b, start)
        if (vendorLen < 0 || start + 4 + vendorLen + 4 > end) return null
        var pos = start + 4 + vendorLen
        val count = readU32Le(b, pos)
        pos += 4
        if (count < 0) return null

        repeat(count) {
            if (pos + 4 > end) return null
            val entryLen = readU32Le(b, pos)
            pos += 4
            if (entryLen < 0 || pos + entryLen > end) return null
            val entry = String(b, pos, entryLen, Charsets.UTF_8)
            pos += entryLen

            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).trim().uppercase()
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS") return entry.substring(eq + 1)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- MP4

    /**
     * MP4 的 atom type 里 `©lyr` 是**单个字节 0xA9** + "lyr"（Latin-1 的 ©，不是 UTF-8 的两字节 C2 A9）。
     * 用字符串比较会把这段永远匹配不上 —— 这是本项目里最容易踩的一个坑。
     */
    private val lyricType = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
    private val moovType = "moov".toByteArray(Charsets.ISO_8859_1)
    private val udtaType = "udta".toByteArray(Charsets.ISO_8859_1)
    private val metaType = "meta".toByteArray(Charsets.ISO_8859_1)
    private val ilstType = "ilst".toByteArray(Charsets.ISO_8859_1)
    private val dataType = "data".toByteArray(Charsets.ISO_8859_1)

    private fun isMp4(b: ByteArray): Boolean =
        b.size >= 12 && matchesAscii(b, 4, "ftyp".toByteArray(Charsets.ISO_8859_1))

    /** 在头部窗口里找 `moov`（faststart / iTunes 转出来的 m4a 通常把 moov 放在前面）。 */
    private fun parseMp4(b: ByteArray): String? = parseMp4Atoms(b, 0, b.size)

    /**
     * 遍历 atom 找 `moov/udta/meta/ilst/©lyr/data`。
     *
     * `start` / `limit` 都是**缓冲区索引**（尾部窗口解析时窗口起点不是 0，
     * 「这个 atom 结束在文件末尾」那条判据已在 [parseMp4Tail] 里先用绝对位置验过了）。
     */
    private fun parseMp4Atoms(b: ByteArray, start: Int, limit: Int): String? {
        var pos = start
        while (pos + 8 <= limit) {
            var size = readU32(b, pos)
            val typeAt = pos + 4
            var headerSize = 8
            if (size == 1L) {                       // 64 位扩展长度
                if (pos + 16 > limit) return null
                size = readU64(b, pos + 8)
                headerSize = 16
            } else if (size == 0L) {                // 延伸到本窗口末尾
                size = (limit - pos).toLong()
            }
            if (size < headerSize) return null
            val body = pos + headerSize
            val end = pos + size
            if (end > limit) return null

            if (matchesAscii(b, typeAt, moovType)) {
                val found = parseMoovBody(b, body, end)
                if (found != null) return normalize(found)
            }
            pos = end.toInt()
        }
        return null
    }

    private fun parseMoovBody(b: ByteArray, start: Int, end: Long): String? =
        walkChildren(b, start, end, udtaType) { udtaStart, udtaEnd ->
            walkChildren(b, udtaStart, udtaEnd, metaType) { metaStart0, metaEnd ->
                // `meta` 是 full box：前 4 字节是 version + flags，子 atom 从 +4 开始
                walkChildren(b, metaStart0 + 4, metaEnd, ilstType) { ilstStart, ilstEnd ->
                    findLyricInIlst(b, ilstStart, ilstEnd)
                }
            }
        }

    /** `ilst` 下每个条目本身就是一个 atom，type 即字段名（`©lyr`），体内含 `data`。 */
    private fun findLyricInIlst(b: ByteArray, start: Int, end: Long): String? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= b.size) {
            val size = readU32(b, pos)
            if (size < 8 || pos + size > end || pos + size > b.size) return null
            val typeAt = pos + 4
            if (matchesAscii(b, typeAt, lyricType)) {
                val text = readDataAtom(b, pos + 8, pos + size)
                if (text != null) return text
            }
            pos += size.toInt()
        }
        return null
    }

    /** `data` atom：4 字节类型（1 = UTF-8 文本）+ 4 字节 locale + 正文。 */
    private fun readDataAtom(b: ByteArray, start: Int, end: Long): String? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= b.size) {
            val size = readU32(b, pos)
            if (size < 8 || pos + size > end || pos + size > b.size) return null
            if (matchesAscii(b, pos + 4, dataType)) {
                val payload = pos + 16                     // 跳过 size+type+type+locale
                if (payload < pos + size) {
                    val text = String(b, payload, (pos + size - payload).toInt(), Charsets.UTF_8)
                    if (text.isNotBlank()) return text
                }
                return null
            }
            pos += size.toInt()
        }
        return null
    }

    /** 在 [start, end) 里找出指定 type 的第一个 atom，交给 [onFound] 解析其内部。 */
    private inline fun walkChildren(
        b: ByteArray,
        start: Int,
        end: Long,
        type: ByteArray,
        onFound: (Int, Long) -> String?
    ): String? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= b.size) {
            val size = readU32(b, pos)
            if (size < 8 || pos + size > end || pos + size > b.size) return null
            if (matchesAscii(b, pos + 4, type)) {
                val found = onFound(pos + 8, pos + size)
                if (found != null) return found
            }
            pos += size.toInt()
        }
        return null
    }

    // ---------------------------------------------------------------- 工具

    private fun matchesAscii(b: ByteArray, at: Int, expect: ByteArray): Boolean {
        if (at < 0 || at + expect.size > b.size) return false
        for (i in expect.indices) if (b[at + i] != expect[i]) return false
        return true
    }

    private fun readU32(b: ByteArray, at: Int): Long {
        if (at + 4 > b.size) return -1
        return ((b[at].toLong() and 0xff) shl 24) or
            ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or
            (b[at + 3].toLong() and 0xff)
    }

    private fun readU64(b: ByteArray, at: Int): Long {
        if (at + 8 > b.size) return -1
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xff)
        return v
    }

    /** VORBIS_COMMENT 的长度字段是**小端**。 */
    private fun readU32Le(b: ByteArray, at: Int): Int {
        if (at + 4 > b.size) return -1
        return ((b[at].toInt() and 0xff)) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or
            ((b[at + 3].toInt() and 0xff) shl 24)
    }
}
