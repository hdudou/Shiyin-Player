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

    @Query("DELETE FROM albums")
    suspend fun clear()
}
