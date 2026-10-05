package com.shiyinplayer.data.sync

import android.content.Context
import android.util.Log
import com.shiyinplayer.data.sync.model.SyncContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** 一次 `/sync/push-file` 分块接收的结果。 */
data class FileChunkResult(
    /** 服务端**累计**已落盘字节数（PC 据此 seek 续推，契约 §4）。 */
    val received: Long,
    val completed: Boolean,
    val error: String? = null
)

/**
 * 同步文件接收仓（契约 §5 文件接收 / §9 沙盒约束）。
 *
 * **路径语义（最容易踩错的一点）**：PC 发来的 `X-Sync-File-RelPath` **已含 `synced/` 前缀**
 * （形如 `synced/3a7f…c1.mp3`），且相对**应用文件根目录**。
 * 因此：`接收根 = filesDir`，`绝对路径 = filesDir + relPath`。
 * ⚠️ **绝不能再叠加一次 `synced/`**，否则会写成 `files/synced/synced/xxx.mp3`。
 *
 * **沙盒纪律**：所有落盘/删除都先经 [resolve] 做规范化 + 前缀校验，
 * 越权路径（`../` 逃逸、绝对路径、指向沙盒外）一律拒绝——删除是不可逆操作，宁拒勿错。
 *
 * **分块续传**：以 `.part` 临时文件的**实际长度**作为「已落盘进度」，`received` 恒为该累计值
 * （不是本片字节数）。`offset + 本片长度 == total` 时**原子改名**为最终名，
 * 避免半截文件被播放器当成品。
 */
@Singleton
class SyncFileStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 接收根目录：`<filesDir>/`（契约 §7：根目录即应用文件根，`synced/` 由其下的 relPath 决定）。 */
    val receiveRoot: File get() = context.filesDir

    /** `synced/` 子目录（设置页展示用）。 */
    val syncedDir: File get() = File(receiveRoot, SYNC_SUBDIR)

    private val gate = Any()

    // ------------------------------------------------------------ 路径

    /**
     * `relPath` → 沙盒内绝对文件；越权/非法返回 null。
     * 入参是 PC 发送的 **URL 编码** 串（`Uri.EscapeDataString`），此处先解码。
     */
    fun resolve(encodedRelPath: String?): File? {
        val relPath = decode(encodedRelPath) ?: return null
        if (relPath.isBlank() || relPath.contains('\u0000')) return null

        val root = receiveRoot
        val target = runCatching { File(root, relPath).canonicalFile }.getOrNull() ?: return null
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return null

        // canonicalFile 已解析掉 `..` 与符号链接 → 前缀比对即可阻断越权
        if (!target.path.startsWith(rootPath + File.separator)) {
            Log.w(TAG, "拒绝沙盒外路径：$relPath")
            return null
        }

        return target
    }

    /** URL 解码（`+` 在 URL 编码里代表空格，但 relPath 里的 `+` 是字面量，先转义回来）。 */
    private fun decode(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrNull()
    }

    /**
     * 与 PC 端**逐字节一致**的接收相对路径：`synced/<sha256(dedupKey) 前 16 位 hex><扩展名>`。
     * PC 侧 `SyncPushService.PushFilesForBatchAsync` 用同一算法（确定性哈希，跨进程不变）。
     */
    fun relPathFor(dedupKey: String, extension: String): String {
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(dedupKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
        return "$SYNC_SUBDIR/$hex${extension.lowercase()}"
    }

    /** 该曲若已完整落地，返回其绝对文件；否则 null。 */
    fun completedFileFor(dedupKey: String, extension: String): File? {
        val file = resolve(relPathFor(dedupKey, extension)) ?: return null
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    // ------------------------------------------------------------ 接收

    /**
     * 写入一个分块。
     *
     * 语义严格按契约 §4：`received` 是**服务端累计已落盘字节数**。
     * - `offset == 已落盘进度` → 追加写入
     * - `offset < 已落盘进度` → 该片已被收过（PC 重推），**不重复写**，仅回报进度让 PC seek
     * - `offset > 已落盘进度` → 存在缺口，回报进度让 PC 回到缺口处续推（不写，避免文件空洞）
     */
    fun receiveChunk(encodedRelPath: String?, offset: Long, total: Long, bytes: ByteArray): FileChunkResult =
        synchronized(gate) {
            if (total < 0 || total > SyncContract.MAX_RECEIVE_FILE_BYTES) {
                return FileChunkResult(0, false, "too_large_or_negative_total")
            }

            val target = resolve(encodedRelPath)
                ?: return FileChunkResult(0, false, "bad_path")

            target.parentFile?.let { if (!it.exists() && !it.mkdirs()) {
                return FileChunkResult(0, false, "mkdir_failed")
            } }

            val part = File(target.parentFile, target.name + PART_SUFFIX)

            // 已完成（长度吻合）→ 幂等回报，PC 无需再传
            if (target.isFile && target.length() == total) {
                return FileChunkResult(total, true)
            }

            val done = if (part.isFile) part.length() else 0L
            if (offset != done) {
                // 重传已收部分 / 存在缺口：不写，把真实进度回报给 PC（它会 seek 后重推）
                Log.i(TAG, "分块 offset=$offset 与已落盘进度 $done 不一致，回报进度等待 PC 续推")
                return FileChunkResult(done, false)
            }

            val written = runCatching {
                java.io.FileOutputStream(part, true).use { it.write(bytes) }
                bytes.size.toLong()
            }.getOrElse { e ->
                Log.w(TAG, "写入分块失败：${e.message}", e)
                return FileChunkResult(done, false, "write_failed:${e.message}")
            }

            val received = done + written
            if (received >= total) {
                // 原子改名：半截文件绝不暴露为成品名
                if (target.exists() && !target.delete()) {
                    return FileChunkResult(received, false, "finalize_delete_failed")
                }
                if (!part.renameTo(target)) {
                    return FileChunkResult(received, false, "finalize_rename_failed")
                }
                Log.i(TAG, "文件接收完成：${target.absolutePath}（$total 字节）")
                return FileChunkResult(total, true)
            }

            return FileChunkResult(received, false)
        }

    // ------------------------------------------------------------ 删除

    /**
     * 删除该曲在**接收文件夹内**的物理文件（契约 §5 `deleteFile`）。
     *
     * 只删由本模块写入的文件（即按 [relPathFor] 算出的路径），且必须通过沙盒前缀校验；
     * 不碰 .part 之外的任何其它目录与文件。返回是否真的删掉了某个文件。
     */
    fun deleteByDedupKey(dedupKey: String, extension: String): Boolean {
        val file = resolve(relPathFor(dedupKey, extension)) ?: return false
        val part = File(file.parentFile, file.name + PART_SUFFIX)

        var deleted = false
        synchronized(gate) {
            if (part.isFile && part.delete()) deleted = true
            if (file.isFile && file.delete()) deleted = true
        }

        if (deleted) {
            Log.i(TAG, "已删除接收文件夹内文件：${file.absolutePath}")
        }
        return deleted
    }

    private companion object {
        const val TAG = "SyncFileStore"
        const val SYNC_SUBDIR = "synced"
        const val PART_SUFFIX = ".part"
    }
}
