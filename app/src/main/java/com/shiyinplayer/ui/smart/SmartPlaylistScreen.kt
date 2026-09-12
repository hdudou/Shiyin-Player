package com.shiyinplayer.ui.smart

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.R
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.common.SongMenuHost
import com.shiyinplayer.ui.common.components.SongRow
import com.shiyinplayer.ui.player.PlayerViewModel

/** 智能播放列表页（最近/最常/随机）。 */
@Composable
fun SmartPlaylistScreen(
    type: String,
    navController: NavController,
    viewModel: SmartPlaylistViewModel = hiltViewModel()
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    var menuSong by remember { mutableStateOf<Song?>(null) }
    val title = when (type) {
        "most" -> stringResource(R.string.smart_most)
        "random" -> stringResource(R.string.smart_random)
        else -> stringResource(R.string.smart_recent)
    }
    // 需求 5：读取当前播放曲目，用于列表加粗 + 切歌时自动滚动
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val pstate by playerViewModel.state.collectAsStateWithLifecycle()
    val currentPlayingId = pstate.currentSong?.id
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (songs.isNotEmpty()) {
                OutlinedButton(onClick = { viewModel.playAll() }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.action_play_all))
                }
                OutlinedButton(onClick = { viewModel.stop() }) {
                    Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.action_stop))
                }
            }
        }
        HorizontalDivider()
        val listState = rememberLazyListState()
        // 切歌时自动滚动到当前播放曲目（需求 5）
        LaunchedEffect(currentPlayingId) {
            val idx = songs.indexOfFirst { it.id == currentPlayingId }
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(songs, key = { it.id }) { song ->
                SongRow(song = song, onClick = { viewModel.play(song) }, onLongPress = { menuSong = song }, isCurrent = song.id == currentPlayingId)
            }
            if (songs.isEmpty()) {
                item {
                    Text(stringResource(R.string.smart_no_data), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
                }
            }
        }
    }
    SongMenuHost(menuSong = menuSong, onDismiss = { menuSong = null })
}