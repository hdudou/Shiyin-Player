package com.shiyinplayer.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移集合（exportSchema = true：Room 将 schema JSON 导出至 app/schemas 供 CI 校验，
 * 迁移 SQL 必须与实体注解保持一致）。
 */

/**
 * v1 → v3（P0-2 修复）：把早期 v1 旧库"无损收敛"到 v3 结构。
 *
 * 推断依据（项目为开发期工程 versionCode=1，磁盘无 v1/v2 schema JSON 历史，只能由当前
 * 实体与既有迁移反向推断）：
 * 1. v3 = 当前 v5 schema − formatVerified（4→5 新增）− songs 七组索引（3→4 新增），
 *    即六个实体表（songs/albums/artists/playlists/playlist_items/music_sources）在 v3 时
 *    结构与当前实体完全一致（v3→v4 只加索引、v4→v5 只加 formatVerified）；
 * 2. 实体字段注释表明 CUE 分轨（T15：cueId/trackIndex/clipStartMs/clipEndMs）、
 *    去重键（T12：dedupKey）、P2 增强列（genre/year/rating/playCount/lastPlayedMs）与
 *    多源表 music_sources（T14）均属后期追加，v1/v2 很可能缺少其中部分列/表；
 * 3. 无法 100% 还原 v1/v2 真实 DDL，故采用"探测 + 补齐"策略：PRAGMA table_info 读取旧库
 *    实际列，缺列则 ALTER TABLE ADD COLUMN（NOT NULL 列带安全默认值），缺表则
 *    CREATE TABLE IF NOT EXISTS 重建 v3 结构；已存在且结构兼容的行数据完整保留。
 *    若旧库存在当前实体已删除的未知列（SQLite 无法安全删列），Room 迁移后 schema 校验会
 *    显式抛错（不会静默清空，DB 文件保留），属开发期可接受的兜底。
 */
val MIGRATION_1_3 = object : Migration(1, 3) {
    override fun migrate(db: SupportSQLiteDatabase) = reconcileToV3(db)
}

/** v2 → v3（P0-2 修复）：同上，把 v2 旧库无损收敛到 v3 结构。 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) = reconcileToV3(db)
}

/**
 * 3 → 4：为 songs 表补齐查询/排序索引（item [8] 加载优化，DATABASE_VERSION=4）。
 * 索引名遵循 Room 约定 index_<表>_<列>，与 [SongEntity] 的 @Index 完全一致，保留已有曲库数据。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_songs_dedupKey` ON `songs` (`dedupKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_title` ON `songs` (`title`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_artistName` ON `songs` (`artistName`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_albumName` ON `songs` (`albumName`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_sourceType` ON `songs` (`sourceType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_albumId` ON `songs` (`albumId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_artistId` ON `songs` (`artistId`)")
    }
}

/**
 * 4 → 5：为 songs 表新增 formatVerified 列（P0 解码扩展，magic 校验结果，DATABASE_VERSION=5）。
 * 存量数据默认 true（视为已校验），保留已有曲库数据。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `formatVerified` INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * 5 → 6：为 songs 表新增 lyricOffsetMs 列（§12 歌词时间手动校正，DATABASE_VERSION=6）。
 * 存量数据默认 0（不偏移），保留已有曲库数据。
 *
 * 2026-08-19 修复：同时清理运行时遗留的表达式索引 index_songs_mergekey_expr。
 * 该索引由旧版 AppModule.onOpen 创建（实体未声明），导致 Room 打开校验报
 * "Migration didn't properly handle: songs" 而启动闪退；旧库（含 v5）一律 DROP 清理，
 * 新库不再创建（AppModule 已移除 onOpen 建索引逻辑）。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 幂等防护：Room 迁移按事务提交、校验失败会回滚（列不会残留），但若出现任何半迁移/手动改库
        // 导致列已存在，重复 ALTER 会抛 "duplicate column name" 并触发 destructive 清库，故先探测。
        if (!columnExists(db, "songs", "lyricOffsetMs")) {
            db.execSQL("ALTER TABLE `songs` ADD COLUMN `lyricOffsetMs` INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL("DROP INDEX IF EXISTS `index_songs_mergekey_expr`")
    }
}

/**
 * 6 → 7：删除 songs 表 isFavorite 列（需求 8 收藏整链删除，DATABASE_VERSION=7）。
 * SQLite DROP COLUMN（3.35+）。该列无索引、无外键依赖，可安全删除；幂等探测防半迁移/新表无此列。
 * 收藏整链删除后实体不再含该字段，Room 期望 schema 也一并移除，故此处必须 DROP 才能满足校验。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (columnExists(db, "songs", "isFavorite")) {
            db.execSQL("ALTER TABLE `songs` DROP COLUMN `isFavorite`")
        }
    }
}

/** 探测表中是否存在指定列（PRAGMA table_info）。 */
private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
    db.query("PRAGMA table_info(\"$table\")").use { cursor ->
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == column) return true
        }
    }
    return false
}

