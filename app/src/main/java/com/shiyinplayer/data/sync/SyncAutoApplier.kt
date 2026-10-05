package com.shiyinplayer.data.sync

import android.util.Log
import com.shiyinplayer.data.sync.model.OpResult
import com.shiyinplayer.data.sync.model.SyncBatchStatus
import com.shiyinplayer.ui.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PC 推送批次的**自动落地器**（无确认版，契约 §6）。
 *
 * 本机是「PC 主控 / 安卓仅接收」，PC 推来的变更**无需本机确认即直接写入曲库**，
 * 因此这里没有「允许 / 拒绝」的人机环节，只有一条路径：
 *
 * ```
 * /sync/push → ticketStore.stash(PENDING) → applyAsync(ticketId, deviceToken)
 *            → applyEngine.applyBatch(ops, deviceToken) → 回填 results → 标记 ALLOWED
 * ```
 *
 * 为什么**异步**应用而不是在 `/sync/push` 里同步写完再返回：
 * 一批最多 500 条 op（大面积 upsert / 歌单成员全量覆盖），写库耗时不可控；
 * 同步写会让 PC 侧 30s 的 HTTP 超时先到，PC 判失败而本机其实已写入。
 * 异步后 `/sync/push` 立返回 `needsConfirm:false`，PC 照旧轮询 `/sync/confirm`
 * 拿到 `allowed`（首次轮询可能读到 `pending`，即「正在落地」），语义与耗时都干净。
 *
 * **终止状态恒为 `ALLOWED`**（不存在用户拒绝）。单条 op 的成败由 `results` 如实回填；
 * 整批异常时把全部 op 标为失败并把异常信息带回去，PC 据此把对应变更记 `failed` 以便重试。
 *
 * 进程被杀导致批次停在 `PENDING` 的，下次启动由 [recoverAndApply] 补落地（无需用户介入）。
 */
@Singleton
class SyncAutoApplier @Inject constructor(
    private val ticketStore: SyncTicketStore,
    private val applyEngine: SyncApplyEngine,
    private val settings: SettingsRepository
) {

    /** 进程生命周期的作用域：批次落地不依赖任何 Activity / Service 存活。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化落地，避免多批并发写库互相干扰（PC 本身是「推一批等一批」，这里是兜底）。 */
    private val mutex = Mutex()

    /** `/sync/push` 暂存完成后调用：后台落地，不阻塞 HTTP 工作线程。 */
    fun applyAsync(ticketId: String, deviceToken: String? = null) {
        scope.launch { applyTicket(ticketId, deviceToken) }
    }

    /** 启动恢复：载入落盘批次，并把**所有未终结批次**补落地。 */
    fun recoverAndApply() {
        scope.launch {
            val unfinished = runCatching { ticketStore.recover() }
                .onFailure { Log.w(TAG, "恢复落盘批次失败：${it.message}") }
                .getOrDefault(emptyList())
            if (unfinished.isNotEmpty()) {
                Log.i(TAG, "启动恢复：待补落地批次 ${unfinished.size} 个")
            }
            // 恢复路径没有请求上下文 → token 传 null，由引擎回退到配对记录里的 token
            unfinished.forEach { applyTicket(it) }
        }
    }

    /**
     * 落地单个批次（幂等：已终结的批次直接跳过）。
     *
     * @param deviceToken 发起本批次的 PC 设备 token（凭据信封口令）。恢复路径为 null 时由
     *   [SyncApplyEngine.resolveEnvelopeToken] 回退到配对记录。
     * @return 本次是否真正执行了落地
     */
    suspend fun applyTicket(ticketId: String, deviceToken: String? = null): Boolean = mutex.withLock {
        val batch = ticketStore.get(ticketId) ?: run {
            Log.w(TAG, "批次 $ticketId 不在暂存区，跳过")
            return@withLock false
        }
        if (batch.status != SyncBatchStatus.PENDING) return@withLock false

        val startedAt = System.currentTimeMillis()
        val results = try {
            applyEngine.applyBatch(batch.ops, deviceToken)
        } catch (e: Exception) {
            // 整批失败（如 DB 异常）：逐条回填失败原因，PC 会把对应变更记 failed 并在下轮重推
            Log.w(TAG, "批次 $ticketId 落地异常：${e.message}", e)
            failAll(batch.ops.size, e.message ?: "apply_failed")
        }

        ticketStore.fillResults(ticketId, results)
        ticketStore.setStatus(ticketId, SyncBatchStatus.ALLOWED)
        settings.setLanSyncLastSyncAt(System.currentTimeMillis())
        ticketStore.prune()

        val failed = results.count { !it.ok }
        Log.i(
            TAG,
            "批次 $ticketId 已落地：${results.size} 条，失败 $failed 条，" +
                "耗时 ${System.currentTimeMillis() - startedAt}ms"
        )
        true
    }

    private fun failAll(count: Int, error: String): List<OpResult> =
        (0 until count).map { OpResult(it, false, error) }

    private companion object {
        const val TAG = "SyncAutoApplier"
    }
}
