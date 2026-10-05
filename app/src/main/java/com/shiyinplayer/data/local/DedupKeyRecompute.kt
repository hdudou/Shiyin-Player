package com.shiyinplayer.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.shiyinplayer.data.media.DedupKey
import com.shiyinplayer.data.model.MediaSourceType

/**
 * dedupKey 重算 + 撞键行**无损**折叠——[MIGRATION_13_14] 与 [MIGRATION_14_15] 共用的核心实现。
 *
 * 规则：`{sourceType}:{sha256(小写规范化路径或 uri)}`，CUE 子轨追加 `#idx{n}`（见 [DedupKey]）。
 * 全库逐行按当前规则重算；重算后与「保留行」撞键的行，视为同一物理文件在旧规则下的另一种写法
 * （如源根带/不带尾斜杠、`//` 与 `/`、大小写差异），折叠为一行。**保留行 = 同键中 id 最小者**（最早入库那条）。
 *
 * ## 折叠必须无损——被折叠行的用户数据与引用关系一律并入保留行，绝不随行丢弃
 * 1. `songs` 用户数据：rating 取大、playCount 求和、lastPlayedMs 取大，favorite / formatVerified 取或；
 *    歌词偏移、searchKey、discNo、时长、大小与 genre / year / 封面按「只补不覆盖」补齐；updatedAt 取大；
 * 2. `playlist_items`：保留行已在同一歌单的重复成员先删掉（该表有唯一索引 `(playlistId, songId)`），
 *    其余**改指**保留行——否则被折叠行独有的歌单成员关系会整条消失，表现为「歌单凭空少一首仍在库中的曲目」；
 * 3. `folder_entry`：同样按 `(sourceId, folderPath)` 判重后改指，避免目录树留下悬空 `songId`
 *    （该表虽是扫描时全量重建的派生缓存，但迁移到下次扫描之间会让文件节点点进去无响应）；
 * 4. 以上全部完成，最后才删除被折叠行。
 *
 * ## 幂等
 * 键已全部符合规则的库：重算值与原值相同即跳过（不产生任何 UPDATE），折叠数 0，可安全重复执行。
 * 实测本机库（16148 行全部合规）升级后曲库与用户数据逐行不变，代价仅一次全表扫描。
 */
internal object DedupKeyRecompute {

    /** [scanned] 扫描行数；[rekeyed] 键被改写的行数；[folded] 被无损折叠掉的行数。 */
    data class Result(val scanned: Int, val rekeyed: Int, val folded: Int)

