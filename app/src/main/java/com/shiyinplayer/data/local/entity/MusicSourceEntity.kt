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
    val lastScanTime: Long = 0
)
