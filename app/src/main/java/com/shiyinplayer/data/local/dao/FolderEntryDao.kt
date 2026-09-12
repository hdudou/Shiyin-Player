package com.shiyinplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.shiyinplayer.data.local.entity.FolderEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderEntryDao {

    /** 观察某源某目录的直接子项（目录在前、文件在后，各自按名排序）。 */
    @Query(
        "SELECT * FROM folder_entry WHERE sourceId = :sourceId AND parentPath = :parent " +
            "ORDER BY isDir DESC, name COLLATE LOCALIZED"
    )
    fun observeByParent(sourceId: Long, parent: String): Flow<List<FolderEntryEntity>>

    /** 某源是否已存在任何条目（用于判断「其他来源」是否有内容）。 */
    @Query("SELECT COUNT(*) FROM folder_entry WHERE sourceId = :sourceId")
    fun observeCount(sourceId: Long): Flow<Int>

    /** 取某目录（含子文件夹）递归下的全部文件歌曲 id；[prefix] 为目录完整相对路径。 */
    @Query(
        "SELECT songId FROM folder_entry WHERE sourceId = :sourceId AND isDir = 0 AND folderPath LIKE :likePattern ORDER BY folderPath"
    )
    suspend fun fileSongIdsRecursive(sourceId: Long, likePattern: String): List<Long>

    /** 取某源根目录下全部文件歌曲 id（相对路径为空情况）。 */
    @Query("SELECT songId FROM folder_entry WHERE sourceId = :sourceId AND isDir = 0 ORDER BY folderPath")
    suspend fun allFileSongIds(sourceId: Long): List<Long>

    /** 取某目录的全部直接子文件夹（用于判断单链折叠：size==1 即需下探）。 */
    @Query("SELECT * FROM folder_entry WHERE sourceId = :sourceId AND parentPath = :parent AND isDir = 1 ORDER BY name COLLATE LOCALIZED")
    suspend fun directDirs(sourceId: Long, parent: String): List<FolderEntryEntity>

    @Insert
    suspend fun insertAll(entries: List<FolderEntryEntity>)

    @Query("DELETE FROM folder_entry")
    suspend fun clearAll()

    /** 原子整体替换目录树：清空 + 分批重建在同一事务内，避免中断时留下"表已清空、树未写入"的半写入状态。 */
    @Transaction
    suspend fun replaceAll(entries: List<FolderEntryEntity>) {
        clearAll()
        if (entries.isNotEmpty()) {
            entries.chunked(500).forEach { insertAll(it) }
        }
    }
}