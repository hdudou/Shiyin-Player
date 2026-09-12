package com.shiyinplayer.ui.playlistsdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.R
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.common.SongActionsViewModel
import com.shiyinplayer.ui.common.SongMenuHost
import com.shiyinplayer.ui.common.SongSelectionBar
import com.shiyinplayer.ui.common.components.SongRow
import com.shiyinplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 播放列表详情（P0 补全：R-P0-07 创建/重命名/删除/添加/移除）+ 选择模式（2026-08-19）。 */
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    navController: NavController,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
    scope: CoroutineScope = androidx.compose.runtime.rememberCoroutineScope()
) {
    val name by viewModel.playlistName.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val allSongs by viewModel.allSongs.collectAsStateWithLifecycle()
    var renameDialog by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
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
                Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { renameDialog = true }) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_rename))
                }
                IconButton(onClick = {
                    scope.launch { viewModel.delete(); navController.popBackStack() }
                }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                }
                OutlinedButton(onClick = { selectionMode = true }) { Text(stringResource(R.string.action_select)) }
            }
        }
        if (!selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { viewModel.play(songs.firstOrNull() ?: return@OutlinedButton) }) {
                    Text(stringResource(R.string.action_play_all))
                }
                OutlinedButton(onClick = { viewModel.stop() }) {
                    Text(stringResource(R.string.action_stop))
                }
                OutlinedButton(onClick = { addDialog = true }) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = stringResource(R.string.action_add))
                    Text(stringResource(R.string.action_add_song))
                }
            }
        }
        val listState = rememberLazyListState()
        // 切歌时自动滚动到当前播放曲目（需求 5）
        LaunchedEffect(currentPlayingId) {
            val idx = songs.indexOfFirst { it.id == currentPlayingId }
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(songs, key = { it.id }) { song ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectionMode) {
                        Checkbox(checked = song.id in selectedIds, onCheckedChange = { toggleSelect(song) })
                        SongRow(song = song, onClick = { toggleSelect(song) }, modifier = Modifier.weight(1f), isCurrent = song.id == currentPlayingId)
                    } else {
                        SongRow(song = song, onClick = { viewModel.play(song) }, onLongPress = { menuSong = song }, modifier = Modifier.weight(1f), isCurrent = song.id == currentPlayingId)
                        IconButton(onClick = { viewModel.removeSong(song.id) }) {
                            Icon(Icons.Default.RemoveCircle, contentDescription = stringResource(R.string.action_remove))
                        }
                    }
                }
            }
        }
        SongSelectionBar(selectedSongs = selectedSongs, actionsViewModel = actionsViewModel)
    }

    if (renameDialog) {
        var text by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text(stringResource(R.string.playlist_rename_title)) },
            text = { TextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) viewModel.rename(text.trim())
                    renameDialog = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (addDialog) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text(stringResource(R.string.action_add_song)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(allSongs, key = { it.id }) { song ->
                        val alreadyAdded = songs.any { it.id == song.id }
                        SongRow(
                            song = song,
                            onClick = {
                                if (!alreadyAdded) {
                                    viewModel.addSong(song.id)
                                    addDialog = false
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { addDialog = false }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }

    SongMenuHost(
        menuSong = menuSong,
        onDismiss = { menuSong = null },
        playlistId = playlistId
    )
}
