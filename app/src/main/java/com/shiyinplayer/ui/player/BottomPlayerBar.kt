package com.shiyinplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.ui.navigation.Screen

@Composable
fun BottomPlayerBar(
    navController: NavController,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val song = state.currentSong ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { navController.navigate(Screen.NowPlaying.route) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(song.artistName ?: "未知艺术家", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        IconButton(onClick = viewModel::prev) { Icon(Icons.Default.SkipPrevious, contentDescription = "上一首") }
        IconButton(onClick = viewModel::togglePlay) {
            Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "播放/暂停")
        }
        IconButton(onClick = viewModel::stop) { Icon(Icons.Default.Stop, contentDescription = "停止") }
        IconButton(onClick = viewModel::next) { Icon(Icons.Default.SkipNext, contentDescription = "下一首") }
    }
}
