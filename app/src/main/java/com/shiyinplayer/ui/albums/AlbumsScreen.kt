package com.shiyinplayer.ui.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.data.model.Album
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.common.AddToPlaylistDialog
import com.shiyinplayer.ui.common.SongActionsViewModel
import com.shiyinplayer.ui.common.components.AlbumCard
import com.shiyinplayer.ui.navigation.Screen
import kotlinx.coroutines.launch

@Composable
fun AlbumsScreen(navController: NavController? = null, viewModel: AlbumsViewModel = hiltViewModel()) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val actionsViewModel: SongActionsViewModel = hiltViewModel()
    val playlists by actionsViewModel.playlists.collectAsStateWithLifecycle()

    // F1-4：选择模式（参照歌曲 tab）——选择多个专辑播放 / 加入歌单
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var pendingSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    val selectedAlbums = albums.filter { it.id in selectedIds }
    fun toggleSelect(album: Album) {
        selectedIds = if (album.id in selectedIds) selectedIds - album.id else selectedIds + album.id
    }

    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp)
        ) {
            if (selectionMode) {
                Text(
                    "已选 ${selectedIds.size} 项",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(onClick = {
                    selectedIds = if (selectedIds.size == albums.size) emptySet() else albums.map { it.id }.toSet()
                }) { Text(if (selectedIds.size == albums.size) "取消全选" else "全选") }
                OutlinedButton(onClick = { selectionMode = false; selectedIds = emptySet() }) { Text("完成") }
            } else {
                listOf("名称" to AlbumsViewModel.SORT_NAME, "年代" to AlbumsViewModel.SORT_YEAR, "艺术家" to AlbumsViewModel.SORT_ARTIST)
                    .forEach { (label, value) ->
                        OutlinedButton(
                            onClick = { viewModel.setSortMode(value) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                label,
                                color = if (sortMode == value) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                OutlinedButton(onClick = { selectionMode = true }) { Text("选择") }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier
        ) {
            if (sortMode == AlbumsViewModel.SORT_YEAR) {
                albums.groupBy { it.year?.toString() ?: "未知年代" }.forEach { (year, list) ->
                    item(key = "year-$year", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            year,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    items(list, key = { it.id }) { album ->
                        Box {
                            AlbumCard(album = album, onClick = {
                                if (selectionMode) toggleSelect(album)
                                else navController?.navigate(Screen.AlbumDetail.createRoute(album.name, album.artistName))
                            })
                            if (selectionMode) {
                                Checkbox(
                                    checked = album.id in selectedIds,
                                    onCheckedChange = { toggleSelect(album) },
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(start = 10.dp, top = 2.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                items(albums, key = { it.id }) { album ->
                    Box {
                        AlbumCard(album = album, onClick = {
                            if (selectionMode) toggleSelect(album)
                            else navController?.navigate(Screen.AlbumDetail.createRoute(album.name, album.artistName))
                        })
                        if (selectionMode) {
                            Checkbox(
                                checked = album.id in selectedIds,
                                onCheckedChange = { toggleSelect(album) },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(start = 10.dp, top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
        // 底部操作栏：参照歌曲 tab 的选择批量操作（播放全部 / 停止 / 加入歌单；专辑不做删除）
        if (selectionMode && selectedIds.isNotEmpty()) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = {
                    scope.launch { actionsViewModel.playAll(viewModel.songsFor(selectedAlbums)) }
                }, modifier = Modifier.weight(1f)) {
                    Text("播放全部")
                }
                OutlinedButton(onClick = { actionsViewModel.stop() }, modifier = Modifier.weight(1f)) {
                    Text("停止")
                }
                OutlinedButton(onClick = {
                    scope.launch { pendingSongs = viewModel.songsFor(selectedAlbums); showPlaylistPicker = true }
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