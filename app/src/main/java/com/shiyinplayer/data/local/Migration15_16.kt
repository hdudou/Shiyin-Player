package com.shiyinplayer.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v15 → v16：为两张表的「按 songId 查询」补索引（纯索引变更，无数据搬迁）。
 *
 * 动机：`playlist_items.songId` 上有唯一复合索引 `(playlistId, songId)`，
 * 但按 songId 单列过滤（删曲目 / 删源时清理歌单引用：
 * `WHERE songId = ?` / `songId IN (...)` / `UPDATE ... SET songId`）用不到该复合索引的最左前缀，
 * 只能全表扫描；大歌单 + 大曲库下每次删曲都要扫整张关联表。
 *
 * 该迁移只需建索引，对已有数据零影响；`IF NOT EXISTS` 保证重复执行安全。
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_playlist_items_songId` " +
                "ON `playlist_items` (`songId`)"
        )
        Log.i(TAG, "playlist_items.songId 索引已建立（按曲目清理歌单引用不再全表扫描）")
    }
}

/**
 * 缓存库 v2 → v3：`lyrics.songId` 补索引。
 *
 * 播放页每次加载歌词都按 `WHERE songId = ?` 查（绑定歌词优先），删曲目时按
 * `songId IN (...)` 清缓存 —— 无索引时同样是全表扫描。
 */
val MIGRATION_METADATA_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_lyrics_songId` ON `lyrics` (`songId`)")
        Log.i(TAG, "lyrics.songId 索引已建立")
    }
}

private const val TAG = "Migration15_16"
