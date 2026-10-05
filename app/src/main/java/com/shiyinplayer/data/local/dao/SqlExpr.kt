package com.shiyinplayer.data.local.dao

/**
 * 局域网同步「最后写入者赢（LWW）」统一使用的 SQL 表达式。
 *
 * ## 为什么需要它（2026-09-17）
 * 双向同步要按 `updatedAt` 判定冲突（契约 §4）。但**安卓侧原先没有任何本地写路径去刷新它**：
 * 改评分、改标题、切收藏的 SQL 全都不带时间戳，`playlists` 表更是连时间戳列都被忽略
 * （契约里映射的是既有列 `dateModified`）。结果是「在安卓上收藏一首歌」不会让该行变新，
 * PC 侧按 LWW 会判「PC 更新」，反过来把安卓的改动覆盖掉——正好是「升级/同步丢数据」。
 *
 * ## 用法
 * 所有「**用户可见数据发生变更**」的 UPDATE 都要把 `updatedAt` 写成 [NOW_MS_SQL]：
 * ```kotlin
 * @Query("UPDATE songs SET rating = :rating, updatedAt = $NOW_MS_SQL WHERE id = :id")
 * ```
 * SQLite 没有毫秒精度的 `now()`，故用儒略日换算得 Unix 毫秒（与 `System.currentTimeMillis()`
 * 同语义、同量纲）。
 *
 * ## ⚠️ 刻意排除的写路径
 * 派生数据回填**不刷**时间戳：`songs.albumArtUri`（封面缓存）、`songs.durationMs`（时长探测）、
 * `songs.albumId`/`artistId`（归并回填）、`folder_entry`（物化派生表）。
 * 否则一次全库扫描就把所有行刷成"刚刚"，设备端会在 LWW 里无意义地赢，反过来覆盖 PC 侧更好的数据。
 */
internal const val NOW_MS_SQL = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