/** v1/v2 → v3：缺表建表 + 缺列补列，无损收敛到 v3 结构（不建 v4 索引、不加 v5 formatVerified）。 */
private fun reconcileToV3(db: SupportSQLiteDatabase) {
    createTablesIfAbsent(db)
    ensureSongsColumns(db)
    // 旧行 dedupKey 回填：ADD COLUMN 后存量行 dedupKey 全为 ''，若不回填，
    // 后续 MIGRATION_3_4 的 UNIQUE index_songs_dedupKey 会因多行空键创建失败。
    // 以 'legacy:'+id 生成逐行唯一键（id 为主键），旧库每行成为独立合并组，行为正确且不丢数据。
    db.execSQL(
        "UPDATE `songs` SET `dedupKey` = 'legacy:' || `id` WHERE `dedupKey` IS NULL OR `dedupKey` = ''"
    )
    // playlist_items 旧表若存在重复 (playlistId, songId) 行，先按主键保留每组首行去重，
    // 否则下方 UNIQUE 索引创建会失败；对全新表/无重复表是幂等空操作。
    db.execSQL(
        "DELETE FROM `playlist_items` WHERE `id` NOT IN " +
            "(SELECT MIN(`id`) FROM `playlist_items` GROUP BY `playlistId`, `songId`)"
    )
}

