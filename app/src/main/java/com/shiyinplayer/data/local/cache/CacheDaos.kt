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

    @Query("DELETE FROM lyrics WHERE updatedAt < :before")
    suspend fun prune(before: Long)

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