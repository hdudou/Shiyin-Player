package com.shiyinplayer.ui.artistsdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.shiyinplayer.data.model.Album
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.common.SongActionsViewModel
import com.shiyinplayer.ui.common.SongMenuHost
import com.shiyinplayer.ui.common.SongSelectionBar
import com.shiyinplayer.ui.common.components.SongRow
import com.shiyinplayer.ui.navigation.Screen
import com.shiyinplayer.ui.player.PlayerViewModel

/** 艺术家详情页：头像/简介（在线）+ 作品年表 + 曲目列表 + 全部播放 + 选择模式（2026-08-19）。 */
@Composable
fun ArtistDetailScreen(
    artistName: String,
    navController: NavController,
    viewModel: ArtistDetailViewModel = hiltViewModel()
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artistInfo by viewModel.artistInfo.collectAsStateWithLifecycle()
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
                Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = if (selectionMode) "退出选择" else "返回")
            }
            if (selectionMode) {
                Text(
                    "已选 ${selectedIds.size} 首",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                OutlinedButton(onClick = {
                    selectedIds = if (selectedIds.size == songs.size) emptySet() else songs.map { it.id }.toSet()
                }) { Text(if (selectedIds.size == songs.size) "取消全选" else "全选") }
                OutlinedButton(onClick = { selectionMode = false; selectedIds = emptySet() }) { Text("完成") }
            } else {
                Text(artistName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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
            if (!selectionMode) {
                if (artistInfo != null) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (artistInfo?.avatarUrl != null) {
                                AsyncImage(
                                    model = artistInfo?.avatarUrl,
                                    contentDescription = "歌手头像",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(72.dp).clip(CircleShape)
                                )
                                Spacer(Modifier.size(16.dp))
                            }
                            artistInfo?.bio?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 6)
                            }
                        }
                    }
                }
                if (albums.isNotEmpty()) {
                    item {
                        Text(
                            "作品年表（${albums.size} 张专辑）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(albums, key = { "album-${it.id}" }) { album ->
                        DiscographyRow(
                            album = album,
                            onClick = {
                                navController.navigate(Screen.AlbumDetail.createRoute(album.name, album.artistName))
                            }
                        )
                    }
                    item {
                        Text(
                            "曲目（${songs.size} 首）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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

/** 作品年表行：专辑名 + 发行年代 + 曲目数。 */
@Composable
private fun DiscographyRow(album: Album, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(album.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
        album.year?.let { Text("$it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (album.songCount > 0) {
            Text("  · ${album.songCount} 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}
