package com.shiyinplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放列表项表（playlist 与 song 的关联，含排序）。
 *
 * 两个索引各有用途，缺一不可：
 * - `(playlistId, songId)` 唯一：保证同一歌单内同一首歌不重复；
 * - `songId` 单列：删曲目 / 删源时按曲目清理歌单引用（`WHERE songId = ?`）走得到索引 ——
 *   复合索引的最左前缀是 playlistId，单列过滤用不上它，会退化成全表扫描。
 */
@Entity(
    tableName = "playlist_items",
    indices = [
        Index(value = ["playlistId", "songId"], unique = true),
        Index(value = ["songId"])
    ]
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: Long,
    val position: Int = 0
)
