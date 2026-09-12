package com.shiyinplayer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** F3-4：一组重复曲目的展示模型。keepId 为当前选中「保留」的成员，合并时其余成员被删除。 */
data class DuplicateGroupUi(
    val key: String,
    val title: String,
    val artist: String,
    val members: List<Song>,
    val keepId: Long
)

/** F3-4：重复曲目清理——按「标题+歌手+时长(2 秒桶)」聚合重复组，允许逐组选择保留项后合并。 */
@HiltViewModel
class DuplicateCleanupViewModel @Inject constructor(
    private val repo: LibraryRepository
) : ViewModel() {

    private val _groups = MutableStateFlow<List<DuplicateGroupUi>>(emptyList())
    val groups: StateFlow<List<DuplicateGroupUi>> = _groups.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    /** 重新拉取重复组（合并/删除后调用，实现即时刷新）。 */
    fun refresh() {
        viewModelScope.launch {
            _busy.value = false
            val rows = repo.getDuplicateGroups().first()
            _groups.value = buildList {
                rows.forEach { row ->
                    val ids = row.ids.split('\u0001').mapNotNull { it.trim().toLongOrNull() }
                    if (ids.size < 2) return@forEach
                    val members = repo.getDuplicateGroupMembers(ids)
                    if (members.size < 2) return@forEach
                    add(
                        DuplicateGroupUi(
                            key = row.dkey,
                            title = row.title ?: "未知标题",
                            artist = row.artist ?: "",
                            members = members,
                            keepId = members.first().id
                        )
                    )
                }
            }
        }
    }

    /** 切换某组选中的保留成员。 */
    fun selectKeep(groupKey: String, songId: Long) {
        _groups.value = _groups.value.map { g ->
            if (g.key == groupKey && g.members.any { it.id == songId }) g.copy(keepId = songId) else g
        }
    }

    /** 合并一组：保留 keepId，删除其余成员（含歌单引用重定向）。 */
    fun mergeGroup(g: DuplicateGroupUi) {
        val deleteIds = g.members.map { it.id }.filter { it != g.keepId }
        if (deleteIds.isEmpty() || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                repo.mergeDuplicateGroup(g.keepId, deleteIds)
                _message.value = "已合并「${g.title}」，保留 1 个，移除 ${deleteIds.size} 个重复项"
            } catch (e: Exception) {
                _message.value = "合并失败：${e.message}"
            } finally {
                refresh()
            }
        }
    }
}