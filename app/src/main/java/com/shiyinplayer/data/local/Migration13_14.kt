package com.shiyinplayer.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v13 → v14：dedupKey 规则变更 —— 从「原始路径/URI」改为「`{sourceType}:{sha256(小写规范化路径或 uri)}`」，
 * 与 PC 端 `Shiyin.Core/Library/DedupKey.cs` 逐字节一致（CUE 子轨追加 `#idx{trackIndex}`）。
 *
 * 背景：两端各自扫描同一目录时，旧规则（安卓存原始路径、PC 存哈希）产生两条互不相认的记录，
 * 局域网同步按 dedupKey 对齐时永远命不中。统一为哈希后，同源同文件在两端得到同一键。
 *
 * 为什么必须在这里重算而不是等下次扫描：扫描器「按 dedupKey 命中则更新、否则插入」，
 * 旧键与新键无交集 → 全库曲目会被当作新歌重复插入，且旧行因无前缀可用而逃过失效清理。
 *
 * 实现见 [DedupKeyRecompute]（与 [MIGRATION_14_15] 共用）：
 * 摘掉唯一索引 → 逐行重算 → **无损**折叠撞键行 → 重建唯一索引。
 * rating / playCount / lastPlayedMs / lyricOffsetMs / dateAdded 等用户数据全程不丢——
 * 撞键行（同一物理文件的另一种写法）的用户数据与歌单/目录树引用会并入保留行后才删除。
 *
 * 已知代价：PC 曾按旧 dedupKey 推送到 `synced/<sha256(旧键)前16位>` 的落地文件，重算后路径不再命中，
 * 会被判为"未接收"并要求 PC 重推一次（一次性、且不丢元数据）。
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val r = DedupKeyRecompute.apply(db)
        Log.i(TAG, "dedupKey 重算：扫描 ${r.scanned} 行，改写 ${r.rekeyed} 行，无损折叠 ${r.folded} 行")
    }
}

private const val TAG = "Migration13_14"
