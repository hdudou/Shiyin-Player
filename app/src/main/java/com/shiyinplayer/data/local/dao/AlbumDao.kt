package com.shiyinplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shiyinplayer.data.local.entity.AlbumEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY name COLLATE LOCALIZED")
    fun observeAll(): Flow<List<AlbumEntity>>

    /** 2026-08-28：分页拉取全表（导出等后台批量场景替代 observeAll().first()）。 */
    @Query("SELECT * FROM albums ORDER BY name COLLATE LOCALIZED LIMIT :limit OFFSET :offset")
    suspend fun getAllPaged(offset: Int, limit: Int): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getById(id: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE name = :name AND (artistName IS :artist OR artistName IS NULL)")
    fun observeByName(name: String, artist: String?): Flow<List<AlbumEntity>>

    /** 局域网同步：按专辑名 + 歌手定位（命中则更新，否则插入）。 */
    @Query(
        "SELECT * FROM albums WHERE name = :name AND " +
            "(artistName = :artist OR (artistName IS NULL AND :artist IS NULL)) LIMIT 1"
    )
    suspend fun getByName(name: String, artist: String?): AlbumEntity?

    @Query("SELECT * FROM albums WHERE artistName LIKE :artistName ORDER BY year, name COLLATE LOCALIZED")
    fun observeByArtistName(artistName: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE name LIKE :q ESCAPE '\\' OR artistName LIKE :q ESCAPE '\\'")
    fun search(q: String): Flow<List<AlbumEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(albums: List<AlbumEntity>)

    @Update
    suspend fun update(album: AlbumEntity)

    /** 局域网同步：按主键删除（远端下发的删除指令）。 */
    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 需求⑤：还有多少专辑行引用这个封面（封面文件删除前的**引用计数**判据）。
     *
     * 同专辑多首曲目共用一个封面文件是常态，删曲目时若直接删文件必然误删别人的封面；
     * 所以删除前先问「songs / albums 里还有没有人引用它」，两个都为 0 才真删。
     */
    @Query("SELECT COUNT(*) FROM albums WHERE albumArtUri = :uri")
    suspend fun countByAlbumArtUri(uri: String): Int

    @Query("DELETE FROM albums")
    suspend fun clear()
}
