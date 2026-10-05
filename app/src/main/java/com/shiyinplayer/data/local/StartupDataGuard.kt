package com.shiyinplayer.data.local

import java.io.File

/**
 * 启动期「数据闸门」：检测到**不能安全打开曲库**时置位，由界面层转到一个可读的说明页，
 * 而不是抛异常把启动崩掉。
 *
 * 两种情形共用一个页面（文案不同，自救指引相同 —— 都是"别动数据、去装新版/还原备份"）：
 *
 * 1. [Reason.Downgrade] —— 库里的结构版本高于本 APK 能认的版本（装了新版又装回旧版）。
 *    Room 只会升级不会降级，这种情况它抛 `IllegalStateException`。
 * 2. [Reason.RecoveryFailed] —— 迁移失败且自动恢复（L1 还原重试 / L3 重建搬运）也没救回来。
 *    自 Batch 0 起主库已移除 `fallbackToDestructiveMigration`：**绝不清库**，
 *    宁可用空白曲库继续跑，也要把原始文件留在原地。
 *
 * 设计底线（Batch 0 / O2）：
 * - 真实库**一个字节都不动**，备份留在原地；
 * - 说明页只给"退出"与"复制路径"，**绝不提供"清除数据"** —— 那恰恰会删掉用户唯一的曲库；
 * - 用户装回新版或手动还原备份后，数据原样可用。
 *
 * @see com.shiyinplayer.ui.UnsupportedDataActivity 呈现这一状态的页面
 */
object StartupDataGuard {

    enum class Reason {
        /** 库版本高于本 APK（降级安装）。 */
        Downgrade,

        /** 迁移失败且自动恢复未果。 */
        RecoveryFailed,

        /** E6：连一份迁移前备份都建不出来，因此**没做**本可安全完成的那次结构变更。 */
        BackupFailed,

        /** E3：可用空间不足（清缓存后仍不足），因此没动库。 */
        InsufficientSpace,
    }

    /** 是否处于"数据不可用"状态（此时用的是影子库，真实库未被改动）。 */
    @Volatile
    var isActive: Boolean = false
        private set

    /** 触发原因。 */
    @Volatile
    var reason: Reason = Reason.Downgrade
        private set

    /** 真实库里的结构版本（降级时高于 [expectedVersion]）。 */
    @Volatile
    var actualVersion: Int = 0
        private set

    /** 当前 APK 期望的结构版本。 */
    @Volatile
    var expectedVersion: Int = 0
        private set

    /** 真实库主文件路径（页面上要告诉用户它在哪）。 */
    @Volatile
    var originalDbPath: String? = null
        private set

    /** 备份文件路径（可能为 null：备份失败也要照样保护，只是没有副本）。 */
    @Volatile
    var backupPath: String? = null
        private set

    /** 恢复失败时的补充说明（迁移链断裂等），仅用于排障展示。 */
    @Volatile
    var detail: String? = null
        private set

    /** 记录降级安装（见 [Reason.Downgrade]）。 */
    fun recordDowngrade(
        actualVersion: Int,
        expectedVersion: Int,
        originalDbFile: File,
        backupFile: File?
    ) {
        this.reason = Reason.Downgrade
        this.actualVersion = actualVersion
        this.expectedVersion = expectedVersion
        this.originalDbPath = originalDbFile.absolutePath
        this.backupPath = backupFile?.absolutePath
        this.detail = null
        isActive = true
    }

    /**
     * 记录"迁移被拦下"（见 [Reason.BackupFailed] / [Reason.InsufficientSpace]）。
     *
     * 与另两种情形的关键差别：**这次迁移本可以成功，是我们主动选择不做**。
     * 因为迁移一旦动手就是不可逆的结构变更，而在"没有备份"或"写到一半必然没空间"的条件下
     * 去改用户唯一的曲库，赌的是整个曲库（§6.2 的 E3/E6）。
     *
     * 因此这里**一个字节都没动**真实库：它仍停在旧版本、原样可用；
     * 用户腾出空间或换回正常环境后重启 App，就会照常完成升级。
     */
    fun recordMigrationBlocked(
        reason: Reason,
        versionBefore: Int,
        expectedVersion: Int,
        originalDbFile: File,
        backupFile: File?,
        detail: String?
    ) {
        this.reason = reason
        this.actualVersion = versionBefore
        this.expectedVersion = expectedVersion
        this.originalDbPath = originalDbFile.absolutePath
        this.backupPath = backupFile?.absolutePath
        this.detail = detail
        isActive = true
    }

    /**
     * 记录"迁移失败且自动恢复未果"（见 [Reason.RecoveryFailed]）。
     *
     * ⚠️ 调用前提：真实库已被整组改名保留为 `*.rebuild-src`，本次用的是新建的空白曲库。
     * 页面上要引导用户去那里找回数据，所以 [sourceFile] 不能为空。
     */
    fun recordRecoveryFailed(originalDbFile: File, sourceFile: File?, backupFile: File?, detail: String? = null) {
        this.reason = Reason.RecoveryFailed
        this.actualVersion = 0
        this.expectedVersion = 0
        this.originalDbPath = sourceFile?.absolutePath ?: originalDbFile.absolutePath
        this.backupPath = backupFile?.absolutePath
        this.detail = detail
        isActive = true
    }
}
