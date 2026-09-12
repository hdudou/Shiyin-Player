package com.shiyinplayer.player

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * AZ-删除对账：删曲/删源等数据库删除事务与播放器内存队列解耦的轻量通知。
 *
 * PlayerManager 订阅 [deletions] 触发内存队列剔除失效曲目；LibraryRepository / LibraryScanner
 * 在删除写库完成后调用 [notifyDeleted]。独立单例避免 PlayerManager <-> LibraryRepository
 * 构造依赖环（PlayerManager 已注入 LibraryRepository）。
 */
@Singleton
class QueueReconciler @Inject constructor() {
    private val _deletions =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val deletions: SharedFlow<Unit> = _deletions.asSharedFlow()

    fun notifyDeleted() {
        _deletions.tryEmit(Unit)
    }
}