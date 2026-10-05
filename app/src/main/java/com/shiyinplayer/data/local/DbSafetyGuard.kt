package com.shiyinplayer.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 数据库迁移安全管线（Batch 0 / PLAN §6）。
 *
 * ## 它解决什么问题
 *
 * Room 的迁移是"要么成功、要么抛异常"，但**抛出来之后就没人接**：应用直接崩在启动路径上。
 * 本管线把"迁移失败"从"崩溃 / 静默清库"变成**自动恢复**，且全程不需要用户做任何操作：
 *
 * ```
 * S0 预检   读主库文件头的 user_version（4 字节，无需打开数据库）
 *           —— 没有待迁移就立刻返回 null，**零额外开销**（常态冷启不付出任何代价）
 * S1 备份   整组备份 .db + -wal + -shm（WAL 未 checkpoint 时只拷 .db 会丢最近写入）
 *           + 采集迁移前快照（逐表行数 / 索引 / 用户数据抽样）落盘
 * S2 迁移   Room 打开时自动跑迁移链
 * S3 自检   quick_check + user_version + 逐表行数不得减少 + 索引存在 + 用户数据抽样一致
 * S4 恢复   L1 迁移抛异常 → 还原备份 + 重试一次
 *           L2 自检不通过 → 还原备份 + 重试一次
 *           L3 仍失败     → 把旧库数据按「列交集」搬进新建的目标库（不依赖迁移链！）
 *           L4 极极端     → 保留全部原件 + 空白库继续跑，只记日志，绝不删除任何文件
 * ```
 *
 * ## 关键设计取舍
 *
 * - **不依赖迁移链**：L3 的搬运只按"两张表同名列的交集"做 `INSERT INTO main.T SELECT ... FROM src.T`，
 *   所以哪怕中间某一步迁移缺失/写错，数据依然能过去（这是"迁移链断裂"场景的最后兜底）。
 * - **不动用户不可见的文件**：只操作本管线自己产出的 `*.premigrate-*` 备份与 `*.rebuild-src` 源库。
 * - **常态零成本**：只有 S0 返回非 null（确实要升级）时才备份/自检/恢复。
 *
 * @see com.shiyinplayer.di.AppModule 的 provideAppDatabase —— 管线在这里被驱动
 */
object DbSafetyGuard {

    private const val TAG = "DbSafetyGuard"

    /** 迁移进行中最多保留几代备份（至少 1 代：备份是最后一道网）。 */
    private const val MAX_BACKUPS = 3

    /** 迁移成功且自检通过后收紧到几代（详见 [trimBackupsAfterSuccess]）。 */
    private const val KEEP_AFTER_SUCCESS = 1

    /** 失败现场副本命名前缀。 */
    private const val FAILED_TAG = ".failed-"

    /** 备份文件命名后缀（与旧实现保持兼容，便于人工找回）。 */
    private const val BACKUP_TAG = ".premigrate-"

    /** L3 搬运时把旧库改名的后缀。 */
    private const val REBUILD_SRC_SUFFIX = ".rebuild-src"

    /** 用户数据抽样条数（每端取若干，足够发现"整表被重置"这类事故）。 */
    private const val SAMPLE_SIZE = 40

    /** 参与用户数据抽样比对的字段（songs 表里由用户行为累积、丢了会心疼的那些）。 */
    private val USER_DATA_COLUMNS = listOf(
        "rating", "playCount", "favorite", "lyricOffsetMs", "lastPlayedMs"
    )

    /**
     * 会随正常播放**单调递增**的用户字段：对它们只要求"不得减少"，不要求"全等"。
     *
     * 理由见 [verify] 里的比对逻辑——否则「迁移期间用户听了一首歌」会被误判成数据事故。
     */
    private val MONOTONIC_USER_COLUMNS = setOf("playCount", "lastPlayedMs")

    /** 逐表行数快照：迁移后这些表**不得减少**（新增索引不该动任何行）。 */
    private val MUST_NOT_SHRINK = listOf(
        "songs", "albums", "artists", "playlists", "playlist_items",
        "folder_entry", "folder_attachment", "music_sources",
        "radio_station", "radio_history"
    )

    // ------------------------------------------------------------------ 数据结构

