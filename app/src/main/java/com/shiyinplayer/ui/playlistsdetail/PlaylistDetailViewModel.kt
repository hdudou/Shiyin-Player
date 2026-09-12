package com.shiyinplayer.ui.playlistsdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.data.repository.PlaylistRepository
import com.shiyinplayer.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 播放列表详情（P0 补全：R-P0-07 创建/重命名/删除/添加/移除 + 播放）。 */
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepo: PlaylistRepository,
    private val libraryRepo: LibraryRepository,
    private val playerManager: PlayerManager
) : ViewModel() {

    private val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: 0L

    val playlistName: StateFlow<String> = playlistRepo.getPlaylists()
        .map { it.firstOrNull { p -> p.id == playlistId }?.name ?: "播放列表" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "播放列表")

    val songs: StateFlow<List<Song>> = playlistRepo.getPlaylistSongs(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSongs: StateFlow<List<Song>> = libraryRepo.getSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun play(song: Song) {
        val list = songs.value
        val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playerManager.playQueue(list, idx)
    }

    fun stop() = playerManager.stop()

    fun addSong(songId: Long) = viewModelScope.launch {
        playlistRepo.addSongToPlaylist(playlistId, songId)
    }

    fun removeSong(songId: Long) = viewModelScope.launch {
        playlistRepo.removePlaylistItem(playlistId, songId)
    }

    fun rename(name: String) = viewModelScope.launch {
        playlistRepo.renamePlaylist(playlistId, name)
    }

    fun delete() = viewModelScope.launch {
        playlistRepo.deletePlaylist(playlistId)
    }
}