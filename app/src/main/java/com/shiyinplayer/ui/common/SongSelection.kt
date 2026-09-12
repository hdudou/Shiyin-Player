package com.shiyinplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.data.model.Song
import kotlinx.coroutines.launch

/**
 * 2026-08-19 需求：多界面复用的「选择模式」底部操作栏。
 * 显示「播放全部」+「加入歌单」两个操作；加入歌单弹 [AddToPlaylistDialog]（可选现有播放列表或新建）。
 * 由各列表界面在 selectionMode 下传入选中的歌曲列表。
 */
@Composable
fun SongSelectionBar(
    selectedSongs: List<Song>,
    actionsViewModel: SongActionsViewModel,
    modifier: Modifier = Modifier
) {
    if (selectedSongs.isEmpty()) return
    var showPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val playlists by actionsViewModel.playlists.collectAsStateWithLifecycle()

    Column(modifier) {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { actionsViewModel.playAll(selectedSongs) }, modifier = Modifier.weight(1f)) {
                Text("播放全部")
            }
            OutlinedButton(onClick = { actionsViewModel.stop() }, modifier = Modifier.weight(1f)) {
                Text("停止")
            }
            OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.weight(1f)) {
                Text("加入歌单")
            }
        }
    }

    if (showPicker) {
        AddToPlaylistDialog(
            playlists = playlists,
            subtitle = "将 ${selectedSongs.size} 首曲目加入播放列表",
            onDismiss = { showPicker = false },
            onCreate = { name ->
                scope.launch { actionsViewModel.createAndAddMany(name, selectedSongs) }
                showPicker = false
            },
            onSelect = { pl ->
                scope.launch { actionsViewModel.addSongsToPlaylist(pl.id, selectedSongs) }
                showPicker = false
            }
        )
    }
}
