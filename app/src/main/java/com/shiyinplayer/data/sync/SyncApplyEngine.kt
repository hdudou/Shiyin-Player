package com.shiyinplayer.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.shiyinplayer.data.local.AppDatabase
import com.shiyinplayer.data.local.cache.MetadataCacheDao
import com.shiyinplayer.data.local.cache.MetadataCacheEntity
import com.shiyinplayer.data.local.entity.PlaylistItemEntity
import com.shiyinplayer.data.local.entity.SongEntity
    import com.shiyinplayer.data.media.FolderStructureBuilder
    import com.shiyinplayer.data.media.MediaCacheCleaner
    import com.shiyinplayer.data.media.SmbCredentialStore
import com.shiyinplayer.data.metadata.ArtistMetadata
import com.shiyinplayer.data.metadata.MetadataCacheType
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.remote.webdav.WebDavCredentialStore
import com.shiyinplayer.data.repository.MetadataRepository
import com.shiyinplayer.data.sync.model.OpResult
import com.shiyinplayer.data.sync.model.SyncEntityName
import com.shiyinplayer.data.sync.model.SyncMapper
import com.shiyinplayer.data.sync.model.SyncOp
import com.shiyinplayer.data.sync.model.intOr
import com.shiyinplayer.data.sync.model.longOr
import com.shiyinplayer.data.sync.model.stringOr
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ops → 现有 DAO 落库（契约 §5）。
 *
 * 三条容易踩错的语义，实现时必须守住：
 *
 * 1. **没有 `@Upsert`**：`SongDao.upsert` 是 `@Insert(IGNORE)`，命中既有行会被静默忽略 →
 *    必须「先按 dedupKey 查，再决定 update / insert」，否则 PC 改标签永远不生效。
 * 2. **`playlist_item` 是「批次内该歌单全量覆盖」**：同一歌单的成员分散在多个 op，逐 op 覆盖会
 *    误删先写成员 → 必须先把整批该歌单的成员聚合起来，再 `clear + insertAll` 一次性替换，
 *    这样「从歌单移除歌曲」才能同步。
 * 3. **结果必须按 opIndex 回填**：PC 侧 `resultByIndex.GetValueOrDefault(i)`——索引对不上会错位，
 *    且**缺失条目会被 PC 当作 ok=true**。
 *
 * 结果语义约定：`ok=false` 仅用于「该 op 完全无法应用」（未知实体/缺定位键/写库异常）；
 * 可预期的良性跳过（歌单成员对应歌曲本机没有）返回 `ok=true` + `error="song_not_found"` 作备注——
 * 否则本机库里缺一首歌会把整条歌单变更判失败，PC 侧持续报错。
 *
 * **音频文件与凭据两类旁路**（2026-09-16 补齐）：
 * - 文件：`/sync/push-file` 落盘（[SyncFileStore]）→ 本引擎按 dedupKey 回写 `uri`/`path`，
 *   使 ExoPlayer 能播；`deleteFile=true` 的删除则反向删掉接收文件夹内的文件。
 * - 凭据：`music_source` 的 `record.creds` 是 AES-GCM 信封，口令 = 配对设备 token，
 *   解出的明文写进 [SmbCredentialStore]/[WebDavCredentialStore]（不进 DB 明文列）。
 *
 * **目录树随批次重建**（2026-09-16 补齐）：`folder_entry` 是 [FolderStructureBuilder] 从 `songs`
 * 全量预生成的物化目录树（本地扫描 / 数据导入 / 升级 / 删曲四条路径都会重建它），
 * **同步落地这条路径此前漏了**，留下两个可见缺陷——推过来的曲目在「文件夹」tab 看不见；
 * 删曲后该表留下 `songId` 悬空的孤儿条目。故本引擎在批次含 `song` / `music_source` 变更时
 * 补一次重建（见 [rebuildFolderTreeIfNeeded]，注意其中「不要顺手重建 `albums`/`artists`」的说明）。
 */
