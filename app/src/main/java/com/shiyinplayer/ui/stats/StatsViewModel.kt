package com.shiyinplayer.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.model.PlayStats
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** F2-3：播放统计（概览 + 最常播放 Top N 柱状图 + 最近播放）。 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repo: LibraryRepository,
    private val playerManager: PlayerManager
) : ViewModel() {
    val stats: StateFlow<PlayStats> = repo.getPlayStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayStats(0, 0, 0))

    /** 最常播放 Top 10（供柱状图）。 */
    val topPlayed: StateFlow<List<Song>> = repo.getMostPlayed()
        .map { it.take(10) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 最近播放（最近播放过，按时间倒序）。 */
    val recentlyPlayed: StateFlow<List<Song>> = repo.getRecentlyPlayed()
        .map { it.take(20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun play(songs: List<Song>, index: Int) = playerManager.playQueue(songs, index)
}