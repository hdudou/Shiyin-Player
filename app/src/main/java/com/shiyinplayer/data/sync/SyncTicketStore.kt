package com.shiyinplayer.data.sync

import android.content.Context
import android.util.Log
import com.shiyinplayer.data.sync.model.OpResult
import com.shiyinplayer.data.sync.model.PendingBatch
import com.shiyinplayer.data.sync.model.SyncBatchStatus
import com.shiyinplayer.data.sync.model.SyncOp
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 推送批次暂存（内存 + 落盘可靠队列，契约 §6）。
 *
 * 本机无需确认即落地，因此这里的角色从「待用户决定的队列」变成**「落地过程与结果的凭据」**：
 * - `/sync/push` 暂存后立刻返回 `needsConfirm:false`，PC 轮询 `/sync/confirm` 读到
 *   `pending`（= 正在落地）→ `allowed`（= 已落地，见 [SyncAutoApplier]）
 * - PC 随后取 `/sync/result` 拿每 op 结果
 *
 * 为什么仍要落盘：批次停在 `PENDING` 时进程被杀 → 重启后由 [SyncAutoApplier.recoverAndApply]
 * 补落地，数据不丢；纯内存方案会让批次凭空消失，而 PC 侧只会看到轮询超时。
 *
 * 文件：`filesDir/sync/pending/<ticketId>.json`，内容即 [PendingBatch.toJson]。
 */
@Singleton
class SyncTicketStore @Inject constructor(context: Context) {

    private val pendingDir = File(context.filesDir, "sync/pending")

    /** 内存态为权威；落盘仅用于进程重启恢复。三处访问点跨线程（HTTP 工作线程 / 主线程），故全程加锁。 */
    private val batches = LinkedHashMap<String, PendingBatch>()

    init {
        pendingDir.mkdirs()
    }

    // ------------------------------------------------------------ 写

    /** 暂存一批 op，返回新 ticket（调用方随即交给 [SyncAutoApplier] 落地）。 */
    fun stash(ops: List<SyncOp>, deviceName: String): PendingBatch {
        val batch = PendingBatch(
            ticketId = UUID.randomUUID().toString().replace("-", ""),
            receivedAt = System.currentTimeMillis(),
            status = SyncBatchStatus.PENDING,
            ops = ops,
            deviceName = deviceName
        )
        synchronized(this) {
            // 防磁盘无限增长：正常路径下批次会被立刻落地而离开 PENDING，这里只兜底
            // 「落地持续失败导致它一直挂着」的极端情况，保留最近 MAX_PENDING 批
            while (batches.size >= MAX_PENDING) {
                val oldest = batches.entries.firstOrNull() ?: break
                batches.remove(oldest.key)
                deleteFile(oldest.key)
            }
            batches[batch.ticketId] = batch
            writeFile(batch)
        }
        return batch
    }

    fun setStatus(ticketId: String, status: String) {
        synchronized(this) {
            val batch = batches[ticketId] ?: return
            batch.status = status
            writeFile(batch)
        }
    }

    /** 落地后回填每 op 结果（PC 取 `/sync/result` 读它）。 */
    fun fillResults(ticketId: String, results: List<OpResult>) {
        synchronized(this) {
            val batch = batches[ticketId] ?: return
            batch.results.clear()
            batch.results.addAll(results)
            writeFile(batch)
        }
    }

    // ------------------------------------------------------------ 读

    fun get(ticketId: String): PendingBatch? = synchronized(this) { batches[ticketId] }

    /** 尚未终结（仍为 `pending`）的批次 ticket，按到达顺序；用于启动时补落地。 */
    fun unfinished(): List<String> = synchronized(this) {
        batches.values.filter { it.status == SyncBatchStatus.PENDING }.map { it.ticketId }
    }

    /** `/sync/status` 的 `pendingBatches`：正在落地（或落地异常滞留）的批次数。 */
    fun pendingCount(): Int = synchronized(this) {
        batches.values.count { it.status == SyncBatchStatus.PENDING }
    }

    // ------------------------------------------------------------ 启动恢复

    /**
     * 启动恢复：读取落盘批次。
     * - 未终结（`pending`）→ 载入内存并**返回其 ticket**，由调用方补落地（无用户介入）
     * - 已终结（`allowed`）→ 载入内存，供 PC 补查 `/sync/result`
     *
     * @return 需要补落地的 ticket 列表（按落盘顺序）
     */
    fun recover(): List<String> {
        val files = pendingDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val unfinished = ArrayList<String>()
        synchronized(this) {
            for (file in files) {
                val batch = runCatching { PendingBatch.fromJson(JSONObject(file.readText())) }.getOrNull()
                if (batch == null) {
                    file.delete()
                    continue
                }
                batches[batch.ticketId] = batch
                if (batch.status == SyncBatchStatus.PENDING) {
                    unfinished.add(batch.ticketId)
                    Log.i(TAG, "恢复未落地批次 ${batch.ticketId}（${batch.ops.size} ops），待补落地")
                }
            }
        }
        return unfinished
    }

    /** 清理已终结且超过保留期的批次文件（避免 filesDir 长期堆积）。 */
    fun prune() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            val done = batches.values.filter {
                it.status != SyncBatchStatus.PENDING && now - it.receivedAt > DONE_RETENTION_MS
            }
            done.forEach {
                batches.remove(it.ticketId)
                deleteFile(it.ticketId)
            }
        }
    }

    fun clearAll() {
        synchronized(this) {
            batches.keys.toList().forEach { deleteFile(it) }
            batches.clear()
        }
    }

    // ------------------------------------------------------------ 落盘

    private fun fileFor(ticketId: String) = File(pendingDir, "$ticketId.json")

    private fun writeFile(batch: PendingBatch) {
        runCatching {
            val tmp = File(pendingDir, "${batch.ticketId}.json.tmp")
            tmp.writeText(batch.toJson().toString())
            // 原子改名：避免进程被杀留下半截 JSON 被当成有效批次
            if (!tmp.renameTo(fileFor(batch.ticketId))) {
                fileFor(batch.ticketId).writeText(batch.toJson().toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "暂存批次落盘失败 ${batch.ticketId}: ${it.message}") }
    }

    private fun deleteFile(ticketId: String) {
        runCatching { fileFor(ticketId).delete() }
    }

    private companion object {
        const val TAG = "SyncTicketStore"
        const val MAX_PENDING = 20
        /** 已终结批次的磁盘保留期（PC 轮询已结束后仍允许其补查 result）。 */
        const val DONE_RETENTION_MS = 30 * 60 * 1000L
    }
}
