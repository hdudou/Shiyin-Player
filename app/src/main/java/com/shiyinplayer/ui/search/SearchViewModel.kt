package com.shiyinplayer.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.model.Album
import com.shiyinplayer.data.model.Artist
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.SearchField
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.player.PlayerManager
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 搜索结果分区（R-P1-03 曲库内搜索扩展：歌曲 + 专辑 + 艺术家）。 */
data class SearchResults(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>
)

/** F2-2：来源筛选。 */
enum class SongSourceFilter(val label: String) { ALL("全部来源"), LOCAL("本地"), NETWORK("网络") }

/** F2-2：时长筛选。 */
enum class DurationFilter(val label: String) { ALL("全部时长"), SHORT("3 分钟以内"), LONG("3 分钟以上") }

private data class FilterQuery(
    val q: String,
    val type: SearchField,
    val source: SongSourceFilter,
    val duration: DurationFilter
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: LibraryRepository,
    private val playerManager: PlayerManager,
    private val settings: SettingsRepository
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    // F2-2：搜索类型 / 来源 / 时长筛选状态
    private val _type = MutableStateFlow(SearchField.ALL)
    val type: StateFlow<SearchField> = _type
    private val _source = MutableStateFlow(SongSourceFilter.ALL)
    val source: StateFlow<SongSourceFilter> = _source
    private val _duration = MutableStateFlow(DurationFilter.ALL)
    val duration: StateFlow<DurationFilter> = _duration

    /** 最近搜索历史（M-06）。 */
    val searchHistory: StateFlow<List<String>> = settings.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val results: StateFlow<SearchResults> = combine(
        _query.debounce(250).map { it.trim() }.distinctUntilChanged(),
        _type,
        _source,
        _duration
    ) { q, t, src, dur -> FilterQuery(q, t, src, dur) }
        .flatMapLatest { f ->
            if (f.q.isBlank()) flowOf(SearchResults(emptyList(), emptyList(), emptyList()))
            else {
                val songsF = repo.searchSongsByField(f.q, f.type)
                    .map { songs -> songs.filter { matchesFilters(it, f.source, f.duration) } }
                val albumsF = if (f.type == SearchField.ALBUM || f.type == SearchField.ALL) repo.searchAlbums(f.q)
                    else flowOf(emptyList())
                val artistsF = if (f.type == SearchField.ARTIST || f.type == SearchField.ALL) repo.searchArtists(f.q)
                    else flowOf(emptyList())
                combine(songsF, albumsF, artistsF) { songs, albums, artists -> SearchResults(songs, albums, artists) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchResults(emptyList(), emptyList(), emptyList()))

    private fun matchesFilters(song: Song, src: SongSourceFilter, dur: DurationFilter): Boolean {
        val srcOk = when (src) {
            SongSourceFilter.ALL -> true
            SongSourceFilter.LOCAL -> song.source == MediaSourceType.LOCAL
            SongSourceFilter.NETWORK -> song.source != MediaSourceType.LOCAL
        }
        val durOk = when (dur) {
            DurationFilter.ALL -> true
            DurationFilter.SHORT -> song.durationMs > 0 && song.durationMs <= 180_000
            DurationFilter.LONG -> song.durationMs > 180_000
        }
        return srcOk && durOk
    }

    fun setQuery(q: String) { _query.value = q }
    fun setType(t: SearchField) { if (_type.value != t) _type.value = t }
    fun setSource(s: SongSourceFilter) { if (_source.value != s) _source.value = s }
    fun setDuration(d: DurationFilter) { if (_duration.value != d) _duration.value = d }

    /** 提交搜索（键盘搜索键）：记录到历史并执行。 */
    fun commitQuery() {
        val q = _query.value.trim()
        if (q.isNotEmpty()) viewModelScope.launch { settings.addSearchQuery(q) }
    }

    fun clearHistory() = viewModelScope.launch { settings.clearSearchHistory() }

    fun play(song: Song) {
        val list = results.value.songs
        val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playerManager.playQueue(list, idx)
    }
}