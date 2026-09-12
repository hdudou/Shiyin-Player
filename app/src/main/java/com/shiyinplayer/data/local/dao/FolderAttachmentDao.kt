package com.shiyinplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.shiyinplayer.data.local.entity.FolderAttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderAttachmentDao {

    /** 某目录下的附件（封面前、文本后）。 */
    @Query("SELECT * FROM folder_attachment WHERE sourceId = :sourceId AND parentPath = :parent ORDER BY kind, name COLLATE LOCALIZED")
    fun observeByParent(sourceId: Long, parent: String): Flow<List<FolderAttachmentEntity>>

    @Insert
    suspend fun insertAll(list: List<FolderAttachmentEntity>)

    @Query("DELETE FROM folder_attachment WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    @Query("DELETE FROM folder_attachment")
    suspend fun clearAll()
}