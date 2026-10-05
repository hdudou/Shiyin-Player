package com.shiyinplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.shiyinplayer.data.local.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE LOCALIZED")
    fun observeAll(): Flow<List<PlaylistEntity>>

    /** 局域网同步：按歌单名定位（PC 侧 `playlistName` 是对齐键；删除 op 也只用名字，顶层不带 dedupKey）。 */
    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): PlaylistEntity?

    /** 局域网同步 /sync/library 分页快照（suspend，避免大结果集 Flow 触发 CursorWindow 溢出）。 */
    @Query("SELECT * FROM playlists ORDER BY name COLLATE LOCALIZED LIMIT :limit OFFSET :offset")
    suspend fun getAllPaged(offset: Int, limit: Int): List<PlaylistEntity>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * **LWW 时间戳**（2026-09-17）：歌单成员（`playlist_items`）发生任何增删改序之后必须调用，
     * 把歌单自身的 `dateModified` 推到当前时刻。
     *
     * `playlist_items` 表**没有时间戳列**（契约里成员行只有
     * `playlistId/playlistName/songDedupKey/position`），故成员变更以其所属歌单的时间戳代表 ——
     * LWW 判定时整张歌单（含成员集合）作为一个整体比较先后。不调用的话，
     * 「在安卓上往歌单里加歌」不会让歌单变新，PC 推送时会把这次变更覆盖掉。
     */
    @Query("UPDATE playlists SET dateModified = CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) WHERE id = :id")
    suspend fun touch(id: Long)

    @Query("DELETE FROM playlists")
    suspend fun deleteAll()
}
