package com.shiyinplayer.data

import android.util.Log
import com.shiyinplayer.data.media.FolderStructureBuilder
import com.shiyinplayer.ui.settings.SettingsRepository
import com.shiyinplayer.util.Constants
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 版本升级迁移协调器：升级安装后【首次冷启动】判定并执行数据迁移。
 *
 * 原理：持久化记录「上次成功迁移到的数据版本」([SettingsRepository.lastDatabaseVersion])。
 * 每次数据结构发生变化时，同时在 [Constants.DATABASE_VERSION] 与下方注册对应的一次性迁移任务；
 * 判据 `last < 最新版本` 即触发，完成后把已迁移版本写回，保证旧数据完整过渡到新结构。
 *
 * 注意：Room 迁移链（Migrations.kt，schema 层）自动升级数据库结构；本协调器补的是
 * **业务数据层面**的补全（例如从 songs 表反查重建目录树），两者互补、都须随结构变化同步登记。
 *
 * ## 版本登记表
 * - 8（2026-08-27）：文件夹 tab 目录树表 folder_entry 引入。从不带目录树版本升级后，
 *   触发一次 [FolderStructureBuilder.rebuild]（遍历曲库补全缺失的目录树数据）。
 * - 9（2026-08-27）：文件夹附件表 folder_attachment 引入。**无需业务迁移**——附件数据
 *   （封面图/说明 txt 的 uri 与大小）只能经源目录扫描收集，无法由旧库结构推导，
 *   升级后表为空，待下次完整扫描自动填充（Room 迁移已建空表）。
 *   后续每次数据结构变化都在此追加新的 if 分支，并递增 Constants.DATABASE_VERSION。
 */
@Singleton
class VersionUpgradeCoordinator @Inject constructor(
    private val settings: SettingsRepository,
    private val folderStructure: FolderStructureBuilder
) {
    /** 是否需要执行升级迁移（首次冷启动判断用）。 */
    suspend fun needsUpgrade(): Boolean =
        settings.lastDatabaseVersion() < Constants.DATABASE_VERSION

    /** 执行本次升级对应的全部迁移任务（按旧版本区间顺序执行），完成后写回已迁移版本。 */
    suspend fun runUpgrade() {
        val old = settings.lastDatabaseVersion()
        if (old >= Constants.DATABASE_VERSION) return
        Log.i(TAG, "runUpgrade: $old -> ${Constants.DATABASE_VERSION}")
        // 8 ↑：folder_entry 目录树数据补全（升级时该表已由 Room 迁移建出、内容为空）。
        if (old < DATABASE_VERSION_FOLDER_TREE) {
            folderStructure.rebuild()
        }
        // 未来版本的数据结构变更在此追加：if (old < VERSION_X) { ... }
        settings.setLastDatabaseVersion(Constants.DATABASE_VERSION)
    }

    private companion object {
        private const val TAG = "VersionUpgrade"
        /** 引入文件夹目录树的数据版本（与 MIGRATION_7_8 对应）。 */
        const val DATABASE_VERSION_FOLDER_TREE = 8
    }
}