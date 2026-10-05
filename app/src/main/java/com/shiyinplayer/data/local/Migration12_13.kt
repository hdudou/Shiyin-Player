package com.shiyinplayer.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v12 → v13：局域网同步（PC 主控 / 安卓接收端）所需字段补齐。
 *
 * 背景见 `docs/SYNC_MODULE_IMPLEMENTATION_PLAN.md` 与契约 `android-sync-module.md` §4：
 * PC 推送的 record 字段比现有实体多，需新增列承载（缺失则 PC 推送数据丢失）。
 * 全部为**非破坏性 ADD COLUMN**（NOT NULL 列带安全默认值），保留已有曲库数据。
 *
 * 注意：
 * - songs 新增列命名为契约名 `favorite`（历史 `isFavorite` 已在 MIGRATION_6_7 被 DROP，勿复用旧名）。
 * - radio_station 的 `alternateUrls`（备用流地址数组）与既有 `logoUrl`（封面图）语义不同，两者并存。
 * - 迁移 SQL 必须与实体注解逐列一致，否则 Room 打开时会抛 schema 校验错误。
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- songs：收藏 / 光盘号 / 更新时间 ----
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `discNo` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")

        // ---- albums：艺术家主键 / 更新时间 ----
        db.execSQL("ALTER TABLE `albums` ADD COLUMN `artistId` INTEGER")
        db.execSQL("ALTER TABLE `albums` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")

        // ---- artists：更新时间 ----
        db.execSQL("ALTER TABLE `artists` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")

        // ---- radio_station：备用流 / 码率 / 收听次数 / 最后播放时间 ----
        db.execSQL("ALTER TABLE `radio_station` ADD COLUMN `alternateUrls` TEXT")
        db.execSQL("ALTER TABLE `radio_station` ADD COLUMN `bitrate` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `radio_station` ADD COLUMN `listenCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `radio_station` ADD COLUMN `lastPlayedAt` INTEGER NOT NULL DEFAULT 0")

        // ---- music_sources：创建 / 更新时间 ----
        db.execSQL("ALTER TABLE `music_sources` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `music_sources` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
    }
}
