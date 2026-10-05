package com.shiyinplayer.data.local.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LyricCacheDao {
    @Query("SELECT * FROM lyrics WHERE key = :key")
    suspend fun get(key: String): LyricCacheEntity?

    /** 2026-08-19 需求：按歌曲条目 id 取绑定歌词（最新一条；绑定后永不过期）。 */
    @Query("SELECT * FROM lyrics WHERE songId = :songId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getBySongId(songId: Long): LyricCacheEntity?

    /** 2026-08-19 需求：把已缓存的歌词补绑到歌曲条目（key 已知，songId 后补）。 */
    @Query("UPDATE lyrics SET songId = :songId WHERE key = :key")
    suspend fun bindSong(key: String, songId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricCacheEntity)

    /** DC：删除指定 key 的缓存条目（读取时发现过期即删，避免过期条目永久堆积）。 */
    @Query("DELETE FROM lyrics WHERE key = :key")
    suspend fun deleteByKey(key: String)

    /**
     * 需求⑤：**删曲目时清掉它的歌词缓存**。
     *
     * 按 `songId` 绑定删 —— 只删「确实属于这几首歌」的行；未绑定 songId 的旧行（按 title|artist
     * 存的）不在此列，它们可能被别的曲目复用，误删会让别的歌重新联网取一次歌词。
     * 这也是 D7 的另一半：歌词**永不过期**，唯一的下架时机就是曲目被删。
     */
    @Query("DELETE FROM lyrics WHERE songId IN (:songIds)")
    suspend fun deleteBySongIds(songIds: List<Long>)

    @Query("DELETE FROM lyrics WHERE updatedAt < :before")
    suspend fun prune(before: Long)

    /**
     * 局域网同步（v3）：分页取「**已绑定 `songId`**」的歌词，供 `/sync/library` 的 `lyrics` 节下发。
     *
     * 为什么只取已绑定的行：未绑定的行是按「标题+艺术家」缓存的（`key = "title|artist"`），
     * 而两端的归一化规则不同（全半角、括号后缀、feat. 写法…）⇒ 对端拿到 `key` 也对不上人。
     * `songId` 能一路映射到曲目的 `dedupKey`，那是两端**一致**的稳定键（契约 D3）。
     *
     * `ORDER BY key` 而非 `updatedAt`：分页必须有一个**稳定**的排序键，否则同一次快照的
     * 第 1 页与第 2 页之间若有写入（`updatedAt` 变化）会出现行重复或漏取。
     *
     * 子查询把**同一 songId 的多行收敛成最新一条**：展示侧取歌词本来就用
     * [getBySongId]（`ORDER BY updatedAt DESC LIMIT 1`），若把同曲目的多行都发出去，
     * 对端会反复覆盖同一曲目的歌词，最终留下哪一条取决于传输顺序 —— 不确定行为。
     */
    @Query(
        "SELECT * FROM lyrics WHERE songId IS NOT NULL " +
            "AND updatedAt = (SELECT MAX(x.updatedAt) FROM lyrics x WHERE x.songId = lyrics.songId) " +
            "ORDER BY `key` LIMIT :limit OFFSET :offset"
    )
    suspend fun getBoundPaged(offset: Int, limit: Int): List<LyricCacheEntity>

    @Query("DELETE FROM lyrics")
    suspend fun clearAll()
}

@Dao
interface MetadataCacheDao {
    @Query("SELECT * FROM metadata WHERE key = :key AND type = :type")
    suspend fun get(key: String, type: String): MetadataCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MetadataCacheEntity)

    /** DC：删除指定 key+type 的缓存条目（读取时发现过期即删）。 */
    @Query("DELETE FROM metadata WHERE key = :key AND type = :type")
    suspend fun deleteByKey(key: String, type: String)

    @Query("DELETE FROM metadata WHERE updatedAt < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM metadata")
    suspend fun clearAll()
}