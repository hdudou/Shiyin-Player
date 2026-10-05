package com.shiyinplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.shiyinplayer.data.model.MediaSourceType

/** 音乐库来源表（本地/SMB/WebDAV/HTTP；configJson 存类型相关配置）。 */
@Entity(tableName = "music_sources")
data class MusicSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: MediaSourceType,
    val configJson: String = "{}",
    val enabled: Boolean = true,
    val lastScanTime: Long = 0,
    // ===== 局域网同步（PC 主控推送，DB v13） =====
    /** PC 侧创建时间（Unix ms，契约 createdAt）。 */
    val createdAt: Long = 0,
    /** PC 侧最后修改时间（Unix ms，契约 updatedAt）。 */
    val updatedAt: Long = 0
)
