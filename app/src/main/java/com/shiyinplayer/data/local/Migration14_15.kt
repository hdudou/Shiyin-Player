package com.shiyinplayer.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v14 → v15：dedupKey 自愈重算（同一套引擎，见 [DedupKeyRecompute]；schema 无变化，纯数据迁移）。
 *
 * ## 为什么需要独立一版，而不是只靠 13→14
 * [MIGRATION_13_14] 只在**升级那一刻**重算一次。此后任何绕过当前规则的写入都会在库里留下
 * 「键不符合 dedupKey 规则」的行——唯一索引只保证唯一、不保证合规。症状是：
 * - 同一文件在曲库出现两条（扫描按新规则插入，旧行仍挂着非规则键，两者互不相认）；
 * - 局域网同步按 dedupKey 对齐时命不中 PC 推来的键（表现为「曲目在、点播放失败」或重复入库）。
 * 已确认的来源：导入**旧版本导出**的曲库文件（`DataTransferManager` 原样透传文件里的 dedupKey）。
 *
 * 本版本对全库做一次重算自愈，并顺带用**无损**折叠消化因此产生的撞键行（用户数据与歌单/目录树引用
 * 全部并入保留行，见 [DedupKeyRecompute] 的折叠语义）。
 *
 * ## 代价与实测
 * - 键已全部合规的库是**零 UPDATE 的空操作**，仅一次全表扫描（约 1.6 万行 JVM 侧 sha256）。
 * - 本机实测库（16148 行全部合规）：升级后曲库行数与用户数据逐行不变，`folded = 0`。
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val r = DedupKeyRecompute.apply(db)
        Log.i(TAG, "dedupKey 自愈重算：扫描 ${r.scanned} 行，改写 ${r.rekeyed} 行，无损折叠 ${r.folded} 行")
    }
}

private const val TAG = "Migration14_15"
