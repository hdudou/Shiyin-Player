package com.shiyinplayer.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.shiyinplayer.R
import com.shiyinplayer.util.TimeUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.data.metadata.SongMatch
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.Playlist
import com.shiyinplayer.data.model.Song
import kotlinx.coroutines.launch

/** 长按歌曲动作表（下一首播放 / 加入歌单 / 查看专辑 / 查看艺术家 / 删除）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionsSheet(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueueTail: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onViewAlbum: (() -> Unit)? = null,
    onViewArtist: (() -> Unit)? = null,
    onEditInfo: () -> Unit = {},
    onOnlineMatch: () -> Unit = {},
    onFileInfo: () -> Unit = {},
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                // 2026-08-19：底部留出系统手势导航条高度（OnePlus 手势导航 navigationBars inset=0，
                // insets API 均无效；用固定 padding 保障最后一项不被底部导航覆盖）
                .padding(start = 24.dp, end = 24.dp, bottom = 96.dp)
        ) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(song.artistName ?: stringResource(R.string.unknown_artist), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            ActionItem(stringResource(R.string.song_play_next), Icons.Default.PlaylistPlay) { onPlayNext(); onDismiss() }
            // F2-1：新增「稍后播放」（追加到队尾）
            ActionItem(stringResource(R.string.song_play_later), Icons.Default.QueueMusic) { onEnqueueTail(); onDismiss() }
            ActionItem(stringResource(R.string.song_add_playlist), Icons.Default.PlaylistAdd) { onAddToPlaylist() }
            // 2026-08-19：文案改名（编辑元数据 / 在线查找元数据），新增「查看文件信息」
            ActionItem(stringResource(R.string.song_edit_metadata), Icons.Default.Edit) { onEditInfo(); onDismiss() }
            ActionItem(stringResource(R.string.song_online_match), Icons.Default.Search) { onOnlineMatch(); onDismiss() }
            ActionItem(stringResource(R.string.song_view_file_info), Icons.Default.Info) { onFileInfo(); onDismiss() }
            onViewAlbum?.let { ActionItem(stringResource(R.string.song_view_album), Icons.Default.Album) { it(); onDismiss() } }
            onViewArtist?.let { ActionItem(stringResource(R.string.song_view_artist), Icons.Default.Person) { it(); onDismiss() } }
            onRemoveFromPlaylist?.let { ActionItem(stringResource(R.string.song_remove_from_playlist), Icons.Default.RemoveCircleOutline) { it(); onDismiss() } }
            onDelete?.let { ActionItem(stringResource(R.string.song_delete_from_library), Icons.Default.Delete, destructive = true) { it(); onDismiss() } }
        }
    }
}

@Composable
private fun ActionItem(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // 2026-08-19：压缩菜单项高度（14→8dp），给底部导航条留白让位
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 加入播放列表弹窗：选择已有歌单或新建。 */
@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onSelect: (Playlist) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.song_add_playlist)) },
        text = {
            Column {
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(playlists, key = { it.id }) { pl ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(pl) }.padding(vertical = 10.dp)
                        ) { Text(pl.name) }
                    }
                    if (playlists.isEmpty()) {
                        item { Text(stringResource(R.string.playlist_picker_empty), style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    name, { name = it },
                    label = { Text(stringResource(R.string.playlist_new_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = name.isNotBlank(),
                    onClick = { onCreate(name.trim()) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.action_create_add)) }
            }
        }
    )
}

/**
 * [7] 手工编辑歌曲元数据（标题 / 艺术家 / 专辑）并写回主库。
 */
