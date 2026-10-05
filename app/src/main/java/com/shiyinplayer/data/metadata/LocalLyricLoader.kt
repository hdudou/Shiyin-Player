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
 * 0. read_embed_lyrics 开启时解析**内嵌歌词**（需求⑦：ID3v2 USLT（MP3）/ FLAC LYRICS / MP4 ©lyr
 *    三种容器，对齐 PC 的 TagLib 能力）—— 字节解析细节见 [EmbeddedLyrics]，那边是纯函数、可单测。
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
    /**
     * 本地歌词 + 它的**来源标识**（需求⑧：两端来源取值统一，防止同步时来源漂移）。
     *
     * 改造前调用方只能拿到文本，于是落库时统一写死 `"local"` ——
     * 既分不清是内嵌还是侧车，也与 PC 端的 `embedded` / `sidecar` 对不上，
     * 同步到 PC 后同一首歌会变成「未知来源」。
     */
    data class LocalLyric(val text: String, val source: String)

    /** 只要文本（不需要来源标识的调用方用这个）。 */
    fun load(song: Song): String? = loadWithSource(song)?.text

    fun loadWithSource(song: Song): LocalLyric? {
        if (song.uri.isBlank()) return null
        return try {
            // P0-4：改读内存快照（SettingsRepository readEmbedLyricsSync），消除主线程 runBlocking 阻塞。
            val embedEnabled = settings.readEmbedLyricsSync()
            val embedded = if (embedEnabled) loadEmbedded(song) else null
            if (embedded != null) return LocalLyric(embedded, SOURCE_EMBEDDED)

            // 侧车与「按专辑/标题猜」读的都是 .lrc 文件，来源同属一类
            val sidecar = loadSidecar(song) ?: loadByAlbumOrTitle(song)
            if (sidecar != null) return LocalLyric(sidecar, SOURCE_SIDECAR)

            null
        } catch (_: Exception) {
            null
        }
    }

    // ===== 内嵌歌词（需求⑦：ID3v2 USLT / FLAC LYRICS / MP4 ©lyr，对齐 PC 的 TagLib）=====

    /**
     * 读文件头部的内嵌歌词；MP4 还要补一次**尾部窗口**。
     *
     * 为什么 MP4 要读两处：`moov`（歌词在 `moov/udta/meta/ilst/©lyr` 下）**可能在文件末尾** ——
     * ffmpeg 不加 `-movflags faststart` 转出来的 m4a 就是这样，头部窗口里根本没有 `moov`。
     * 只取尾部窗口来定位是安全的：判据是「这个 atom 的结束位置正好等于文件长度」，
     * 而 moov 在末尾时必然满足（并且能避开正文里恰好出现的 "moov" 字样）。
     *
     * 限制（如实记下）：远程源（smb / http）没有「只取尾部」的通道，故只对本地文件做这一步；
     * 且尾部窗口上限 [MAX_TAIL] —— 带大图封面的 moov 若超过该窗口会读不到。
     */
    private fun loadEmbedded(song: Song): String? {
        val head = readPrefix(song.uri, MAX_HEADER) ?: return null
        EmbeddedLyrics.parse(head)?.let { return it }

        if (EmbeddedLyrics.sniff(head) != EmbeddedLyrics.Container.MP4) return null
        val uri = song.uri
        if (uri.startsWith("content://") || uri.startsWith("smb:") || uri.startsWith("http")) return null

        return runCatching {
            val f = File(uri)
            if (!f.isFile) return@runCatching null
            val len = f.length()
            if (len <= MAX_HEADER) return@runCatching null   // 整个文件都在头部窗口里读过了
            val window = minOf(MAX_TAIL, len)
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(len - window)
                val buf = ByteArray(window.toInt())
                raf.readFully(buf)
                EmbeddedLyrics.parseMp4Tail(buf, len)
            }
        }.getOrNull()
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
        /**
         * 来源标识：**与 PC 端 `Shiyin.Core.Online.OnlineSourceIds` 同一套取值**（需求⑧）。
         *
         * 落库的 source 只允许是这两个值之一，或某个 provider id
         * （netease / qq / kuwo / migu / kugou / genius / theaudiodb）——
         * 不要写展示名（「网易云音乐」），也不要写 "local" 这类自定义串。
         */
        const val SOURCE_EMBEDDED = "embedded"
        const val SOURCE_SIDECAR = "sidecar"

        private const val MAX_HEADER = 512 * 1024 // ID3 头 / FLAC 元数据块 / moov 都只出现在文件两端
        // MP4 的 moov 可能在文件末尾，尾部窗口；带大图封面的 moov 超过它就读不到（见 loadEmbedded 注释）
        private const val MAX_TAIL = 1024 * 1024L
        private const val MAX_LRC_BYTES = 64 * 1024 // 远程 .lrc 最大下载字节
        // 本地 .lrc 也设大小上限，避免超大歌词文件一次性整载入内存
        private const val MAX_LOCAL_LRC_BYTES = 128 * 1024L
    }
}