package com.shiyinplayer.ui.playlists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.R
import com.shiyinplayer.data.model.Playlist
import com.shiyinplayer.ui.navigation.Screen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistsScreen(navController: NavController? = null, viewModel: PlaylistsViewModel = hiltViewModel()) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    var menuFor by remember { mutableStateOf<Playlist?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.playlists_my_playlists), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = { navController?.navigate(Screen.Search.route) }) {
                    Icon(Icons.Default.TravelExplore, contentDescription = stringResource(R.string.playlist_global_search))
                }
                OutlinedButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.playlist_create))
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.smart_playlists))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SmartTile(stringResource(R.string.smart_recent), Icons.Default.History, Modifier.weight(1f)) {
                    navController?.navigate(Screen.SmartPlaylist.createRoute("recent"))
                }
                SmartTile(stringResource(R.string.smart_most), Icons.AutoMirrored.Filled.QueueMusic, Modifier.weight(1f)) {
                    navController?.navigate(Screen.SmartPlaylist.createRoute("most"))
                }
                SmartTile(stringResource(R.string.smart_random), Icons.Default.Shuffle, Modifier.weight(1f)) {
                    navController?.navigate(Screen.SmartPlaylist.createRoute("random"))
                }
            }
        }
        item { SectionHeader(stringResource(R.string.playlists_my_playlists)) }
        items(playlists, key = { it.id }) { pl ->
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { navController?.navigate(Screen.PlaylistDetail.createRoute(pl.id)) },
                            onLongClick = { menuFor = pl }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(pl.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        stringResource(R.string.count_songs, pl.songCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuFor?.id == pl.id,
                    onDismissRequest = { menuFor = null }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlist_menu_play)) },
                        onClick = { viewModel.playPlaylist(pl.id); menuFor = null }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlist_menu_rename)) },
                        onClick = { editing = pl; menuFor = null }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlist_menu_delete)) },
                        onClick = { deleting = pl; menuFor = null }
                    )
                }
            }
            HorizontalDivider()
        }
    }

    // 新建歌单
    if (showCreate) {
        NameDialog(
            title = stringResource(R.string.playlist_create_title),
            initial = "",
            onDismiss = { showCreate = false },
            onConfirm = { name -> viewModel.create(name); showCreate = false }
        )
    }
    // 重命名歌单（长按）
    editing?.let { pl ->
        NameDialog(
            title = stringResource(R.string.playlist_rename_title),
            initial = pl.name,
            onDismiss = { editing = null },
            onConfirm = { name -> viewModel.rename(pl.id, name); editing = null }
        )
    }
    // 删除歌单确认
    deleting?.let { pl ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.playlist_delete_title)) },
            text = { Text(stringResource(R.string.playlist_delete_confirm, pl.name)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(pl.id); deleting = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.playlist_name_label)) }, singleLine = true)
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SmartTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}