    /** 按当前规则重算全库 dedupKey，并无损折叠撞键行；返回统计供调用方记日志。 */
    fun apply(db: SupportSQLiteDatabase): Result {
        // 极旧库（v1/v2 收敛路径）可能还没有该列：无键可算，直接跳过
        if (!columnExists(db, "songs", "dedupKey")) return Result(0, 0, 0)
        // 重算期间新旧键会瞬时共存、且规范化后可能两行落到同一键 → 先摘掉唯一索引，收尾再重建
        db.execSQL("DROP INDEX IF EXISTS `index_songs_dedupKey`")
        val hasFolderEntries = tableExists(db, FOLDER_ENTRY_TABLE)

        val rekeys = ArrayList<Pair<Long, String>>()
        val folds = ArrayList<Duplicate>()
        val keeperIdByKey = HashMap<String, Long>()
        var scanned = 0

        // 读写分离：先在游标里只读、收集，游标关闭后再落写（避免边遍历边改同表）
        db.query(SELECT_SONGS).use { cursor ->
            while (cursor.moveToNext()) {
                scanned++
                val id = cursor.getLong(0)
                val sourceType = runCatching { MediaSourceType.valueOf(cursor.getString(1).orEmpty()) }
                    .getOrDefault(MediaSourceType.LOCAL)
                val uri = cursor.getString(2).orEmpty()
                // CUE 子轨：cueId 非空且 trackIndex 有效时作为分轨序号（对齐写入侧的 i + 1）
                val trackIndex = if (cursor.isNull(4)) 0 else cursor.getInt(4)
                val cueIndex = trackIndex.takeIf { it > 0 && !cursor.isNull(3) }
                val key = DedupKey.forTrack(sourceType, uri, cueIndex)

                val keeperId = keeperIdByKey[key]
                if (keeperId == null) {
                    keeperIdByKey[key] = id
                    if (key != cursor.getString(5)) rekeys += id to key
                } else {
                    folds += Duplicate(
                        dupId = id,
                        keeperId = keeperId,
                        rating = cursor.getInt(6),
                        playCount = cursor.getInt(7),
                        lastPlayedMs = cursor.getLong(8),
                        lyricOffsetMs = cursor.getLong(9),
                        genre = cursor.getString(10),
                        year = if (cursor.isNull(11)) null else cursor.getInt(11),
                        albumArtUri = cursor.getString(12),
                        durationMs = cursor.getLong(13),
                        sizeBytes = cursor.getLong(14),
                        favorite = cursor.getInt(15),
                        formatVerified = cursor.getInt(16),
                        discNo = cursor.getInt(17),
                        updatedAt = cursor.getLong(18),
                        searchKey = cursor.getString(19)
                    )
                }
            }
        }

        for ((id, key) in rekeys) {
            db.execSQL("UPDATE `songs` SET `dedupKey` = ? WHERE `id` = ?", arrayOf<Any?>(key, id))
        }
        for (dup in folds) fold(db, dup, hasFolderEntries)

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_songs_dedupKey` ON `songs` (`dedupKey`)")
        return Result(scanned, rekeys.size, folds.size)
    }

    /** 把 [Duplicate] 无损并入保留行，然后删除被折叠行。 */
    private fun fold(db: SupportSQLiteDatabase, dup: Duplicate, hasFolderEntries: Boolean) {
        // 1. 用户数据并入保留行（必须早于删除被折叠行）
        db.execSQL(
            MERGE_USER_DATA,
            arrayOf<Any?>(
                dup.rating, dup.playCount, dup.lastPlayedMs, dup.lyricOffsetMs,
                dup.genre, dup.year, dup.albumArtUri, dup.durationMs, dup.sizeBytes,
                dup.favorite, dup.formatVerified, dup.discNo, dup.updatedAt, dup.searchKey,
                dup.keeperId
            )
        )
        // 2. 歌单成员关系改指保留行：先清掉「保留行已在同一歌单」的重复成员，再整批改指
        db.execSQL(PURGE_COLLIDING_PLAYLIST_ITEMS, arrayOf<Any?>(dup.dupId, dup.keeperId))
        db.execSQL(
            "UPDATE `playlist_items` SET `songId` = ? WHERE `songId` = ?",
            arrayOf<Any?>(dup.keeperId, dup.dupId)
        )
        // 3. 目录树节点改指保留行（同样先清同路径重复节点，避免目录里出现两行同名文件）
        if (hasFolderEntries) {
            db.execSQL(PURGE_COLLIDING_FOLDER_ENTRIES, arrayOf<Any?>(dup.dupId, dup.keeperId))
            db.execSQL(
                "UPDATE `folder_entry` SET `songId` = ? WHERE `songId` = ?",
                arrayOf<Any?>(dup.keeperId, dup.dupId)
            )
        }
        // 4. 以上都并入完毕，才删除被折叠行
        db.execSQL("DELETE FROM `songs` WHERE `id` = ?", arrayOf<Any?>(dup.dupId))
    }

    /** 待折叠行：主键 + 需要并入保留行的用户数据快照（列顺序与 [SELECT_SONGS] 一致）。 */
    private data class Duplicate(
        val dupId: Long,
        val keeperId: Long,
        val rating: Int,
        val playCount: Int,
        val lastPlayedMs: Long,
        val lyricOffsetMs: Long,
        val genre: String?,
        val year: Int?,
        val albumArtUri: String?,
        val durationMs: Long,
        val sizeBytes: Long,
        val favorite: Int,
        val formatVerified: Int,
        val discNo: Int,
        val updatedAt: Long,
        val searchKey: String?
    )

    /** 探测表是否存在（派生表在后加的版本里才建出，极旧库可能没有）。 */
    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT 1 FROM `sqlite_master` WHERE `type` = 'table' AND `name` = ?",
            arrayOf<Any?>(table)
        ).use { it.moveToFirst() }

    private const val FOLDER_ENTRY_TABLE = "folder_entry"

    private val SELECT_SONGS = """
        SELECT `id`, `sourceType`, `uri`, `cueId`, `trackIndex`, `dedupKey`,
               `rating`, `playCount`, `lastPlayedMs`, `lyricOffsetMs`,
               `genre`, `year`, `albumArtUri`, `durationMs`, `sizeBytes`,
               `favorite`, `formatVerified`, `discNo`, `updatedAt`, `searchKey`
        FROM `songs`
        ORDER BY `id`
    """.trimIndent()

    /**
     * 用户数据并入保留行：计数/标记类求和或取大，内容类「只补不覆盖」。
     * favorite（收藏）与 formatVerified（魔数校验通过）取或——同一物理文件任一写法已标记即应保留。
     */
    private val MERGE_USER_DATA = """
        UPDATE `songs` SET
          `rating` = MAX(`rating`, ?),
          `playCount` = `playCount` + ?,
          `lastPlayedMs` = MAX(`lastPlayedMs`, ?),
          `lyricOffsetMs` = CASE WHEN `lyricOffsetMs` = 0 THEN ? ELSE `lyricOffsetMs` END,
          `genre` = COALESCE(NULLIF(`genre`, ''), ?),
          `year` = COALESCE(`year`, ?),
          `albumArtUri` = COALESCE(NULLIF(`albumArtUri`, ''), ?),
          `durationMs` = CASE WHEN `durationMs` = 0 THEN ? ELSE `durationMs` END,
          `sizeBytes` = CASE WHEN `sizeBytes` = 0 THEN ? ELSE `sizeBytes` END,
          `favorite` = MAX(`favorite`, ?),
          `formatVerified` = MAX(`formatVerified`, ?),
          `discNo` = CASE WHEN `discNo` = 0 THEN ? ELSE `discNo` END,
          `updatedAt` = MAX(`updatedAt`, ?),
          `searchKey` = COALESCE(NULLIF(`searchKey`, ''), ?)
        WHERE `id` = ?
    """.trimIndent()

    /** 删掉被折叠行中「保留行已在同一歌单」的重复成员（否则下一步改指会撞唯一索引）。 */
    private val PURGE_COLLIDING_PLAYLIST_ITEMS = """
        DELETE FROM `playlist_items` WHERE `songId` = ? AND EXISTS (
          SELECT 1 FROM `playlist_items` p
          WHERE p.`playlistId` = `playlist_items`.`playlistId` AND p.`songId` = ?
        )
    """.trimIndent()

    /** 删掉被折叠行中「保留行已有同源同路径节点」的重复条目。 */
    private val PURGE_COLLIDING_FOLDER_ENTRIES = """
        DELETE FROM `folder_entry` WHERE `songId` = ? AND EXISTS (
          SELECT 1 FROM `folder_entry` e
          WHERE e.`sourceId` = `folder_entry`.`sourceId`
            AND e.`folderPath` = `folder_entry`.`folderPath`
            AND e.`songId` = ?
        )
    """.trimIndent()
}
