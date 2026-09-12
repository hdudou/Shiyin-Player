package com.shiyinplayer.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.model.Album
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlinx.coroutines.flow.asStateFlow

/** 专辑列表 ViewModel（T6）：支持按名称首字母 / 发行年代 / 艺术家重排。 */
@HiltViewModel
class AlbumsViewModel @Inject constructor(
    private val repo: LibraryRepository
) : ViewModel() {

    companion object {
        const val SORT_NAME = 0
        const val SORT_YEAR = 1
        const val SORT_ARTIST = 2
    }

    private val _sortMode = MutableStateFlow(SORT_NAME)
    val sortMode: StateFlow<Int> = _sortMode.asStateFlow()

    val albums = combine(repo.getAlbums(), _sortMode) { list, mode ->
        when (mode) {
            SORT_YEAR -> list.sortedBy { it.year ?: 0 }
            SORT_ARTIST -> list.sortedBy { it.artistName ?: "" }
            else -> list.sortedBy { it.name }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSortMode(mode: Int) { _sortMode.value = mode }

    /** F1-4：取选中专辑的全部曲目（合并去重），供「播放全部 / 加入歌单」。 */
    suspend fun songsFor(albums: List<Album>): List<Song> {
        val merged = mutableListOf<Song>()
        for (a in albums) merged += runCatching { repo.getSongsByAlbumName(a.name, a.artistName).first() }
            .getOrDefault(emptyList())
        return merged
    }
}
