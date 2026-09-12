package com.shiyinplayer.data.model

/** 播放队列项（运行期，不持久化）。 */
data class QueueItem(
    val song: Song,
    val position: Int = 0
)