@Composable
fun EditMetadataDialog(song: Song, viewModel: SongActionsViewModel, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artistName ?: "") }
    var album by remember { mutableStateOf(song.albumName ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                viewModel.editMetadata(
                    song,
                    title.trim(),
                    artist.trim().ifBlank { null },
                    album.trim().ifBlank { null }
                )
                onDismiss()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.edit_info_title)) },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.label_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.label_artist)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(album, { album = it }, label = { Text(stringResource(R.string.label_album)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    )
}

/**
 * [7] 在线搜索候选，让用户挑选正确匹配并应用到该曲目（元数据走公网，不经 ZeroTier 虚拟网）。
 */
@Composable
fun OnlineMatchDialog(song: Song, viewModel: SongActionsViewModel, onDismiss: () -> Unit, onApplied: (SongMatch) -> Unit = {}) {
    // §0.1 需求 A：双维度输入（歌名 + 歌手），默认预填当前曲目
    var titleQuery by remember { mutableStateOf(song.title) }
    var artistQuery by remember { mutableStateOf(song.artistName ?: "") }
    var results by remember { mutableStateOf<List<SongMatch>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.online_match_title)) },
        text = {
            Column {
                // 当前文件信息（输入框上方，作匹配参考）—— 来源 + 文件名，多行显示
                val sources by viewModel.musicSources.collectAsStateWithLifecycle()
                val info = remember(song, sources) { buildFileInfo(song, sources) }
                Text(
                    stringResource(R.string.file_source_prefix, info.sourceName ?: typeLabel(song.source)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.file_name_prefix, info.fileName ?: song.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                // 双维度：歌名（固定高度略降输入框高度）
                OutlinedTextField(
                    titleQuery, { titleQuery = it },
                    label = { Text(stringResource(R.string.label_song_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                )
                Spacer(Modifier.height(8.dp))
                // 双维度：歌手（可空）
                OutlinedTextField(
                    artistQuery, { artistQuery = it },
                    label = { Text(stringResource(R.string.label_artist_optional)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    loading = true
                    scope.launch {
                        results = viewModel.searchMatches(titleQuery, artistQuery.ifBlank { null })
                        loading = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (loading) stringResource(R.string.action_searching) else stringResource(R.string.action_search)) }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(results, key = { "${it.source}|${it.id}" }) { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                // 先等匹配写库完成（applyMatchAwait 挂起），再刷新歌词并关闭，
                                // 否则弹窗立即关闭时写库异步未完成，正在播放页信息不会更新。
                                scope.launch {
                                    loading = true
                                    viewModel.applyMatchAwait(song, m)
                                    loading = false
                                    onApplied(m)
                                    onDismiss()
                                }
                            }.padding(vertical = 10.dp)
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                // 结果行：歌名（粗体，单行）
                                Text(
                                    m.title, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold, maxLines = 1
                                )
                                // 结果行：歌手 · 专辑（可未知） · 年份（可选） · 来源
                                val yearPart = m.year?.let { " · $it" } ?: ""
                                Text(
                                    stringResource(R.string.online_match_row, m.artist, m.album ?: stringResource(R.string.unknown_album), yearPart, m.source),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    if (results.isEmpty() && !loading) {
                        item { Text(stringResource(R.string.online_match_hint), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    )
}

/** 长按菜单触发的二级弹窗类型（独立于菜单 Sheet 的状态驱动，避免"关闭菜单时状态残留"bug）。 */
private enum class SongDialogType { PlaylistPicker, Edit, OnlineMatch, FileInfo }

/**
 * 2026-08-19：查看文件信息弹窗——展示文件名 / 存储路径；多源（altUris）时一并列出备用来源。
 * 修复：①路径逐段 URL 解码（网络源 uri 是 %XX 编码，直接显示会乱码）；
 *      ②网络源显示「源名称 + 源内路径/文件名」，隐藏 IP:端口（需求1）。
 */
@Composable
fun SongFileInfoDialog(
    song: Song,
    onDismiss: () -> Unit,
    viewModel: SongActionsViewModel = hiltViewModel()
) {
    val sources by viewModel.musicSources.collectAsStateWithLifecycle()
    val fileInfo = remember(song, sources) { buildFileInfo(song, sources) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.file_info_title)) },
        text = {
            Column {
                // 2026-08-24：与正在播放界面文件信息统一——歌曲名/艺术家/专辑/类型/风格/年份/时长/采样率/MIME/文件名+大小/来源/URI
                // （URI 与文件名已 URL 解码，避免网络源 %XX 乱码）
                val fileType = (fileInfo.fileName ?: "").substringAfterLast('.', "")
                    .takeIf { it.isNotBlank() } ?: stringResource(R.string.unknown_short)
                val sourceLabel = fileInfo.sourceName ?: typeLabel(song.source)
                val rows = listOf(
                    stringResource(R.string.file_info_song_name) to (song.title.ifBlank { "—" }),
                    stringResource(R.string.label_artist) to (song.artistName?.ifBlank { null } ?: "—"),
                    stringResource(R.string.label_album) to (song.albumName?.ifBlank { null } ?: "—"),
                    stringResource(R.string.file_info_type) to fileType,
                    stringResource(R.string.file_info_genre) to (song.genre?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unknown_short)),
                    stringResource(R.string.file_info_release_year) to (song.year?.toString() ?: "—"),
                    stringResource(R.string.file_info_duration) to TimeUtils.formatDuration(song.durationMs),
                    stringResource(R.string.file_info_samplerate) to "—",
                    "MIME" to (song.mimeType?.ifBlank { null } ?: "—")
                )
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("$k", modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(v, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(stringResource(R.string.file_info_filename), modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.file_name_with_size, fileInfo.fileName ?: stringResource(R.string.unknown_short), formatBytes(song.sizeBytes)), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(stringResource(R.string.file_info_source), modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sourceLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.file_uri_prefix, fileInfo.displayUri), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (song.altUris.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.file_info_alt_sources, song.altUris.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    song.altUris.forEach { alt ->
                        val altInfo = buildFileInfo(song.copy(uri = alt, path = alt), sources)
                        Text(
                            if (altInfo.sourceName != null) "${altInfo.sourceName} / ${altInfo.displayPath}" else altInfo.displayPath,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    )
}

/** 文件信息展示模型（需求1：网络源隐藏 IP:端口，只显示源名 + 源内路径）。 */
private data class FileInfoDisplay(
    val fileName: String?,
    val sourceName: String?,
    val displayPath: String,
    val isNetwork: Boolean,
    val displayUri: String
)

@Composable
private fun typeLabel(t: MediaSourceType): String = when (t) {
    MediaSourceType.SMB -> "SMB"
    MediaSourceType.WEBDAV -> "WebDAV"
    MediaSourceType.LOCAL -> stringResource(R.string.type_folder)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val kb = bytes / 1024.0
    return when {
        kb < 1024 -> String.format("%.0f KB", kb)
        kb < 1024 * 1024 -> String.format("%.1f MB", kb / 1024)
        else -> String.format("%.2f GB", kb / (1024 * 1024))
    }
}

private fun buildFileInfo(song: Song, sources: List<com.shiyinplayer.data.model.MusicSource>): FileInfoDisplay {
    val uri = song.path ?: song.uri
    val isNetwork = song.source == MediaSourceType.SMB || song.source == MediaSourceType.WEBDAV

    // 文件名：URL 解码（网络源 uri 路径段为 %XX 编码）
    val fileName = runCatching {
        val seg = android.net.Uri.parse(uri).lastPathSegment ?: return@runCatching null
        decodePathSegment(seg).substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() }
    }.getOrNull()

    if (!isNetwork) {
        return FileInfoDisplay(
            fileName = fileName,
            sourceName = null,
            displayPath = if (uri.startsWith("content://")) fileName ?: uri else uri,
            isNetwork = false,
            displayUri = uri
        )
    }

    // 网络源：匹配 music_sources 拿源名（按 uri 前缀最长匹配）
    val u = runCatching { android.net.Uri.parse(uri) }.getOrNull()
    val sourceName = u?.let { parsed ->
        val base = "${parsed.scheme}://${parsed.host}" + if (parsed.port != -1) ":${parsed.port}" else ""
        sources.firstOrNull { src ->
            runCatching {
                val cfgUrl = org.json.JSONObject(src.configJson).optString("url").trimEnd('/')
                cfgUrl.startsWith(base)
            }.getOrDefault(false)
        }?.name
    }
    // 源内相对路径：去掉 scheme://host:port，剩余路径逐段解码
    val displayPath = if (u != null) {
        val rawPath = u.path ?: uri
        rawPath.split('/').joinToString("/") { decodePathSegment(it) }
    } else {
        decodePathSegment(uri)
    }
    return FileInfoDisplay(
        fileName = fileName,
        sourceName = sourceName,
        displayPath = displayPath,
        isNetwork = true,
        displayUri = decodePathSegment(uri)
    )
}

/** 单个路径段 URL 解码（%XX → 字符），解码失败原样返回。 */
private fun decodePathSegment(seg: String): String = runCatching {
    java.net.URLDecoder.decode(seg, "UTF-8")
}.getOrDefault(seg)

/** 从 content:// / file:// / 绝对路径提取文件名（含扩展名）；失败返回 null。 */
private fun fileNameOf(song: Song): String? = runCatching {
    val uri = song.path ?: song.uri
    val seg = android.net.Uri.parse(uri).lastPathSegment ?: return null
    val decoded = java.net.URLDecoder.decode(seg, "UTF-8")
    decoded.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() }
}.getOrNull()

/**
 * 长按菜单宿主：在屏幕底部挂载，配合列表项 onLongPress = { menuSong = song } 使用。
 * 统一处理歌单选择 / 删除等动作（经 SongActionsViewModel）。
 *
 * 修复说明（2026-08-19）：二级弹窗（加歌单/编辑/在线匹配）由内部 dialogSong+dialogType 独立驱动，
 * 不再依赖 menuSong 非空 —— 旧实现点「在线匹配」后 showSearch=true 但 menuSong 被置 null，
 * 弹窗不显示、状态残留导致下次长按直接弹出匹配窗。
 */
@Composable
fun SongMenuHost(
    menuSong: Song?,
    onDismiss: () -> Unit,
    viewModel: SongActionsViewModel = hiltViewModel(),
    playlistId: Long? = null,
    onViewAlbum: ((Song) -> Unit)? = null,
    onViewArtist: ((Song) -> Unit)? = null,
    enableDelete: Boolean = true
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var dialogSong by remember { mutableStateOf<Song?>(null) }
    var dialogType by remember { mutableStateOf<SongDialogType?>(null) }

    // 防残留：外部 menuSong 被清空（菜单彻底关闭）时，兜底重置内部弹窗状态。
    // 点击子动作时 dialogSong 已先被赋值，此处不会误清。
    LaunchedEffect(menuSong) {
        if (menuSong == null && dialogSong == null) {
            dialogType = null
        }
    }

    // 二级弹窗（独立渲染，与 menuSong 是否为空无关 → 点击后立即弹出）
    dialogSong?.let { song ->
        when (dialogType) {
            SongDialogType.PlaylistPicker -> AddToPlaylistDialog(
                playlists = playlists,
                onDismiss = { dialogSong = null; dialogType = null },
                onCreate = { name -> scope.launch { viewModel.createAndAdd(name, song) }; dialogSong = null; dialogType = null },
                onSelect = { pl -> scope.launch { viewModel.addToPlaylist(pl.id, song) }; dialogSong = null; dialogType = null }
            )
            SongDialogType.Edit -> EditMetadataDialog(song, viewModel) { dialogSong = null; dialogType = null }
            SongDialogType.OnlineMatch -> OnlineMatchDialog(song, viewModel, { dialogSong = null; dialogType = null })
            SongDialogType.FileInfo -> SongFileInfoDialog(song = song, onDismiss = { dialogSong = null; dialogType = null })
            null -> {}
        }
    }

    // 长按功能菜单（仅当 menuSong 非空时显示）
    menuSong?.let { song ->
        SongActionsSheet(
            song = song,
            onDismiss = onDismiss,
            onPlayNext = { viewModel.playNext(song) },
            onEnqueueTail = { viewModel.enqueueTail(song) },
            onAddToPlaylist = { dialogSong = song; dialogType = SongDialogType.PlaylistPicker; onDismiss() },
            onViewAlbum = onViewAlbum?.let { { it(song) } },
            onViewArtist = onViewArtist?.let { { it(song) } },
            onEditInfo = { dialogSong = song; dialogType = SongDialogType.Edit; onDismiss() },
            onOnlineMatch = { dialogSong = song; dialogType = SongDialogType.OnlineMatch; onDismiss() },
            onFileInfo = { dialogSong = song; dialogType = SongDialogType.FileInfo; onDismiss() },
            onRemoveFromPlaylist = playlistId?.let { { viewModel.removeFromPlaylist(it, song); onDismiss() } },
            onDelete = if (enableDelete) { { viewModel.deleteSongs(listOf(song)); onDismiss() } } else null
        )
    }
}