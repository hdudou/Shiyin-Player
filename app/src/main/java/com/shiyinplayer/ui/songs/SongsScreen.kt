package com.shiyinplayer.ui.songs

import com.shiyinplayer.R
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.common.AddToPlaylistDialog
import com.shiyinplayer.ui.common.SongActionsViewModel
import com.shiyinplayer.ui.common.SongMenuHost
import com.shiyinplayer.ui.common.components.SongRow
import com.shiyinplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.launch

/** 歌曲列表分组键：拉丁字母取大写首字母，其余归入 #。 */
private fun groupKey(title: String): String {
    val c = title.trim().firstOrNull() ?: return "#"
    return if (c.isLetter() && c.code < 128) c.uppercaseChar().toString() else "#"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongsScreen(viewModel: SongsViewModel = hiltViewModel()) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val listGroupBy by viewModel.listGroupBy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val actionsViewModel: SongActionsViewModel = hiltViewModel()
    val playlists by actionsViewModel.playlists.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            // 持久化 URI 读取授权，否则进程重启/授权失效后播放将失败
            runCatching {
                context.contentResolver
                    .takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch { viewModel.importUri(it) }
        }
    }
    // [5] 批量添加文件夹：SAF 文档树选择器，递归扫描整个目录下的音乐文件
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it)?.name
                ?: it.lastPathSegment ?: context.getString(R.string.songs_local_folder)
            scope.launch { viewModel.addLocalFolder(it, name) }
        }
    }

    var menuSong by remember { mutableStateOf<Song?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var grouped by remember { mutableStateOf(false) }
    // 需求 5：读取当前播放曲目，用于列表加粗 + 切歌时自动滚动
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val pstate by playerViewModel.state.collectAsStateWithLifecycle()
    val currentPlayingId = pstate.currentSong?.id

    val selectedSongs = songs.filter { it.id in selectedIds }

    fun toggleSelect(song: Song) {
        selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id
    }

    val listState = rememberLazyListState()
        // 需求 5：切歌时自动滚动到当前播放曲目（分组模式下视觉顺序与 songs 顺序不一致，跳过滚动仅加粗）
        LaunchedEffect(currentPlayingId) {
            val groupMode = if (listGroupBy != "none") listGroupBy else if (grouped) "letter" else "none"
            if (groupMode == "none") {
                val idx = songs.indexOfFirst { it.id == currentPlayingId }
                if (idx >= 0) listState.animateScrollToItem(idx)
            }
        }
        Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Text(
                    stringResource(R.string.songs_selected_count, selectedIds.size),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(onClick = {
                    selectedIds = if (selectedIds.size == songs.size) emptySet() else songs.map { it.id }.toSet()
                }) { Text(if (selectedIds.size == songs.size) stringResource(R.string.action_deselect_all) else stringResource(R.string.action_select_all)) }
                OutlinedButton(onClick = { selectionMode = false; selectedIds = emptySet() }) { Text(stringResource(R.string.action_done)) }
            } else {
                Text(stringResource(R.string.tab_songs), modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { grouped = !grouped }) { Text(if (grouped) stringResource(R.string.action_list) else stringResource(R.string.action_group)) }
                OutlinedButton(onClick = { selectionMode = true }) { Text(stringResource(R.string.action_select)) }
                Button(onClick = { folderPicker.launch(null) }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.action_add_folder))
                }
                Button(onClick = { picker.launch(arrayOf("audio/*")) }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.action_add_song))
                }
            }
        }
        // AH-下沉：分组聚合仅在数据/分组模式变化时计算一次（在 @Composable 作用域 remember），
        // 避免 LazyColumn 每次重组都在主线程对全量列表 groupBy + toSortedMap。
        val sortedGroupedSongs = remember(listGroupBy, grouped, selectionMode, songs) {
            val mode = if (listGroupBy != "none") listGroupBy else if (grouped) "letter" else "none"
            if (mode != "none" && !selectionMode) {
                songs.groupBy {
                    when (mode) {
                        "album" -> it.albumName ?: context.getString(R.string.unknown_album)
                        "artist" -> it.artistName ?: context.getString(R.string.unknown_artist)
                        "folder" -> it.path?.substringBeforeLast('/') ?: context.getString(R.string.unknown_folder)
                        else -> groupKey(it.title)
                    }
                }.toSortedMap()
            } else sortedMapOf<String, List<Song>>()
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            val groupMode = if (listGroupBy != "none") listGroupBy
            else if (grouped) "letter"
            else "none"
            if (groupMode != "none" && !selectionMode) {
                sortedGroupedSongs.forEach { (key, list) ->
                    stickyHeader(key = "header-$key") {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                key,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    items(list, key = { it.id }) { song ->
                        SongRow(song = song, onClick = { viewModel.play(song) }, onLongPress = { menuSong = song }, isCurrent = song.id == currentPlayingId)
                    }
                }
            } else {
                items(songs, key = { it.id }) { song ->
                    if (selectionMode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = song.id in selectedIds,
                                onCheckedChange = { toggleSelect(song) }
                            )
                            SongRow(song = song, onClick = { toggleSelect(song) }, modifier = Modifier.weight(1f), isCurrent = song.id == currentPlayingId)
                        }
                    } else {
                        SongRow(song = song, onClick = { viewModel.play(song) }, onLongPress = { menuSong = song }, isCurrent = song.id == currentPlayingId)
                    }
                }
            }
        }
        if (selectionMode && selectedIds.isNotEmpty()) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { actionsViewModel.playAll(selectedSongs) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_play_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { actionsViewModel.stop() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_stop), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { showPlaylistPicker = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_add_to_playlist), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = { showDeleteConfirm = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_delete), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    // 长按菜单
    SongMenuHost(
        menuSong = menuSong,
        onDismiss = { menuSong = null }
    )

    // 批量加入歌单
    if (showPlaylistPicker) {
        AddToPlaylistDialog(
            playlists = playlists,
            subtitle = stringResource(R.string.add_to_playlist_count, selectedSongs.size),
            onDismiss = { showPlaylistPicker = false },
            onCreate = { name -> scope.launch { actionsViewModel.createAndAddMany(name, selectedSongs) }; showPlaylistPicker = false },
            onSelect = { pl -> scope.launch { actionsViewModel.addSongsToPlaylist(pl.id, selectedSongs) }; showPlaylistPicker = false }
        )
    }

    // 批量删除确认
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    selectionMode = false
                    selectedIds = emptySet()
                    scope.launch { actionsViewModel.deleteSongs(selectedSongs) }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.delete_songs_title)) },
            text = { Text(stringResource(R.string.delete_songs_confirm, selectedSongs.size)) }
        )
    }
}