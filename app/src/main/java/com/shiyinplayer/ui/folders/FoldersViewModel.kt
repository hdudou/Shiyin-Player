package com.shiyinplayer.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 文件夹浏览：按歌曲 path 的顶层目录名聚合（架构 Q10：基于 SongEntity.path 分组）。 */
@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val repo: LibraryRepository
) : ViewModel() {
    val folders: StateFlow<List<FolderItem>> = repo.getSongs().map { songs ->
        songs.mapNotNull { song ->
            song.path?.substringBeforeLast('/')?.substringAfterLast('/')
                ?: song.uri.substringBeforeLast('/').substringAfterLast('/')
        }.filter { it.isNotBlank() }
            .distinct()
            .map { FolderItem(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

data class FolderItem(val name: String)
