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

    /** 局域网同步：按名称定位（PC 的主键 id 对本机无意义，名称是两端共同的对齐键）。 */
    @Query("SELECT * FROM music_sources WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): MusicSourceEntity?

    /**
     * `/sync/library` 分页读取（契约 §4 分页约定）。
     * 与其它实体一致走 `LIMIT/OFFSET` 的 suspend 查询，不用 Flow（Room 2.6.1 大结果集 Flow 会闪退）。
     */
    @Query("SELECT * FROM music_sources ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun getPaged(offset: Int, limit: Int): List<MusicSourceEntity>

    @Insert
    suspend fun insert(source: MusicSourceEntity): Long

    @Update
    suspend fun update(source: MusicSourceEntity)

    @Query("DELETE FROM music_sources WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM music_sources")
    suspend fun clearAll()
}
