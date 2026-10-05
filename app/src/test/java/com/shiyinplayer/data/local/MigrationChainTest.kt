package com.shiyinplayer.data.local

import androidx.room.migration.Migration
import com.shiyinplayer.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E9 静态门禁：迁移链必须能把**每一个**受支持的旧版本送到当前目标版本。
 *
 * 这道门禁存在的唯一理由是：**缺口在开发机上永远复现不了**。
 * 开发机上的库总是最新的，启动时压根不走迁移链，漏注册一步毫无症状 ——
 * 直到某个停在旧版本的用户升级，一开 App 就崩在启动路径上。
 * 所以必须由构建期测试来拦，而不是靠运行期日志。
 */
class MigrationChainTest {

    private fun edge(start: Int, end: Int): Migration = object : Migration(start, end) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
    }

    @Test
    fun `主库迁移链覆盖 1 到目标版本无缺口`() {
        val audit = MigrationChain.audit(
            MigrationRegistry.APP_DB_MIGRATIONS,
            MigrationRegistry.APP_DB_TARGET,
            MigrationRegistry.OLDEST_SUPPORTED
        )
        assertTrue(
            "主库迁移链有缺口：${MigrationChain.describe(audit)}。" +
                "新增版本时记得把 MIGRATION_n_n1 加进 MigrationRegistry.APP_DB_MIGRATIONS。",
            audit.ok
        )
        assertTrue("不应有指向目标版本之外的边：${audit.beyondTarget}", audit.beyondTarget.isEmpty())
    }

    @Test
    fun `缓存库迁移链覆盖 1 到目标版本无缺口`() {
        val audit = MigrationChain.audit(
            MigrationRegistry.METADATA_DB_MIGRATIONS,
            MigrationRegistry.METADATA_DB_TARGET,
            MigrationRegistry.OLDEST_SUPPORTED
        )
        assertTrue("缓存库迁移链有缺口：${MigrationChain.describe(audit)}", audit.ok)
    }

    /**
     * 注册清单里的每一条都必须落在 (最老支持版本, 目标版本] 区间内。
     * 迁出区间意味着有人改了版本号却没改迁移（或反之），这类不一致会让 Room 在运行期才报错。
     */
    @Test
    fun `主库每条迁移的起止版本都在支持区间内`() {
        for (m in MigrationRegistry.APP_DB_MIGRATIONS) {
            assertTrue(
                "迁移 ${m.startVersion}→${m.endVersion} 的起点低于支持下限 ${MigrationRegistry.OLDEST_SUPPORTED}",
                m.startVersion >= MigrationRegistry.OLDEST_SUPPORTED
            )
            assertTrue(
                "迁移 ${m.startVersion}→${m.endVersion} 的终点超过目标版本 ${MigrationRegistry.APP_DB_TARGET}",
                m.endVersion <= MigrationRegistry.APP_DB_TARGET
            )
            assertTrue("迁移起点必须小于终点：${m.startVersion}→${m.endVersion}", m.startVersion < m.endVersion)
        }
    }

    /**
     * 目标版本必须与 `@Database(version=)` 一致。
     * 这里是**硬编码断言**：注解里的数字没法在单测里反射读取（Room 生成的是编译期代码），
     * 所以把当前值钉死 —— 将来改版本号时，这条会先红，提醒改的人去同步
     * `Constants.DATABASE_VERSION`、`@Database(version=)` 与迁移清单三处。
     */
    @Test
    fun `目标版本常量与 Room 注解版本一致`() {
        assertEquals(16, Constants.DATABASE_VERSION)
        assertEquals(3, Constants.DATASTORE_METADATA_VERSION)
        assertEquals(Constants.DATABASE_VERSION, MigrationRegistry.APP_DB_TARGET)
        assertEquals(Constants.DATASTORE_METADATA_VERSION, MigrationRegistry.METADATA_DB_TARGET)
    }

    // ------------------------------------------------------------------ 审计器自身的正确性

    @Test
    fun `能发现中间缺一步`() {
        val audit = MigrationChain.audit(
            listOf(edge(1, 2), edge(2, 3), edge(3, 4), edge(5, 6)),   // 缺 4→5
            target = 6,
            oldestSupported = 1
        )
        assertFalse("应当判定为有缺口", audit.ok)
        // 缺口只有一个：4→5。停在 v1..v4 的用户都过不去（v5 反而能到 6，不是缺口）
        assertEquals("断裂点应恰为 4，实际 ${audit.blockingVersions}", listOf(4), audit.blockingVersions)
        assertEquals(listOf(1, 2, 3, 4), audit.unreachableStarts)
    }

    @Test
    fun `能发现旁路版本也是断的`() {
        // v2 不在 1→3 的路径上：只从最老版本试一次的话，它会被漏掉
        val audit = MigrationChain.audit(
            listOf(edge(1, 3), edge(3, 4)),
            target = 4,
            oldestSupported = 1
        )
        assertFalse("v2 无法升级，应当报缺口", audit.ok)
        assertEquals(listOf(2), audit.unreachableStarts)
    }

    @Test
    fun `多跳边可被接受`() {
        // 1→3 是一次跨两级的边，属于合法形态（MIGRATION_1_3 就是如此）。
        // 同时带上 2→3 这条旁路边 —— 真实链里 v2 就是靠它升级的，
        // 少了它 v2 就是缺口（不是"多跳"的问题）。
        val audit = MigrationChain.audit(
            listOf(edge(1, 3), edge(2, 3), edge(3, 4)),
            target = 4,
            oldestSupported = 1
        )
        assertTrue("1→3→4 与 2→3→4 都应可通：${MigrationChain.describe(audit)}", audit.ok)
    }

    @Test
    fun `指向目标之外的边被单独标出但不判失败`() {
        val audit = MigrationChain.audit(
            listOf(edge(1, 2), edge(2, 3), edge(3, 4)),
            target = 3,
            oldestSupported = 1
        )
        assertEquals(listOf(3 to 4), audit.beyondTarget)
        assertTrue("超出目标的边不影响 1..3 的可达性", audit.ok)
    }

    @Test
    fun `完全没有边时报全部缺口`() {
        val audit = MigrationChain.audit(emptyList(), target = 3, oldestSupported = 1)
        assertFalse(audit.ok)
        assertEquals(listOf(1, 2), audit.unreachableStarts)
    }

    @Test
    fun `已是目标版本时无需任何边`() {
        val audit = MigrationChain.audit(emptyList(), target = 1, oldestSupported = 1)
        assertTrue("没有旧版本要升，空链也算完整", audit.ok)
        assertEquals("迁移链完整", MigrationChain.describe(audit))
    }
}
