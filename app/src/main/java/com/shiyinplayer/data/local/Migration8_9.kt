package com.shiyinplayer.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 8 → 9：新增文件夹附件表 folder_attachment（专辑封面图 / 专辑说明 txt）。
 * 在曲目扫描遍历目录时收集，文件夹 tab 进入对应目录时展示并可预览。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `folder_attachment` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `sourceId` INTEGER NOT NULL,
              `parentPath` TEXT NOT NULL,
              `name` TEXT NOT NULL,
              `kind` INTEGER NOT NULL,
              `uri` TEXT NOT NULL,
              `size` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_folder_attachment_sourceId_parentPath` ON `folder_attachment` (`sourceId`,`parentPath`)")
    }
}