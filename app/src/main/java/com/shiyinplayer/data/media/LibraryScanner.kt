package com.shiyinplayer.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import java.io.File
import java.io.FileOutputStream
import com.shiyinplayer.data.local.dao.AlbumDao
import com.shiyinplayer.data.local.dao.ArtistDao
import com.shiyinplayer.data.local.dao.FolderAttachmentDao
import com.shiyinplayer.data.local.dao.PlaylistItemDao
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.util.SongSearchKey
import com.shiyinplayer.data.local.entity.AlbumEntity
import com.shiyinplayer.data.local.entity.ArtistEntity
import com.shiyinplayer.data.local.entity.FolderAttachmentEntity
import com.shiyinplayer.data.local.entity.SongEntity
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.MusicSource
import com.shiyinplayer.data.remote.webdav.WebDavBrowser
import com.shiyinplayer.data.repository.ScanResult
import com.shiyinplayer.ui.settings.SettingsRepository
import com.shiyinplayer.util.Constants
import com.shiyinplayer.util.DispatcherProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-08-24：扫描模式。
 * - [NEW_ONLY]：纯增量——已入库曲目（dedupKey 命中）经 IGNORE 跳过、保留现有内容，只新增。
 * - [FULL_UPDATE]：全量更新——已入库曲目按 id 覆盖扫描得到的元数据内容（保留主键与
 *   rating/playCount/lastPlayedMs/lyricOffsetMs/dateAdded 等用户数据），并新增未见曲目。
 */
enum class ScanMode { NEW_ONLY, FULL_UPDATE }

/**
 * 多源曲库扫描器（架构 §1.2.9）。
 *
 * - LOCAL：SAF DocumentTree 递归，MediaMetadataRetriever 提取元数据 → SongEntity。
 * - SMB / WEBDAV：复用 SmbBrowser / WebDavBrowser 递归扫描，经 ZeroTier mapToLocal 映射后读取。
 * - CUE 分轨（T15）：识别目录内同名 .cue，由 CueParser 拆轨（此处预留接入点）。
 *
 * 多源去重键：(sourceType, uri/path)。合并后重建 albums / artists 聚合表。
 */