@Singleton
class SyncApplyEngine @Inject constructor(
    private val db: AppDatabase,
    private val syncFileStore: SyncFileStore,
    private val smbCredentialStore: SmbCredentialStore,
    private val webDavCredentialStore: WebDavCredentialStore,
    private val pairingStore: SyncPairingStore,
    private val folderStructure: FolderStructureBuilder,
    private val mediaCacheCleaner: MediaCacheCleaner,
    /** 歌手头像 / 简介落在缓存库（`metadata` 表 type=artist），与主库是两个 SQLite 文件。 */
    private val metadataCacheDao: MetadataCacheDao,
) {

    /**
     * @param deviceToken 发起本批次的 PC 设备的 token——凭据信封口令。
     *   来自当前请求上下文（`/sync/push` 已校验过 token）；**进程重启后补落地**时为 null，
     *   此时回退到配对记录里第一个非空 token（单 PC 场景恒正确；多 PC 场景解不开只丢凭据，
     *   不会让 op 失败，见 [resolveEnvelopeToken]）。
     */
    suspend fun applyBatch(ops: List<SyncOp>, deviceToken: String? = null): List<OpResult> {
        val results = arrayOfNulls<OpResult>(ops.size)
        // playlistName -> 该歌单在本批中的 op 下标（第二阶段统一做全量覆盖）
        val memberOpIndexes = LinkedHashMap<String, MutableList<Int>>()
        // LWW：本机较新而被跳过覆盖的歌单名——第二阶段要连带跳过它们的成员 op，
        // 否则会出现「歌单元数据保留本机、成员却被远端覆盖」的半覆盖状态。
        val lwwSkippedPlaylists = mutableSetOf<String>()

        db.withTransaction {
            ops.forEachIndexed { index, op ->
                if (!op.isKnown()) {
                    results[index] = OpResult(index, false, "unknown_op:${op.op}/${op.entity}")
                } else if (op.entity == SyncEntityName.PLAYLIST_ITEM && !op.isDelete) {
                    val playlistName = op.record.stringOr("playlistName", null)
                    if (playlistName.isNullOrEmpty()) {
                        results[index] = OpResult(index, false, "missing_playlistName")
                    } else {
                        memberOpIndexes.getOrPut(playlistName) { mutableListOf() }.add(index)
                    }
                } else {
                    results[index] = guarded(index) { applyOne(op, deviceToken, lwwSkippedPlaylists) }
                }
            }
            applyPlaylistMembers(ops, memberOpIndexes, results, lwwSkippedPlaylists)
        }

        // 事务外重建：目录树自带事务且要分页读全曲库，塞进上面的写事务会长时间持锁。
        rebuildFolderTreeIfNeeded(ops)

        return results.mapIndexed { i, r -> r ?: OpResult(i, false, "not_applied") }
    }

    /**
     * 批次含 `song` / `music_source` 变更时重建 `folder_entry` 目录树。
     *
     * 为什么只认这两类：目录树是「曲目按源根前缀归源」的产物——
     * - `song`：曲目增删改直接改变树内容（新增要出现、删除要去掉）；
     * - `music_source`：源根变了，同一批曲目的归源结果随之改变。
     *
     * 歌单 / 电台 / 专辑 / 艺术家不参与归源（[FolderStructureBuilder.assign] 只看 `dedupKey`），
     * 跳过可避免这些批次白跑一趟全量重建（1.6 万首分页遍历并不便宜）。
     *
     * 重建失败**不影响落库结论**：曲目数据已提交，目录树退化为旧状态（下次扫描会自愈），
     * 因此这里 `runCatching` 吞异常只告警——与 [com.shiyinplayer.data.media.LibraryScanner] 同策略。
     *
     * ⚠️ **不要在这里顺便调 `LibraryScanner.rebuildAlbumsArtists()`**（2026-09-16 真机实测踩过）：
     * `albums` / `artists` 虽然也从 `songs` 聚合而来，但**同步协议把它们当独立实体**——
     * `album` / `artist` 都有各自的 upsert / delete op，PC 会推来**没有对应曲目**的独立行
     * （第 10 节用例正是「推独立艺术家/专辑 → 回读断言其已出现」）。而 `rebuildAlbumsArtists()`
     * 是「清空后仅从 songs 重建」，一旦在同步批次末尾执行，就会把刚落地的独立行**当垃圾清掉**，
     * 表现为「推送成功但回读找不到艺术家/专辑」。
     * `folder_entry` 没有这个问题——它是**纯派生物**，除本地扫描外没有第二条写入路径。
     * 因此聚合表的滞后（删曲后 `songCount` 虚高、残留无引用行）留给**本地扫描自愈**，不在此处处理。
     */
    private suspend fun rebuildFolderTreeIfNeeded(ops: List<SyncOp>) {
        val touchesTree = ops.any {
            it.entity == SyncEntityName.SONG || it.entity == SyncEntityName.MUSIC_SOURCE
        }
        if (!touchesTree) return
        runCatching { folderStructure.rebuild() }
            .onFailure { Log.w(TAG, "folder_entry 目录树重建失败（曲目数据已落库，目录树待下次扫描自愈）: ${it.message}", it) }
    }

    // ------------------------------------------------------------ 单 op 分派

    private suspend fun applyOne(
        op: SyncOp,
        deviceToken: String?,
        lwwSkippedPlaylists: MutableSet<String>
    ): OpResult = when (op.entity) {
        SyncEntityName.SONG -> if (op.isDelete) deleteSong(op) else upsertSong(op)
        SyncEntityName.ALBUM -> if (op.isDelete) deleteAlbum(op) else upsertAlbum(op)
        SyncEntityName.ARTIST -> if (op.isDelete) deleteArtist(op) else upsertArtist(op)
        SyncEntityName.PLAYLIST ->
            if (op.isDelete) deletePlaylist(op) else upsertPlaylist(op, lwwSkippedPlaylists)
        SyncEntityName.MUSIC_SOURCE ->
            if (op.isDelete) deleteMusicSource(op) else upsertMusicSource(op, deviceToken)
        SyncEntityName.RADIO_STATION -> if (op.isDelete) deleteRadio(op) else upsertRadio(op)
        else -> OpResult(-1, false, "unsupported_entity:${op.entity}")
    }

    /**
     * 统一出口：把 op 下标回填进结果（各 apply 分支只关心 ok/error，不关心自己在批里的位置），
     * 异常不吞：转成 op 级失败结果，让 PC 看到具体原因（整批仍提交已成功的 op）。
     */
    private suspend fun guarded(index: Int, block: suspend () -> OpResult): OpResult {
        val outcome = runCatching { block() }.getOrElse { e ->
            Log.w(TAG, "op #$index 应用失败: ${e.message}", e)
            OpResult(index, false, "apply_error:${e.javaClass.simpleName}:${e.message}")
        }
        return outcome.copy(opIndex = index)
    }

    // ------------------------------------------------------------ song

    private suspend fun upsertSong(op: SyncOp): OpResult {
        val dedupKey = op.dedupKey
        if (dedupKey.isNullOrEmpty()) return fail("missing_dedupKey")

        val songDao = db.songDao()
        val existing = songDao.getByDedupKeys(listOf(dedupKey)).firstOrNull()

        // ★ LWW（2026-09-17，契约 §5）：本机该行比推来的更新 → **保留本机、不覆盖**。
        // 背景：旧实现是「PC 权威直接覆盖」，会把设备端的评分/收藏/改名一并抹掉。
        // 必须放在 songFromJson 之前（那一步会把 PC 值合进 entity）。相同时间戳视为无冲突，照常覆盖
        // （保证「PC 主动重推同一条」幂等）。
        val incomingUpdatedAt = op.record.longOr("updatedAt", 0L)
        if (existing != null && existing.updatedAt > incomingUpdatedAt) {
            Log.i(
                TAG,
                "LWW：本机较新，跳过覆盖 [$dedupKey] 本机=${existing.updatedAt} 远端=$incomingUpdatedAt"
            )
            return ok("skipped_local_newer")
        }

        // PC 的 artistId/albumId 是本机无意义的远端主键 → 用名称在本机解析外键，解析不到沿用既有值
        val artistName = op.record.stringOr("artistName", existing?.artistName)
        val albumTitle = op.record.stringOr("albumTitle", existing?.albumName)
        val artistId = artistName?.takeIf { it.isNotEmpty() }
            ?.let { db.artistDao().getByName(it)?.id } ?: existing?.artistId
        val albumId = albumTitle?.takeIf { it.isNotEmpty() }
            ?.let { db.albumDao().getByName(it, artistName)?.id } ?: existing?.albumId

        var entity = SyncMapper.songFromJson(op.record, existing, artistId, albumId)

        // 音频文件同步（契约 §5）：PC 顺序是「先推 ops → 再推文件」，故这里文件**通常还没到**，
        // 靠文件落地后的 relinkSongFile 回写。但进程重启补落地、或 PC 重推同一批 op 时文件已在，
        // 此处顺手命中一次可省掉一个「元数据在、播不了」的窗口。
        val extension = extensionOf(op.record.stringOr("path", null), op.record.stringOr("uri", null))
        val received = extension?.let { syncFileStore.completedFileFor(dedupKey, it) }
        if (received != null) {
            entity = entity.pointedTo(received)
        }

        if (existing == null) {
            songDao.upsert(entity)
        } else {
            songDao.update(entity)
        }
        return ok()
    }

    private suspend fun deleteSong(op: SyncOp): OpResult {
        val dedupKey = op.dedupKey ?: return fail("missing_dedupKey")
        val song = db.songDao().getByDedupKeys(listOf(dedupKey)).firstOrNull()
            ?: return ok("not_found")
        db.playlistItemDao().removeAllForSong(song.id)
        db.songDao().deleteById(song.id)
        // 需求⑤：同步下发的删除同样要清缓存 —— 歌词按 songId、封面按引用计数回收
        runCatching { mediaCacheCleaner.cleanAfterDelete(listOf(song.id), listOf(song.albumArtUri)) }
        if (op.deleteFile) {
            // 契约 §5：只删「本模块自己接收进来的」文件——路径由 dedupKey 确定性算出，
            // 且 SyncFileStore 内部再做一次沙盒前缀校验；用户扫描/导入进来的文件绝不动。
            // 扩展名从本机记录的 path/uri 取（接收文件的 path 就指向 synced/ 下的实体文件）。
            val extension = extensionOf(song.path, song.uri)
            if (extension == null) {
                Log.w(TAG, "deleteFile=true 但无法从本机记录推断扩展名，跳过物理删除：$dedupKey")
            } else {
                val deleted = syncFileStore.deleteByDedupKey(dedupKey, extension)
                Log.i(TAG, "deleteFile=true：$dedupKey 物理文件${if (deleted) "已删除" else "本机不存在（跳过）"}")
            }
        }
        return ok()
    }

    /**
     * 音频文件落地完成后的回写（由 `/sync/push-file` 收齐一个文件后调用）。
     *
     * 为什么必须有这一步：PC 的推送顺序是 ops 先、文件后，`upsertSong` 执行时文件尚未落盘，
     * 那时只能落 PC 的原始 path（`D:\...` 之类，安卓根本读不到）。文件到齐后若不回写，
     * 表现为「曲目在库里、点播放立刻失败」——这正是审计发现的断链。
     *
     * @return 是否命中了本机某个曲目记录（未命中不算错：文件也可能先于 ops 到达）
     */
    suspend fun relinkSongFile(dedupKey: String, file: File): Boolean {
        if (dedupKey.isEmpty()) return false
        val song = db.songDao().getByDedupKeys(listOf(dedupKey)).firstOrNull() ?: return false
        if (song.uri == file.absolutePath && song.path == file.absolutePath) return false // 已回写过
        db.songDao().update(song.pointedTo(file))
        Log.i(TAG, "文件落地回写：$dedupKey → ${file.absolutePath}")
        return true
    }

    /** 把播放地址指向接收文件夹内的实体文件；`sourceType` 同时收敛为 LOCAL（文件确实在本机）。 */
    private fun SongEntity.pointedTo(file: File): SongEntity =
        copy(uri = file.absolutePath, path = file.absolutePath, sourceType = MediaSourceType.LOCAL)

    /**
     * 从若干候选串里推断扩展名（含点，小写）；都不含扩展名时返回 null。
     * 用于对齐 [SyncFileStore.relPathFor] 的 `synced/<hash><ext>` 命名。
     */
    private fun extensionOf(vararg candidates: String?): String? {
        for (c in candidates) {
            if (c.isNullOrBlank()) continue
            // 先剥掉 URL 的 query/fragment（SAF 的 content:// 形态可能带参数）
            val clean = c.substringBefore('?').substringBefore('#')
            val ext = clean.substringAfterLast('/', "").substringAfterLast('.', "")
            if (ext.isNotEmpty() && ext.length <= 8 && ext.all { it.isLetterOrDigit() }) {
                return ".${ext.lowercase()}"
            }
        }
        return null
    }

    // ------------------------------------------------------------ album / artist

    private suspend fun upsertAlbum(op: SyncOp): OpResult {
        val name = op.record.stringOr("title", null)
        if (name.isNullOrEmpty()) return fail("missing_title")
        val artistName = op.record.stringOr("artistName", null)
        val existing = db.albumDao().getByName(name, artistName)
        db.albumDao().upsert(SyncMapper.albumFromJson(op.record, existing))
        return ok()
    }

    private suspend fun deleteAlbum(op: SyncOp): OpResult {
        val name = op.record.stringOr("title", null) ?: op.dedupKey ?: return fail("unlocatable")
        val artistName = op.record.stringOr("artistName", null)
        // 远端删除只给键，本机 albums 无 dedupKey 列 → 按名匹配（同一专辑名多歌手时命中首个即为可接受结果）
        val album = db.albumDao().getByName(name, artistName) ?: return ok("not_found")
        db.albumDao().deleteById(album.id)
        return ok()
    }

    private suspend fun upsertArtist(op: SyncOp): OpResult {
        val name = op.record.stringOr("name", null)
        if (name.isNullOrEmpty()) return fail("missing_name")
        val existing = db.artistDao().getByName(name)
        db.artistDao().upsert(SyncMapper.artistFromJson(op.record, existing))
        // v3：歌手头像 / 中文简介（PC 刮削的成果）—— 落进缓存库，见函数说明
        applyArtistProfile(name, op.record)
        return ok()
    }

    /**
     * 把 PC 推来的歌手头像 / 简介落进缓存库（`metadata` 表 `type=artist`）。
     *
     * ## 为什么不给 `artists` 表加列
     * 本机歌手资料的**唯一真源**就是这张缓存表：在线取源（`MetadataRepository.getArtistInfo`）、
     * 歌手详情页、歌词页头像**全部**读它，且已按 D7「永不过期」去掉了 TTL。
     * 若另立 `artists.artUrl/bio` 两列，就会出现「两个来源、两套优先级」，
     * 而显示层还得再改三处 —— 收益为零、隔阂 +1。
     *
     * ## 合并规则：**按字段合并，非空才写**
     * - 空串是**合法常态**（G2 口径：PC 存量 `ArtUrl`/`Bio` 大量为空，两端约定"照实传空"），
     *   若把空串也写进去，PC 一次推送就会**清掉本机已刮到的头像** —— 这是不可接受的回归。
     * - 两个字段各自独立判断（头像与简介常来自**不同源**，见 G3 的「按字段分别取首家」）。
     * - `artUrl` 只认 http(s)：契约里它恒为远程地址，非 http 的值在安卓侧无法加载。
     *
     * ## 覆盖判断
     * 远端非空即覆盖本机 —— 与 `updatedAt` 的 LWW 不冲突：歌手的头像/简介不是用户可编辑数据
     * （用户在两端都改不了它），两端拿到的也是同一批在线源的结果，覆盖不会丢用户意图。
     */
    private suspend fun applyArtistProfile(name: String, rec: JSONObject) {
        val artUrl = rec.stringOr("artUrl", null)?.trim()
            ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        val bio = rec.stringOr("bio", null)?.trim()?.takeIf { it.isNotEmpty() }
        if (artUrl == null && bio == null) return

        val key = artistCacheKey(name)
        val old = metadataCacheDao.get(key, MetadataCacheType.ARTIST)
            ?.let { MetadataCacheType.decodeArtist(it.payload) }
        val merged = ArtistMetadata(
            name = name,
            avatarUrl = artUrl ?: old?.avatarUrl,
            bio = bio ?: old?.bio
        )
        metadataCacheDao.upsert(
            MetadataCacheEntity(
                key = key,
                type = MetadataCacheType.ARTIST,
                payload = MetadataCacheType.encodeArtist(merged),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 歌手缓存的键：与 [MetadataRepository.getArtistInfo] 逐字一致，否则写进去却读不到。 */
    private fun artistCacheKey(name: String): String =
        "artist|" + MetadataRepository.songKey(name, null)

    private suspend fun deleteArtist(op: SyncOp): OpResult {
        val name = op.record.stringOr("name", null) ?: op.dedupKey ?: return fail("unlocatable")
        val artist = db.artistDao().getByName(name) ?: return ok("not_found")
        db.artistDao().deleteById(artist.id)
        return ok()
    }

    // ------------------------------------------------------------ playlist

    private suspend fun upsertPlaylist(op: SyncOp, lwwSkippedPlaylists: MutableSet<String>? = null): OpResult {
        val name = op.record.stringOr("name", null)
        if (name.isNullOrEmpty()) return fail("missing_name")
        val playlistDao = db.playlistDao()
        val existing = playlistDao.getByName(name)

        // ★ LWW：歌单本机较新（含「往歌单里加/删歌」这类成员变更——会由 PlaylistDao.touch 前移
        //   dateModified）→ 保留本机、不覆盖；并把歌单名登记进跳过集，让本批的成员 op 也一并跳过，
        //   否则会出现「歌单元数据保留本机、成员却被远端覆盖」的半覆盖状态。
        val incomingUpdatedAt = op.record.longOr("updatedAt", 0L)
        if (existing != null && existing.dateModified > incomingUpdatedAt) {
            Log.i(
                TAG,
                "LWW：歌单本机较新，跳过覆盖（含成员）[$name] 本机=${existing.dateModified} 远端=$incomingUpdatedAt"
            )
            lwwSkippedPlaylists?.add(name)
            return ok("skipped_local_newer")
        }

        val entity = SyncMapper.playlistFromJson(op.record, existing)
        if (existing == null) playlistDao.insert(entity) else playlistDao.update(entity)
        return ok()
    }

    /** 契约 §4 例外：playlist 删除顶层不带 dedupKey，只用 `record.name` 定位。 */
    private suspend fun deletePlaylist(op: SyncOp): OpResult {
        val name = op.record.stringOr("name", null) ?: return fail("missing_name")
        val playlist = db.playlistDao().getByName(name) ?: return ok("not_found")
        db.playlistItemDao().clear(playlist.id)
        db.playlistDao().deleteById(playlist.id)
        return ok()
    }

    // ------------------------------------------------------------ playlist_item（批次内全量覆盖）

    private suspend fun applyPlaylistMembers(
        ops: List<SyncOp>,
        memberOpIndexes: Map<String, List<Int>>,
        results: Array<OpResult?>,
        lwwSkippedPlaylists: Set<String>
    ) {
        for ((playlistName, indexes) in memberOpIndexes) {
            // ★ LWW：该歌单本机较新（upsertPlaylist 阶段已判定）→ 成员整组保持本机，避免半覆盖
            if (playlistName in lwwSkippedPlaylists) {
                indexes.forEach { results[it] = OpResult(it, true, "skipped_local_newer") }
                continue
            }

            val playlist = db.playlistDao().getByName(playlistName)
            if (playlist == null) {
                indexes.forEach { results[it] = OpResult(it, false, "playlist_not_found") }
                continue
            }

            // 聚合本批该歌单的全集：songDedupKey -> position（同键后者覆盖，position 以最后一条为准）
            val positionBySongKey = LinkedHashMap<String, Int>()
            for (i in indexes) {
                val rec = ops[i].record
                val songKey = rec.stringOr("songDedupKey", null)
                if (songKey.isNullOrEmpty()) {
                    results[i] = OpResult(i, true, "missing_songDedupKey")
                    continue
                }
                positionBySongKey[songKey] = rec.intOr("position", positionBySongKey.size)
            }

            val idBySongKey = lookupSongIdsByDedupKeys(positionBySongKey.keys.toList())

            val items = positionBySongKey.mapNotNull { (songKey, position) ->
                idBySongKey[songKey]?.let { songId ->
                    PlaylistItemEntity(playlistId = playlist.id, songId = songId, position = position)
                }
            }
            // 先清后插：不在本批集合内的成员被移除（「从歌单移除歌曲」靠这一步同步过来）
            db.playlistItemDao().clear(playlist.id)
            if (items.isNotEmpty()) db.playlistItemDao().insertAll(items)

            for (i in indexes) {
                if (results[i] != null) continue
                val songKey = ops[i].record.stringOr("songDedupKey", null)
                results[i] = if (songKey != null && idBySongKey.containsKey(songKey)) {
                    OpResult(i, true)
                } else {
                    // 本机没有这首曲目（尚未同步过来）→ 跳过该成员，但不算 op 失败
                    OpResult(i, true, "song_not_found")
                }
            }
        }
    }

    /** dedupKey → 本机 songId；分块查询，规避 SQLite 变量数上限。 */
    private suspend fun lookupSongIdsByDedupKeys(keys: List<String>): Map<String, Long> {
        if (keys.isEmpty()) return emptyMap()
        val result = HashMap<String, Long>(keys.size)
        for (chunk in keys.chunked(SQL_CHUNK)) {
            db.songDao().getByDedupKeys(chunk).forEach { result[it.dedupKey] = it.id }
        }
        return result
    }

    // ------------------------------------------------------------ music_source

    private suspend fun upsertMusicSource(op: SyncOp, deviceToken: String?): OpResult {
        val name = op.record.stringOr("name", null)
        if (name.isNullOrEmpty()) return fail("missing_name")
        val dao = db.musicSourceDao()
        val existing = dao.getByName(name)

        // ★ LWW：本机来源配置较新 → 保留本机（连同本机改过的路径/账号与凭据一起不动）
        val incomingUpdatedAt = op.record.longOr("updatedAt", 0L)
        if (existing != null && existing.updatedAt > incomingUpdatedAt) {
            Log.i(
                TAG,
                "LWW：音乐库来源本机较新，跳过覆盖 [$name] 本机=${existing.updatedAt} 远端=$incomingUpdatedAt"
            )
            return ok("skipped_local_newer")
        }

        val entity = SyncMapper.musicSourceFromJson(op.record, existing)
        if (existing == null) dao.insert(entity) else dao.update(entity)

        // 凭据落在加密凭据库（与源配置分离存储，绝不进 DB 明文列）
        val creds = SyncMapper.musicSourceCredsFromJson(op.record, resolveEnvelopeToken(deviceToken))
        if (creds != null) {
            storeMusicSourceCreds(name, entity.type, entity.configJson, creds)
        } else if (op.record.has("creds")) {
            // 有信封但没解开：可能是多 PC 场景取错了 token，或对端将来提升了 KDF。
            // 凭据是增值信息，解不开只丢密码，绝不让 op 失败（否则整条音乐源变更被反复重推）。
            Log.w(TAG, "music_source「$name」的 creds 信封未解开（口令不符或格式不支持），仅缺凭据")
        }

        if (SyncMapper.isCredentialEnvelope(entity.configJson)) {
            Log.w(TAG, "music_source「$name」的 configJson 是加密信封，本机未解密，原样保存")
        }
        return ok()
    }

    /**
     * 凭据写入对应的加密存储：
     * - SMB → 按 `configJson.host`（[SmbCredentialStore.save] 的键就是 host）
     * - WEBDAV → 按 `configJson.url`（[WebDavCredentialStore.saveForUrl] 内部取 host，带不带路径都对）
     */
    private fun storeMusicSourceCreds(
        name: String,
        type: MediaSourceType,
        configJson: String,
        creds: com.shiyinplayer.data.sync.model.MusicSourceCreds
    ) {
        val config = runCatching { JSONObject(configJson) }.getOrNull()
        when (type) {
            MediaSourceType.SMB -> {
                val host = config?.optString("host", "")?.trim().orEmpty()
                if (host.isEmpty()) {
                    Log.w(TAG, "music_source「$name」为 SMB 但 configJson 缺 host，凭据未保存")
                    return
                }
                smbCredentialStore.save(host, creds.username, creds.password)
                Log.i(TAG, "已同步 SMB 凭据：$host")
            }

            MediaSourceType.WEBDAV -> {
                val url = config?.optString("url", "")?.trim().orEmpty()
                if (url.isEmpty()) {
                    Log.w(TAG, "music_source「$name」为 WebDAV 但 configJson 缺 url，凭据未保存")
                    return
                }
                webDavCredentialStore.saveForUrl(url, creds.username, creds.password)
                Log.i(TAG, "已同步 WebDAV 凭据：$url")
            }

            else -> Log.i(TAG, "music_source「$name」类型为 ${type.name}，无需凭据")
        }
    }

    /**
     * 凭据信封口令 = 配对设备的 `deviceToken`。
     *
     * 优先用当前请求上下文里的 token（精确）；进程重启补落地时该值为 null，
     * 回退到配对记录里第一个非空 token——单 PC 场景恒正确，多 PC 场景取错只会**解不开信封**，
     * 上层只丢凭据不判失败，因此不需要为此引入额外信令。
     */
    private fun resolveEnvelopeToken(deviceToken: String?): String? {
        if (!deviceToken.isNullOrBlank()) return deviceToken
        return pairingStore.getAll().firstOrNull { it.token.isNotBlank() }?.token
    }

    private suspend fun deleteMusicSource(op: SyncOp): OpResult {
        val name = op.record.stringOr("name", null) ?: op.dedupKey ?: return fail("unlocatable")
        val source = db.musicSourceDao().getByName(name) ?: return ok("not_found")
        db.musicSourceDao().deleteById(source.id)
        return ok()
    }

    // ------------------------------------------------------------ radio_station

    private suspend fun upsertRadio(op: SyncOp): OpResult {
        val url = op.record.stringOr("url", null)
        if (url.isNullOrEmpty()) return fail("missing_url")
        val dao = db.radioStationDao()
        val existing = dao.getByUrl(url)

        // ★ LWW：本机电台较新（典型场景＝在安卓上刚点过收藏/取消收藏，会前移 updatedAt）→
        //   保留本机，不覆盖。旧实现是 PC 权威直接覆盖，会把设备端的收藏状态抹掉。
        val incomingUpdatedAt = op.record.longOr("updatedAt", 0L)
        if (existing != null && existing.updatedAt > incomingUpdatedAt) {
            Log.i(
                TAG,
                "LWW：电台本机较新，跳过覆盖 [$url] 本机=${existing.updatedAt} 远端=$incomingUpdatedAt"
            )
            return ok("skipped_local_newer")
        }

        val entity = SyncMapper.radioFromJson(op.record, existing, System.currentTimeMillis())
        // dao.insert 是 @Insert(REPLACE)：existing 非空时 entity.id 已保留，等效于更新
        dao.insert(entity)
        return ok()
    }

    private suspend fun deleteRadio(op: SyncOp): OpResult {
        val url = op.record.stringOr("url", null) ?: op.dedupKey ?: return fail("unlocatable")
        val station = db.radioStationDao().getByUrl(url) ?: return ok("not_found")
        db.radioStationDao().deleteById(station.id)
        return ok()
    }

    // ------------------------------------------------------------ 结果构造

    // 下标由 [guarded] 统一回填，这里只需 ok / error
    private fun ok(note: String? = null) = OpResult(0, true, note)
    private fun fail(reason: String) = OpResult(0, false, reason)

    private companion object {
        const val TAG = "SyncApplyEngine"
        const val SQL_CHUNK = 500
    }
}
