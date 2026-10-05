package com.shiyinplayer.data.local

import androidx.room.migration.Migration
import com.shiyinplayer.util.Constants

/**
 * 迁移清单的**唯一出处**（Batch 0 / PLAN §6.2 的 E9）。
 *
 * 注册（`Room.databaseBuilder(...).addMigrations(...)`）与审计（[MigrationChain]）都从这里取，
 * 不再各自维护一份 —— 之前 `addMigrations(...)` 是一长串散在 `AppModule` 里的参数，
 * 而"链是否覆盖 1..目标版本"没人能一眼看出来，漏注册一步在开发机上完全不可见。
 *
 * ⚠️ **新增版本时的动作**：写 `MIGRATION_n_n1` → 加进本清单 → 把 `Constants` 里对应版本号 +1。
 * `MigrationChainTest` 会在构建时校验清单仍能把 `OLDEST_SUPPORTED`..目标 的每个版本送到目标，
 * 漏加一步就红。
 */
object MigrationRegistry {

    /**
     * 代码声明支持的最老库版本。
     *
     * v1/v2 没有 schema 导出（早于 `exportSchema = true` 的年代），无法做自动化夹具，
     * 但迁移代码仍在（`MIGRATION_1_3` / `MIGRATION_2_3`），所以审计要从 1 开始算。
     */
    const val OLDEST_SUPPORTED = 1

    /** 主库（曲库）迁移链。 */
    val APP_DB_MIGRATIONS: List<Migration> = listOf(
        MIGRATION_1_3,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16
    )

    /** 缓存库（歌词/元数据）迁移链。 */
    val METADATA_DB_MIGRATIONS: List<Migration> = listOf(
        MIGRATION_METADATA_1_2,
        MIGRATION_METADATA_2_3
    )

    /** 主库的目标版本（与 `@Database(version=)` 必须一致，否则审计会算错）。 */
    const val APP_DB_TARGET = Constants.DATABASE_VERSION

    /** 缓存库的目标版本。 */
    const val METADATA_DB_TARGET = Constants.DATASTORE_METADATA_VERSION
}
