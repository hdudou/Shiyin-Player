package com.shiyinplayer.data.local

import androidx.room.migration.Migration

/**
 * 迁移链完整性审计（Batch 0 / PLAN §6.2 的 E9）。
 *
 * ## 为什么需要
 *
 * Room 能"跨版本直升"（v9 → v16 自动串接 9→10→…→16），但前提是**链上没有缺口**。
 * 一旦有人漏注册一步（例如新增 16→17 时忘了 `addMigrations(MIGRATION_16_17)`，
 * 或重构时误删了一步），后果不是"报个错"而是：**老用户一升级就崩在启动路径上**，
 * 而且这个缺口在开发机上永远复现不了 —— 开发机上的库总是最新的，压根不会走进那条链。
 *
 * 所以它必须是一道**静态门禁**：由 `MigrationChainTest` 在每次构建时校验，
 * 而不是等用户来告诉你。运行时（S0）也顺手审一次，把结果写进日志便于事后定位。
 *
 * ## 与 Room 的关系
 *
 * 这里**不重复声明**迁移清单 —— 直接从 [Migration] 对象上读 `startVersion`/`endVersion`。
 * 清单只有一处（`Migrations.kt` 里的 APP_DB_MIGRATIONS / METADATA_DB_MIGRATIONS），
 * 两边各写一份迟早会漂移，而"边表与实际注册不一致"恰恰是这道门禁要防的东西。
 */
object MigrationChain {

    /** 审计结论。 */
    data class Audit(
        /** 无法到达目标版本的起始版本（用户库停在这些版本就会卡住）。 */
        val unreachableStarts: List<Int>,
        /** 链被切断的位置：能到达、但没有任何出边继续往前走。 */
        val blockingVersions: List<Int>,
        /** 指向目标版本之外的冗余边（无害，但通常意味着改错了）。 */
        val beyondTarget: List<Pair<Int, Int>>
    ) {
        val ok: Boolean get() = unreachableStarts.isEmpty() && blockingVersions.isEmpty()
    }

    /**
     * 审计 [edges] 能否把 [oldestSupported]..[target] 区间内的**每一个**旧版本送到 [target]。
     *
     * 逐个起始版本独立做可达性搜索，而不是只从最老的那个试一次 ——
     * `MIGRATION_2_3` 这类"旁路"边（v2 不在 1→3 的路径上）只有逐起点检查才看得见。
     *
     * @param oldestSupported 代码声明支持的最老库版本（更老的版本本就不打算兼容）。
     * @param target 当前 APK 期望的版本（= `@Database(version=)`）。
     */
    fun audit(edges: List<Migration>, target: Int, oldestSupported: Int): Audit {
        val outgoing = edges.groupBy({ it.startVersion }, { it.endVersion })

        val unreachable = ArrayList<Int>()
        val blocking = LinkedHashSet<Int>()

        for (start in oldestSupported until target) {
            // 从 start 出发能到哪些版本（BFS；边数十几条，不值得上更复杂的算法）
            val visited = HashSet<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited.add(start)

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val next = outgoing[current] ?: continue
                for (v in next) {
                    // 只往目标方向走：回退边对"升级"没有意义，也不该被当成通路
                    if (v in visited || v > target) continue
                    visited.add(v)
                    queue.add(v)
                }
            }

            if (target !in visited) {
                unreachable.add(start)
                // 找出"走到头了"的那些版本，它们就是缺口所在处
                for (v in visited) {
                    if (v < target && outgoing[v].isNullOrEmpty()) {
                        blocking.add(v)
                    }
                }
            }
        }

        val beyond = edges.filter { it.endVersion > target }
            .map { it.startVersion to it.endVersion }

        return Audit(unreachable.sorted(), blocking.sorted(), beyond)
    }

    /** 审计结果的单行摘要（日志用）。 */
    fun describe(audit: Audit): String =
        if (audit.ok) {
            "迁移链完整"
        } else {
            "迁移链存在缺口：无法升级的旧版本=${audit.unreachableStarts}，" +
                "链在 ${audit.blockingVersions} 处断裂" +
                (if (audit.beyondTarget.isEmpty()) "" else "，另有超出目标的边=${audit.beyondTarget}")
        }
}
