package com.shiyinplayer.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.repository.PlaylistRepository
import com.shiyinplayer.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistRepo: PlaylistRepository,
    private val playerManager: PlayerManager
) : ViewModel() {
    val playlists = playlistRepo.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun create(name: String) = viewModelScope.launch { playlistRepo.createPlaylist(name) }
    fun rename(id: Long, name: String) = viewModelScope.launch { playlistRepo.renamePlaylist(id, name) }
    fun delete(id: Long) = viewModelScope.launch { playlistRepo.deletePlaylist(id) }

    /** 播放当前歌单（替换播放队列）。 */
    fun playPlaylist(id: Long) = viewModelScope.launch {
        val songs = playlistRepo.getPlaylistSongs(id).first()
        if (songs.isNotEmpty()) playerManager.playQueue(songs, 0)
    }
}
