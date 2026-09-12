package com.shiyinplayer.data.radio

import android.content.Context
import android.util.Log
import com.shiyinplayer.data.local.dao.RadioStationDao
import com.shiyinplayer.data.local.entity.RadioStationEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置电台清单播种器：每次启动比对 assets 版本，增删同步。
 * - 首次安装：全量写入
 * - 覆盖升级：保留 isFavorite 状态，仅更新变化的字段
 *   （删除已下线的 builtin；插入新增的 builtin；更新仍存在的 builtin 的名称/URL/分类）
 */
@Singleton
class RadioStationSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stationDao: RadioStationDao
) {
    companion object {
        private const val TAG = "RadioStationSeeder"
    }

    suspend fun seedIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val builtInStations = loadBuiltInStations()
            if (builtInStations.isEmpty()) return@withContext

            val existingAll = stationDao.getAllStationsSync()
            val existingBuiltIn = existingAll.filter { it.source == "builtin" }
            val existingByUrl = existingBuiltIn.associateBy { it.url }

            if (existingAll.isEmpty()) {
                // 首次安装：直接全量插入
                stationDao.insertAll(builtInStations)
                Log.i(TAG, "Seeded ${builtInStations.size} built-in radio stations")
                return@withContext
            }

            // 已有数据：增量同步，保留 isFavorite 状态
            val newUrls = builtInStations.map { it.url }.toSet()

            // 1. 计算要删除的 builtin（不在新清单中的）
            val toRemove = existingBuiltIn.filter { it.url !in newUrls }

            // 2. 计算要新增和更新的 builtin
            val toInsert = mutableListOf<RadioStationEntity>()
            val toUpdate = mutableListOf<RadioStationEntity>()
            builtInStations.forEach { newStation ->
                val existing = existingByUrl[newStation.url]
                if (existing == null) {
                    toInsert.add(newStation)
                } else if (existing.name != newStation.name ||
                           existing.genre != newStation.genre ||
                           existing.country != newStation.country ||
                           existing.logoUrl != newStation.logoUrl
                ) {
                    // 更新（保留 id 和 isFavorite）
                    toUpdate.add(
                        existing.copy(
                            name = newStation.name,
                            genre = newStation.genre,
                            country = newStation.country,
                            logoUrl = newStation.logoUrl,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }

            // 3. 在一个事务中执行所有增删改（原子化，避免 app 中途被杀导致不一致）
            stationDao.syncBuiltInStations(toRemove, toInsert, toUpdate)

            if (toRemove.isNotEmpty()) {
                Log.i(TAG, "Removed ${toRemove.size} obsolete built-in stations")
            }
            Log.i(TAG, "Re-seeded built-in: +${toInsert.size} new, ~${toUpdate.size} updated, -${toRemove.size} removed")
        } catch (e: Exception) {
            Log.e(TAG, "seedIfNeeded failed", e)
        }
    }

    private fun loadBuiltInStations(): List<RadioStationEntity> {
        val stations = mutableListOf<RadioStationEntity>()
        try {
            val jsonStr = context.assets.open("radio_stations.json").use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    reader.readText()
                }
            }

            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                stations.add(
                    RadioStationEntity(
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        genre = if (obj.has("genre")) obj.getString("genre") else null,
                        country = if (obj.has("country")) obj.getString("country") else null,
                        logoUrl = if (obj.has("logoUrl")) obj.getString("logoUrl") else null,
                        source = "builtin",
                        isFavorite = false,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadBuiltInStations failed", e)
        }
        return stations
    }
}
