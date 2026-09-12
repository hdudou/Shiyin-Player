package com.shiyinplayer.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 7 → 8：新增文件夹 tab 预生成的目录树表 folder_entry。
 * 在曲目扫描时由 FolderStructureBuilder 重建填充，文件夹 tab 直接按 (sourceId, parentPath) 查询，
 * 不再每次打开遍历全曲库。新安装与升级均在新版本首次扫描后填充；升级旧库表缺失为正常状态。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `folder_entry` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `sourceId` INTEGER NOT NULL,
              `parentPath` TEXT NOT NULL,
              `name` TEXT NOT NULL,
              `folderPath` TEXT NOT NULL,
              `isDir` INTEGER NOT NULL,
              `songCount` INTEGER NOT NULL,
              `songId` INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_folder_entry_sourceId_parentPath` ON `folder_entry` (`sourceId`,`parentPath`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_folder_entry_sourceId_isDir_folderPath` ON `folder_entry` (`sourceId`,`isDir`,`folderPath`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_folder_entry_folderPath` ON `folder_entry` (`folderPath`)")
    }
}