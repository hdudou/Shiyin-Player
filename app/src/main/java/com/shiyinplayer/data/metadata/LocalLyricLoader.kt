package com.shiyinplayer.data.metadata

import android.content.Context
import android.net.Uri
import com.shiyinplayer.data.media.SmbBrowser
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.remote.webdav.WebDavBrowser
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地歌词加载（文件内嵌歌词优先）：
 * 0. read_embed_lyrics 开启时，解析音频文件开头的 ID3v2 USLT/ULT 帧（MP3 等）取内嵌歌词。
 * 1. 音频文件同目录、同文件名的 `.lrc` 侧车文件（覆盖 file / content URI / smb / webdav 路径）。
 * 2. 回退到 `{专辑名}.lrc` 与 `{标题}.lrc`。
 * 返回的是相对音频文件起点的原始 LRC 文本；CUE 分轨的偏移在 [LrcParser] 侧统一按 clipStartMs 校正。
 *
 * 注（2026-08-17）：远程（smb/http(s)）侧车歌词与内嵌歌词现经 SmbBrowser/WebDavBrowser 下载，
 * 失败回退 null 不影响播放。详见 PROJECT_STATUS §五 P2 #11。
 */
@Singleton
class LocalLyricLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val smbBrowser: SmbBrowser,
    private val webDavBrowser: WebDavBrowser
) {
    fun load(song: Song): String? {
        if (song.uri.isBlank()) return null
        return try {
            // P0-4：改读内存快照（SettingsRepository readEmbedLyricsSync），消除主线程 runBlocking 阻塞。
            val embedEnabled = settings.readEmbedLyricsSync()
            val embedded = if (embedEnabled) loadEmbedded(song) else null
            embedded ?: loadSidecar(song) ?: loadByAlbumOrTitle(song)
        } catch (_: Exception) {
            null
        }
    }

    // ===== 内嵌歌词（ID3v2 USLT/ULT 帧） =====

    private fun loadEmbedded(song: Song): String? {
        val bytes = readPrefix(song.uri, MAX_HEADER) ?: return null
        return parseId3Uslt(bytes)
    }

    private fun readPrefix(uriString: String, max: Int): ByteArray? {
        return try {
            val u = Uri.parse(uriString)
            when (u.scheme) {
                "content" -> context.contentResolver.openInputStream(u)?.use { readN(it, max) }
                "smb" -> smbBrowser.readPrefix(uriString, max)
                "http", "https" -> webDavBrowser.readPrefix(uriString, max)
                else -> {
                    val f = File(uriString)
                    if (f.exists()) f.inputStream().use { readN(it, max) } else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readN(stream: InputStream, max: Int): ByteArray {
        val buffer = ByteArray(max)
        var total = 0
        while (total < max) {
            val n = stream.read(buffer, total, max - total)
            if (n < 0) break
            total += n
        }
        return buffer.copyOf(total)
    }

    private fun parseId3Uslt(b: ByteArray): String? {
        if (b.size < 10) return null
        if (b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return null
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
                if (id == "ULT" && fs > 1 && pos + fs <= b.size) return decodeUslt(b, pos + 1, fs - 1)
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
                if (id == "USLT" && fs > 1 && pos + fs <= b.size) return decodeUslt(b, pos + 1, fs - 1)
                pos += fs
            }
        }
        return null
    }

    private fun syncSafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7f) shl 21) or
            ((b[off + 1].toInt() and 0x7f) shl 14) or
            ((b[off + 2].toInt() and 0x7f) shl 7) or
            (b[off + 3].toInt() and 0x7f)

    /** 取语言[3] + 描述 + 正文，返回 LRC 风格文本（过滤空/非时间轴文本）。 */
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
        val text = when (encoding) {
            1 -> String(b, idx, end - idx, Charsets.UTF_16LE)
            2 -> String(b, idx, end - idx, Charsets.UTF_16BE)
            3 -> String(b, idx, end - idx, Charsets.UTF_8)
            else -> String(b, idx, end - idx, Charsets.ISO_8859_1)
        }
        return text.trim().takeIf { it.startsWith("[") && it.contains("]") }
    }

    // ===== 侧车 / 专辑 / 标题歌词 =====

    private fun loadSidecar(song: Song): String? {
        // content:// 树状 document URI：先转成物理路径再找兄弟 .lrc。直接 openInputStream 一个
        // 拼接出的"子 document" URI 时，若该 .lrc 不存在，ExternalStorageProvider 解析子路径会抛
        // 未捕获的 StringIndexOutOfBoundsException，每次都是 binder 远程异常，累积触发系统
        // "Too many transaction errors, throttling freezer binder callback" 把本进程冻结，
        // 导致后续 SAF 音频读取停滞（表现：用久后全部歌曲卡 BUFFERING、重启即恢复）。
        if (song.uri.startsWith("content://")) {
            val noExtPath = contentUriNoExtPath(song.uri) ?: return null
            return readLocalLrc(File("$noExtPath.lrc"))
        }
        // file / smb / webdav 路径：取目录 + 去扩展名
        val slash = song.uri.lastIndexOf('/')
        if (slash < 0) return null
        val dir = song.uri.substring(0, slash + 1)
        val base = song.uri.substring(slash + 1).substringBeforeLast('.')
        if (base.isEmpty()) return null
        return readUri("$dir$base.lrc")
    }

    private fun loadByAlbumOrTitle(song: Song): String? {
        val candidates = listOfNotNull(
            song.albumName?.takeIf { it.isNotBlank() },
            song.title.takeIf { it.isNotBlank() }
        )
        if (candidates.isEmpty()) return null
        return candidates.firstNotNullOfOrNull { name ->
            if (song.uri.startsWith("content://")) {
                val dir = contentUriDir(song.uri)
                if (dir != null) readLocalLrc(File("$dir/$name.lrc")) else null
            } else {
                val dir = if (song.uri.contains('/')) song.uri.substring(0, song.uri.lastIndexOf('/') + 1) else ""
                readUri("$dir$name.lrc")
            }
        }
    }

    /**
     * content:// externalstorage 树状 document URI → 同目录去扩展名的**物理路径**（.lrc 待补齐），
     * 解析失败返回 null（不发起任何 ContentProvider 调用，避免缺失文件触发 provider 崩溃）。
     */
    private fun contentUriNoExtPath(uriString: String): String? = runCatching {
        val uri = Uri.parse(uriString)
        val docId = uriAsDocumentId(uri) ?: return@runCatching null
        val ci = docId.indexOf(':')
        if (ci <= 0) return@runCatching null
        val pathPart = docId.substring(ci + 1)
        val slash = pathPart.lastIndexOf('/')
        val base = if (slash >= 0) {
            pathPart.substring(0, slash + 1) + pathPart.substring(slash + 1).substringBeforeLast('.')
        } else pathPart.substringBeforeLast('.')
        extDocToPath(uri.authority, docId.substring(0, ci + 1) + base)
    }.getOrNull()

    /** content:// externalstorage 树状 document URI → 所在目录的物理路径（无尾斜杠），失败返回 null。 */
    private fun contentUriDir(uriString: String): String? = runCatching {
        val uri = Uri.parse(uriString)
        val docId = uriAsDocumentId(uri) ?: return@runCatching null
        val ci = docId.indexOf(':')
        if (ci <= 0) return@runCatching null
        val pathPart = docId.substring(ci + 1)
        val slash = pathPart.lastIndexOf('/')
        if (slash < 0) return@runCatching null
        extDocToPath(uri.authority, docId.substring(0, ci + 1) + pathPart.substring(0, slash))
    }.getOrNull()

    /** 从 /document/{docId} 解析 documentId（纯字符串，不解码断言），非 document URI 返回 null。 */
    private fun uriAsDocumentId(uri: Uri): String? {
        val path = uri.path ?: return null
        val marker = "/document/"
        val idx = path.lastIndexOf(marker)
        if (idx < 0) return null
        return Uri.decode(path.substring(idx + marker.length))
    }

    /** externalstorage document path（如 "primary:中文单曲合集/..."）→ 真实文件路径；其他 providers 返回 null。 */
    private fun extDocToPath(authority: String?, docPath: String): String? {
        if (authority != "com.android.externalstorage.documents") return null
        val i = docPath.indexOf(':')
        if (i <= 0) return null
        val vol = docPath.substring(0, i)
        val rel = docPath.substring(i + 1)
        // secondary volumes 映射 /storage/<vol>/<rel>
        return if (vol == "primary") "/storage/emulated/0/$rel" else "/storage/$vol/$rel"
    }

    /** 本地文件读取（仅当存在、可读且不超上限），无则 null——不触碰 ContentProvider。 */
    private fun readLocalLrc(f: File): String? =
        if (f.isFile && f.canRead() && f.length() <= MAX_LOCAL_LRC_BYTES)
            runCatching {
                val bytes = f.readBytes()
                decodeTextAndStripBom(bytes)
            }.getOrNull()
        else null

    private fun readUri(uriString: String): String? {
        if (uriString.isBlank()) return null
        return try {
            val u = Uri.parse(uriString)
            val bytes = when (u.scheme) {
                "content" -> context.contentResolver.openInputStream(u)?.use { it.readBytes() }
                "smb" -> smbBrowser.readPrefix(uriString, MAX_LRC_BYTES)
                "http", "https" -> webDavBrowser.readPrefix(uriString, MAX_LRC_BYTES)
                else -> {
                    val f = File(uriString)
                    if (f.exists() && f.length() <= MAX_LOCAL_LRC_BYTES) f.readBytes() else null
                }
            }
            if (bytes == null || bytes.isEmpty()) null else decodeTextAndStripBom(bytes)
        } catch (_: Exception) {
            null
        }
    }

    /** 按检测到的编码解码字节，并去除 UTF-8/UTF-16 的 BOM 前缀（若有），避免首行解析异常。 */
    private fun decodeTextAndStripBom(bytes: ByteArray): String {
        val cs = detectCharset(bytes)
        val raw = String(bytes, cs)
        // BOM 字符 U+FEFF 不是 Unicode 空白，trim() 不会去除；显式剥掉
        return if (raw.isNotEmpty() && raw[0] == '\uFEFF') raw.substring(1) else raw
    }

    /**
     * DB：根据文件头猜测编码。优先级：UTF-8 BOM → UTF-16 LE/BE BOM → GBK（中文资源常见）→ 兜底 UTF-8。
     * 仅用于 .lrc 侧车文件（文本文件）；内嵌 USLT 帧的编码由 ID3 头字节指定，不走此函数。
     */
    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return Charsets.UTF_8
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        // GBK 常见于中文老歌资源；若字节序列含大量 ASCII 可打印 + 偶发高位字节且 UTF-8 校验失败，回退 GBK。
        return if (looksLikeGbk(bytes)) {
            runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.UTF_8)
        } else {
            Charsets.UTF_8
        }
    }

    /** 简易 GBK 启发式：统计高位字节（>0x7F）的比例，且 UTF-8 多字节序列不合法时倾向 GBK。 */
    private fun looksLikeGbk(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var highCount = 0
        var i = 0
        var invalidUtf8 = false
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xff
            if (b < 0x80) {
                i++
                continue
            }
            highCount++
            // 尝试按 UTF-8 序列长度验证
            val n = when {
                b and 0xE0 == 0xC0 -> 1
                b and 0xF0 == 0xE0 -> 2
                b and 0xF8 == 0xF0 -> 3
                else -> { invalidUtf8 = true; break }
            }
            if (i + n >= bytes.size) { invalidUtf8 = true; break }
            for (k in 1..n) {
                val cb = bytes[i + k].toInt() and 0xff
                if (cb and 0xC0 != 0x80) { invalidUtf8 = true; break }
            }
            if (invalidUtf8) break
            i += 1 + n
        }
        // 高位字节占比 ≥ 10% 且 UTF-8 序列非法 → 大概率 GBK
        return invalidUtf8 && highCount * 10 >= bytes.size
    }

    companion object {
        private const val MAX_HEADER = 512 * 1024 // ID3 头只出现在文件开头
        private const val MAX_LRC_BYTES = 64 * 1024 // 远程 .lrc 最大下载字节
        // 本地 .lrc 也设大小上限，避免超大歌词文件一次性整载入内存
        private const val MAX_LOCAL_LRC_BYTES = 128 * 1024L
    }
}