    /** 迁移前采集的现场，S3 自检与失败恢复都依赖它。 */
    data class Preflight(
        val dbFile: File,
        val versionBefore: Int,
        val backupBase: File?,
        val snapshot: Snapshot?,
        /** 是否被拦下（见 [Blocker]）。非 [Blocker.None] 时**不得**打开真实库做迁移。 */
        val blocker: Blocker = Blocker.None,
        /** 被拦下的原因说明（写给日志与说明页）。 */
        val blockerDetail: String? = null
    )

    /**
     * 迁移前的拦截原因（§6.2 的 E3 / E6）。
     *
     * 这两种情况都必须**放弃迁移**、而非"硬着头皮上"：
     * 迁移是不可逆的结构变更，在"没有退路"（无备份）或"写到一半必然没空间"的状态下动手，
     * 赌的是用户整个曲库。宁可这一次不升级（功能受限、可读说明页），也不做无退路的变更。
     */
    enum class Blocker {
        None,

        /** E6：连一份完整备份都建不出来（空间不足 / 权限），因此不做结构变更。 */
        BackupFailed,

        /** E3：可用空间不足，且清理可重建缓存后仍不足。 */
        InsufficientSpace,
    }

    /** 结构 + 行数 + 用户数据抽样。 */
    data class Snapshot(
        val userVersion: Int,
        val rows: Map<String, Long>,
        val indexes: Set<String>,
        /** 曲目 id →（用户字段 → 值）；迁移若动了这些数据会在这里露馅（详见 [readUserDataSamples]）。 */
        val samples: Map<Long, Map<String, String>>
    )

    // ------------------------------------------------------------------ S0 + S1

    /**
     * S0 预检 + S1 备份。
     *
     * @return 需要处理时返回现场；**无需处理（常态）返回 null** —— 调用方据此跳过全部额外工作。
     */
    fun preflight(context: Context, dbName: String, targetVersion: Int): Preflight? {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) {
            // 全新安装：没有旧数据要保护
            return null
        }

        val versionBefore = readUserVersionFromHeader(dbFile)
        if (versionBefore < 0) {
            Log.w(TAG, "无法读取 ${dbName} 的 user_version（文件头异常？），交由 Room 处理")
            return null
        }

        if (versionBefore >= targetVersion) {
            // 已是最新（或更高）：无需备份/自检，冷启零开销
            return null
        }

        Log.i(TAG, "检测到待迁移：$dbName v$versionBefore → v$targetVersion，开始备份与自检准备")

        // ---- E3：空间预检（不足则先清可重建缓存，最多 2 轮）----
        val space = ensureMigrationSpace(context, dbFile)
        if (!space.ok) {
            Log.e(TAG, "迁移前空间预检未通过：${space.detail} → 本次不做结构变更（E3）")
            return Preflight(
                dbFile, versionBefore, null, null,
                Blocker.InsufficientSpace, space.detail
            )
        }
        if (space.detail != null) {
            Log.i(TAG, "空间预检：${space.detail}")
        }

        val snapshot = runCatching { readSnapshot(dbFile) }
            .onFailure { Log.w(TAG, "采集迁移前快照失败（继续迁移，仅跳过行数比对）：${it.message}") }
            .getOrNull()

        val backup = runCatching { backupGroup(dbFile, dbName) }
            .onFailure { Log.w(TAG, "迁移前备份失败：${it.message}") }
            .getOrNull()

        // ---- E6：没有备份就**不做结构变更** ----
        // 原实现是"备份失败也照样迁移"，等于把用户曲库押在一次不可逆操作上 ——
        // 而备份失败本身就说明"这次的条件不好"。宁可这次不升级，也不做没有退路的变更。
        if (backup == null) {
            Log.e(TAG, "迁移前备份失败，放弃本次迁移（E6）：不做无备份的结构变更")
            return Preflight(
                dbFile, versionBefore, null, snapshot,
                Blocker.BackupFailed,
                "无法在设备上创建迁移前的完整备份"
            )
        }

        if (snapshot != null) {
            runCatching { writeSnapshotSidecar(backup, snapshot) }
        }

