package com.shiyinplayer.ui.artists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.model.Artist
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ArtistsViewModel @Inject constructor(
    private val repo: LibraryRepository
) : ViewModel() {
    val artists = repo.getArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** F1-4：取选中艺术家的全部曲目（合并去重），供「播放全部 / 加入歌单」。 */
    suspend fun songsFor(artists: List<Artist>): List<Song> {
        val merged = mutableListOf<Song>()
        for (ar in artists) merged += runCatching { repo.getSongsByArtistName(ar.name).first() }
            .getOrDefault(emptyList())
        return merged
    }
}
