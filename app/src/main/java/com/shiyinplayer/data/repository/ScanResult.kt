package com.shiyinplayer.data.repository

import com.shiyinplayer.data.media.ScanMode

/** 单源扫描实时进度（供来源列表「扫描状态」中显示计数提示）；无扫描时对应的 StateFlow 值为 null。 */
data class ScanProgress(
    val sourceId: Long,
    val mode: ScanMode,
    val added: Int,
    val updated: Int
)

/** 扫描结果汇总。 */
data class ScanResult(
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val errors: List<String> = emptyList()
) {
    val total: Int get() = added + updated
}
