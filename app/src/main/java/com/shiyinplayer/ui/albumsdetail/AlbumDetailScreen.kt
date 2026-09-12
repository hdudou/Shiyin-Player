package com.shiyinplayer.ui.albumsdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.shiyinplayer.R
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.common.SongActionsViewModel
import com.shiyinplayer.ui.common.SongMenuHost
import com.shiyinplayer.ui.common.SongSelectionBar
import com.shiyinplayer.ui.common.components.SongRow
import com.shiyinplayer.ui.player.PlayerViewModel

/** 专辑详情页：封面（在线）+ 曲目列表 + 全部播放 + 选择模式（2026-08-19）。 */
@Composable
fun AlbumDetailScreen(
    albumName: String,
    artistName: String?,
    navController: NavController,
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val coverUrl by viewModel.coverUrl.collectAsStateWithLifecycle()
    val year by viewModel.year.collectAsStateWithLifecycle()
    var menuSong by remember { mutableStateOf<Song?>(null) }
    // 2026-08-19 选择模式
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val actionsViewModel: SongActionsViewModel = hiltViewModel()
    // 需求 5：读取当前播放曲目，用于列表加粗 + 切歌时自动滚动
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val pstate by playerViewModel.state.collectAsStateWithLifecycle()
    val currentPlayingId = pstate.currentSong?.id
    val selectedSongs = songs.filter { it.id in selectedIds }

    fun toggleSelect(song: Song) {
        selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (selectionMode) { selectionMode = false; selectedIds = emptySet() }
                else navController.popBackStack()
            }) {
                Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = if (selectionMode) stringResource(R.string.action_exit_selection) else stringResource(R.string.action_back))
            }
            if (selectionMode) {
                Text(
                    stringResource(R.string.songs_selected_count, selectedIds.size),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                OutlinedButton(onClick = {
                    selectedIds = if (selectedIds.size == songs.size) emptySet() else songs.map { it.id }.toSet()
                }) { Text(if (selectedIds.size == songs.size) stringResource(R.string.action_deselect_all) else stringResource(R.string.action_select_all)) }
                OutlinedButton(onClick = { selectionMode = false; selectedIds = emptySet() }) { Text(stringResource(R.string.action_done)) }
            } else {
                Column(Modifier.weight(1f)) {
                    Text(albumName, style = MaterialTheme.typography.titleLarge)
                    Row {
                        artistName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        val y = year
                        if (y != null) {
                            Text(
                                stringResource(R.string.album_year_suffix, y),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { selectionMode = true }) { Text(stringResource(R.string.action_select)) }
            }
        }
        HorizontalDivider()
        val listState = rememberLazyListState()
        // 切歌时自动滚动到当前播放曲目（需求 5）
        LaunchedEffect(currentPlayingId) {
            val idx = songs.indexOfFirst { it.id == currentPlayingId }
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!selectionMode) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        OutlinedButton(onClick = { viewModel.playAll() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text(stringResource(R.string.album_play_all_count, songs.size))
                        }
                        OutlinedButton(onClick = { viewModel.stop() }) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.action_stop))
                        }
                    }
                }
                if (coverUrl != null) {
                    item {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = stringResource(R.string.album_cover),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                }
            }
            items(songs, key = { it.id }) { song ->
                if (selectionMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = song.id in selectedIds, onCheckedChange = { toggleSelect(song) })
                        SongRow(song = song, onClick = { toggleSelect(song) }, modifier = Modifier.weight(1f), isCurrent = song.id == currentPlayingId)
                    }
                } else {
                    SongRow(song = song, onClick = { viewModel.play(song) }, onLongPress = { menuSong = song }, isCurrent = song.id == currentPlayingId)
                }
            }
        }
        SongSelectionBar(selectedSongs = selectedSongs, actionsViewModel = actionsViewModel)
    }
    SongMenuHost(menuSong = menuSong, onDismiss = { menuSong = null })
}
