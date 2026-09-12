package com.shiyinplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 文件夹附件持久化条目（扫描时随目录遍历收集，文件夹 tab 加载对应目录显示与预览）。
 *
 * 代表某【音乐来源】目录里的非曲目媒体文件——专辑封面图 / 专辑说明 txt。
 * 不参与曲目列表；仅当用户进入该目录时在列表展示，可点击预览。
 */
@Entity(
    tableName = "folder_attachment",
    indices = [Index(value = ["sourceId", "parentPath"])]
)
data class FolderAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属来源 id。 */
    val sourceId: Long,
    /** 附件所在目录的相对路径（相对源根，根目录为 ""）。 */
    val parentPath: String,
    /** 附件文件名。 */
    val name: String,
    /** 附件类型：0=专辑封面图（COVER），1=专辑说明文本（TEXT）。 */
    val kind: Int,
    /** 访问句柄：本地为绝对路径或 SAF content uri；SMB 为 smb url；WebDAV 为 http(s) url。 */
    val uri: String,
    /** 文件字节数。 */
    val size: Long = 0
) {
    companion object {
        const val KIND_COVER = 0
        const val KIND_TEXT = 1
    }

    /** 是否为专辑封面图。 */
    val isCover: Boolean get() = kind == KIND_COVER
}