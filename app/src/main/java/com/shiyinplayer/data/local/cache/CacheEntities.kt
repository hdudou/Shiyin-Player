package com.shiyinplayer.data.local.cache

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 歌词缓存：键 = "title|artist"。2026-08-19：加 songId 与歌曲条目绑定（绑定后永不过期）。
 *
 * `songId` 单列索引：播放页加载歌词时按 `WHERE songId = ?` 优先取绑定歌词，
 * 删曲目时按 `songId IN (...)` 清缓存 —— 没有索引这两个查询都是全表扫描（歌词表随曲库增长）。
 */
@Entity(
    tableName = "lyrics",
    indices = [Index(value = ["songId"])]
)
data class LyricCacheEntity(
    @PrimaryKey val key: String,
    val lrcText: String,
    val translatedText: String?,
    val source: String,
    val updatedAt: Long,
    /** 2026-08-19 需求：绑定的主库歌曲条目 id（songs.id）。非空 = 与歌曲绑定，查询/展示永不过期。 */
    val songId: Long? = null
)

/** 元数据缓存（专辑封面/歌手头像/简介）。type = song / artist。 */
@Entity(tableName = "metadata")
data class MetadataCacheEntity(
    @PrimaryKey val key: String,
    val type: String,
    val payload: String,
    val updatedAt: Long
)
