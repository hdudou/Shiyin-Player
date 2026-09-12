package com.shiyinplayer.ui.smart

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 智能播放列表（P3）：recent=最近播放 / most=最常播放 / random=随机全部。 */
@HiltViewModel
class SmartPlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: LibraryRepository,
    private val playerManager: PlayerManager
) : ViewModel() {
    val type: String = savedStateHandle.get<String>("type") ?: "recent"

    val title: String = when (type) {
        "most" -> "最常播放"
        "random" -> "随机播放"
        else -> "最近播放"
    }

    val songs: StateFlow<List<Song>> = when (type) {
        "most" -> repo.getMostPlayed()
        "random" -> repo.getSongs().map { it.shuffled() }
        else -> repo.getRecentlyPlayed()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun play(song: Song) {
        val list = songs.value
        val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playerManager.playQueue(list, idx)
    }

    fun playAll() {
        val list = songs.value
        if (list.isNotEmpty()) playerManager.playQueue(list, 0)
    }

    fun stop() = playerManager.stop()
}