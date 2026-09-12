package com.shiyinplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 文件夹 tab 的目录树持久化条目（扫描时预生成，tab 直接查库加载）。
 *
 * 每一条代表某【音乐来源】目录树上的一个节点（目录或音乐文件）：
 * - 目录行：`isDir = true`，[songCount] 为该目录（含子文件夹）递归歌曲数。
 * - 文件行：`isDir = false`，[songId] 关联 songs 表的歌曲主键；[folderPath] 为完整相对路径。
 *
 * 根目录（相对路径为空）不单独建条目，直接用 `parentPath = ''` 查询其直接子项。
 * 未归入任何来源的歌曲置于 `sourceId = -1`（「其他来源」）。
 */
@Entity(
    tableName = "folder_entry",
    indices = [
        Index(value = ["sourceId", "parentPath"]),
        Index(value = ["sourceId", "isDir", "folderPath"]),
        Index(value = ["folderPath"])
    ]
)
data class FolderEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属来源 id；-1 = 其他来源。 */
    val sourceId: Long,
    /** 父目录相对路径（根目录为空串）。 */
    val parentPath: String,
    /** 本条目显示名（目录名或文件名）。 */
    val name: String,
    /** 本条目完整相对路径（相对源根，目录不含尾斜杠；文件含文件名）。 */
    val folderPath: String,
    /** true = 目录节点，false = 音乐文件节点。 */
    val isDir: Boolean,
    /** 目录：递归歌曲数；文件：恒 1。 */
    val songCount: Int,
    /** 文件节点关联的歌曲主键；目录节点为 null。 */
    val songId: Long? = null
)