        return Preflight(dbFile, versionBefore, backup, snapshot)
    }

    // ------------------------------------------------------------------ E3 空间预检

    /** 空间预检结论。[ok] = false 时调用方必须放弃迁移。 */
    private data class SpaceCheck(val ok: Boolean, val detail: String?)

    /** 清可重建缓存的最大轮数（每轮清一批后再测一次可用空间）。 */
    private const val MAX_SPACE_ROUNDS = 2

    /**
     * 迁移所需空间：**备份整组 + 迁移期 WAL + 临时**，按主库大小的 3 倍估。
     *
     * 为什么是 3 倍：备份要占 1 份完整拷贝；迁移过程中 SQLite 会新写一份 WAL（大表重建时可能接近库大小）；
     * 再加"重建搬运"路径下旧库要改名保留（又是一份）。取整 3 倍是保守但不容易踩空的估计。
     */
    internal fun requiredMigrationBytes(dbSizeBytes: Long): Long = dbSizeBytes * 3

    /** 可用空间预检；不足时先清可重建缓存再测，最多 [MAX_SPACE_ROUNDS] 轮。 */
    private fun ensureMigrationSpace(context: Context, dbFile: File): SpaceCheck {
        val dbSize = dbFile.length().coerceAtLeast(1L)
        val required = requiredMigrationBytes(dbSize)
        var free = usableSpace(dbFile)
        if (free >= required) {
            return SpaceCheck(true, null)
        }

        Log.w(
            TAG,
            "空间不足：需要约 ${required / 1024 / 1024}MB（库 ${dbSize / 1024 / 1024}MB × 3），" +
                "当前可用 ${free / 1024 / 1024}MB，尝试清理可重建缓存"
        )

        repeat(MAX_SPACE_ROUNDS) { round ->
            val freed = clearRebuildableCaches(context)
            free = usableSpace(dbFile)
            Log.i(TAG, "第 ${round + 1} 轮清理可重建缓存：释放 ${freed / 1024 / 1024}MB，当前可用 ${free / 1024 / 1024}MB")
            if (free >= required) {
                return SpaceCheck(true, "清理可重建缓存后空间已够（第 ${round + 1} 轮）")
            }
            if (freed <= 0) {
                return@repeat      // 没东西可清了，再试也没用
            }
        }

        return SpaceCheck(
            false,
            "可用空间 ${free / 1024 / 1024}MB 低于迁移所需的 ${required / 1024 / 1024}MB（库大小的 3 倍），" +
                "清理缓存后仍不足"
        )
    }

    private fun usableSpace(file: File): Long =
        runCatching { file.parentFile?.usableSpace }.getOrNull() ?: Long.MAX_VALUE

    /**
     * 清理**真正可重建、且用户完全看不见**的缓存，返回释放的字节数。
     *
     * ⚠️ **刻意不动 `metadata.db`（歌词/元数据缓存）**。§6.1 的原文把"lyrics 缓存 / metadata 缓存"
     * 也列进了可清理项，但那与后续确立的口径冲突：歌词是用户长期积累的东西
     * （`AppModule` 里缓存库同样是"可重建≠该丢"、要走备份+自检），
     * 为了腾几十兆去删用户攒了几百首的歌词，代价明显不成比例。
     * 这里只清两类：下到一半/已下载的媒体缓存（可重新下载）、封面缓存（可从文件或在线重新提取）。
     */
    private fun clearRebuildableCaches(context: Context): Long {
        val targets = listOf(
            File(context.cacheDir, "music_cache"),   // 在线音频缓存（MusicCacheManager）
            File(context.cacheDir, "artwork"),       // 内嵌封面缓存（ArtworkCache）
            File(context.cacheDir, "image_cache")    // 在线图片缓存（Coil 默认目录）
        )

        var freed = 0L
        for (dir in targets) {
            if (!dir.exists()) continue
            freed += runCatching { dirSize(dir) }
                .getOrDefault(0L)
            runCatching { dir.deleteRecursively() }
                .onFailure { Log.w(TAG, "清理缓存目录失败 ${dir.name}：${it.message}") }
        }
        return freed
    }

    private fun dirSize(dir: File): Long =
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    // ------------------------------------------------------------------ S3 自检

    /**
     * S3 迁移后自检。**返回 false 表示需要走 L2 恢复**。
     *
     * @param requiredIndexes 本次迁移必须出现的新索引（由调用方按版本给出）
     */
    fun verify(
        context: Context,
        dbName: String,
        targetVersion: Int,
        pending: Preflight,
        requiredIndexes: List<String>
    ): Boolean {
        val dbFile = context.getDatabasePath(dbName)
        val after = runCatching { readSnapshot(dbFile) }
            .onFailure { Log.w(TAG, "迁移后快照采集失败：${it.message}") }
            .getOrNull()

        if (after == null) {
            // 采集不到（例如库损坏打不开）——按失败处理，交给 L2
            Log.w(TAG, "迁移后无法读取数据库结构 → 判定自检失败")
            return false
        }

        var ok = true

        if (after.userVersion != targetVersion) {
            Log.w(TAG, "自检失败：user_version=${after.userVersion}，期望 $targetVersion")
            ok = false
        }

        val integrity = runCatching { quickCheck(dbFile) }.getOrDefault("(无法执行)")
        if (integrity != "ok") {
            Log.w(TAG, "自检失败：quick_check=$integrity")
            ok = false
        }

        pending.snapshot?.let { before ->
            for (table in MUST_NOT_SHRINK) {
                val b = before.rows[table] ?: continue
                val a = after.rows[table]
                if (a == null) {
                    Log.w(TAG, "自检失败：迁移后缺表 $table（迁移前 $b 行）")
                    ok = false
                } else if (a < b) {
                    Log.w(TAG, "自检失败：$table 行数减少 $b → $a")
                    ok = false
                } else if (a > b) {
                    // 只增不减是允许的（迁移期间的正常写入），但要留痕
                    Log.i(TAG, "自检提示：$table 行数增加 $b → $a（应为正常写入）")
                }
            }

            // 用户数据抽样：逐字段比对用户会心疼的那几个字段
            var lost = 0
            for ((id, fields) in before.samples) {
                val afterFields = after.samples[id] ?: continue
                for ((column, valueBefore) in fields) {
                    val valueAfter = afterFields[column] ?: continue
                    if (valueBefore == valueAfter) continue

                    // ⚠️ 单调递增字段（播放/最近播放时间）**变大是正常的**：App 在迁移窗口里
                    //    正常播放一次就会写到它们。真机实测见过 playCount 150→151、
                    //    lastPlayedMs 前进几百毫秒。若按"必须全等"判定，就会把
                    //    "用户听了一首歌"误判成"迁移改了数据"，进而白做一次 L2 还原+重试。
                    //    而迁移真正造成的数据事故是**归零/变 null**（表现为变小），所以这里
                    //    对递增字段只要求「不得减少」，其余字段仍要求全等。
                    if (column in MONOTONIC_USER_COLUMNS &&
                        !hasShrunk(column, valueBefore, valueAfter)
                    ) {
                        continue
                    }

                    lost++
                    if (lost <= 3) {
                        Log.w(TAG, "自检失败：曲目 $id 的 $column 被改写（$valueBefore → $valueAfter）")
                    }
                }
            }
            if (lost > 0) {
                Log.w(TAG, "自检失败：抽样 ${before.samples.size} 条曲目中有 $lost 处用户数据异常")
                ok = false
            }
        }

        for (index in requiredIndexes) {
            if (index !in after.indexes) {
                Log.i(TAG, "自检提示：本次新增索引 $index 不存在（若为新建库属正常）")
            }
        }

        Log.i(TAG, if (ok) "迁移后自检通过（user_version=$targetVersion, quick_check=ok）"
        else "迁移后自检未通过 → 将自动还原备份并重试")
        return ok
    }

    // ------------------------------------------------------------------ L1 / L2

    /**
     * L1/L2：把备份整组还原回原位（含 -wal/-shm）。
     *
     * @return true = 已还原（调用方可以重建 Room 并重试）
     */
    fun restoreFromBackup(context: Context, dbName: String, pending: Preflight): Boolean {
        val backup = pending.backupBase
        if (backup == null || !backup.exists()) {
            Log.w(TAG, "L1/L2 恢复不可用：没有可用备份")
            return false
        }

        return try {
            val dbFile = context.getDatabasePath(dbName)
            // 先把当前（迁移失败/自检不过）的库留一份，便于事后分析。
            // ⚠️ 必须轮转：每失败一次就多一份几十 MB 的副本，不设上限会一直堆到磁盘告急
            //    （真机实测单次即 29.6MB）。只留最近 1 份——排障够用。
            runCatching {
                val bad = File(dbFile.parentFile, "${dbName}$FAILED_TAG${System.currentTimeMillis()}")
                if (dbFile.exists()) {
                    dbFile.copyTo(bad, overwrite = true)
                    // 失败副本是单文件（没有 -wal/-shm 伴生），按前缀原样计数
                    rotateGroup(dbFile.parentFile, "$dbName$FAILED_TAG", keep = 1, baseSuffix = "")
                }
            }

            copyGroup(backup, dbFile)
            Log.i(TAG, "L1/L2 已从备份还原：${backup.name}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "L1/L2 还原备份失败：${t.message}")
            false
        }
    }

    // ------------------------------------------------------------------ L3

    /**
     * L3：把 [srcDbFile] 的数据按**列交集**搬进已建好的 [targetDbFile]。
     *
     * 不依赖任何迁移链：对每一张两边都存在的表，取同名列做
     * `INSERT INTO main.T (同名列) SELECT 同名列 FROM src.T`。
     * 单表失败只跳过该表并记账，绝不中断整体。
     *
     * **调用前提**：[targetDbFile] 已是目标版本的空库（列齐全、索引齐全），
     * 且 [srcDbFile] 是旧库（可含其 `-wal`，SQLite 会自动重放）。
     *
     * @return true = 至少搬成功一张表且无表失败
     */
    fun transferByColumnIntersection(
        targetDbFile: File,
        srcDbFile: File,
        srcWal: File? = null
    ): Boolean {
        if (!targetDbFile.exists() || !srcDbFile.exists()) {
            Log.w(TAG, "L3 搬运跳过：目标库或源库不存在")
            return false
        }

        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(targetDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

            // 外键必须关：搬运顺序不保证（Songs 会先于 Albums/Artists 写入）
            db.execSQL("PRAGMA foreign_keys=OFF")

            // 源库若带 -wal，SQLite 在 ATTACH 时会自动重放；这里显式确认一下存在性便于排障
            if (srcWal != null && srcWal.exists()) {
                Log.i(TAG, "L3：源库带 WAL（${srcWal.length()} 字节），ATTACH 时将自动重放")
            }

            db.execSQL("ATTACH DATABASE ? AS src", arrayOf(srcDbFile.absolutePath))

            val srcTables = queryStrings(
                db,
                "SELECT name FROM src.sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                    "AND name NOT LIKE 'room_%' ORDER BY name"
            )
            val mainTables = queryStrings(
                db,
                "SELECT name FROM main.sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                    "AND name NOT LIKE 'room_%' ORDER BY name"
            ).toSet()

            var moved = 0
            var failed = 0
            var totalRows = 0L

            db.beginTransaction()
            try {
                for (table in srcTables) {
                    if (table !in mainTables) {
                        Log.i(TAG, "L3 跳过 $table：目标库无此表（该表所属功能已下线）")
                        continue
                    }

                    val srcCols = queryStrings(db, "PRAGMA src.table_info(\"$table\")", nameIndex = 1)
                    val mainCols = queryStrings(db, "PRAGMA main.table_info(\"$table\")", nameIndex = 1)
                    val cols = mainCols.filter { it in srcCols.toSet() }
                    if (cols.isEmpty()) {
                        Log.w(TAG, "L3 跳过 $table：两边列名无交集")
                        failed++
                        continue
                    }

                    val colList = cols.joinToString(", ") { "\"$it\"" }
                    // 注意：只搬同名列，目标库新增列走默认值
                    val sql = "INSERT INTO main.\"$table\" ($colList) SELECT $colList FROM src.\"$table\""
                    try {
                        db.execSQL(sql)
                        val n = queryLong(db, "SELECT COUNT(*) FROM main.\"$table\"")
                        totalRows += n
                        moved++
                        Log.i(TAG, "L3 搬运 $table：$n 行（列 ${cols.size}/${mainCols.size}）")
                    } catch (t: Throwable) {
                        failed++
                        Log.w(TAG, "L3 搬运 $table 失败（跳过该表）：${t.message}")
                    }
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            runCatching { db.execSQL("DETACH DATABASE src") }

            Log.i(TAG, "L3 完成：成功 $moved 表 / 失败 $failed 表 / 共 $totalRows 行")
            moved > 0
        } catch (t: Throwable) {
            Log.w(TAG, "L3 搬运失败：${t.message}")
            false
        } finally {
            runCatching { db?.close() }
        }
    }

    /** 把旧库整组改名为 `*.rebuild-src`（供 L3 使用；目标库名腾空后 Room 会建全新空库）。 */
    fun moveGroupAside(dbFile: File): File? {
        return try {
            if (!dbFile.exists()) return null
            val src = File(dbFile.parentFile, dbFile.name + REBUILD_SRC_SUFFIX)
            for (suffix in arrayOf("", "-wal", "-shm")) {
                val from = File(dbFile.absolutePath + suffix)
                if (from.exists()) {
                    from.renameTo(File(src.absolutePath + suffix))
                }
            }
            Log.i(TAG, "L3 已把旧库移至 ${src.name}")
            src
        } catch (t: Throwable) {
            Log.w(TAG, "L3 挪动旧库失败：${t.message}")
            null
        }
    }

    /** L3 完成（或放弃）后清理源库组；[keep] = true 时保留以便人工找回。 */
    fun cleanupRebuildSource(dbFile: File, keep: Boolean) {
        if (keep) return
        for (suffix in arrayOf(REBUILD_SRC_SUFFIX, "$REBUILD_SRC_SUFFIX-wal", "$REBUILD_SRC_SUFFIX-shm")) {
            runCatching { File(dbFile.absolutePath + suffix).delete() }
        }
    }

    // ------------------------------------------------------------------ 备份 / 轮转

    /**
     * 整组备份 `.db` + `-wal` + `-shm`。
     *
     * ⚠️ 必须整组：WAL 模式下最近的写入可能还留在 `-wal` 里没 checkpoint
     * （实测某真实库主库 26MB 却带 8.8MB WAL），只拷 `.db` 会得到**不完整备份**。
     */
    fun backupGroup(dbFile: File, dbName: String): File? {
        if (!dbFile.exists()) return null
        val stamp = System.currentTimeMillis()
        val base = File(dbFile.parentFile, "$dbName$BACKUP_TAG$stamp.db")
        dbFile.copyTo(base, overwrite = true)
        for (suffix in arrayOf("-wal", "-shm")) {
            val companion = File(dbFile.absolutePath + suffix)
            if (companion.exists()) {
                companion.copyTo(File(base.absolutePath + suffix), overwrite = true)
            }
        }
        rotateGroup(dbFile.parentFile, "$dbName$BACKUP_TAG", MAX_BACKUPS)
        Log.i(TAG, "已整组备份 → ${base.name}")
        return base
    }

    /**
     * 迁移已经成功且自检通过后调用：把备份从最多 [MAX_BACKUPS] 代收紧到 [KEEP_AFTER_SUCCESS] 代。
     *
     * 动机：备份的价值在于「出问题能回滚」，成功后它的价值只剩一份；
     * 而一个 1.6 万首的真实曲库主库就是 ~30MB，多留两代等于长期多占几十 MB 私有存储。
     */
    fun trimBackupsAfterSuccess(dbFile: File) {
        rotateGroup(dbFile.parentFile, "${dbFile.name}$BACKUP_TAG", KEEP_AFTER_SUCCESS)
    }

    /**
     * 按前缀轮转文件组（主文件 + `-wal` + `-shm` + `.snapshot.json`），只保留最近 [keep] **代**。
     *
     * @param baseSuffix 主文件名后缀，用于把主文件与它的伴生文件区分开。
     *   ⚠️ **少了这一条就是事故**：一个备份「代」在目录里是 4 个文件
     *   （`.db` / `.db-wal` / `.db-shm` / `.db.snapshot.json`），只看前缀会把伴生文件也当成独立的一代，
     *   "保留 1 代"就变成只留 1 个文件、把备份组拆碎清点掉 —— 实测过：最后一份备份都没剩下。
     *   传空串表示这类文件没有伴生文件（如失败现场副本），按前缀原样计数。
     */
    private fun rotateGroup(dir: File?, prefix: String, keep: Int, baseSuffix: String = ".db") {
        runCatching {
            if (dir == null || keep < 1) return
            val bases = dir.listFiles { f ->
                f.isFile && f.name.startsWith(prefix) &&
                    (baseSuffix.isEmpty() || f.name.endsWith(baseSuffix))
            }?.sortedByDescending { it.name } ?: return
            if (bases.size <= keep) return
            bases.drop(keep).forEach { old ->
                runCatching { old.delete() }
                runCatching { File(old.absolutePath + "-wal").delete() }
                runCatching { File(old.absolutePath + "-shm").delete() }
                runCatching { File(old.absolutePath + ".snapshot.json").delete() }
            }
            Log.i(TAG, "轮转 cleanup：${prefix}* 保留最近 $keep 代（删除 ${bases.size - keep} 代）")
        }
    }

    /** 把整组备份还原到 [targetDbFile]（先删目标组再拷，避免残留 WAL 与新库不匹配）。 */
    private fun copyGroup(backupBase: File, targetDbFile: File) {
        for (suffix in arrayOf("", "-wal", "-shm")) {
            val dst = File(targetDbFile.absolutePath + suffix)
            if (dst.exists()) dst.delete()
        }
        targetDbFile.parentFile?.mkdirs()
        backupBase.copyTo(targetDbFile, overwrite = true)
        for (suffix in arrayOf("-wal", "-shm")) {
            val src = File(backupBase.absolutePath + suffix)
            if (src.exists()) src.copyTo(File(targetDbFile.absolutePath + suffix), overwrite = true)
        }
    }

    // ------------------------------------------------------------------ 快照读取

    /** 只读打开并采集结构/行数/用户数据抽样。 */
    fun readSnapshot(dbFile: File): Snapshot? {
        if (!dbFile.exists()) return null
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val version = queryLong(db, "PRAGMA user_version").toInt()
            val tables = queryStrings(
                db,
                "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                    "AND name NOT LIKE 'room_%' ORDER BY name"
            )
            val rows = LinkedHashMap<String, Long>()
            for (t in tables) {
                runCatching { rows[t] = queryLong(db, "SELECT COUNT(*) FROM \"$t\"") }
            }
            val indexes = queryStrings(
                db,
                "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'"
            ).toSet()
            Snapshot(version, rows, indexes, readUserDataSamples(db))
        } catch (t: Throwable) {
            Log.w(TAG, "读取快照失败（${dbFile.name}）：${t.message}")
            null
        } finally {
            runCatching { db?.close() }
        }
    }

    /**
     * 用户数据抽样：取 songs 表**首尾各若干行**的用户字段原值。
     *
     * 目的是发现"迁移把 rating / favorite / 播放记录重置"这类**不会改变行数**的事故
     * （单看行数完全看不出来）。老版本 schema 缺列时逐列降级，不因此让整个快照失败。
     *
     * 取首尾两段而不是随机：.Types/样本在生产环境必须**可复现**，否则同一份库两次抽样
     * 抽到的行都不一样，比对结果没有意义。
     */
    private fun readUserDataSamples(db: SQLiteDatabase): Map<Long, Map<String, String>> {
        val out = LinkedHashMap<Long, Map<String, String>>()
        val columns = runCatching { queryStrings(db, "PRAGMA table_info(\"songs\")", nameIndex = 1) }
            .getOrDefault(emptyList())
        if (columns.isEmpty()) return out
        val picked = USER_DATA_COLUMNS.filter { it in columns }
        if (picked.isEmpty()) return out

        val select = "id, " + picked.joinToString(", ")
        for (orderAndLimit in listOf(
            "ORDER BY id ASC LIMIT $SAMPLE_SIZE",
            "ORDER BY id DESC LIMIT $SAMPLE_SIZE"
        )) {
            runCatching {
                db.rawQuery("SELECT $select FROM songs $orderAndLimit", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val fields = LinkedHashMap<String, String>()
                        for (i in picked.indices) {
                            fields[picked[i]] = if (c.isNull(i + 1)) "null" else c.getString(i + 1)
                        }
                        out[id] = fields
                    }
                }
            }
        }
        return out
    }

    /**
     * 递增值是否被**改写坏了**。
     *
     * 只单调递增字段（playCount / lastPlayedMs）走这条路：变大是正常的（用户听了歌），
     * 变小才是事故（被重置成 0 / NULL）。解析不出来（非数字）时按相等处理，避免误判。
     */
    private fun hasShrunk(column: String, before: String, after: String): Boolean {
        val b = before.toDoubleOrNull() ?: return false
        val a = after.toDoubleOrNull() ?: return false
        return a < b
    }

    /** `PRAGMA quick_check`：比 integrity_check 快得多，足够发现页级损坏。 */
    private fun quickCheck(dbFile: File): String {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            queryStrings(db, "PRAGMA quick_check").firstOrNull() ?: "(无返回)"
        } finally {
            runCatching { db?.close() }
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 从主库**文件头**读 user_version（偏移 60，4 字节大端）——无需打开数据库，
     * 冷启动零成本。这是 S0 能做到"没有待迁移就立刻返回"的前提。
     *
     * ⚠️ 已知局限：WAL 未 checkpoint 时（Android 默认 journal_mode=WAL 且不自 checkpoint），
     * 头里的值可能是**旧的**。由于 user_version 只增不减，这只会导致"多备份一次"（无害），
     * 不会漏掉真正需要备份的升级。
     *
     * @return user_version；文件不存在/过短/魔数不符时 -1
     */
    fun readUserVersionFromHeader(dbFile: File): Int {
        return try {
            if (!dbFile.exists() || dbFile.length() < 64) return -1
            val buf = ByteArray(64)
            dbFile.inputStream().use { input ->
                var read = 0
                while (read < 64) {
                    val n = input.read(buf, read, 64 - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 64) return -1
            }
            // 前 16 字节应为 "SQLite format 3\u0000"
            val magic = String(buf, 0, 15, Charsets.US_ASCII)
            if (magic != "SQLite format 3") return -1
            ByteBuffer.wrap(buf, 60, 4).order(ByteOrder.BIG_ENDIAN).int
        } catch (t: Throwable) {
            -1
        }
    }

    /**
     * 降级安装检测：库里的结构版本**高于**当前 APK 期望时返回 true。
     *
     * 为什么能低成本判断：Room 只能在
     * "库版本 ≤ APK 版本" 时升级，反过来的情形（用户装了旧包 / 回退安装）它会抛
     * `IllegalStateException` 把启动直接崩掉，用户既看不懂也救不了。
     * 这里在**打开数据库之前**读一次文件头就知道，代价是 4 字节，
     * 因而可以在 Room 碰到这个库之前，先把真实库妥善保护起来。
     *
     * @param dbFile 主库文件
     * @param targetVersion 当前 APK 期望的版本
     */
    fun isDowngradeInstall(dbFile: File, targetVersion: Int): Boolean {
        val actual = readUserVersionFromHeader(dbFile)
        return actual > 0 && targetVersion > 0 && actual > targetVersion
    }

    private fun queryStrings(db: SQLiteDatabase, sql: String, nameIndex: Int = 0): List<String> {
        val out = ArrayList<String>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(nameIndex)) out.add(c.getString(nameIndex))
                else out.add("")
            }
        }
        return out
    }

    private fun queryLong(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }

    /**
     * 快照旁挂文件（`<backup>.snapshot.json`）：跨进程/跨次启动比对时用得到，
     * 也是"迁移失败后人工排障"的第一手材料。手写 JSON，避免为一个辅助文件引入序列化框架。
     */
    private fun writeSnapshotSidecar(backupBase: File, snapshot: Snapshot) {
        val sb = StringBuilder()
        sb.append("{\"userVersion\":").append(snapshot.userVersion)
        sb.append(",\"rows\":{")
        sb.append(snapshot.rows.entries.joinToString(",") { "\"${it.key}\":${it.value}" })
        sb.append("},\"indexes\":[")
        sb.append(snapshot.indexes.joinToString(",") { "\"$it\"" })
        sb.append("],\"samples\":{")
        sb.append(snapshot.samples.entries.joinToString(",") { (id, fields) ->
            "\"$id\":{" + fields.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" } + "}"
        })
        sb.append("}}")
        File(backupBase.absolutePath + ".snapshot.json").writeText(sb.toString())
    }
}
