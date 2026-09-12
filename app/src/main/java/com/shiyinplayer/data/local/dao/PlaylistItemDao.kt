package com.shiyinplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shiyinplayer.data.local.entity.PlaylistItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistItemDao {
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    fun observeByPlaylist(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PlaylistItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PlaylistItemEntity>)

    @Query("UPDATE playlist_items SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun setPosition(playlistId: Long, songId: Long, position: Int)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun remove(playlistId: Long, songId: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clear(playlistId: Long)

    @Query("DELETE FROM playlist_items WHERE songId = :songId")
    suspend fun removeAllForSong(songId: Long)

    @Query("SELECT MAX(position) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int?

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun countByPlaylistAndSong(playlistId: Long, songId: Long): Int

    @Query("SELECT playlistId, COUNT(*) AS cnt FROM playlist_items GROUP BY playlistId")
    fun observeCountByPlaylist(): Flow<List<PlaylistCount>>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId, position, id")
    suspend fun getAllOnce(): List<PlaylistItemEntity>

    @Query("DELETE FROM playlist_items")
    suspend fun deleteAllItems()

    // ===== F3-2/F3-4：重复曲目合并时歌单引用重定向 =====
    /** 先删除「歌单里已同时含保留曲 keepId 与重复曲 id」的重复条目，避免重定向后违反 (playlistId,songId) 唯一索引。 */
    @Query(
        "DELETE FROM playlist_items WHERE songId IN (:ids) AND playlistId IN " +
            "(SELECT playlistId FROM playlist_items WHERE songId = :keepId)"
    )
    suspend fun deleteConflictingForMerge(keepId: Long, ids: List<Long>)

    /** 把歌单中对重复曲的引用统一指向保留曲 keepId（保留位置、按原 order 继续）。 */
    @Query("UPDATE playlist_items SET songId = :keepId WHERE songId IN (:ids)")
    suspend fun remapSongRefs(keepId: Long, ids: List<Long>)
}
