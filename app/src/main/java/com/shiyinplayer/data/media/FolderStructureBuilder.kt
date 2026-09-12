package com.shiyinplayer.data.media

import android.net.Uri
import com.shiyinplayer.data.local.dao.FolderEntryDao
import com.shiyinplayer.data.local.dao.MusicSourceDao
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.local.entity.FolderEntryEntity
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件夹 tab 的目录树预构建器：在【曲目扫描完成】后，把当前曲库歌曲按「源根路径前缀」归源，
 * 生成目录树节点写入 folder_entry 表。之后文件夹 tab 直接按 (sourceId, parentPath) 查询即时加载，
 * 不再每次打开时遍历全曲库。
 *
 * - 源根从各 MusicSource.configJson 读取（local folderPath/treeUri、网络 url），SAF tree 转 document 前缀。
 * - 未归入任何源的歌曲置于「其他来源」(sourceId = -1)。
 * - 目录节点 songCount = 该目录（含子文件夹）递归歌曲数；空目录自然不出现。
 * - CUE 分轨：每首子轨各占一个文件节点（展示曲目名，可单独选择），不展示分轨前的整轨文件信息。
 */
@Singleton
class FolderStructureBuilder @Inject constructor(
    private val songDao: SongDao,
    private val musicSourceDao: MusicSourceDao,
    private val folderEntryDao: FolderEntryDao
) {

    /** 目录节点临时聚合（parent/name/count，folderPath 即 map key）。 */
    private data class DirAcc(val parent: String, val name: String, var count: Int)

    /** 文件节点临时聚合：展示分轨曲目名；CUE 多子轨各占一行记录。 */
    private data class FileAcc(val parent: String, val folderPath: String, val name: String, val songId: Long)

    /** 重建全部来源的目录树并整体替换 folder_entry。须在扫描落库之后调用。 */
    suspend fun rebuild() {
        val sources = musicSourceDao.observeAll().first()
        val roots = sources.associate { it.id to normalizeRoot(it.configJson) }

        // sourceId -> folderPath -> DirAcc
        val dirs = mutableMapOf<Long, LinkedHashMap<String, DirAcc>>()
        // sourceId -> 去重键 -> FileAcc（普通曲目去重键=路径；CUE 子轨去重键=dedupKey，同整轨每轨一行）
        val files = mutableMapOf<Long, LinkedHashMap<String, FileAcc>>()

        // 分页拉取歌曲，避免一次消费全表 Flow 触发 CursorWindow 溢出
        var offset = 0
        var total = 0
        while (true) {
            val page = songDao.getAllPaged(offset, REBUILD_PAGE_SIZE)
            for (song in page) {
                total++
                val (srcId, segs) = assign(song.uri, song.dedupKey, roots)
                if (segs.isEmpty()) continue // 恰为源根本身，非文件
                for (i in 1 until segs.size) {
                    val path = segs.take(i).joinToString("/")
                    val parent = if (i == 1) "" else segs.take(i - 1).joinToString("/")
                    val map = dirs.getOrPut(srcId) { LinkedHashMap() }
                    map.getOrPut(path) { DirAcc(parent, segs[i - 1], 0) }.count++
                }
                val folderPath = segs.joinToString("/")
                val isCue = !song.cueId.isNullOrBlank()
                val fileKey = if (isCue) song.dedupKey.ifBlank { folderPath } else folderPath
                files.getOrPut(srcId) { LinkedHashMap() }.putIfAbsent(
                    fileKey,
                    FileAcc(
                        parent = folderPath.substringBeforeLast('/', ""),
                        folderPath = folderPath,
                        name = song.title.ifBlank { folderPath.substringAfterLast('/') },
                        songId = song.id
                    )
                )
            }
            offset += page.size
            if (page.size < REBUILD_PAGE_SIZE) break
        }
        if (total == 0) {
            folderEntryDao.clearAll()
            return
        }

        val entries = mutableListOf<FolderEntryEntity>()
        dirs.forEach { (srcId, map) ->
            map.forEach { (path, d) ->
                entries += FolderEntryEntity(
                    sourceId = srcId,
                    parentPath = d.parent,
                    name = d.name,
                    folderPath = path,
                    isDir = true,
                    songCount = d.count
                )
            }
        }
        files.forEach { (srcId, map) ->
            map.forEach { (_, acc) ->
                entries += FolderEntryEntity(
                    sourceId = srcId,
                    parentPath = acc.parent,
                    name = acc.name,
                    folderPath = acc.folderPath,
                    isDir = false,
                    songCount = 1,
                    songId = acc.songId
                )
            }
        }

        folderEntryDao.replaceAll(entries)
    }

    /** 把一首歌归到源 id，并算出相对该源的路径段。 */
    private fun assign(uri: String, dedupKey: String, roots: Map<Long, String?>): Pair<Long, List<String>> {
        // CUE 子轨 dedupKey 形如 "uri#idx"，目录归属按文件基址 uri 计算
        val base = dedupKey.substringBefore('#')
        val u = Uri.decode(base.trimEnd('/'))
        for ((id, r) in roots) {
            if (r.isNullOrBlank()) continue
            val segs = when {
                u == r -> emptyList()
                u.startsWith("$r/") -> u.removePrefix("$r/").split('/').filter { it.isNotBlank() }
                u.startsWith(r) -> u.removePrefix(r).split('/').filter { it.isNotBlank() }
                else -> null
            }
            if (segs != null) return id to segs
        }
        return OTHER to u.split('/').filter { it.isNotBlank() }
    }

    /** 从 configJson 提取源根并归一化（URL 解码；SAF tree 转 document 前缀）。 */
    private fun normalizeRoot(configJson: String): String? {
        val raw = runCatching {
            val j = JSONObject(configJson)
            j.optString("folderPath").ifBlank { j.optString("treeUri") }.ifBlank { j.optString("url") }
        }.getOrNull()?.ifBlank { null } ?: return null
        val r = Uri.decode(raw).trimEnd('/')
        return if (r.contains("/tree/")) {
            r.substringBefore("/tree/") + "/document/" + r.substringAfter("/tree/")
        } else r
    }

    private companion object {
        const val OTHER = -1L
        /** 目录树重建时逐页拉取歌曲的页大小。 */
        const val REBUILD_PAGE_SIZE = 500
    }
}