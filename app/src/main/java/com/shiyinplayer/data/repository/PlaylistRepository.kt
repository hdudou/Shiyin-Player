package com.shiyinplayer.data.repository

import androidx.room.withTransaction
import com.shiyinplayer.data.local.dao.PlaylistDao
import com.shiyinplayer.data.local.dao.PlaylistItemDao
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.mapper.EntityMappers.toModel
import com.shiyinplayer.data.model.Playlist
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.util.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 播放列表仓库（R-P0-09 / T9）。 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val db: com.shiyinplayer.data.local.AppDatabase,
    private val playlistDao: PlaylistDao,
    private val playlistItemDao: PlaylistItemDao,
    private val songDao: SongDao,
    private val dispatcher: DispatcherProvider
) {
    /** 全部歌单（含各自曲目数）。F1-2：「上次播放队列」快照歌单置顶显示，其余保持按名称排序。 */
    fun getPlaylists(): Flow<List<Playlist>> =
        combine(playlistDao.observeAll(), playlistItemDao.observeCountByPlaylist()) { pls, counts ->
            val m = counts.associate { it.playlistId to it.cnt }
            pls.map { it.toModel().copy(songCount = m[it.id] ?: 0) }
                // sortedByDescending 稳定，快照置顶且不改变其它歌单相对顺序
                .sortedByDescending { it.name == QUEUE_SNAPSHOT_NAME }
        }.flowOn(dispatcher.io)

    suspend fun createPlaylist(name: String): Long {
        val now = System.currentTimeMillis()
        return playlistDao.insert(
            com.shiyinplayer.data.local.entity.PlaylistEntity(name = name, dateCreated = now, dateModified = now)
        )
    }

    suspend fun renamePlaylist(id: Long, name: String) {
        // EJ：先读取原实体保留 dateCreated，避免重命名时创建时间被重置为 0
        val existing = playlistDao.observeAll().first().firstOrNull { it.id == id } ?: return
        playlistDao.update(
            existing.copy(name = name, dateModified = System.currentTimeMillis())
        )
    }

    suspend fun deletePlaylist(id: Long) {
        db.withTransaction {
            playlistDao.deleteById(id)
            playlistItemDao.clear(id)
        }
    }

    /** 单首加入（CD-去重：已在歌单则忽略，与 [addSongsToPlaylist] 批量入口语义一致，避免 REPLACE 删旧行致 position 断裂）。 */
    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        if (playlistItemDao.countByPlaylistAndSong(playlistId, songId) > 0) return
        val position = (playlistItemDao.maxPosition(playlistId) ?: -1) + 1
        playlistItemDao.insert(
            com.shiyinplayer.data.local.entity.PlaylistItemEntity(
                playlistId = playlistId, songId = songId, position = position
            )
        )
    }

    /** 批量加入（去重，保持原顺序）。 */
    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        val existing = playlistItemDao.observeByPlaylist(playlistId).first().map { it.songId }.toSet()
        var position = (playlistItemDao.maxPosition(playlistId) ?: -1) + 1
        val items = songIds.filter { it !in existing }.map { id ->
            com.shiyinplayer.data.local.entity.PlaylistItemEntity(
                playlistId = playlistId, songId = id, position = position++
            )
        }
        if (items.isNotEmpty()) playlistItemDao.insertAll(items)
    }

    suspend fun removePlaylistItem(playlistId: Long, songId: Long) =
        playlistItemDao.remove(playlistId, songId)

    fun getPlaylistSongs(playlistId: Long): Flow<List<Song>> =
        playlistItemDao.observeByPlaylist(playlistId).map { items ->
            val ids = items.map { it.songId }
            if (ids.isEmpty()) return@map emptyList()
            // P1-7：一次 IN 批量查询（songDao.getByIds）替代逐条 getByIdSync 的 N+1；按 id 列表保持原顺序。
            val byId = songDao.getByIds(ids).associateBy { it.id }
            ids.mapNotNull { byId[it]?.toModel() }
        }.flowOn(dispatcher.io)

    /**
     * playlist_autosave 接线（2026-08-21）：退出时把当前队列存为固定名歌单「上次播放队列」快照。
     * 已有则清空重建（保持单份快照），无则新建。
     */
    suspend fun saveQueueSnapshot(songs: List<Song>) {
        if (songs.isEmpty()) return
        val songIds = songs.mapNotNull { it.id.takeIf { id -> id > 0 } }.distinct()
        if (songIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = playlistDao.observeAll().first().firstOrNull { it.name == QUEUE_SNAPSHOT_NAME }
        val playlistId = existing?.id ?: playlistDao.insert(
            com.shiyinplayer.data.local.entity.PlaylistEntity(
                name = QUEUE_SNAPSHOT_NAME, dateCreated = now, dateModified = now
            )
        )
        db.withTransaction {
            playlistItemDao.clear(playlistId)
            val items = songIds.mapIndexed { pos, sid ->
                com.shiyinplayer.data.local.entity.PlaylistItemEntity(
                    playlistId = playlistId, songId = sid, position = pos
                )
            }
            if (items.isNotEmpty()) playlistItemDao.insertAll(items)
            playlistDao.update(
                com.shiyinplayer.data.local.entity.PlaylistEntity(
                    playlistId, QUEUE_SNAPSHOT_NAME,
                    dateCreated = existing?.dateCreated ?: now,
                    dateModified = System.currentTimeMillis()
                )
            )
        }
    }

    companion object {
        private const val QUEUE_SNAPSHOT_NAME = "上次播放队列"
    }
}