@Singleton
class LibraryScanner @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val db: com.shiyinplayer.data.local.AppDatabase,
    private val songDao: SongDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistItemDao: PlaylistItemDao,
    private val dispatcher: DispatcherProvider,
    private val smbBrowser: SmbBrowser,
    private val webDavBrowser: WebDavBrowser,
    private val settings: SettingsRepository,
    private val folderStructure: FolderStructureBuilder,
    private val folderAttachmentDao: FolderAttachmentDao
) {
    companion object {
        private const val TAG = "LibraryScanner"
        private const val MAX_ARTWORK_BYTES = 1 * 1024 * 1024  // 1MB，超此降采样
        private const val MAX_ARTWORK_EDGE_PX = 1024           // 边长上限，超此降采样
        // P1-8：远程时长探测前缀从 512KB 收窄到 128KB——各格式头部/时长元数据均在前 128KB
        // （MP3 ID3v2、FLAC STREAMINFO、OGG/Opus 头、WAV/AC3 头等），大曲库扫描网络开销降低 4 倍。
        private const val PROBE_PREFIX_BYTES = 128 * 1024
        private const val PROBE_TIMEOUT_MS = 5_000L            // 单文件探测超时
        /** P1-6：hide_short_clips 隐藏阈值（<30s 视为短片段）。 */
        private const val SHORT_CLIP_THRESHOLD_MS = 30_000L
        /** 2026-08-19：增量保存周期——每 30s 把已扫描完成的歌曲批量落库，不等整源扫描完毕。 */
        private const val SAVE_INTERVAL_MS = 30_000L
        /** 2026-08-19：并发扫描的来源数上限（避免同时拉爆网络/IO）。 */
        private const val MAX_CONCURRENT_SOURCES = 2
        /** 聚合重建时逐页拉取歌曲的页大小（避免一次性消费全表触发 CursorWindow 溢出）。 */
        private const val AGGREGATE_PAGE_SIZE = 500
        /** 可被识别为「专辑封面图」附件的扩展名。 */
        private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        /** 可被识别为「专辑说明文本」附件的扩展名。 */
        private val TEXT_EXTENSIONS = setOf("txt")
    }

    /**
     * 扫描来源列表。
     *
     * @param sources 全部已配置来源（内部按 [singleSourceId] 决定扫描范围）
     * @param singleSourceId 2026-08-19 需求：非空 = 只同步该来源（网络源行「同步」按钮）。
     *                       单源模式：①不按 enabled 过滤目标源（用户手动点同步即应执行）
     *                       ②跳过 pruneMissingBySource（避免按 type 清理时误删同类型其它源，
     *                       如 WebDAVLAN 与 WebDAV-ZT 同为 WEBDAV）——失效清理只留给全量扫描。
     */
    suspend fun scan(
        sources: List<MusicSource>,
        singleSourceId: Long? = null,
        mode: ScanMode = ScanMode.NEW_ONLY,
        onProgress: ((added: Int, updated: Int) -> Unit)? = null
    ): ScanResult =
        withContext(dispatcher.io) {
        val enabled = if (singleSourceId != null) {
            sources.filter { it.id == singleSourceId }
        } else {
            sources.filter { it.enabled }
        }
        // P1-6：扫描类设置接线（scan_hidden / scan_extensions / hide_short_clips 实际生效）
        val scanHidden = settings.scanHidden.first()
        val extWhitelist = settings.scanExtensions.first()
        val hideShort = settings.hideShortClips.first()
        // 2026-08-19：增量保存器（每 30s 落库已扫描部分，不等整源完成）；onProgress 随每次落库上报实时计数
        val saver = IncrementalSaver(songDao, SAVE_INTERVAL_MS, mode, onProgress)
        // 并发收集的 errors / presentKeys（来源级并发安全）。
        // 2026-08-27 需求：present keys 按「来源 id」而非「类型」聚合，使网络源失效清理能按源根前缀精确执行，
        // 避免 P1-4 按 type 清理时误删同类型其它源（如 WebDAVLAN 与 WebDAV-ZT 同 type）。
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val presentKeysBySource = ConcurrentHashMap<Long, MutableSet<String>>()
        // 2026-08-27：目录附件（封面图/说明 txt）按来源聚合；扫描成功才重建该源附件表
        val attachmentsBySource = ConcurrentHashMap<Long, MutableList<FolderAttachmentEntity>>()
        val scannedOk = ConcurrentHashMap<Long, Boolean>()
        // 2026-08-19：来源级并发上限 2（网络/IO 限流）
        val semaphore = Semaphore(MAX_CONCURRENT_SOURCES)

        // 周期保存协程：每 SAVE_INTERVAL_MS flush 一次队列中已扫描完成的歌曲
        val saverJob = launch { saver.run() }
        try {
            val jobs = enabled.map { src ->
                launch {
                    semaphore.withPermit {
                        try {
                            Log.i(TAG, "开始扫描来源: ${src.name} (${src.type})")
                            // 本来源已确认存在的 dedupKey（仅来源成功时计入清理；hideShort 过滤后）
                            val pendingKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
                            // 本来源收集到的目录附件
                            val atts = java.util.Collections.synchronizedList(mutableListOf<FolderAttachmentEntity>())
                            // emit 仅做内存投递（队列 add / 集合 add），无需挂起；非 suspend 也避开
                            // Kotlin 编译器"局部 suspend fun 调用 suspend lambda"的代码生成 bug
                            val emit: (SongEntity) -> Unit = { song ->
                                val keep = !hideShort ||
                                    song.durationMs <= 0 || song.durationMs >= SHORT_CLIP_THRESHOLD_MS
                                if (keep) {
                                    saver.offer(song)
                                    pendingKeys.add(song.dedupKey)
                                }
                            }
                            val ok = when (src.type) {
                                MediaSourceType.LOCAL -> scanLocal(src, scanHidden, extWhitelist, emit, { atts.add(it) })
                                MediaSourceType.SMB -> scanSmb(src, scanHidden, extWhitelist, emit, { atts.add(it) })
                                MediaSourceType.WEBDAV -> scanWebDav(src, scanHidden, extWhitelist, emit, { atts.add(it) })
                            }
                            Log.i(TAG, "来源完成: ${src.name} (attachments=${atts.size})")
                            // 只有"成功扫描"的来源参与失效清理（配置缺失/根失败不清库）。
                            // 每个源独立聚合（按 id），网络源失效清理按源根前缀精确删除，互不干扰。
                            if (ok && pendingKeys.isNotEmpty()) {
                                presentKeysBySource.getOrPut(src.id) { ConcurrentHashMap.newKeySet() }
                                    .addAll(pendingKeys)
                            }
                            // 仅成功扫描的来源参与附件表重建（失败来源保留旧附件，不清）
                            if (ok) {
                                scannedOk[src.id] = true
                                attachmentsBySource[src.id] = atts
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e   // P2-11：扫描协程被取消时不吞掉，避免继续写库
                        } catch (e: Exception) {
                            Log.w(TAG, "来源「${src.name}」扫描失败：${e.message}")
                            errors += "来源「${src.name}」扫描失败：${e.message}"
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
        } finally {
            saver.stop()
            // === K：聚合/目录树兜底重建。无论扫描正常完成还是中途异常/取消，都按当前 songs 表
            // 尽力 flush 并重建聚合与目录树，避免「歌曲已入库但聚合/目录树缺失」的不一致残留。
            // 各段独立容错：某一步失败不阻断后续步骤。
            runCatching { saver.flushNow() }
            // 2026-08-27 需求：源中被移动/删除的条目 → 本地库同步。
            // 「移动」表现为路径变化（dedupKey 变）→ 旧记录以「删」处理、新路径以「增」入库；
            // 「删除」表现为源中已无该文件 → 由失效清理删除本地对应记录（含歌单引用）。
            // 仅对「本次成功扫描且 present 非空」的源执行（按源根前缀精确清理，
            // 配置缺失/根失败的源不清理，避免源临时不可用而误删库中曲目）。
            runCatching {
                for ((srcId, keys) in presentKeysBySource) {
                    val src = enabled.firstOrNull { it.id == srcId } ?: continue
                    val prefix = remoteRootPrefix(src) ?: continue
                    pruneMissingRoot(src.type, prefix, keys)
                }
            }
            // 聚合表始终从完整歌曲表重建（保证增量扫描时计数/年份/封面正确，并清理已删除曲目残留）
            runCatching { rebuildAlbumsArtists() }
            // 文件夹 tab 目录树：扫描落库后一次性预生成（folder_entry 表），tab 打开时直接查库。
            runCatching { folderStructure.rebuild() }
            // 目录附件表：仅重建本次扫描成功的来源（失败/未扫来源保留旧数据，避免误删附件）
            runCatching {
                for ((srcId, ok) in scannedOk) {
                    if (ok) {
                        folderAttachmentDao.deleteBySource(srcId)
                        attachmentsBySource[srcId].orEmpty().chunked(200)
                            .forEach { folderAttachmentDao.insertAll(it) }
                    }
                }
            }
        }

        // 计数区分模式：NEW_ONLY 仅 added；FULL_UPDATE 分别统计 added(新增) 与 updated(覆盖更新)。
        ScanResult(added = saver.addedCount(), updated = saver.updatedCount(), errors = errors.toList())
    }

    /**
     * 2026-08-19：增量保存器。来源扫描协程通过 [offer] 投递已扫描完成的歌曲，
     * 后台周期协程每 [intervalMs] 批量 upsert 一次；[flushNow] 供扫描结束时最后一次落库。
     * 队列为 ConcurrentLinkedQueue，来源级并发安全。
     */
    private class IncrementalSaver(
        private val songDao: SongDao,
        private val intervalMs: Long,
        private val mode: ScanMode,
        private val onProgress: ((added: Int, updated: Int) -> Unit)? = null
    ) {
        private val queue = ConcurrentLinkedQueue<SongEntity>()
        @Volatile private var running = true
        @Volatile private var savedCount = 0
        @Volatile private var addedCount = 0
        @Volatile private var updatedCount = 0

        fun offer(song: SongEntity) { queue.add(song) }
        fun stop() { running = false }

        /** 当前已新增（入库）数量。 */
        fun addedCount(): Int = addedCount

        /** 当前已覆盖更新（按 id 重写元数据）数量。 */
        fun updatedCount(): Int = updatedCount

        /** 周期 flush 循环（由 saverJob 驱动）。 */
        suspend fun run() {
            report()
            while (running) {
                delay(intervalMs)
                flush()
            }
        }

        /** 立即落库一次（扫描结束时调用）。 */
        suspend fun flushNow() { flush() }

        private suspend fun flush() {
            val batch = mutableListOf<SongEntity>()
            while (true) { queue.poll()?.let { batch += it } ?: break }
            if (batch.isEmpty()) return
            when (mode) {
                // 增量：对已存在 dedupKey 行做元数据刷新（远端标题/歌手/专辑/时长/流派等更正
                // 能落到库内），且保留用户数据（playCount/rating/歌词偏移等）；未见曲目正常插入。
                ScanMode.NEW_ONLY -> {
                    val (up, ins) = persistFullUpdate(batch)
                    updatedCount += up
                    addedCount += ins
                }
                // 全量更新：已入库按 id 覆盖扫描元数据内容，保留主键与用户数据；未入库的插入
                ScanMode.FULL_UPDATE -> {
                    val (up, ins) = persistFullUpdate(batch)
                    updatedCount += up
                    addedCount += ins
                }
            }
            savedCount += batch.size
            report()
            Log.i(TAG, "增量保存 ${batch.size} 首（累计 $savedCount，mode=$mode，新增=$addedCount 更新=$updatedCount）")
        }

        /** 向外部实时上报当前计数（新增 / 更新），供 UI 在扫描状态中显示。 */
        private fun report() {
            onProgress?.invoke(addedCount, updatedCount)
        }

        /**
         * 2026-08-24 全量更新：按 dedupKey 定位库中已存在行，将扫描结果覆盖到这些行
         * （用 [SongEntity.copy] 保留 id 与用户数据 rating/playCount/lastPlayedMs/lyricOffsetMs/dateAdded，
         * 主键不变），未批量覆盖掉歌单/播放统计引用；未见曲目走正常插入。
         * @return (覆盖更新数量, 新增插入数量)
         */
        private suspend fun persistFullUpdate(batch: List<SongEntity>): Pair<Int, Int> {
            val existing = songDao.getByDedupKeys(batch.map { it.dedupKey })
                .associateBy { it.dedupKey }
            val updates = mutableListOf<SongEntity>()
            val inserts = mutableListOf<SongEntity>()
            for (s in batch) {
                val sk = SongSearchKey.of(s.title, s.artistName, s.albumName)
                val old = existing[s.dedupKey]
                if (old != null) {
                    updates += s.copy(
                        id = old.id,
                        playCount = old.playCount,
                        lastPlayedMs = old.lastPlayedMs,
                        rating = old.rating,
                        lyricOffsetMs = old.lyricOffsetMs,
                        dateAdded = old.dateAdded,
                        searchKey = sk
                    )
                } else {
                    inserts += s.copy(searchKey = sk)
                }
            }
            if (updates.isNotEmpty()) songDao.updateAll(updates)
            if (inserts.isNotEmpty()) songDao.upsertAll(inserts)
            return updates.size to inserts.size
        }

        fun totalCount(): Int = savedCount
    }

    /**
     * 需求：删除某网络源中「本次扫描未出现」的旧曲目（文件在源中被移动/删除），并清理其歌单引用。
     * 按源根前缀精确匹配该源全部记录（instr=1），只删不在 presen 中的——不触碰同类型其它源。
     */
    private suspend fun pruneMissingRoot(type: MediaSourceType, prefix: String, presentDedupKeys: Set<String>) {
        val existing = songDao.getIdsAndDedupKeysByPrefix(type.name, prefix)
        val toDelete = existing.filter { it.dedupKey !in presentDedupKeys }.map { it.id }
        if (toDelete.isEmpty()) return
        toDelete.forEach { id ->
            try {
                playlistItemDao.removeAllForSong(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
        toDelete.chunked(500).forEach { chunk ->
            try {
                songDao.deleteByIds(chunk)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
        Log.i(TAG, "网源失效清理：源根 $prefix 删除 ${toDelete.size} 首旧曲目")
    }

    /**
     * 需求：源根前缀（该源的曲目 dedupKey 均以此开头），用于失效清理精确匹配。
     * 全部来源类型都参与：LOCAL（folderPath 绝对路径 / SAF tree→document 前缀）、SMB、WEBDAV。
     * 返回 null = 配置缺失，不参与失效清理（由调用处跳过）。
     */
    private fun remoteRootPrefix(src: MusicSource): String? {
        val j = runCatching { JSONObject(src.configJson) }.getOrNull() ?: return null
        return when (src.type) {
            MediaSourceType.LOCAL -> {
                val folder = j.optString("folderPath").takeIf { it.isNotBlank() }
                if (folder != null) {
                    folder.trimEnd('/').let { "$it/" }
                } else {
                    j.optString("treeUri").takeIf { it.isNotBlank() }?.let { tree ->
                        // SAF tree → document 前缀（与 FolderStructureBuilder.normalizeRoot 一致），如
                        // content://a/tree/XYZ → content://a/document/XYZ
                        val doc = if (tree.contains("/tree/"))
                            tree.substringBefore("/tree/") + "/document/" + tree.substringAfter("/tree/")
                        else tree
                        doc.trimEnd('/').let { "$it/" }
                    }
                }
            }
            MediaSourceType.SMB, MediaSourceType.WEBDAV ->
                j.optString("url").takeIf { it.isNotBlank() }?.trimEnd('/')?.let { "$it/" }
        }
    }

    // ===== 本地 SAF 分支 =====

    private suspend fun scanLocal(
        src: MusicSource,
        scanHidden: Boolean,
        extWhitelist: Set<String>,
        emit: (SongEntity) -> Unit,
        emitAtt: (FolderAttachmentEntity) -> Unit
    ): Boolean {
        // 2026-08-24：本机文件夹源（configJson.folderPath = 绝对路径）与 SAF 目录源分流
        val folderPath = runCatching { JSONObject(src.configJson).optString("folderPath") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        return if (folderPath != null) {
            scanLocalFolder(src, folderPath, scanHidden, extWhitelist, emit, emitAtt)
        } else {
            scanLocalSaf(src, scanHidden, extWhitelist, emit, emitAtt)
        }
    }

    /**
     * L-实时监控重扫前置：检查 LOCAL 源根是否仍可访问，避免权限被撤销/目录被移除时对整个源静默空扫。
     * folderPath 源校验目录存在且可读；SAF（treeUri）源校验 DocumentTree 权限仍有效。
     */
    fun localSourceAccessible(src: MusicSource): Boolean {
        val json = runCatching { JSONObject(src.configJson) }.getOrNull() ?: return false
        val folderPath = json.optString("folderPath").takeIf { it.isNotBlank() }
        if (folderPath != null) {
            // 绝对路径依赖 MANAGE_EXTERNAL_STORAGE：权限被撤销则视为不可访问，避免对权限不足的源静默空扫
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return false
            }
            val f = File(folderPath)
            return f.exists() && f.isDirectory && f.canRead()
        }
        val treeUri = json.optString("treeUri").takeIf { it.isNotBlank() } ?: return false
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) != null }.getOrDefault(false)
    }

    /** 2026-08-24：本机文件夹源——folderPath 为绝对路径，经 MANAGE_EXTERNAL_STORAGE 权限直接读文件系统递归扫描。 */
    private suspend fun scanLocalFolder(
        src: MusicSource,
        folderPath: String,
        scanHidden: Boolean,
        extWhitelist: Set<String>,
        emit: (SongEntity) -> Unit,
        emitAtt: (FolderAttachmentEntity) -> Unit
    ): Boolean {
        // 权限门控：绝对路径依赖 MANAGE_EXTERNAL_STORAGE，未授予时跳过（显式拒绝，勿静默空扫）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Log.w(TAG, "scanLocalFolder: MANAGE_EXTERNAL_STORAGE 未授予，跳过源 ${src.name}")
            return false
        }
        val root = File(folderPath)
        if (!root.exists() || !root.isDirectory || !root.canRead()) return false
        walkFiles(root, scanHidden) { file ->
            if (file.isFile && (scanHidden || !file.name.startsWith('.'))) {
                val ext = file.name.substringAfterLast('.', "").lowercase()
                when {
                    isAudioExt(ext, extWhitelist) -> extractFileMetadata(file)?.let { emit(it) }
                    ext in COVER_EXTENSIONS || ext in TEXT_EXTENSIONS ->
                        emitAtt(
                            FolderAttachmentEntity(
                                sourceId = src.id,
                                parentPath = relDir(file.parentFile.absolutePath, root.absolutePath),
                                name = file.name,
                                kind = attachmentKind(ext),
                                uri = file.absolutePath,
                                size = file.length()
                            )
                        )
                }
            }
        }
        return true
    }

    /** 本机文件夹递归遍历（File 版）。 */
    private fun walkFiles(dir: File, scanHidden: Boolean, onFile: (File) -> Unit) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (scanHidden || !child.name.startsWith('.')) walkFiles(child, scanHidden, onFile)
            } else {
                onFile(child)
            }
        }
    }

    /** 本机文件提取元数据（File 版，供 folderPath 文件夹源复用）。返回 null = 无法读取。 */
    private fun extractFileMetadata(file: File): SongEntity? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            val fileNameBase = file.name.substringBeforeLast('.')
            val (parsedArtist, parsedTitle) = parseFileNameTitle(fileNameBase)
            val title = cleanTitleKeep(metaTitle) ?: cleanTitleKeep(parsedTitle) ?: cleanTitleKeep(fileNameBase) ?: "未知曲目"
            val artist = cleanArtist(metaArtist) ?: cleanArtist(parsedArtist)
            val album = cleanAlbum(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0
            val track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.trim()?.takeIf { it.isNotEmpty() }
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.trim()?.toIntOrNull()
            val mimeType = guessMime(file.name)
            val size = file.length()
            val artworkBytes = runCatching { retriever.embeddedPicture }.getOrNull()

            val albumArtUri = artworkBytes?.let { saveEmbeddedArtwork(it, file.absolutePath) }
            val ext = file.name.substringAfterLast('.', "").lowercase()
            val formatVerified = com.shiyinplayer.player.decoder.MagicNumberValidator.validate(
                Uri.fromFile(file), ext, context
            )
            return SongEntity(
                title = title,
                artistName = artist,
                albumName = album,
                albumArtUri = albumArtUri,
                formatVerified = formatVerified,
                durationMs = duration,
                trackNumber = track,
                uri = file.absolutePath,
                mimeType = mimeType,
                sourceType = MediaSourceType.LOCAL,
                path = file.absolutePath,
                dateAdded = file.lastModified(),
                sizeBytes = size,
                genre = genre,
                year = year,
                dedupKey = file.absolutePath
            )
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun scanLocalSaf(
        src: MusicSource,
        scanHidden: Boolean,
        extWhitelist: Set<String>,
        emit: (SongEntity) -> Unit,
        emitAtt: (FolderAttachmentEntity) -> Unit
    ): Boolean {
        val treeUriStr = runCatching { JSONObject(src.configJson).optString("treeUri") }.getOrNull()
            .takeIf { !it.isNullOrEmpty() } ?: return false   // 配置缺失：不视为"空目录"，避免误清库
        val treeUri = Uri.parse(treeUriStr)
        // 树 URI 无法解析（SAF 权限已撤销等）视为"未能扫描"，返回 false 不触发该来源清理
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        // 子项 uri = <treeUri>/document/<rel>，据此计算附件相对目录（与 folder_entry 的 folderPath 对齐）
        val docPrefix = "$treeUriStr/document/"

        // P1-9：按"父目录 + 去扩展名 basename"分组建索引，避免跨目录同名文件互相覆盖/误消费
        val audioFiles = mutableMapOf<String, MutableMap<String, DocumentFile>>()   // dirKey -> (baseKey -> file)
        val cueFiles = mutableListOf<DocumentFile>()

        traverse(root) { file ->
            val name = file.name ?: return@traverse
            if (!scanHidden && name.startsWith('.')) return@traverse
            val ext = name.substringAfterLast('.', "").lowercase()
            when {
                file.isFile && isAudioExt(ext, extWhitelist) ->
                    audioFiles.getOrPut(file.uri.toString().substringBeforeLast('/')) { mutableMapOf() }[
                        name.substringBeforeLast('.').lowercase()
                    ] = file
                ext == Constants.CUE_EXTENSION -> cueFiles += file
                file.isFile && (ext in COVER_EXTENSIONS || ext in TEXT_EXTENSIONS) -> {
                    val rel = file.uri.toString().substringAfter(docPrefix).substringBeforeLast('/')
                    emitAtt(
                        FolderAttachmentEntity(
                            sourceId = src.id,
                            parentPath = rel,
                            name = name,
                            kind = attachmentKind(ext),
                            uri = file.uri.toString(),
                            size = file.length()
                        )
                    )
                }
            }
        }

        val consumedAudioKeys = mutableSetOf<Pair<String, String>>()   // (dirKey, baseKey)

        // CUE 分轨（T15）：整轨文件拆为子曲目，整轨本身不入库；同目录优先匹配，无则全局兜底
        for (cue in cueFiles) {
            val text = runCatching {
                context.contentResolver.openInputStream(cue.uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: continue
            val sheet = CueParser.parse(text) ?: continue
            val cueDirKey = cue.uri.toString().substringBeforeLast('/')
            val key = sheet.file.substringBeforeLast('.').lowercase()
            val matchedDirKey = if (audioFiles[cueDirKey]?.containsKey(key) == true) {
                cueDirKey
            } else {
                audioFiles.entries.firstOrNull { (_, map) -> map.containsKey(key) }?.key
            } ?: continue
            val audioDoc = audioFiles[matchedDirKey]!![key]!!
            consumedAudioKeys += matchedDirKey to key
            val wholeDuration = extractDuration(audioDoc.uri)
            // P2-7：CUE 子曲目回填专辑/艺术家（REM ALBUM/REM ARTIST → 目录名/分轨 PERFORMER 兜底），
            // 使子曲目参与专辑/艺术家聚合（此前 albumName 恒 null 被聚合遗漏）。
            val cueAlbum = sheet.albumName?.takeIf { it.isNotBlank() } ?: parentDirName(audioDoc.uri)
            sheet.tracks.forEachIndexed { i, t ->
                val start = t.index01Ms
                val end = sheet.tracks.getOrNull(i + 1)?.index01Ms ?: wholeDuration
                emit(
                    SongEntity(
                        title = cleanTitleKeep(t.title) ?: t.title,
                        artistName = cleanArtist(sheet.artistName) ?: cleanArtist(t.performer),
                        albumName = cleanAlbum(cueAlbum),
                        albumArtUri = null,
                        durationMs = if (end > start) end - start else 0,
                        trackNumber = i + 1,
                        uri = audioDoc.uri.toString(),
                        mimeType = context.contentResolver.getType(audioDoc.uri),
                        sourceType = MediaSourceType.LOCAL,
                        path = audioDoc.uri.toString(),
                        dateAdded = System.currentTimeMillis(),
                        sizeBytes = audioDoc.length(),
                        cueId = audioDoc.uri.toString(),
                        dedupKey = "${audioDoc.uri}#${i + 1}",
                        trackIndex = i + 1,
                        clipStartMs = start,
                        clipEndMs = if (end > start) end else null
                    )
                )
            }
        }

        // 普通音频（无 CUE 或非整轨）直接入库
        for ((dirKey, map) in audioFiles) {
            for ((key, doc) in map) {
                if (dirKey to key in consumedAudioKeys) continue
                extractMetadata(doc)?.let { emit(it) }
            }
        }
        return true
    }

    // ===== SMB 分支（T8） =====

    private suspend fun scanSmb(
        src: MusicSource,
        scanHidden: Boolean,
        extWhitelist: Set<String>,
        emit: (SongEntity) -> Unit,
        emitAtt: (FolderAttachmentEntity) -> Unit
    ): Boolean {
        val root = runCatching { JSONObject(src.configJson).optString("url") }.getOrNull()
            .takeIf { !it.isNullOrEmpty() } ?: return false   // 配置缺失：不视为"空目录"，避免误清库
        val now = System.currentTimeMillis()
        suspend fun walk(path: String, isRoot: Boolean = false) {
            val entries = try {
                smbBrowser.listFiles(path)
            } catch (t: Throwable) {
                if (isRoot) throw t   // 根枚举失败：冒泡到扫描器外层记入 errors 并标记本源未成功，避免误清库
                return                // 子目录失败：跳过该子目录继续扫
            }
            val parentRel = relDir(path, root)
            for (e in entries) {
                if (!scanHidden && e.name.startsWith('.')) continue
                val child = if (path.endsWith("/")) "$path${e.name}" else "$path/${e.name}"
                if (e.isDir) { walk(child) }
                else {
                    val ext = e.name.substringAfterLast('.', "").lowercase()
                    when {
                        isAudioExt(ext, extWhitelist) -> {
                            val prefix = probeRemoteBytes { smbBrowser.readPrefix(child, PROBE_PREFIX_BYTES) }
                            val formatVerified = prefix?.let { com.shiyinplayer.player.decoder.MagicNumberValidator.validate(it, ext) } ?: true
                            val duration = prefix?.let { p ->
                                probeDurationFromPrefix(p, ext).takeIf { it > 0 }
                                    ?: com.shiyinplayer.player.decoder.FormatSpecificDurationProber.probe(ext, p, e.size)
                            } ?: 0L
                            // 需求 9：远程入库时从文件名解析出真实歌名/歌手，避免标题显示为原始文件名
                            val titleBase = e.name.substringBeforeLast('.')
                            var (remoteArtist, remoteTitle) = parseFileNameTitle(titleBase)
                            remoteArtist = stripDiscMarkers(remoteArtist)
                            val albumArtUri = prefix?.let { extractRemoteArtwork(it, e.name, child) }
                            emit(
                                SongEntity(
                                    title = remoteTitle ?: titleBase,
                                    artistName = remoteArtist,
                                    uri = child,
                                    sourceType = MediaSourceType.SMB,
                                    albumArtUri = albumArtUri,
                                    mimeType = guessMime(e.name),
                                    formatVerified = formatVerified,
                                    durationMs = duration,
                                    dateAdded = now,
                                    sizeBytes = e.size,
                                    dedupKey = child
                                )
                            )
                        }
                        ext in COVER_EXTENSIONS || ext in TEXT_EXTENSIONS ->
                            emitAtt(
                                FolderAttachmentEntity(
                                    sourceId = src.id,
                                    parentPath = parentRel,
                                    name = e.name,
                                    kind = attachmentKind(ext),
                                    uri = child,
                                    size = e.size
                                )
                            )
                    }
                }
            }
        }
        walk(root, isRoot = true)
        // 根目录枚举失败已抛异常冒泡到扫描器外层（记入 errors 并跳过本源清理），走到底即为成功
        return true
    }

    // ===== WebDAV 分支（T14） =====

    private suspend fun scanWebDav(
        src: MusicSource,
        scanHidden: Boolean,
        extWhitelist: Set<String>,
        emit: (SongEntity) -> Unit,
        emitAtt: (FolderAttachmentEntity) -> Unit
    ): Boolean {
        val root = runCatching { JSONObject(src.configJson).optString("url") }.getOrNull()
            .takeIf { !it.isNullOrEmpty() } ?: return false   // 配置缺失：不视为"空目录"，避免误清库
        val now = System.currentTimeMillis()
        val visited = mutableSetOf<String>()
        suspend fun walk(path: String, isRoot: Boolean = false) {
            if (!visited.add(path)) return
            val entries = try {
                webDavBrowser.listFiles(path)
            } catch (t: Throwable) {
                if (isRoot) throw t   // 根枚举失败：冒泡到扫描器外层记入 errors 并标记本源未成功，避免误清库
                return                // 子目录失败：跳过该子目录继续扫
            }
            val parentRel = relDir(path, root)
            for (e in entries) {
                if (!scanHidden && e.name.startsWith('.')) continue
                if (e.isDir) { walk(e.path) }
                else {
                    val ext = e.name.substringAfterLast('.', "").lowercase()
                    when {
                        isAudioExt(ext, extWhitelist) -> {
                            val prefix = probeRemoteBytes { webDavBrowser.readPrefix(e.path, PROBE_PREFIX_BYTES) }
                            val formatVerified = prefix?.let { com.shiyinplayer.player.decoder.MagicNumberValidator.validate(it, ext) } ?: true
                            val duration = prefix?.let { p ->
                                probeDurationFromPrefix(p, ext).takeIf { it > 0 }
                                    ?: com.shiyinplayer.player.decoder.FormatSpecificDurationProber.probe(ext, p, e.size)
                            } ?: 0L
                            // 需求 9：远程入库时从文件名解析出真实歌名/歌手，避免标题显示为原始文件名
                            val titleBase = e.name.substringBeforeLast('.')
                            val (remoteArtist, remoteTitle) = parseFileNameTitle(titleBase)
                            val albumArtUri = prefix?.let { extractRemoteArtwork(it, e.name, e.path) }
                            emit(
                                SongEntity(
                                    title = remoteTitle ?: titleBase,
                                    artistName = remoteArtist,
                                    uri = e.path,
                                    sourceType = MediaSourceType.WEBDAV,
                                    albumArtUri = albumArtUri,
                                    mimeType = e.contentType,
                                    formatVerified = formatVerified,
                                    durationMs = duration,
                                    dateAdded = now,
                                    sizeBytes = e.size,
                                    dedupKey = e.path
                                )
                            )
                        }
                        ext in COVER_EXTENSIONS || ext in TEXT_EXTENSIONS ->
                            emitAtt(
                                FolderAttachmentEntity(
                                    sourceId = src.id,
                                    parentPath = parentRel,
                                    name = e.name,
                                    kind = attachmentKind(ext),
                                    uri = e.path,
                                    size = e.size
                                )
                            )
                    }
                }
            }
        }
        walk(root, isRoot = true)
        // 根目录枚举失败已抛异常冒泡到扫描器外层（记入 errors 并跳过本源清理），走到底即为成功
        return true
    }

    // ===== 工具 =====

    /** P2-7：取 DocumentFile 所在目录名（URI 解码后），供 CUE 子曲目专辑名兜底。 */
    private fun parentDirName(uri: Uri): String? {
        val parent = uri.toString().substringBeforeLast('/')
        return Uri.decode(parent.substringAfterLast('/')).takeIf { it.isNotBlank() }
    }

    /** P1-6：扩展名白名单过滤（白名单为空 = 全部 active 扩展名）。 */
    private fun isAudioExt(ext: String, extWhitelist: Set<String>): Boolean =
        Constants.AUDIO_EXTENSIONS.contains(ext) && (extWhitelist.isEmpty() || ext in extWhitelist)

    private fun isAudio(name: String, extWhitelist: Set<String>): Boolean =
        isAudioExt(name.substringAfterLast('.', "").lowercase(), extWhitelist)

    /** 把某完整路径相对源根，折算为目录的相对路径（根目录返回 ""）。 */
    private fun relDir(full: String, root: String): String {
        val f = full.trimEnd('/')
        val r = root.trimEnd('/')
        return when {
            f == r -> ""
            f.startsWith("$r/") -> f.removePrefix(r).trimStart('/')
            else -> f.removePrefix(r).trimStart('/')
        }
    }

    /** 附加文件扩展名 → 附件类型（0=封面图，1=说明文本）。 */
    private fun attachmentKind(ext: String): Int =
        if (ext in TEXT_EXTENSIONS) FolderAttachmentEntity.KIND_TEXT else FolderAttachmentEntity.KIND_COVER

    private fun guessMime(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return com.shiyinplayer.player.decoder.AudioFormatRegistry.mimeTypeOf(ext)
    }

    private fun extractDuration(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (_: Exception) { 0 } finally { r.release() }
    }

    /** 远程时长探测：下载前缀字节写临时文件，用 MediaMetadataRetriever 解析时长。失败返回 0。 */
    private fun probeDurationFromPrefix(bytes: ByteArray, ext: String): Long {
        if (bytes.isEmpty()) return 0
        val tmp = File.createTempFile("probe_", ".$ext", context.cacheDir)
        return try {
            tmp.writeBytes(bytes)
            val r = MediaMetadataRetriever()
            r.setDataSource(tmp.absolutePath)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            r.release()
            d
        } catch (_: Exception) {
            0
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 带超时的远程探测包装：超时或失败回退 0，不阻塞扫描主流程。P2-11：取消时 rethrow。 */
    private suspend fun probeRemoteDuration(block: suspend () -> Long): Long =
        try {
            withTimeout(PROBE_TIMEOUT_MS) { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            0L
        }

    /** 带超时的远程前缀字节下载：超时或失败返回 null。P2-11：取消时 rethrow。 */
    private suspend fun probeRemoteBytes(block: suspend () -> ByteArray?): ByteArray? =
        try {
            withTimeout(PROBE_TIMEOUT_MS) { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun traverse(dir: DocumentFile, onFile: suspend (DocumentFile) -> Unit) {
        val children = dir.listFiles()
        for (child in children) {
            if (child.isDirectory) traverse(child, onFile) else onFile(child)
        }
    }

    private fun extractMetadata(file: DocumentFile): SongEntity? {
        val uri = file.uri
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
        } catch (e: Exception) {
            retriever.release()
            return null
        }
        val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
        val fileNameBase = file.name?.substringBeforeLast('.')
        // 2026-08-19：无元数据标签时从「歌手 - 歌名」文件名拆出真实歌名/歌手，避免显示文件名
        val (parsedArtist, parsedTitle) = parseFileNameTitle(fileNameBase)
        val title = cleanTitleKeep(metaTitle) ?: cleanTitleKeep(parsedTitle) ?: cleanTitleKeep(fileNameBase) ?: "未知曲目"
        val artist = cleanArtist(metaArtist) ?: cleanArtist(parsedArtist)
        val album = cleanAlbum(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0
        val track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
        val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            ?.trim()?.toIntOrNull()
        val mimeType = context.contentResolver.getType(uri)
        val size = file.length()
        // 提取内嵌封面字节（须在 release 前调用 embeddedPicture）
        val artworkBytes = runCatching { retriever.embeddedPicture }.getOrNull()
        retriever.release()

        // 内嵌封面写入缓存（OOM 防护：超 1MB 或边长 > 1024px 降采样），无封面返回 null
        val albumArtUri = artworkBytes?.let { saveEmbeddedArtwork(it, uri.toString()) }

        // 文件头魔数校验（防止扩展名伪造，如 .mp3 改名 .ac3）
        val ext = file.name?.substringAfterLast('.', "")?.lowercase() ?: ""
        val formatVerified = com.shiyinplayer.player.decoder.MagicNumberValidator.validate(uri, ext, context)

        return SongEntity(
            title = title,
            artistName = artist,
            albumName = album,
            albumArtUri = albumArtUri,
            formatVerified = formatVerified,
            durationMs = duration,
            trackNumber = track,
            uri = uri.toString(),
            mimeType = mimeType,
            sourceType = MediaSourceType.LOCAL,
            path = uri.toString(),
            dateAdded = System.currentTimeMillis(),
            sizeBytes = size,
            genre = genre,
            year = year,
            dedupKey = uri.toString()
        )
    }

    /** 从远程前缀字节尽力提取内嵌封面（写临时文件委托 MediaMetadataRetriever）。
     *  适用于封面位于文件头部前缀区间的格式（MP3 ID3v2 / FLAC / OGG 等）；
     *  APE/WMA 等标签在文件尾的格式在前缀中取不到，留空（可接受，播放后由在线匹配封面兜底）。 */
    private fun extractRemoteArtwork(prefix: ByteArray?, name: String, uriKey: String): String? {
        if (prefix == null || prefix.isEmpty()) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        val tmp = File(context.cacheDir, "artwork/remote_${"$uriKey:$name".hashCode()}.$ext")
        return try {
            tmp.parentFile?.mkdirs()
            tmp.writeBytes(prefix)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tmp.absolutePath)
                val art = retriever.embeddedPicture
                art?.takeIf { it.isNotEmpty() }?.let { saveEmbeddedArtwork(it, uriKey) }
            } finally {
                runCatching { retriever.release() }
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 内嵌封面写入缓存目录，返回 file:// URI；超尺寸时降采样避免 OOM。无有效图返回 null。 */
    private fun saveEmbeddedArtwork(bytes: ByteArray, key: String): String? {
        if (bytes.isEmpty()) return null
        val dir = File(context.cacheDir, "artwork")
        if (!dir.exists()) dir.mkdirs()
        val outFile = File(dir, "${key.hashCode()}.jpg")
        if (outFile.exists()) return "file://${outFile.absolutePath}"

        // 先解码边界获取尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        // 降采样：边长 > 1024 时按 2 的幂降采样
        var sample = 1
        while (maxDim / sample > MAX_ARTWORK_EDGE_PX) sample *= 2

        // 原始字节 ≤ 1MB 且无需降采样 → 直接写
        if (bytes.size <= MAX_ARTWORK_BYTES && sample == 1) {
            return runCatching {
                outFile.writeBytes(bytes)
                "file://${outFile.absolutePath}"
            }.getOrNull()
        }
        // 需降采样或压缩 → 解码→压缩写
        return runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            }
            bmp.recycle()
            "file://${outFile.absolutePath}"
        }.getOrNull()
    }

    // ===== 聚合重建 =====

    /** 从完整歌曲表重建 albums / artists 聚合（含年份、封面、曲目数），先清空再插入避免残留。 */
    suspend fun rebuildAlbumsArtists() {
        val albumMap = LinkedHashMap<Pair<String?, String?>, AlbumEntity>()
        val artistMap = LinkedHashMap<String?, ArtistEntity>()
        // 分页拉取，避免万级曲目一次性消费全表 Flow 触发 CursorWindow 溢出
        var offset = 0
        while (true) {
            val page = songDao.getAllPaged(offset, AGGREGATE_PAGE_SIZE)
            for (s in page) {
                if (!s.albumName.isNullOrEmpty()) {
                    val key = s.albumName to s.artistName
                    val cur = albumMap[key]
                    albumMap[key] = AlbumEntity(
                        name = s.albumName,
                        artistName = s.artistName,
                        albumArtUri = cur?.albumArtUri ?: s.albumArtUri,
                        year = cur?.year ?: s.year,
                        songCount = (cur?.songCount ?: 0) + 1
                    )
                }
                if (!s.artistName.isNullOrEmpty()) {
                    val cur = artistMap[s.artistName]
                    artistMap[s.artistName] = ArtistEntity(
                        name = s.artistName,
                        songCount = (cur?.songCount ?: 0) + 1
                    )
                }
            }
            offset += page.size
            if (page.size < AGGREGATE_PAGE_SIZE) break
        }
        db.withTransaction {
            albumDao.clear()
            artistDao.clear()
            if (albumMap.isNotEmpty()) albumDao.upsertAll(albumMap.values.toList())
            if (artistMap.isNotEmpty()) artistDao.upsertAll(artistMap.values.toList())
            songDao.rewriteAlbumIds()
            songDao.rewriteArtistIds()
        }
    }
}
