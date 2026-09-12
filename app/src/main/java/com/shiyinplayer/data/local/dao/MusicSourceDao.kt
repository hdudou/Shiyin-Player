package com.shiyinplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.shiyinplayer.data.local.entity.MusicSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicSourceDao {
    @Query("SELECT * FROM music_sources ORDER BY id")
    fun observeAll(): Flow<List<MusicSourceEntity>>

    @Query("SELECT * FROM music_sources WHERE id = :id")
    suspend fun getById(id: Long): MusicSourceEntity?

    @Insert
    suspend fun insert(source: MusicSourceEntity): Long

    @Update
    suspend fun update(source: MusicSourceEntity)

    @Query("DELETE FROM music_sources WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM music_sources")
    suspend fun clearAll()
}
