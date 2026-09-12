package com.shiyinplayer.data.local.cache

import androidx.room.Database
import androidx.room.RoomDatabase

/** 歌词/元数据缓存库（独立于主曲库，避免升级清空扫描结果）。
 *  version 2（2026-08-19）：lyrics 表加 songId 列（与主库歌曲条目绑定，绑定后永不过期）。
 */
@Database(
    entities = [LyricCacheEntity::class, MetadataCacheEntity::class],
    version = 2,
    // P0-2：缓存库同样导出 schema，纳入 CI 校验
    exportSchema = true
)
abstract class MetadataDatabase : RoomDatabase() {
    abstract fun lyricCacheDao(): LyricCacheDao
    abstract fun metadataCacheDao(): MetadataCacheDao
}