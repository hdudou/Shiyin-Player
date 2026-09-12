package com.shiyinplayer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 网络电台（Phase 0 · P0-4）。来源：builtin（内置清单）、user（手动添加）、browsing（RadioBrowser 目录）。 */
@Entity(tableName = "radio_station")
data class RadioStationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    @ColumnInfo(name = "logoUrl") val logoUrl: String? = null,
    val genre: String? = null,
    val country: String? = null,
    /** builtin | user | browsing */
    val source: String = "user",
    @ColumnInfo(name = "isFavorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "createdAt") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updatedAt") val updatedAt: Long = System.currentTimeMillis()
)
