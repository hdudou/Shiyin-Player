package com.shiyinplayer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 收音机播放历史（Phase 0 · P0-4）。记录每次收听时长与时间，用于「最近收听」排序。 */
@Entity(
    tableName = "radio_history",
    foreignKeys = [
        ForeignKey(
            entity = RadioStationEntity::class,
            parentColumns = ["id"],
            childColumns = ["stationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("stationId")]
)
data class RadioHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "stationId") val stationId: Long,
    @ColumnInfo(name = "listenedSeconds") val listenedSeconds: Int = 0,
    @ColumnInfo(name = "lastPlayedAt") val lastPlayedAt: Long = System.currentTimeMillis()
)
