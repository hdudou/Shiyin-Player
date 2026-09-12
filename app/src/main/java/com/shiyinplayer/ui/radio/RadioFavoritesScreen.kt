package com.shiyinplayer.ui.radio

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.R
import com.shiyinplayer.data.radio.MergedRadioStation

/**
 * 电台收藏页：列表显示收藏的电台。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioFavoritesScreen(
    viewModel: RadioViewModel
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val radioState by viewModel.radioState.collectAsStateWithLifecycle()
    val isOpmlBusy by viewModel.isOpmlBusy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // OPML 导入选择器
    val opmlImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importFavoritesFromOpml(uri)
        }
    }

    // 导入结果 Toast
    LaunchedEffect(Unit) {
        viewModel.opmlImportResult.collect { count ->
            if (count > 0) {
                Toast.makeText(context, context.getString(R.string.radio_import_success, count), Toast.LENGTH_SHORT).show()
            } else if (count == 0) {
                Toast.makeText(context, context.getString(R.string.radio_import_fail_empty), Toast.LENGTH_SHORT).show()
            }
        }
    }
    // 导出结果 Toast
    LaunchedEffect(Unit) {
        viewModel.opmlExportResult.collect { uri ->
            if (uri != null) {
                Toast.makeText(context, context.getString(R.string.radio_export_success), Toast.LENGTH_SHORT).show()
            }
        }
    }

    var longPressStation by remember { mutableStateOf<MergedRadioStation?>(null) }
    var showInfoDialog by remember { mutableStateOf<MergedRadioStation?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<MergedRadioStation?>(null) }
    var showEditDialog by remember { mutableStateOf<MergedRadioStation?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.radio_favorites_title, favorites.size)) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            actions = {
                // 导出 OPML
                IconButton(
                    onClick = { viewModel.exportFavoritesToOpml() },
                    enabled = !isOpmlBusy && favorites.isNotEmpty()
                ) {
                    if (isOpmlBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.SaveAlt, contentDescription = stringResource(R.string.radio_export_opml))
                    }
                }
                // 导入 OPML
                IconButton(
                    onClick = { opmlImportLauncher.launch(arrayOf("text/x-opml", "text/xml", "*/*")) },
                    enabled = !isOpmlBusy
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = stringResource(R.string.radio_import_opml))
                }
            }
        )

        if (favorites.isEmpty()) {
            // 空态引导
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.radio_fav_empty_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.radio_fav_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
            ) {
                items(favorites, key = { "fav_${it.primaryStationId}" }) { station ->
                    FavoriteStationListItem(
                        station = station,
                        isPlaying = isMergedRadioPlaying(radioState.streamUrl, station) && radioState.isPlaying,
                        isBuffering = isMergedRadioPlaying(radioState.streamUrl, station) && radioState.isBuffering,
                        onClick = {
                            if (isMergedRadioPlaying(radioState.streamUrl, station) && (radioState.isPlaying || radioState.isBuffering)) {
                                // 正在播放此电台 → 暂停
                                viewModel.togglePlayPause()
                            } else {
                                // 播放新电台（不切换 tab，由迷你播放条/通知提供状态反馈）
                                viewModel.playStation(station)
                            }
                        },
                        onLongPress = { longPressStation = station }
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    // 长按菜单 - 使用 ModalBottomSheet 避免阻挡底部导航栏
    longPressStation?.let { station ->
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { longPressStation = null }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    station.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                if (station.streamCount > 1) {
                    Text(
                        stringResource(R.string.radio_lines_count, station.streamCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
                    )
                }
                androidx.compose.material3.HorizontalDivider()
                androidx.compose.material3.NavigationDrawerItem(
                    label = { Text(stringResource(R.string.radio_view_info)) },
                    icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                    selected = false,
                    onClick = {
                        showInfoDialog = station
                        longPressStation = null
                    }
                )
                androidx.compose.material3.NavigationDrawerItem(
                    label = { Text(stringResource(R.string.radio_unfavorite)) },
                    icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                    selected = false,
                    onClick = {
                        viewModel.toggleFavorite(station)
                        longPressStation = null
                    }
                )
                androidx.compose.material3.NavigationDrawerItem(
                    label = { Text(stringResource(R.string.radio_edit)) },
                    icon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                    selected = false,
                    onClick = {
                        showEditDialog = station
                        longPressStation = null
                    }
                )
                androidx.compose.material3.NavigationDrawerItem(
                    label = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                    icon = {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    selected = false,
                    onClick = {
                        showDeleteConfirm = station
                        longPressStation = null
                    }
                )
            }
        }
    }

    // 查看电台信息对话框
    showInfoDialog?.let { station ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showInfoDialog = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.radio_station_info)) },
            text = {
                Column {
                    InfoRow(stringResource(R.string.radio_name_label), station.displayName)
                    InfoRow(stringResource(R.string.radio_category), RadioDictTranslate.text(station.genre?.split("/")?.firstOrNull() ?: "").ifEmpty { stringResource(R.string.radio_uncategorized) })
                    InfoRow(stringResource(R.string.radio_region), RadioDictTranslate.text(station.country ?: station.genre?.split("/")?.getOrNull(1) ?: "-"))
                    station.genre?.split("/")?.getOrNull(2)?.let { InfoRow(stringResource(R.string.radio_province), RadioDictTranslate.text(it)) }
                    station.genre?.split("/")?.getOrNull(3)?.let { InfoRow(stringResource(R.string.radio_city), RadioDictTranslate.text(it)) }
                    InfoRow(stringResource(R.string.radio_line_total_label), "${station.streamCount}")
                    station.streams.forEachIndexed { index, stream ->
                        InfoRow(stringResource(R.string.radio_line_item, index + 1), stream.name)
                    }
                    InfoRow(stringResource(R.string.radio_source), station.source)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showInfoDialog = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }

    // 删除确认对话框
    showDeleteConfirm?.let { station ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.radio_delete_station_title)) },
            text = {
                if (station.streamCount > 1) {
                    Text(stringResource(R.string.radio_delete_station_confirm_multi, station.displayName, station.streamCount))
                } else {
                    Text(stringResource(R.string.radio_delete_station_confirm, station.displayName))
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.deleteStation(station)
                    showDeleteConfirm = null
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // 修改电台对话框
    showEditDialog?.let { station ->
        EditStationDialog(
            station = station,
            onDismiss = { showEditDialog = null },
            onSave = { name, urls, genre ->
                viewModel.saveEditedStation(name, urls, genre, station)
                showEditDialog = null
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteStationListItem(
    station: MergedRadioStation,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 圆形图标
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isBuffering) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (isPlaying) {
                Icon(
                    Icons.Filled.Pause,
                    contentDescription = stringResource(R.string.radio_now_playing),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                val logoUrl = station.logoUrl
                if (!logoUrl.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = logoUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        error = androidx.compose.ui.graphics.painter.ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
                    )
                } else {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        // 电台信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                station.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val genreParts = station.genre?.split("/") ?: emptyList()
            val subtitle = listOfNotNull(
                genreParts.getOrNull(0),
                genreParts.getOrNull(2),
                genreParts.getOrNull(3),
                if (station.streamCount > 1) stringResource(R.string.radio_line_count_short, station.streamCount) else null
            ).filter { it.isNotBlank() }.map { RadioDictTranslate.text(it) }.joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
