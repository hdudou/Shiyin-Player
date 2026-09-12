package com.shiyinplayer.ui.foldersdetail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 文件夹详情（P0 补全：R-P0-06 文件夹内歌曲列表）。按 Song.path 的父目录末段匹配。 */
@HiltViewModel
class FolderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: LibraryRepository,
    private val playerManager: PlayerManager
) : ViewModel() {

    private val folderName: String = Uri.decode(savedStateHandle.get<String>("folderName").orEmpty())

    val songs: StateFlow<List<Song>> = repo.getSongs()
        .map { list ->
            list.filter { song ->
                (song.path ?: song.uri).substringBeforeLast('/').substringAfterLast('/') == folderName
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun play(song: Song) {
        val list = songs.value
        val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playerManager.playQueue(list, idx)
    }
}