package com.shiyinplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shiyinplayer.data.local.entity.ArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists ORDER BY name COLLATE LOCALIZED")
    fun observeAll(): Flow<List<ArtistEntity>>

    /** 2026-08-28：分页拉取全表（导出等后台批量场景替代 observeAll().first()）。 */
    @Query("SELECT * FROM artists ORDER BY name COLLATE LOCALIZED LIMIT :limit OFFSET :offset")
    suspend fun getAllPaged(offset: Int, limit: Int): List<ArtistEntity>

    @Query("SELECT * FROM artists WHERE name LIKE :q ESCAPE '\\'")
    fun search(q: String): Flow<List<ArtistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artist: ArtistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(artists: List<ArtistEntity>)

    @Query("DELETE FROM artists")
    suspend fun clear()
}
