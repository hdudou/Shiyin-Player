package com.shiyinplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.shiyinplayer.data.local.dao.AlbumDao
import com.shiyinplayer.data.local.dao.ArtistDao
import com.shiyinplayer.data.local.dao.FolderAttachmentDao
import com.shiyinplayer.data.local.dao.FolderEntryDao
import com.shiyinplayer.data.local.dao.MusicSourceDao
import com.shiyinplayer.data.local.dao.PlaylistDao
import com.shiyinplayer.data.local.dao.PlaylistItemDao
import com.shiyinplayer.data.local.dao.RadioHistoryDao
import com.shiyinplayer.data.local.dao.RadioStationDao
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.local.entity.AlbumEntity
import com.shiyinplayer.data.local.entity.ArtistEntity
import com.shiyinplayer.data.local.entity.FolderAttachmentEntity
import com.shiyinplayer.data.local.entity.FolderEntryEntity
import com.shiyinplayer.data.local.entity.MusicSourceEntity
import com.shiyinplayer.data.local.entity.PlaylistEntity
import com.shiyinplayer.data.local.entity.PlaylistItemEntity
import com.shiyinplayer.data.local.entity.RadioHistoryEntity
import com.shiyinplayer.data.local.entity.RadioStationEntity
import com.shiyinplayer.data.local.entity.SongEntity

@Database(
    entities = [
        SongEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        MusicSourceEntity::class,
        FolderEntryEntity::class,
        FolderAttachmentEntity::class,
        RadioStationEntity::class,
        RadioHistoryEntity::class
    ],
    version = com.shiyinplayer.util.Constants.DATABASE_VERSION,
    // P0-2：导出 schema JSON（app/schemas），迁移链与实体漂移可纳入 CI 校验
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistItemDao(): PlaylistItemDao
    abstract fun musicSourceDao(): MusicSourceDao
    abstract fun folderEntryDao(): FolderEntryDao
    abstract fun folderAttachmentDao(): FolderAttachmentDao
    abstract fun radioStationDao(): RadioStationDao
    abstract fun radioHistoryDao(): RadioHistoryDao
}
