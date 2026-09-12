package com.shiyinplayer.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.R
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.common.AddToPlaylistDialog
import com.shiyinplayer.ui.common.SongActionsViewModel
import com.shiyinplayer.ui.common.SongMenuHost
import com.shiyinplayer.ui.common.components.ArtistRow
import com.shiyinplayer.ui.common.components.SongRow
import com.shiyinplayer.ui.navigation.Screen
import com.shiyinplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(navController: NavController? = null, viewModel: SearchViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val history by viewModel.searchHistory.collectAsStateWithLifecycle()
    var menuSong by remember { mutableStateOf<Song?>(null) }
    // 需求 5：读取当前播放曲目，用于列表加粗 + 切歌时自动滚动
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val pstate by playerViewModel.state.collectAsStateWithLifecycle()
    val currentPlayingId = pstate.currentSong?.id
    // ── F6-1：搜索结果多选（歌曲）→ 批量立即播放 / 加入歌单 ──
    val scope = rememberCoroutineScope()
    val actionsViewModel: SongActionsViewModel = hiltViewModel()
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val selectedSongs = results.songs.filter { it.id in selectedIds }
    fun toggleSelect(song: Song) {
        selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id
    }
    Column(Modifier) {
        if (selectionMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    stringResource(R.string.songs_selected_count, selectedIds.size),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(onClick = {
                    selectedIds = if (selectedIds.size == results.songs.size) emptySet() else results.songs.map { it.id }.toSet()
                }) { Text(if (selectedIds.size == results.songs.size && results.songs.isNotEmpty()) stringResource(R.string.action_deselect_all) else stringResource(R.string.action_select_all)) }
                OutlinedButton(onClick = { selectionMode = false; selectedIds = emptySet() }) { Text(stringResource(R.string.action_done)) }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(stringResource(R.string.action_search), modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { selectionMode = true }) { Text(stringResource(R.string.action_select)) }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.commitQuery() })
        )
        // F2-2：搜索类型 / 来源 / 时长筛选
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val type by viewModel.type.collectAsStateWithLifecycle()
            val source by viewModel.source.collectAsStateWithLifecycle()
            val duration by viewModel.duration.collectAsStateWithLifecycle()
            val tv = type
            FlowRow {
                com.shiyinplayer.data.model.SearchField.entries.forEach { t ->
                    FilterChip(
                        onClick = { viewModel.setType(t) },
                        label = { Text(searchFieldLabel(t), maxLines = 1) },
                        selected = t == tv
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SongSourceFilter.entries.forEach { s ->
                    FilterChip(
                        onClick = { viewModel.setSource(s) },
                        label = { Text(sourceLabel(s), maxLines = 1) },
                        selected = s == source
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DurationFilter.entries.forEach { d ->
                    FilterChip(
                        onClick = { viewModel.setDuration(d) },
                        label = { Text(durationLabel(d), maxLines = 1) },
                        selected = d == duration
                    )
                }
            }
        }
        if (query.isBlank() && history.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.search_recent),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                TextButton(onClick = { viewModel.clearHistory() }) { Text(stringResource(R.string.action_clear)) }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                history.forEach { h ->
                    SuggestionChip(onClick = { viewModel.setQuery(h) }, label = { Text(h, maxLines = 1) })
                }
            }
        }
        val listState = rememberLazyListState()
        // 切歌时自动滚动到当前播放曲目（需求 5）
        LaunchedEffect(currentPlayingId) {
            val idx = results.songs.indexOfFirst { it.id == currentPlayingId }
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
        LazyColumn(state = listState) {
            if (results.artists.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.tab_artists)) }
                items(results.artists, key = { "a${it.id}" }) { artist ->
                    ArtistRow(artist = artist, onClick = {
                        navController?.navigate(Screen.ArtistDetail.createRoute(artist.name))
                    })
                }
            }
            if (results.albums.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.tab_albums)) }
                items(results.albums, key = { "b${it.id}" }) { album ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController?.navigate(
                                    Screen.AlbumDetail.createRoute(album.name, album.artistName)
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(album.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(album.artistName ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
            if (results.songs.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.tab_songs)) }
                items(results.songs, key = { "s${it.id}" }) { song ->
                    SongRow(
                        song = song,
                        onClick = { if (selectionMode) toggleSelect(song) else viewModel.play(song) },
                        onLongPress = { if (!selectionMode) menuSong = song },
                        isCurrent = song.id == currentPlayingId,
                        showCheckbox = selectionMode,
                        checked = song.id in selectedIds,
                        onCheckedChange = { toggleSelect(song) }
                    )
                }
            }
            // F6-1：选择模式下，歌曲行占满后底部附批量操作栏
            if (selectionMode && selectedSongs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { actionsViewModel.playAll(selectedSongs) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_play_now))
                        }
                        OutlinedButton(onClick = { showPlaylistPicker = true }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_add_to_playlist))
                        }
                    }
                }
            }
        }
    }
    if (showPlaylistPicker) {
        AddToPlaylistDialog(
            playlists = actionsViewModel.playlists.collectAsStateWithLifecycle().value,
            subtitle = stringResource(R.string.add_to_playlist_count, selectedSongs.size),
            onDismiss = { showPlaylistPicker = false },
            onCreate = { name -> scope.launch { actionsViewModel.createAndAddMany(name, selectedSongs) }; showPlaylistPicker = false },
            onSelect = { pl -> scope.launch { actionsViewModel.addSongsToPlaylist(pl.id, selectedSongs) }; showPlaylistPicker = false }
        )
    }
    SongMenuHost(
        menuSong = menuSong,
        onDismiss = { menuSong = null },
        onViewAlbum = { s -> navController?.navigate(Screen.AlbumDetail.createRoute(s.albumName.orEmpty(), s.artistName)) },
        onViewArtist = { s -> s.artistName?.let { navController?.navigate(Screen.ArtistDetail.createRoute(it)) } }
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun searchFieldLabel(f: com.shiyinplayer.data.model.SearchField): String = when (f) {
    com.shiyinplayer.data.model.SearchField.ALL -> stringResource(R.string.search_field_all)
    com.shiyinplayer.data.model.SearchField.TITLE -> stringResource(R.string.tab_songs)
    com.shiyinplayer.data.model.SearchField.ARTIST -> stringResource(R.string.tab_artists)
    com.shiyinplayer.data.model.SearchField.ALBUM -> stringResource(R.string.tab_albums)
    com.shiyinplayer.data.model.SearchField.FILENAME -> stringResource(R.string.file_info_filename)
}

@Composable
private fun sourceLabel(s: SongSourceFilter): String = when (s) {
    SongSourceFilter.ALL -> stringResource(R.string.search_source_all)
    SongSourceFilter.LOCAL -> stringResource(R.string.search_source_local)
    SongSourceFilter.NETWORK -> stringResource(R.string.search_source_network)
}

@Composable
private fun durationLabel(d: DurationFilter): String = when (d) {
    DurationFilter.ALL -> stringResource(R.string.search_duration_all)
    DurationFilter.SHORT -> stringResource(R.string.search_duration_short)
    DurationFilter.LONG -> stringResource(R.string.search_duration_long)
}