package com.shiyinplayer.ui.artistsdetail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.metadata.ArtistMetadata
import com.shiyinplayer.data.model.Album
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.data.repository.MetadataRepository
import com.shiyinplayer.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 艺术家详情（P0 补全：R-P0-02 列表可点击进入详情 + 在线歌手信息）。 */
@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: LibraryRepository,
    private val metadataRepo: MetadataRepository,
    private val playerManager: PlayerManager
) : ViewModel() {

    private val artistName: String = Uri.decode(savedStateHandle.get<String>("artistName").orEmpty())

    val songs: StateFlow<List<Song>> = repo.getSongsByArtistName(artistName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 作品年表：按发行年代排序的专辑列表。 */
    val albums: StateFlow<List<Album>> = repo.getAlbumsByArtistName(artistName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 在线歌手信息（头像/简介），尽力而为。 */
    val artistInfo: StateFlow<ArtistMetadata?> = flow {
        emit(runCatching { metadataRepo.getArtistInfo(artistName).artist }.getOrNull())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun play(song: Song) {
        val list = songs.value
        val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playerManager.playQueue(list, idx)
    }
}