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
import com.shiyinplayer.data.local.DbSafetyGuard
import com.shiyinplayer.data.local.MigrationChain
import com.shiyinplayer.data.local.MigrationRegistry
import com.shiyinplayer.data.local.StartupDataGuard
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

    private const val TAG = "AppModule"

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    /** 本次迁移（v15→v16）必须出现的新索引，用于 S3 自检。 */
    private val REQUIRED_INDEXES_16 = listOf("index_playlist_items_songId")

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        // ===== 降级安装保护（Batch 0 / B0-3）=====
        // 库版本高于本 APK 能认的版本时，Room 只会抛 IllegalStateException 把启动崩掉，
        // 用户看到的"打开就闪退"，而唯一自以为的解决办法（清除数据）恰恰会删掉整个曲库。
        // 这里在打开库**之前**（只读 4 字节文件头）就识别出来：真实库原样不动，
        // 本次改用影子库空跑，由 SplashActivity 跳到 UnsupportedDataActivity 说明情况。
        val dbFile = context.getDatabasePath(Constants.DATABASE_NAME)
        if (DbSafetyGuard.isDowngradeInstall(dbFile, Constants.DATABASE_VERSION)) {
            val actual = DbSafetyGuard.readUserVersionFromHeader(dbFile)
            val backup = runCatching { DbSafetyGuard.backupGroup(dbFile, Constants.DATABASE_NAME) }
                .onFailure { Log.w(TAG, "降级保护：备份原始库失败：${it.message}") }
                .getOrNull()
            StartupDataGuard.recordDowngrade(actual, Constants.DATABASE_VERSION, dbFile, backup)
            Log.w(
                TAG, "检测到降级安装：库 v$actual > 本版 v${Constants.DATABASE_VERSION}，" +
                    "真实库保持不动，本次改用影子库启动"
            )
            return buildAppDatabase(context, Constants.DATABASE_NAME + ".downgraded")
        }

        // ===== 迁移安全管线（Batch 0 / PLAN §6）=====
        // S0+S1：**只在确实要升级时**动作。常态（已是最新）直接返回 null ——
        // 冷启动不付出任何额外代价（user_version 是读文件头 4 字节，不打开数据库）。
        val pending = runCatching {
            DbSafetyGuard.preflight(context, Constants.DATABASE_NAME, Constants.DATABASE_VERSION)
        }.getOrNull()

        if (pending == null) {
            return buildAppDatabase(context)
        }

        // ---- E3/E6：迁移被拦下 → **一个字节都不动真实库** ----
        // 这两条与"迁移失败"是不同性质：迁移本可成功，是我们主动选择不做。
        // 理由：迁移是不可逆的结构变更，"没有备份"或"写到一半必然没空间"时动手是拿用户整个曲库在赌。
        // 处理方式与降级保护一致：真实库原样留在旧版本，本次改用影子库空跑 + 说明页；
        // 用户腾出空间后重启，会照常完成升级。
        if (pending.blocker != DbSafetyGuard.Blocker.None) {
            val reason = when (pending.blocker) {
                DbSafetyGuard.Blocker.BackupFailed -> StartupDataGuard.Reason.BackupFailed
                else -> StartupDataGuard.Reason.InsufficientSpace
            }
            StartupDataGuard.recordMigrationBlocked(
                reason = reason,
                versionBefore = pending.versionBefore,
                expectedVersion = Constants.DATABASE_VERSION,
                originalDbFile = dbFile,
                backupFile = pending.backupBase,
                detail = pending.blockerDetail
            )
            Log.w(
                TAG,
                "迁移被拦下（${pending.blocker}）：${pending.blockerDetail}；" +
                    "真实库保持 v${pending.versionBefore} 原样不动，本次改用影子库启动"
            )
            return buildAppDatabase(context, Constants.DATABASE_NAME + ".blocked")
        }

        // E9：迁移前先审一遍链是否覆盖 v${pending.versionBefore} → v目标。
        // 缺口在开发机上永远看不到（开发机的库总是最新的，压根不走这条链），
        // 所以在这里落一条明确日志，并把静态门禁交给 MigrationChainTest 在构建时拦。
        // 只在这一刻审（真的要迁移时才审），常态冷启仍然零开销。
        val audit = MigrationChain.audit(
            MigrationRegistry.APP_DB_MIGRATIONS,
            Constants.DATABASE_VERSION,
            MigrationRegistry.OLDEST_SUPPORTED
        )
        if (!audit.ok) {
            Log.e(
                TAG,
                "迁移链审计未通过：${MigrationChain.describe(audit)}；" +
                    "本次仍会尝试迁移，失败则由 S4 阶梯接管（请尽快修链，报告见 MigrationChainTest）"
            )
        }

        // S2：打开即触发 Room 迁移链；S3：迁移后自检
        val first = buildAppDatabase(context)
        return try {
            first.openHelper.writableDatabase
            if (DbSafetyGuard.verify(
                    context, Constants.DATABASE_NAME, Constants.DATABASE_VERSION,
                    pending, REQUIRED_INDEXES_16
                )
            ) {
                Log.i(TAG, "迁移 ${pending.versionBefore} → ${Constants.DATABASE_VERSION} 完成且自检通过")
                // 已被证明无用的旧备份不再长期占盘（真实曲库主库动辄 30MB/代）
                DbSafetyGuard.trimBackupsAfterSuccess(context.getDatabasePath(Constants.DATABASE_NAME))
                first
            } else {
                recoverAppDatabase(context, first, pending, "L2 迁移后自检未通过")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "L1 迁移抛异常，进入自动恢复：${t.message}", t)
            recoverAppDatabase(context, first, pending, "L1 迁移抛异常")
        }
    }

    /**
     * 常规构建（与迁移无关的配置集中在这里，恢复阶梯会多次调用它）。
     *
     * @param dbName 库文件名；降级保护下会传入**影子库**名，让 Room 去开另一个文件，
     *               从而彻底不碰用户的真实库（见 [provideAppDatabase] 里的降级分支）。
     */
    private fun buildAppDatabase(context: Context, dbName: String = Constants.DATABASE_NAME): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            // P0-2：迁移链覆盖 1→16（v1/v2 旧库无损收敛到 v3，再逐级升到最新），不再静默清空早期安装。
            // E9：清单集中在 MigrationRegistry（注册与审计同一出处），不再在这里铺一长串参数 ——
            // 那样"链是否覆盖 1..目标版本"没人能一眼看出来，漏一步在开发机上完全不可见。
            .addMigrations(*MigrationRegistry.APP_DB_MIGRATIONS.toTypedArray())
            // ⚠️ Batch 0 / O2：**刻意不设** `fallbackToDestructiveMigration`。
            //    它会在"迁移链断裂"时把整个曲库静默重建 —— 备份虽然还在，
            //    但用户根本不会去翻 `.premigrate-*.bak`，等价于数据永久丢失。
            //    现在没有迁移路径时 Room 抛异常，由本文件的恢复阶梯接住：
            //      L1 还原备份重试 → L3 按列交集重建搬运 → L4 保留原件 + 空白库 + 提示页。
            //    代价是"迁移彻底失败时 App 不可用"，而这条路径已被
            //    `MigrationMatrixTest`（v5–v16 直升）覆盖，因此可接受。
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
                        TAG,
                        "AppDatabase 触发 destructive migration：旧库版本/结构不在迁移链内，曲库将被重建。" +
                            (if (backup != null) " 旧库已备份至：$backup" else "（备份失败）")
                    )
                }
            })
            .build()

    /**
     * S4 自动恢复阶梯（**全程无用户交互**）：
     * 1. L1/L2 —— 从迁移前备份整组还原，重建 Room 再试一次；
     * 2. L3    —— 仍失败则把旧库数据按「列交集」搬进新建的目标库（不依赖迁移链）；
     * 3. L4    —— 连搬运都失败时，保留全部原件（旧库改名留档），以空白库继续跑，**绝不删除任何文件**。
     */
    private fun recoverAppDatabase(
        context: Context,
        broken: AppDatabase,
        pending: DbSafetyGuard.Preflight,
        reason: String
    ): AppDatabase {
        runCatching { broken.close() }

        if (DbSafetyGuard.restoreFromBackup(context, Constants.DATABASE_NAME, pending)) {
            val retry = buildAppDatabase(context)
            try {
                retry.openHelper.writableDatabase
                if (DbSafetyGuard.verify(
                        context, Constants.DATABASE_NAME, Constants.DATABASE_VERSION,
                        pending, REQUIRED_INDEXES_16
                    )
                ) {
                    Log.i(TAG, "$reason → 已还原备份并重试成功")
                    return retry
                }
                Log.w(TAG, "$reason → 还原后自检仍未通过，转入 L3 重建搬运")
            } catch (t: Throwable) {
                Log.w(TAG, "$reason → 还原后重试仍失败：${t.message}，转入 L3 重建搬运")
            }
            runCatching { retry.close() }
        } else {
            Log.w(TAG, "$reason → 无可用备份，直接转 L3 重建搬运")
        }

        return rebuildByTransfer(context, pending, reason)
    }

    /**
     * L3：把旧库整组挪到 `*.rebuild-src` → 让 Room 建出目标版本的**全新空库** →
     * 按列交集把数据搬回来 → 再自检。搬运只依赖"同名列"，因此迁移链断裂也能兜住。
     */
    private fun rebuildByTransfer(
        context: Context,
        pending: DbSafetyGuard.Preflight,
        reason: String
    ): AppDatabase {
        val dbFile = context.getDatabasePath(Constants.DATABASE_NAME)
        val src = DbSafetyGuard.moveGroupAside(dbFile)
        val fresh = buildAppDatabase(context)

        if (src == null) {
            Log.w(TAG, "L4 $reason → 无法挪动旧库，以空白库继续（旧库文件未被删除）")
            StartupDataGuard.recordRecoveryFailed(dbFile, null, pending.backupBase, reason)
            return fresh
        }

        return try {
            fresh.openHelper.writableDatabase   // 建出目标版本完整 schema（含新索引）
            val moved = DbSafetyGuard.transferByColumnIntersection(
                targetDbFile = dbFile,
                srcDbFile = src,
                srcWal = File(src.absolutePath + "-wal")
            )
            val verified = moved && DbSafetyGuard.verify(
                context, Constants.DATABASE_NAME, Constants.DATABASE_VERSION,
                pending, REQUIRED_INDEXES_16
            )

            if (verified) {
                Log.i(TAG, "L3 $reason → 按列交集重建搬运成功，数据已恢复")
                DbSafetyGuard.cleanupRebuildSource(dbFile, keep = false)
                DbSafetyGuard.trimBackupsAfterSuccess(dbFile)
            } else {
                // L4：不删任何东西，旧库留在 *.rebuild-src 供人工找回
                Log.w(
                    TAG, "L4 $reason → 重建搬运未完全成功；旧库已保留在 ${src.name}（未被删除），" +
                        "曲库可能不完整，请保留该文件以便恢复"
                )
                StartupDataGuard.recordRecoveryFailed(dbFile, src, pending.backupBase, reason)
            }
            fresh
        } catch (t: Throwable) {
            Log.w(TAG, "L4 $reason → 重建搬运异常：${t.message}；旧库保留在 ${src.name}（未被删除）", t)
            StartupDataGuard.recordRecoveryFailed(dbFile, src, pending.backupBase, reason)
            fresh
        }
    }

    @Provides
    @Singleton
    fun provideMetadataDatabase(@ApplicationContext context: Context): MetadataDatabase {
        val name = Constants.DATASTORE_METADATA_DB

        // 与主库同一套安全管线（批处理 0 / PLAN §6）：用户的歌词是长期积累的，
        // "可重建"不等于"该丢"。缓存库只有几百 KB 到几 MB，S0/S1 的开销可以忽略。
        val pending = runCatching {
            DbSafetyGuard.preflight(context, name, Constants.DATASTORE_METADATA_VERSION)
        }.getOrNull()

        if (pending == null) {
            return buildMetadataDatabase(context)
        }

        val first = buildMetadataDatabase(context)
        return try {
            first.openHelper.writableDatabase
            if (DbSafetyGuard.verify(context, name, Constants.DATASTORE_METADATA_VERSION, pending, emptyList())) {
                Log.i(TAG, "缓存库迁移 ${pending.versionBefore} → ${Constants.DATASTORE_METADATA_VERSION} 完成且自检通过")
                DbSafetyGuard.trimBackupsAfterSuccess(context.getDatabasePath(name))
                first
            } else {
                recoverMetadataDatabase(context, first, pending, "L2 缓存库自检未通过")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "L1 缓存库迁移抛异常，进入自动恢复：${t.message}", t)
            recoverMetadataDatabase(context, first, pending, "L1 缓存库迁移抛异常")
        }
    }

    private fun buildMetadataDatabase(context: Context): MetadataDatabase =
        Room.databaseBuilder(context, MetadataDatabase::class.java, Constants.DATASTORE_METADATA_DB)
            // P0-2：缓存库（歌词/元数据）无历史迁移链，内容可随时重建；
            // 2026-08-19：v1→v2 显式迁移（lyrics 加 songId 列）——否则 fallbackToDestructiveMigration
            // 会清空已缓存歌词，违背「歌词与歌曲绑定后永不过期」需求。
            // E9：同样走集中清单（注册与审计同一出处），避免漏注册在开发机上不可见。
            .addMigrations(*MigrationRegistry.METADATA_DB_MIGRATIONS.toTypedArray())
            // 仍保留 destructive 兜底（后续版本未覆盖时）但记录日志而非静默。
            .fallbackToDestructiveMigration()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    // 缓存库内容可重建，但"可重建"不等于"该丢"：用户歌词是长期积累的，
                    // 且删曲清缓存的路径已经做过精细处理。此处先备份（整组 db+wal+shm）再让它重建，
                    // 与主库口径一致；备份失败也不阻塞启动。
                    val backup = backupDbFile(db)
                    Log.w(
                        TAG,
                        "MetadataDatabase 触发 destructive migration（缓存库将重建）" +
                            (if (backup != null) "，旧库已备份：$backup" else "，但备份失败")
                    )
                }
            })
            .build()

    /** 缓存库的恢复阶梯：L1/L2 还原备份重试 → L3 按列交集搬运 → L4 空白库继续。 */
    private fun recoverMetadataDatabase(
        context: Context,
        broken: MetadataDatabase,
        pending: DbSafetyGuard.Preflight,
        reason: String
    ): MetadataDatabase {
        val name = Constants.DATASTORE_METADATA_DB
        runCatching { broken.close() }

        if (DbSafetyGuard.restoreFromBackup(context, name, pending)) {
            val retry = buildMetadataDatabase(context)
            try {
                retry.openHelper.writableDatabase
                if (DbSafetyGuard.verify(context, name, Constants.DATASTORE_METADATA_VERSION, pending, emptyList())) {
                    Log.i(TAG, "$reason → 已还原备份并重试成功")
                    return retry
                }
            } catch (t: Throwable) {
                Log.w(TAG, "$reason → 还原后重试仍失败：${t.message}，转入 L3 搬运")
            }
            runCatching { retry.close() }
        }

        // L3：旧库改名 → Room 建全新目标库 → 按列交集搬回来
        val dbFile = context.getDatabasePath(name)
        val src = DbSafetyGuard.moveGroupAside(dbFile)
        val fresh = buildMetadataDatabase(context)
        if (src == null) {
            Log.w(TAG, "L4 $reason → 无法挪动旧缓存库，以空缓存继续（旧库未被删除）")
            return fresh
        }
        return try {
            fresh.openHelper.writableDatabase
            if (DbSafetyGuard.transferByColumnIntersection(dbFile, src, File(src.absolutePath + "-wal"))) {
                Log.i(TAG, "L3 $reason → 缓存数据搬运成功")
                DbSafetyGuard.cleanupRebuildSource(dbFile, keep = false)
            } else {
                Log.w(TAG, "L4 $reason → 未能搬运缓存数据；旧文件保留在 ${src.name}（未被删除）")
            }
            fresh
        } catch (t: Throwable) {
            Log.w(TAG, "L4 $reason → 搬运异常：${t.message}；旧文件保留在 ${src.name}", t)
            fresh
        }
    }

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

    /**
     * destructive 兜底 / 迁移前把数据库**整组文件**备一份，供数据保留与自动恢复。
     *
     * 实现统一收敛到 [DbSafetyGuard.backupGroup]（整组 `.db` + `-wal` + `-shm` + 轮转），
     * 避免两处各写一遍 —— 整组备份这件事错一次就等于备份不可用。
     *
     * @return 备份主文件的绝对路径；失败返回 null，**绝不抛出**（兜底路径上的备份失败
     *         不能连带把启动流程打挂）。
     */
    private fun backupDbFile(db: SupportSQLiteDatabase): String? {
        return try {
            val srcPath = db.path ?: return null
            val src = File(srcPath)
            if (!src.exists()) return null
            DbSafetyGuard.backupGroup(src, src.name)?.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
