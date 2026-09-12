package com.shiyinplayer.ui.foldersdetail

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
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.common.SongActionsViewModel
import com.shiyinplayer.ui.common.SongMenuHost
import com.shiyinplayer.ui.common.SongSelectionBar
import com.shiyinplayer.ui.common.components.SongRow
import com.shiyinplayer.ui.player.PlayerViewModel

/** 文件夹详情页：曲目列表 + 全部播放 + 多选（含文件夹名行「全选」复选框，需求 4）。 */
@Composable
fun FolderDetailScreen(
    folderName: String,
    navController: NavController,
    viewModel: FolderDetailViewModel = hiltViewModel()
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val actionsViewModel: SongActionsViewModel = hiltViewModel()
    // 需求 5：读取当前播放曲目，用于列表加粗 + 切歌时自动滚动
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val pstate by playerViewModel.state.collectAsStateWithLifecycle()
    val currentPlayingId = pstate.currentSong?.id
    var menuSong by remember { mutableStateOf<Song?>(null) }

    // 多选（需求 4）：文件夹名行提供「全选」复选框，一键勾选本文件夹全部曲目
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val selectedSongs = songs.filter { it.id in selectedIds }
    val allSelected = songs.isNotEmpty() && selectedIds.size == songs.size

    fun toggleSelect(song: Song) {
        selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id
    }
    fun toggleSelectAll() {
        selectedIds = if (allSelected) emptySet() else songs.map { it.id }.toSet()
    }

    Column(Modifier.fillMaxSize()) {
        if (selectionMode) {
            // 文件夹名行：最左侧全选复选框与下方曲目复选框在 x=0 处对齐
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(checked = allSelected, onCheckedChange = { toggleSelectAll() })
                Text(
                    "已选 ${selectedIds.size} 首",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                OutlinedButton(onClick = { toggleSelectAll() }) {
                    Text(if (allSelected) "取消全选" else "全选")
                }
                OutlinedButton(onClick = { selectionMode = false; selectedIds = emptySet() }) {
                    Text("完成")
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "返回")
                }
                Text(folderName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { selectionMode = true }) { Text("选择") }
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
        SongSelectionBar(selectedSongs = selectedSongs, actionsViewModel = actionsViewModel)
    }
    SongMenuHost(menuSong = menuSong, onDismiss = { menuSong = null })
}