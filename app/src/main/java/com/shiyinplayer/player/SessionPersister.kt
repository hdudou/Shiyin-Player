package com.shiyinplayer.player

import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.mapper.EntityMappers.toModel
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 断点续播会话持久化（A1 步骤 1：从 PlayerManager 拆出的独立职责，行为等价）。
 *
 * 统一封装两个职责：
 * 1. 播放中按节流（5s）把当前队列快照写入设置（last_queue_ids / last_index / last_position）。
 *    一次 [persist] 调用 = 同步节流判定 + 异步 DataStore 写入，与拆分前语义一致。
 * 2. 启动时一次性读取会话并构造 [SessionSnapshot]（批量 IN 查询歌曲，替代逐条串行查询），
 *    是否/如何续播的决策仍由 PlayerManager 负责，本类只提供数据。
 */
@Singleton
class SessionPersister @Inject constructor(
    private val settings: SettingsRepository,
    private val songDao: SongDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastPersisted = 0L
    private var restoreChecked = false

    /** 播放中持久化当前队列快照。队列为空或距上次写入 <5s 则忽略。 */
    fun persist(queue: List<Song>, index: Int, positionMs: Long) {
        val ids = queue.map { it.id }
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastPersisted < 5_000) return
        lastPersisted = now
        scope.launch {
            runCatching { settings.setLastSession(ids, index, positionMs.coerceAtLeast(0)) }
        }
    }

    /**
     * 启动时构造续播快照。仅进程首次调用返回非空；受 startup_action / auto_resume 设置约束。
     * 返回 null 表示无会话或不应续播。
     */
    suspend fun buildRestoreSnapshot(): SessionSnapshot? {
        if (restoreChecked) return null
        restoreChecked = true
        val action = runCatching { settings.startupAction.first() }.getOrDefault("resume")
        if (action != "resume" && action != "play") return null
        val autoResume = runCatching { settings.autoResume.first() }.getOrDefault(true)
        if (!autoResume) return null
        val ids = runCatching { settings.lastQueueIds.first() }.getOrDefault(emptyList())
        if (ids.isEmpty()) return null
        val index = runCatching { settings.lastIndex.first() }.getOrDefault(-1)
        val position = runCatching { settings.lastPosition.first() }.getOrDefault(0L)
        // 批量一次 IN 查询，替代逐 id 串行 getById（万级队列恢复时显著提速）
        val songs = try {
            songDao.getByIds(ids).associateBy { it.id }.let { byId -> ids.mapNotNull { byId[it]?.toModel() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (songs.isEmpty()) return null
        // P-一致性：原队列被删除/修改后，恢复队列会过滤失效主键而缩容，
        // 旧绝对 index 直接 coerceIn 会错位（续播到错误曲目）。改按「当时的当前曲主键」
        // 在幸存队列中重新定位：当前曲仍在 → 对齐到它；否则兜底到最近有效位置。
        val currentId = ids.getOrNull(index.coerceIn(0, ids.size - 1))
        val startIndex = if (currentId != null) {
            songs.indexOfFirst { it.id == currentId }
                .takeIf { it >= 0 }
                ?: index.coerceIn(0, songs.size - 1)
        } else {
            index.coerceIn(0, songs.size - 1)
        }
        return SessionSnapshot(songs = songs, startIndex = startIndex, positionMs = position, action = action)
    }
}

/** 续播快照：由 [SessionPersister.buildRestoreSnapshot] 产出，PlayerManager 据此决定续播方式。 */
data class SessionSnapshot(
    val songs: List<Song>,
    val startIndex: Int,
    val positionMs: Long,
    val action: String
)