package com.shiyinplayer.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shiyinplayer.data.local.AppDatabase
import com.shiyinplayer.data.local.MIGRATION_1_3
import com.shiyinplayer.data.local.MIGRATION_2_3
import com.shiyinplayer.data.local.MIGRATION_3_4
import com.shiyinplayer.data.local.MIGRATION_4_5
import com.shiyinplayer.data.local.MIGRATION_5_6
import com.shiyinplayer.data.local.MIGRATION_6_7
import com.shiyinplayer.data.local.MIGRATION_METADATA_1_2
import com.shiyinplayer.data.local.MIGRATION_7_8
import com.shiyinplayer.data.local.MIGRATION_8_9
import com.shiyinplayer.data.local.MIGRATION_9_10
import com.shiyinplayer.data.local.MIGRATION_10_11
import com.shiyinplayer.data.local.MIGRATION_11_12
import com.shiyinplayer.data.local.cache.LyricCacheDao
import com.shiyinplayer.data.local.cache.MetadataCacheDao
import com.shiyinplayer.data.local.cache.MetadataDatabase
import com.shiyinplayer.data.local.dao.AlbumDao
import com.shiyinplayer.data.local.dao.ArtistDao
import com.shiyinplayer.data.local.dao.FolderEntryDao
import com.shiyinplayer.data.local.dao.FolderAttachmentDao
import com.shiyinplayer.data.local.dao.MusicSourceDao
import com.shiyinplayer.data.local.dao.PlaylistDao
import com.shiyinplayer.data.local.dao.PlaylistItemDao
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.util.Constants
import com.shiyinplayer.util.DefaultDispatcherProvider
import com.shiyinplayer.util.DispatcherProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = Constants.DATASTORE_SETTINGS)

/**
 * 应用级依赖：Context、Room 数据库与 DAO、DataStore、调度器。
 * 其他可 @Inject 构造的仓库/管理器无需在此声明。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, Constants.DATABASE_NAME)
            // P0-2：迁移链覆盖 1→5（v1/v2 旧库无损收敛到 v3，再经 3→4/4→5），不再静默清空早期安装。
            .addMigrations(MIGRATION_1_3, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            // 兜底：仅当旧库版本/结构与迁移链均不匹配时触发 destructive（此时无可用迁移路径），
            // 由下方 onDestructiveMigration 记录日志，杜绝"静默清空"。
            .fallbackToDestructiveMigration()
            .addCallback(object : RoomDatabase.Callback() {
                // 注意：不再在 onOpen 创建 index_songs_mergekey_expr 表达式索引（2026-08-19 修复）。
                // Room 每次打开（含无迁移时）都会对库做全量 schema 校验（表 + 列 + 索引集合），
                // 运行时把实体未声明的索引落进库文件，会在"第二次及以后"打开时触发
                // "Migration didn't properly handle: songs"，导致启动即闪退。
                // 合并查询 observeMergedPrimaries/Alts 改为无该表达式索引执行（GROUP BY 全扫），
                // 功能不变，仅大曲库下略慢；如需优化请落真实 mergeKey 列（见 Migrations.kt 注释）。

                /** P0-2：destructive 迁移发生时显式记录日志（曲库将被重建，数据不可恢复）。 */
                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    // 先备份旧库文件，使即便触发兜底清库，数据仍可恢复（P0-2 加固）。
                    val backup = backupDbFile(db)
                    Log.w(
                        "AppModule",
                        "AppDatabase 触发 destructive migration：旧库版本/结构不在迁移链内，曲库将被重建。" +
                            (if (backup != null) " 旧库已备份至：$backup" else "（备份失败）")
                    )
                }
            })
            .build()
        return db
    }

    @Provides
    @Singleton
    fun provideMetadataDatabase(@ApplicationContext context: Context): MetadataDatabase =
        Room.databaseBuilder(context, MetadataDatabase::class.java, Constants.DATASTORE_METADATA_DB)
            // P0-2：缓存库（歌词/元数据）无历史迁移链，内容可随时重建；
            // 2026-08-19：v1→v2 显式迁移（lyrics 加 songId 列）——否则 fallbackToDestructiveMigration
            // 会清空已缓存歌词，违背「歌词与歌曲绑定后永不过期」需求。
            .addMigrations(MIGRATION_METADATA_1_2)
            // 仍保留 destructive 兜底（后续版本未覆盖时）但记录日志而非静默。
            .fallbackToDestructiveMigration()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    Log.w("AppModule", "MetadataDatabase 触发 destructive migration（缓存库，将自动重建）")
                }
            })
            .build()

    @Provides
    fun provideLyricCacheDao(db: MetadataDatabase): LyricCacheDao = db.lyricCacheDao()

    @Provides
    fun provideMetadataCacheDao(db: MetadataDatabase): MetadataCacheDao = db.metadataCacheDao()

    @Provides
    fun provideSongDao(db: AppDatabase): SongDao = db.songDao()

    @Provides
    fun provideAlbumDao(db: AppDatabase): AlbumDao = db.albumDao()

    @Provides
    fun provideArtistDao(db: AppDatabase): ArtistDao = db.artistDao()

    @Provides
    fun providePlaylistDao(db: AppDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun providePlaylistItemDao(db: AppDatabase): PlaylistItemDao = db.playlistItemDao()

    @Provides
    fun provideFolderEntryDao(db: AppDatabase): FolderEntryDao = db.folderEntryDao()

    @Provides
    fun provideFolderAttachmentDao(db: AppDatabase): FolderAttachmentDao = db.folderAttachmentDao()

    @Provides
    fun provideMusicSourceDao(db: AppDatabase): MusicSourceDao = db.musicSourceDao()

    @Provides
    fun provideRadioStationDao(db: AppDatabase): com.shiyinplayer.data.local.dao.RadioStationDao = db.radioStationDao()

    @Provides
    fun provideRadioHistoryDao(db: AppDatabase): com.shiyinplayer.data.local.dao.RadioHistoryDao = db.radioHistoryDao()

    @Provides
    @Singleton
    fun provideRadioRepository(
        stationDao: com.shiyinplayer.data.local.dao.RadioStationDao,
        historyDao: com.shiyinplayer.data.local.dao.RadioHistoryDao
    ): com.shiyinplayer.data.repository.RadioRepository =
        com.shiyinplayer.data.repository.RadioRepository(stationDao, historyDao)

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider

    /** 复制主库 db 文件为带时间戳的 .bak，供 destructive 兜底前保留数据副本。返回备份绝对路径。 */
    private fun backupDbFile(db: SupportSQLiteDatabase): String? {
        return try {
            val srcPath = db.path ?: return null
            val src = File(srcPath)
            if (!src.exists()) return null
            val dst = File(src.parentFile, src.name + ".destructive-backup-" + System.currentTimeMillis() + ".db")
            src.copyTo(dst, overwrite = true)
            dst.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
