package com.shiyinplayer.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E3 空间预检的**策略**部分（纯计算，可在 JVM 上测）。
 *
 * 为什么值得单独测：这个系数决定了"要不要放弃一次升级"。定小了 → 迁移写到一半没空间，
 * 事务回滚、用户白升一次；定大了 → 存储紧张的用户被无谓地挡在旧版本。
 * 而且它一旦被改错，**真机上很难复现**（得正好处在临界空间），所以把口径钉在测试里。
 */
class DbSafetyGuardSpaceTest {

    @Test
    fun `迁移所需空间为主库大小的三倍`() {
        // 真实曲库量级：PJF110 上的主库约 26.5MB
        val dbSize = 26_521_600L
        assertEquals(dbSize * 3, DbSafetyGuard.requiredMigrationBytes(dbSize))
    }

    @Test
    fun `三倍空间的构成与文档一致`() {
        // 1 份备份 + 迁移期 WAL（大表重建时可能接近库大小）+ 重建搬运时改名保留的旧库
        val dbSize = 1024L * 1024 * 50        // 50MB
        val required = DbSafetyGuard.requiredMigrationBytes(dbSize)
        assertTrue(
            "至少要有备份(1×)+WAL(1×)+旧库保留(1×)，当前只有 ${required / 1024 / 1024}MB",
            required >= dbSize * 3
        )
    }

    @Test
    fun `随库大小线性增长`() {
        assertTrue(
            DbSafetyGuard.requiredMigrationBytes(200) > DbSafetyGuard.requiredMigrationBytes(100)
        )
        assertEquals(
            DbSafetyGuard.requiredMigrationBytes(10) * 10,
            DbSafetyGuard.requiredMigrationBytes(100)
        )
    }
}
