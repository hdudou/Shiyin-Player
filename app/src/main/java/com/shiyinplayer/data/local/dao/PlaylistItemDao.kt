package com.shiyinplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    // removeAllForSong 见下方「LWW 时间戳」区的 @Transaction 版本（会一并前移受影响歌单的时间戳）

    @Query("SELECT MAX(position) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int?

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun countByPlaylistAndSong(playlistId: Long, songId: Long): Int

    @Query("SELECT playlistId, COUNT(*) AS cnt FROM playlist_items GROUP BY playlistId")
    fun observeCountByPlaylist(): Flow<List<PlaylistCount>>

    // ===== LWW 时间戳：歌单成员变更必须前移所属歌单的 dateModified（2026-09-17） =====
    // playlist_items 无时间戳列，成员变更由所属歌单的 dateModified 代表；否则 PC 侧会判
    // 「设备端对方未更新」而把这次成员变更覆盖掉。跨歌单操作（如删曲连带清引用）必须先用
    // 下面两个查询列出受影响歌单，操作完逐个 touch。

    /** 某曲当前所属的全部歌单 id。 */
    @Query("SELECT DISTINCT playlistId FROM playlist_items WHERE songId = :songId")
    suspend fun playlistIdsForSong(songId: Long): List<Long>

    /** 多曲所属的全部歌单 id（批量，N+1 规避）。 */
    @Query("SELECT DISTINCT playlistId FROM playlist_items WHERE songId IN (:songIds)")
    suspend fun playlistIdsForSongs(songIds: List<Long>): List<Long>

    /** 前移歌单的 LWW 时间戳（表达式见同包 SqlExpr.kt）。 */
    @Query("UPDATE playlists SET dateModified = CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) WHERE id = :id")
    suspend fun touchPlaylist(id: Long)

    /**
     * 删除某曲在所有歌单中的引用，并前移受影响歌单的 LWW 时间戳。
     * 原子执行（成员删除与时间戳同批），调用方无需感知 touch 细节。
     */
    @Transaction
    suspend fun removeAllForSong(songId: Long) {
        val affected = playlistIdsForSong(songId)
        removeAllForSongRaw(songId)
        affected.forEach { touchPlaylist(it) }
    }

    @Query("DELETE FROM playlist_items WHERE songId = :songId")
    suspend fun removeAllForSongRaw(songId: Long)

    /**
     * 重复曲目合并：把对 [ids] 的引用统一指向保留曲 [keepId]，并前移受影响歌单的 LWW 时间戳。
     * 内含「先删冲突条目」以绕开 (playlistId,songId) 唯一索引，两步与 touch 同一事务。
     */
    @Transaction
    suspend fun mergeDuplicateSongRefs(keepId: Long, ids: List<Long>) {
        if (ids.isEmpty()) return
        val affected = playlistIdsForSongs(ids)
        deleteConflictingForMerge(keepId, ids)
        remapSongRefs(keepId, ids)
        affected.forEach { touchPlaylist(it) }
    }

    @Query("SELECT * FROM playlist_items ORDER BY playlistId, position, id")
    suspend fun getAllOnce(): List<PlaylistItemEntity>

    /**
     * 局域网同步 /sync/library 分页快照：契约里成员行的形态是
     * `{playlistId, playlistName, songDedupKey, position}`，故需 join 出歌单名与歌曲 dedupKey。
     * 用 suspend 分页而非 Flow（Room 2.6.1 大结果集 Flow 会触发 CursorWindow NO_MEMORY 闪退）。
     */
    @Query(
        "SELECT i.playlistId AS playlistId, p.name AS playlistName, " +
            "s.dedupKey AS songDedupKey, i.position AS position " +
            "FROM playlist_items i " +
            "JOIN playlists p ON p.id = i.playlistId " +
            "JOIN songs s ON s.id = i.songId " +
            "ORDER BY i.playlistId, i.position, i.id LIMIT :limit OFFSET :offset"
    )
    suspend fun getPagedWithNames(offset: Int, limit: Int): List<PlaylistItemSyncRow>

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

/** /sync/library 成员行（已解出契约需要的歌单名与歌曲 dedupKey）。 */
data class PlaylistItemSyncRow(
    val playlistId: Long,
    val playlistName: String,
    val songDedupKey: String,
    val position: Int
)
