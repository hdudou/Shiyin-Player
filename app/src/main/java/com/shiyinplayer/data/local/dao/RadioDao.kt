package com.shiyinplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.shiyinplayer.data.local.entity.RadioHistoryEntity
import com.shiyinplayer.data.local.entity.RadioStationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RadioStationDao {
    @Query("SELECT * FROM radio_station ORDER BY name COLLATE LOCALIZED")
    fun observeAll(): Flow<List<RadioStationEntity>>

    @Query("SELECT * FROM radio_station ORDER BY name COLLATE LOCALIZED")
    suspend fun getAllStationsSync(): List<RadioStationEntity>

    @Query("SELECT * FROM radio_station WHERE source = :source ORDER BY name COLLATE LOCALIZED")
    fun observeBySource(source: String): Flow<List<RadioStationEntity>>

    @Query("SELECT * FROM radio_station WHERE isFavorite = 1 ORDER BY name COLLATE LOCALIZED")
    fun observeFavorites(): Flow<List<RadioStationEntity>>

    @Query("SELECT * FROM radio_station WHERE isFavorite = 1 ORDER BY name COLLATE LOCALIZED")
    suspend fun getFavoritesSync(): List<RadioStationEntity>

    @Query("SELECT * FROM radio_station WHERE id = :id")
    suspend fun getById(id: Long): RadioStationEntity?

    @Query("SELECT * FROM radio_station WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): RadioStationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(station: RadioStationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stations: List<RadioStationEntity>)

    @Update
    suspend fun update(station: RadioStationEntity)

    @Delete
    suspend fun delete(station: RadioStationEntity)

    @Query("DELETE FROM radio_station WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM radio_station")
    suspend fun count(): Int

    @Query("DELETE FROM radio_station WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("UPDATE radio_station SET isFavorite = 1 WHERE id = :id")
    suspend fun setFavorite(id: Long)

    @Query("UPDATE radio_station SET isFavorite = 0 WHERE id = :id")
    suspend fun unsetFavorite(id: Long)

    /**
     * 原子化执行多个电台的增删改，保证数据库一致性。
     * Seeder 同步逻辑需要整体成功或回滚。
     */
    @Transaction
    suspend fun syncBuiltInStations(
        toRemove: List<RadioStationEntity>,
        toInsert: List<RadioStationEntity>,
        toUpdate: List<RadioStationEntity>
    ) {
        toRemove.forEach { delete(it) }
        toInsert.forEach { insert(it.copy(isFavorite = false)) }
        toUpdate.forEach { update(it) }
    }

    /**
     * 内置电台远程清单同步（RadioBuiltInUpdater）——按 URL 匹配，且不覆盖用户已修改的电台。
     * - URL 已在库且 source=="builtin" → 更新其字段；
     * - URL 已在库但 source≠builtin（用户长按修改后已接管为 user）→ 跳过不覆盖；
     * - URL 不在库 → 作为新内置台插入；
     * - 本地 builtin 而远程无 → 作为下架删除。
     * 原子执行，整体成功或回滚，避免重启被杀导致半同步。
     */
    @Transaction
    suspend fun syncBuiltInFromRemote(remoteStations: List<RadioStationEntity>) {
        val existingAll = getAllStationsSync()
        val existingByUrl = existingAll.associateBy { it.url }
        val remoteUrls = remoteStations.map { it.url }.toMutableSet()
        val now = System.currentTimeMillis()

        val toRemove = existingAll.filter { it.source == "builtin" && it.url !in remoteUrls }
        val toInsert = mutableListOf<RadioStationEntity>()
        val toUpdate = mutableListOf<RadioStationEntity>()
        for (remote in remoteStations) {
            val existing = existingByUrl[remote.url]
            when {
                existing == null -> toInsert.add(
                    remote.copy(source = "builtin", isFavorite = false, createdAt = now, updatedAt = now)
                )
                existing.source == "builtin" -> toUpdate.add(
                    existing.copy(
                        name = remote.name,
                        genre = remote.genre,
                        country = remote.country,
                        logoUrl = remote.logoUrl,
                        updatedAt = now
                    )
                )
                // else：source != builtin → 用户已修改接管，跳过不覆盖
            }
        }
        toRemove.forEach { delete(it) }
        toInsert.forEach { insert(it) }
        toUpdate.forEach { update(it) }
    }
}

@Dao
interface RadioHistoryDao {
    @Query("SELECT * FROM radio_history ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<RadioHistoryEntity>>

    @Query("SELECT * FROM radio_history WHERE stationId = :stationId LIMIT 1")
    suspend fun getByStationId(stationId: Long): RadioHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: RadioHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: RadioHistoryEntity)

    @Query("DELETE FROM radio_history WHERE stationId = :stationId")
    suspend fun deleteByStationId(stationId: Long)

    @Query("DELETE FROM radio_history WHERE id = :id")
    suspend fun deleteById(id: Long)
}
