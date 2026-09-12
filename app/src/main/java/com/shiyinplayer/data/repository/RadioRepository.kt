package com.shiyinplayer.data.repository

import com.shiyinplayer.data.local.dao.RadioHistoryDao
import com.shiyinplayer.data.local.dao.RadioStationDao
import com.shiyinplayer.data.local.entity.RadioHistoryEntity
import com.shiyinplayer.data.local.entity.RadioStationEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 收音机数据仓库：封装 RadioStationDao + RadioHistoryDao。
 * ViewModel 仅注入此仓库，不直接操作 DAO。
 */
@Singleton
class RadioRepository @Inject constructor(
    private val stationDao: RadioStationDao,
    private val historyDao: RadioHistoryDao
) {
    // ---- 电台 ----
    fun getAllStations(): Flow<List<RadioStationEntity>> = stationDao.observeAll()

    fun getFavoriteStations(): Flow<List<RadioStationEntity>> = stationDao.observeFavorites()

    /**
     * 同步读收藏列表（用于媒体按键/通知栏下一首等不能挂协程的场景）。
     * 注意：按 name 排序，与收藏页一致。
     */
    suspend fun getFavoriteStationsSync(): List<RadioStationEntity> = stationDao.getFavoritesSync()

    suspend fun getStationById(id: Long): RadioStationEntity? = stationDao.getById(id)

    suspend fun findStationByUrl(url: String): RadioStationEntity? = stationDao.getByUrl(url)

    suspend fun insertStation(station: RadioStationEntity): Long = stationDao.insert(station)

    suspend fun insertStations(stations: List<RadioStationEntity>) = stationDao.insertAll(stations)

    suspend fun deleteStation(station: RadioStationEntity) = stationDao.delete(station)

    suspend fun updateStation(station: RadioStationEntity) = stationDao.update(station)

    suspend fun setFavorite(id: Long, favorite: Boolean) {
        if (favorite) stationDao.setFavorite(id) else stationDao.unsetFavorite(id)
    }

    // ---- 历史 ----
    fun getRecentHistory(limit: Int = 20): Flow<List<RadioHistoryEntity>> =
        historyDao.observeRecent(limit)

    suspend fun insertHistory(history: RadioHistoryEntity) = historyDao.upsert(history)
}
