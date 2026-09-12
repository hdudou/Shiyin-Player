package com.shiyinplayer.ui.artists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.data.model.Artist
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.common.AddToPlaylistDialog
import com.shiyinplayer.ui.common.SongActionsViewModel
import com.shiyinplayer.ui.common.components.ArtistRow
import com.shiyinplayer.ui.common.components.ArtistActionCard
import com.shiyinplayer.ui.navigation.Screen
import kotlinx.coroutines.launch

@Composable
fun ArtistsScreen(navController: NavController? = null, viewModel: ArtistsViewModel = hiltViewModel()) {
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val actionsViewModel: SongActionsViewModel = hiltViewModel()
    val playlists by actionsViewModel.playlists.collectAsStateWithLifecycle()

    // F1-4：选择模式（参照歌曲 tab）——选择多个艺术家播放 / 加入歌单
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var pendingSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    // F3-3：列表 / 图片墙（网格卡片）视图切换
    var gridView by remember { mutableStateOf(false) }
    val selectedArtists = artists.filter { it.id in selectedIds }
    fun toggleSelect(artist: Artist) {
        selectedIds = if (artist.id in selectedIds) selectedIds - artist.id else selectedIds + artist.id
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Text(
                    "已选 ${selectedIds.size} 项",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(onClick = {
                    selectedIds = if (selectedIds.size == artists.size) emptySet() else artists.map { it.id }.toSet()
                }) { Text(if (selectedIds.size == artists.size) "取消全选" else "全选") }
                OutlinedButton(onClick = { selectionMode = false; selectedIds = emptySet() }) { Text("完成") }
            } else {
                Text("艺术家", modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { gridView = !gridView }) {
                    Icon(
                        if (gridView) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView,
                        contentDescription = if (gridView) "切换到列表" else "切换到图片墙"
                    )
                }
                OutlinedButton(onClick = { selectionMode = true }) { Text("选择") }
            }
        }
        if (gridView) {
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 130.dp), modifier = Modifier.weight(1f)) {
                gridItems(artists, key = { it.id }) { artist ->
                    Box(Modifier.padding(4.dp)) {
                        ArtistActionCard(artist = artist, onClick = {
                            if (selectionMode) toggleSelect(artist)
                            else navController?.navigate(Screen.ArtistDetail.createRoute(artist.name))
                        })
                        if (selectionMode) {
                            Checkbox(
                                checked = artist.id in selectedIds,
                                onCheckedChange = { toggleSelect(artist) },
                                modifier = Modifier.align(Alignment.TopStart).padding(2.dp)
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(artists, key = { it.id }) { artist ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectionMode) {
                            Checkbox(
                                checked = artist.id in selectedIds,
                                onCheckedChange = { toggleSelect(artist) }
                            )
                        }
                        ArtistRow(artist = artist, modifier = Modifier.weight(1f), onClick = {
                            if (selectionMode) toggleSelect(artist)
                            else navController?.navigate(Screen.ArtistDetail.createRoute(artist.name))
                        })
                    }
                    HorizontalDivider()
                }
            }
        }
        // 底部操作栏：参照歌曲 tab 的选择批量操作（播放全部 / 停止 / 加入歌单；艺术家不做删除）
        if (selectionMode && selectedIds.isNotEmpty()) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = {
                    scope.launch { actionsViewModel.playAll(viewModel.songsFor(selectedArtists)) }
                }, modifier = Modifier.weight(1f)) {
                    Text("播放全部")
                }
                OutlinedButton(onClick = { actionsViewModel.stop() }, modifier = Modifier.weight(1f)) {
                    Text("停止")
                }
                OutlinedButton(onClick = {
                    scope.launch { pendingSongs = viewModel.songsFor(selectedArtists); showPlaylistPicker = true }
                }, modifier = Modifier.weight(1f)) {
                    Text("加入歌单")
                }
            }
        }
    }

    if (showPlaylistPicker) {
        AddToPlaylistDialog(
            playlists = playlists,
            subtitle = "将 ${pendingSongs.size} 首曲目加入播放列表",
            onDismiss = { showPlaylistPicker = false },
            onCreate = { name -> scope.launch { actionsViewModel.createAndAddMany(name, pendingSongs) }; showPlaylistPicker = false },
            onSelect = { pl -> scope.launch { actionsViewModel.addSongsToPlaylist(pl.id, pendingSongs) }; showPlaylistPicker = false }
        )
    }
}