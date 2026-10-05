package com.shiyinplayer.data.media

import android.net.Uri
import com.shiyinplayer.data.model.MediaSourceType
import java.io.File
import java.security.MessageDigest

/**
 * 跨设备去重键——**单一来源**，与 PC 端 `Shiyin.Core/Library/DedupKey.cs` 逐字节一致。
 *
 * 规则：`{sourceType}:{sha256(小写规范化路径或 uri)}`；CUE 整轨分轨再追加 `#idx{n}`（n 从 1 起）。
 * 两端对同一音频文件必须生成相同 dedupKey，局域网同步（PC 推送 / 拉取快照）才能命中同一行。
 *
 * 为什么是 hash 而不是原始路径：原始路径会把「同一文件在不同设备的挂载点差异」直接带进键，
 * 且长度不可控（SAF document URI 可达数百字符）、大小写/分隔符变体产生重复行。
 *
 * 注意：键即为路径哈希，**不再携带路径前缀语义**。任何「按 dedupKey 前缀归属某源根」的判定
 * 都必须改用 [com.shiyinplayer.data.local.entity.SongEntity.uri] 前缀（见 LibraryScanner.pruneMissingRoot
 * 与 LibraryRepository.pruneMissingNetworkOrphans）。
 */
object DedupKey {

    /** 本地文件（绝对路径 / SAF document URI / file:// URI）。CUE 子轨传 [cueIndex]（1 起）。 */
    fun forLocalFile(path: String, cueIndex: Int? = null): String =
        compose(MediaSourceType.LOCAL, normalizePath(path), cueIndex)

    /** 远程资源（SMB / WebDAV URI）。CUE 子轨传 [cueIndex]（1 起）。 */
    fun forRemote(sourceType: MediaSourceType, uri: String, cueIndex: Int? = null): String =
        compose(sourceType, normalizeUri(uri), cueIndex)

    /** 按来源类型分派：LOCAL 走文件路径规范化，SMB/WEBDAV 走 URI 规范化。 */
    fun forTrack(sourceType: MediaSourceType, pathOrUri: String, cueIndex: Int? = null): String =
        if (sourceType == MediaSourceType.LOCAL) forLocalFile(pathOrUri, cueIndex)
        else forRemote(sourceType, pathOrUri, cueIndex)

    private fun compose(sourceType: MediaSourceType, identity: String, cueIndex: Int?): String {
        val key = "${sourceType.name}:${sha256Hex(identity)}"
        return if (cueIndex != null && cueIndex > 0) "$key#idx$cueIndex" else key
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 路径规范化（对齐 .NET `Path.GetFullPath` 语义：转绝对 + 折叠 `.`/`..` 与重复分隔符，不解析符号链接）
     * 后统一小写（Windows/macOS 路径大小写不敏感；Android 外部存储同样不敏感）。
     *
     * 输入含 `://` 时按 URI 规范化，避免把 SAF `content://` 当作文件系统路径处理。
     */
    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.contains("://")) return normalizeUri(trimmed)
        // Unix 风格绝对路径（Android 外部存储路径）直接采用：`File.absolutePath` 在非 Unix 宿主机
        // （如 JVM 单测跑在 Windows）会给它补盘符前缀，而它在 Android 上本就是绝对路径、无需转换。
        val absolute = if (trimmed.startsWith('/')) {
            trimmed
        } else {
            runCatching { File(trimmed).absolutePath }.getOrDefault(trimmed)
        }
        return collapseSegments(absolute.replace('\\', '/')).lowercase()
    }

    /** URI 规范化：解析后回写再小写；解析失败退回原文小写（对齐 PC 端 TryCreate 失败分支）。 */
    private fun normalizeUri(uri: String): String {
        val trimmed = uri.trim()
        return runCatching { Uri.parse(trimmed).toString() }
            .getOrDefault(trimmed)
            .lowercase()
    }

    /** 折叠 `.` / `..` / 空段，保留前导 `/` 与盘符语义。 */
    private fun collapseSegments(path: String): String {
        val rooted = path.startsWith('/')
        val parts = mutableListOf<String>()
        for (seg in path.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> Unit
                seg == ".." ->
                    if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.size - 1)
                    else if (!rooted) parts.add(seg)
                else -> parts.add(seg)
            }
        }
        val body = parts.joinToString("/")
        // Windows 盘符（如 C:）由 split 保留为普通段，拼回时无需补前导斜杠
        return if (rooted) "/$body" else body
    }
}