private fun createTablesIfAbsent(db: SupportSQLiteDatabase) {
    // 六表 DDL 与当前实体一致（songs 去掉 formatVerified，v4 索引由 MIGRATION_3_4 负责）
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `songs` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `title` TEXT NOT NULL,
          `artistId` INTEGER,
          `albumId` INTEGER,
          `artistName` TEXT,
          `albumName` TEXT,
          `albumArtUri` TEXT,
          `durationMs` INTEGER NOT NULL,
          `trackNumber` INTEGER NOT NULL,
          `uri` TEXT NOT NULL,
          `mimeType` TEXT,
          `sourceType` TEXT NOT NULL,
          `path` TEXT,
          `dateAdded` INTEGER NOT NULL,
          `sizeBytes` INTEGER NOT NULL,
          `cueId` TEXT,
          `trackIndex` INTEGER,
          `clipStartMs` INTEGER,
          `clipEndMs` INTEGER,
          `dedupKey` TEXT NOT NULL,
          `genre` TEXT,
          `year` INTEGER,
          `rating` INTEGER NOT NULL,
          `playCount` INTEGER NOT NULL,
          `lastPlayedMs` INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `albums` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` TEXT NOT NULL,
          `artistName` TEXT,
          `albumArtUri` TEXT,
          `year` INTEGER,
          `songCount` INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `artists` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` TEXT NOT NULL,
          `albumCount` INTEGER NOT NULL,
          `songCount` INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `playlists` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` TEXT NOT NULL,
          `dateCreated` INTEGER NOT NULL,
          `dateModified` INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `playlist_items` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `playlistId` INTEGER NOT NULL,
          `songId` INTEGER NOT NULL,
          `position` INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `music_sources` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` TEXT NOT NULL,
          `type` TEXT NOT NULL,
          `configJson` TEXT NOT NULL,
          `enabled` INTEGER NOT NULL,
          `lastScanTime` INTEGER NOT NULL
        )
        """.trimIndent()
    )
    // playlist_items 唯一索引在 v3 已存在（3→4 未涉及），旧表若缺则补齐；IF NOT EXISTS 幂等
    db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_items_playlistId_songId` " +
            "ON `playlist_items` (`playlistId`, `songId`)"
    )
}

/** songs 表 v3 列全集：缺列则 ALTER TABLE ADD COLUMN（NOT NULL 列带安全默认值，兼容旧库缺少后加列）。 */
private fun ensureSongsColumns(db: SupportSQLiteDatabase) {
    ensureColumn(db, "songs", "title", "`title` TEXT NOT NULL DEFAULT ''")
    ensureColumn(db, "songs", "artistId", "`artistId` INTEGER")
    ensureColumn(db, "songs", "albumId", "`albumId` INTEGER")
    ensureColumn(db, "songs", "artistName", "`artistName` TEXT")
    ensureColumn(db, "songs", "albumName", "`albumName` TEXT")
    ensureColumn(db, "songs", "albumArtUri", "`albumArtUri` TEXT")
    ensureColumn(db, "songs", "durationMs", "`durationMs` INTEGER NOT NULL DEFAULT 0")
    ensureColumn(db, "songs", "trackNumber", "`trackNumber` INTEGER NOT NULL DEFAULT 0")
    ensureColumn(db, "songs", "uri", "`uri` TEXT NOT NULL DEFAULT ''")
    ensureColumn(db, "songs", "mimeType", "`mimeType` TEXT")
    ensureColumn(db, "songs", "sourceType", "`sourceType` TEXT NOT NULL DEFAULT 'LOCAL'")
    ensureColumn(db, "songs", "path", "`path` TEXT")
    ensureColumn(db, "songs", "dateAdded", "`dateAdded` INTEGER NOT NULL DEFAULT 0")
    ensureColumn(db, "songs", "sizeBytes", "`sizeBytes` INTEGER NOT NULL DEFAULT 0")
    ensureColumn(db, "songs", "cueId", "`cueId` TEXT")
    ensureColumn(db, "songs", "trackIndex", "`trackIndex` INTEGER")
    ensureColumn(db, "songs", "clipStartMs", "`clipStartMs` INTEGER")
    ensureColumn(db, "songs", "clipEndMs", "`clipEndMs` INTEGER")
    ensureColumn(db, "songs", "dedupKey", "`dedupKey` TEXT NOT NULL DEFAULT ''")
    ensureColumn(db, "songs", "genre", "`genre` TEXT")
    ensureColumn(db, "songs", "year", "`year` INTEGER")
    ensureColumn(db, "songs", "rating", "`rating` INTEGER NOT NULL DEFAULT 0")
    ensureColumn(db, "songs", "playCount", "`playCount` INTEGER NOT NULL DEFAULT 0")
    ensureColumn(db, "songs", "lastPlayedMs", "`lastPlayedMs` INTEGER NOT NULL DEFAULT 0")
}

/** 探测旧表实际列（PRAGMA table_info），缺列才 ADD COLUMN；已存在则跳过（保留数据）。 */
private fun ensureColumn(db: SupportSQLiteDatabase, table: String, column: String, ddl: String) {
    val exists = db.query("PRAGMA table_info(\"$table\")").use { cursor ->
        var found = false
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == column) {
                found = true
                break
            }
        }
        found
    }
    if (!exists) {
        db.execSQL("ALTER TABLE `$table` ADD COLUMN $ddl")
    }
}

/**
 * 2026-08-19：metadata.db（歌词/元数据缓存库）v1→v2——lyrics 表加 songId 列
 * （与主库歌曲条目绑定，绑定后歌词永不过期）。幂等探测避免重复加列。
 */
val MIGRATION_METADATA_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ensureColumn(db, "lyrics", "songId", "`songId` INTEGER")
    }
}

/** v9 → v10：新增收音机电台表 + 播放历史表。 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `radio_station` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `logoUrl` TEXT,
                `genre` TEXT,
                `country` TEXT,
                `source` TEXT NOT NULL DEFAULT 'user',
                `createdAt` INTEGER NOT NULL DEFAULT 0,
                `updatedAt` INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `radio_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `stationId` INTEGER NOT NULL,
                `listenedSeconds` INTEGER NOT NULL DEFAULT 0,
                `lastPlayedAt` INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(`stationId`) REFERENCES `radio_station`(`id`) ON DELETE CASCADE
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_radio_history_stationId` ON `radio_history` (`stationId`)")
    }
}

/** v10 → v11：为 radio_station 表新增 isFavorite 列。 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `radio_station` ADD COLUMN `isFavorite` INTEGER NOT NULL DEFAULT 0")
    }
}

/** v11 → v12（F6-2）：为 songs 表新增拼音搜索索引列 searchKey（扫描/导入时计算）。非破坏，旧数据保空串。 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `searchKey` TEXT NOT NULL DEFAULT ''")
    }
}
