package com.shiyinplayer.ui.albumsdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.data.repository.MetadataRepository
import com.shiyinplayer.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 专辑详情（P0 补全：R-P0-02 列表可点击进入详情 + 在线封面）。 */
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: LibraryRepository,
    private val metadataRepo: MetadataRepository,
    private val playerManager: PlayerManager
) : ViewModel() {

    private val albumName: String = Uri.decode(savedStateHandle.get<String>("albumName").orEmpty())
    private val artistName: String? =
        savedStateHandle.get<String>("artist")?.takeIf { it.isNotBlank() }?.let { Uri.decode(it) }

    val songs: StateFlow<List<Song>> = repo.getSongsByAlbumName(albumName, artistName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 专辑发行年份（专辑详情年份显示）。 */
    val year: StateFlow<Int?> = repo.getAlbumYear(albumName, artistName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _coverUrl = MutableStateFlow<String?>(null)
    val coverUrl: StateFlow<String?> = _coverUrl

    init {
        viewModelScope.launch {
            val cover = runCatching { metadataRepo.getAlbumCover(albumName, artistName)?.coverUrl }.getOrNull()
            _coverUrl.value = cover
            // 封面写回该专辑各曲目（供列表封面显示）
            if (cover != null) {
                songs.value.forEach { song ->
                    if (song.albumArtUri != cover) {
                        viewModelScope.launch { runCatching { metadataRepo.applyAlbumArt(song.id, cover) } }
                    }
                }
            }
        }
    }

    fun play(song: Song) {
        val list = songs.value
        val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playerManager.playQueue(list, idx)
    }

    /** 全部播放（专辑详情头部）。 */
    fun playAll() {
        playerManager.playQueue(songs.value, 0)
    }

    fun stop() = playerManager.stop()
}