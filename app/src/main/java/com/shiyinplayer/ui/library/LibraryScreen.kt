package com.shiyinplayer.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.data.model.Album
import com.shiyinplayer.data.model.Artist
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.albums.AlbumsViewModel
import com.shiyinplayer.ui.artists.ArtistsViewModel
import com.shiyinplayer.ui.folder.FolderPane
import com.shiyinplayer.ui.common.AddToPlaylistDialog
import com.shiyinplayer.ui.common.SongActionsViewModel
import com.shiyinplayer.ui.common.SongMenuHost
import com.shiyinplayer.ui.common.components.AlbumCard
import com.shiyinplayer.ui.common.components.ArtistRow
import com.shiyinplayer.ui.common.components.SongRow
import com.shiyinplayer.ui.navigation.Screen
import com.shiyinplayer.ui.player.PlayerViewModel
import com.shiyinplayer.ui.songs.SongsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 曲库二级导航标签（歌曲/文件夹/专辑/艺术家）。 */
private val tabs = listOf("歌曲", "文件夹", "专辑", "艺术家")

/** 歌曲分组键：拉丁字母取大写首字母，其余归入 #。 */
private fun groupKey(title: String): String {
    val c = title.trim().firstOrNull() ?: return "#"
    return if (c.isLetter() && c.code < 128) c.uppercaseChar().toString() else "#"
}

/** 艺术家/专辑分组键：取名称（或非拉丁）首字母，中文/符号归入 #。 */
private fun nameKey(name: String?): String = groupKey(name ?: "")

/**
 * 曲库：二级导航（歌曲/专辑/艺术家）+ 右侧统一按钮（全局搜索/添加源/选择）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController? = null) {
    var tab by remember { mutableIntStateOf(0) }
    // 各 tab 多选模式状态（提升到 LibraryScreen 统一管理）
    var songsSelectionMode by remember { mutableStateOf(false) }
    var foldersSelectionMode by remember { mutableStateOf(false) }
    var albumsSelectionMode by remember { mutableStateOf(false) }
    var artistsSelectionMode by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // LibraryScreen 全局返回键：selectionMode → tab 0 → 退到后台
    BackHandler {
        val anySelection = (tab == 0 && songsSelectionMode) ||
            (tab == 1 && foldersSelectionMode) ||
            (tab == 2 && albumsSelectionMode) ||
            (tab == 3 && artistsSelectionMode)
        when {
            anySelection -> {
                when (tab) {
                    0 -> songsSelectionMode = false
                    1 -> foldersSelectionMode = false
                    2 -> albumsSelectionMode = false
                    3 -> artistsSelectionMode = false
                }
            }
            tab != 0 -> { tab = 0 }
            else -> {
                (context as? android.app.Activity)?.moveTaskToBack(true)
            }
        }
    }
    val scope = rememberCoroutineScope()
    val songsViewModel: SongsViewModel = hiltViewModel()

    // 「添加歌曲」多选导入：OpenMultipleDocuments
    val multiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        uris?.takeIf { it.isNotEmpty() }?.let { list ->
            list.forEach {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            scope.launch { songsViewModel.importUris(list) }
        }
    }
    // 「添加文件夹」：SAF 文档树，递归扫描
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it)?.name
                ?: it.lastPathSegment ?: "本地文件夹"
            scope.launch { songsViewModel.addLocalFolder(it, name) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val onGlobalSearch: () -> Unit = { navController?.navigate(Screen.Search.route) }
        val onAdd: () -> Unit = { navController?.navigate(Screen.Network.route) }
        val selectionMode = (tab == 0 && songsSelectionMode) ||
            (tab == 1 && foldersSelectionMode) ||
            (tab == 2 && albumsSelectionMode) ||
            (tab == 3 && artistsSelectionMode)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrimaryTabRow(
                selectedTabIndex = tab,
                modifier = Modifier.weight(1f)
            ) {
                tabs.forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            // 统一点击按钮：右侧 MoreVert 下拉菜单（全局搜索/添加源/选择），并入 Tab 栏同一行
            Box(Modifier.padding(horizontal = 4.dp)) {
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("全局搜索") },
                        leadingIcon = { Icon(Icons.Default.TravelExplore, contentDescription = null) },
                        onClick = { menuExpanded = false; onGlobalSearch() }
                    )
                    DropdownMenuItem(
                        text = { Text("添加源") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                        onClick = { menuExpanded = false; onAdd() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (selectionMode) "退出选择" else "选择") },
                        leadingIcon = { Icon(Icons.Default.Checklist, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            when (tab) {
                                0 -> songsSelectionMode = !songsSelectionMode
                                1 -> foldersSelectionMode = !foldersSelectionMode
                                2 -> albumsSelectionMode = !albumsSelectionMode
                                3 -> artistsSelectionMode = !artistsSelectionMode
                            }
                        }
                    )
                }
            }
        }
        HorizontalDivider()
        when (tab) {
            0 -> SongsPane(
                selectionMode = songsSelectionMode,
                onSelectionChange = { songsSelectionMode = it }
            )
            1 -> FolderPane(
                navController = navController,
                selectionMode = foldersSelectionMode,
                onSelectionChange = { foldersSelectionMode = it }
            )
            2 -> AlbumsPane(
                navController = navController,
                selectionMode = albumsSelectionMode,
                onSelectionChange = { albumsSelectionMode = it }
            )
            else -> ArtistsPane(
                navController = navController,
                selectionMode = artistsSelectionMode,
                onSelectionChange = { artistsSelectionMode = it }
            )
        }
    }
}

/** 通用右侧「快速滑动条」：拖动跳转到对应分组首项，并显示当前字母气泡（需求 3 修正显示）。 */
@Composable
private fun FastScroller(
    totalCount: Int,
    sections: List<Pair<String, Int>>,
    scrollTo: suspend (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (sections.isEmpty() || totalCount <= 0) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var bubble by remember { mutableStateOf<String?>(null) }
    var thumbY by remember { mutableFloatStateOf(0f) }
    val letters = remember(sections) { sections.map { it.first } }

    // 根据手指位置算出对应分组并滚动（拖动/点击共用）
    fun handlePointer(fingerY: Float, height: Int) {
        if (height <= 0 || totalCount <= 0) return
        val target = ((fingerY / height) * totalCount).coerceIn(0f, (totalCount - 1).toFloat()).roundToInt()
        val label = sections.lastOrNull { it.second <= target }?.first ?: sections.first().first
        bubble = label
        thumbY = fingerY
        val idx = sections.firstOrNull { it.first == label }?.second ?: return
        scope.launch { scrollTo(idx.coerceAtLeast(0)) }
    }

    fun handleTap(fingerY: Float, height: Int) {
            if (height <= 0 || sections.isEmpty()) return
            val i = ((fingerY / height) * sections.size).coerceIn(0f, (sections.size - 1).toFloat()).roundToInt()
            val pair = sections[i]
            bubble = pair.first
            thumbY = fingerY
            scope.launch { scrollTo(pair.second.coerceAtLeast(0)) }
        }

        Box(modifier.fillMaxHeight().width(44.dp)) {
            // 轨道：右侧细竖线
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 6.dp)
                .width(3.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
        )
        // 首字母分组竖排：右侧边栏直接显示 A–Z/#，直观反映分组（需求 6）
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(44.dp)
                .fillMaxHeight()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEach { l ->
                Text(
                    l,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
        // 字母气泡：跟随拖动/点击位置显示
        val currentBubble = bubble
        if (currentBubble != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = with(density) { (thumbY - 18f).coerceAtLeast(0f).dp })
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(currentBubble, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        // 手势层①：拖动滚动
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(sections, totalCount) {
                    detectVerticalDragGestures(
                        onDragCancel = { bubble = null },
                        onDragEnd = { bubble = null },
                        onDragStart = { offset -> handlePointer(offset.y, size.height) },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            handlePointer(change.position.y, size.height)
                        }
                    )
                }
        )
        // 手势层②：点击字母跳转到对应分组首项（需求 6）
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(sections, totalCount, letters) {
                    detectTapGestures { offset ->
                            handleTap(offset.y, size.height)
                            scope.launch { delay(600); bubble = null }
                        }
                }
        )
    }
}

/** 歌曲 tab：首字母滑动条 + 选择/分组批量操作（复用 SongsViewModel / 共享组件）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongsPane(
    selectionMode: Boolean,
    onSelectionChange: (Boolean) -> Unit
) {
    val viewModel: SongsViewModel = hiltViewModel()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val actionsViewModel: SongActionsViewModel = hiltViewModel()
    val playlists by actionsViewModel.playlists.collectAsStateWithLifecycle()
    // 需求 5：读取当前播放曲目，用于列表加粗 + 切歌时自动滚动
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val pstate by playerViewModel.state.collectAsStateWithLifecycle()
    val currentPlayingId = pstate.currentSong?.id

    // 歌曲 tab 无内联搜索，直接使用全量列表
    val filtered = songs
    // FastScroller 分组点缓存
    val sections = remember(filtered) {
        buildList {
            var last: String? = null
            filtered.forEachIndexed { i, s ->
                val k = groupKey(s.title)
                if (k != last) { add(k to i); last = k }
            }
        }
    }

    var menuSong by remember { mutableStateOf<Song?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val selectedSongs = filtered.filter { it.id in selectedIds }

    // 退出选择模式时清空选中，避免下次进入残留旧勾选
    LaunchedEffect(selectionMode) { if (!selectionMode) selectedIds = emptySet() }

    fun toggleSelect(song: Song) {
        selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id
    }

    Column(Modifier.fillMaxSize()) {
        HorizontalDivider()
        if (selectionMode) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选 ${selectedIds.size} 首",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(onClick = {
                    selectedIds = if (selectedIds.size == filtered.size) emptySet() else filtered.map { it.id }.toSet()
                }) { Text(if (selectedIds.size == filtered.size) "取消全选" else "全选") }
                OutlinedButton(onClick = { onSelectionChange(false) }) { Text("完成") }
            }
        } else {
            Text(
                "${filtered.size} 首",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        val listState = rememberLazyListState()
        // 切歌时自动滚动到当前播放曲目（需求 5）
        LaunchedEffect(currentPlayingId) {
            val idx = filtered.indexOfFirst { it.id == currentPlayingId }
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { song ->
                    val isCurrent = song.id == currentPlayingId
                    if (selectionMode) {
                        SongRow(
                            song = song,
                            onClick = { toggleSelect(song) },
                            modifier = Modifier.weight(1f),
                            isCurrent = isCurrent,
                            showCheckbox = true,
                            checked = song.id in selectedIds,
                            onCheckedChange = { toggleSelect(song) }
                        )
                    } else {
                        SongRow(song = song, onClick = { viewModel.play(song) }, onLongPress = { menuSong = song }, isCurrent = isCurrent)
                    }
                }
            }
            if (!selectionMode) {
                FastScroller(
                    totalCount = filtered.size,
                    sections = sections,
                    scrollTo = { listState.scrollToItem(it) },
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }

        if (selectionMode && selectedIds.isNotEmpty()) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actionsViewModel.playAll(selectedSongs) }, modifier = Modifier.weight(1f)) { Text("播放全部") }
                OutlinedButton(onClick = { actionsViewModel.stop() }, modifier = Modifier.weight(1f)) { Text("停止") }
                OutlinedButton(onClick = { showPlaylistPicker = true }, modifier = Modifier.weight(1f)) { Text("加入歌单") }
                TextButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.weight(1f)) { Text("删除") }
            }
        }
    }

    SongMenuHost(menuSong = menuSong, onDismiss = { menuSong = null })
    if (showPlaylistPicker) {
        AddToPlaylistDialog(
            playlists = playlists,
            subtitle = "将 ${selectedSongs.size} 首曲目加入播放列表",
            onDismiss = { showPlaylistPicker = false },
            onCreate = { name -> scope.launch { actionsViewModel.createAndAddMany(name, selectedSongs) }; showPlaylistPicker = false },
            onSelect = { pl -> scope.launch { actionsViewModel.addSongsToPlaylist(pl.id, selectedSongs) }; showPlaylistPicker = false }
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onSelectionChange(false)
                    selectedIds = emptySet()
                    scope.launch { actionsViewModel.deleteSongs(selectedSongs) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } },
            title = { Text("删除曲目") },
            text = { Text("确定从曲库删除选中的 ${selectedSongs.size} 首曲目？") }
        )
    }
}

/** 专辑 tab：名称滑动条（名称视图） + 选择模式（多选后播放/加入歌单）。 */
@Composable
private fun AlbumsPane(
    navController: NavController?,
    selectionMode: Boolean,
    onSelectionChange: (Boolean) -> Unit
) {
    val viewModel: AlbumsViewModel = hiltViewModel()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val actionsViewModel: SongActionsViewModel = hiltViewModel()
    val playlists by actionsViewModel.playlists.collectAsStateWithLifecycle()
    // F1-4：选择（勾选专辑）用于加歌单 / 全部播放
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var pendingSongs by remember { mutableStateOf<List<Song>>(emptyList()) }

    val filtered = albums
    val selectedAlbums = filtered.filter { it.id in selectedIds }
    LaunchedEffect(selectionMode) { if (!selectionMode) selectedIds = emptySet() }

    fun toggle(album: Album) {
        selectedIds = if (album.id in selectedIds) selectedIds - album.id else selectedIds + album.id
    }

    Column(Modifier.fillMaxSize()) {
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            listOf("名称" to AlbumsViewModel.SORT_NAME, "年代" to AlbumsViewModel.SORT_YEAR, "艺术家" to AlbumsViewModel.SORT_ARTIST)
                .forEach { (label, value) ->
                    val selected = sortMode == value
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                            .clickable { viewModel.setSortMode(value) }
                            .padding(vertical = 5.dp)
                    )
                }
        }
        if (selectionMode) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选 ${selectedIds.size} 张",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(onClick = {
                    selectedIds = if (selectedIds.size == filtered.size) emptySet() else filtered.map { it.id }.toSet()
                }) { Text(if (selectedIds.size == filtered.size) "取消全选" else "全选") }
                OutlinedButton(onClick = { onSelectionChange(false) }) { Text("完成") }
            }
        }
        val gridState = rememberLazyGridState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(end = 44.dp)
            ) {
                if (sortMode == AlbumsViewModel.SORT_YEAR) {
                    filtered.groupBy { it.year?.toString() ?: "未知年代" }.forEach { (year, list) ->
                        item(key = "year-$year", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                year, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        items(list, key = { it.id }) { album ->
                            AlbumGridCell(
                                album = album,
                                selectionMode = selectionMode,
                                selected = album.id in selectedIds,
                                onClick = {
                                    if (selectionMode) toggle(album)
                                    else navController?.navigate(Screen.AlbumDetail.createRoute(album.name, album.artistName))
                                }
                            )
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { album ->
                        AlbumGridCell(
                            album = album,
                            selectionMode = selectionMode,
                            selected = album.id in selectedIds,
                            onClick = {
                                if (selectionMode) toggle(album)
                                else navController?.navigate(Screen.AlbumDetail.createRoute(album.name, album.artistName))
                            }
                        )
                    }
                }
            }
            if (sortMode == AlbumsViewModel.SORT_NAME) {
                Row(Modifier.matchParentSize()) {
                    Box(Modifier.weight(1f))
                    val sections = buildList {
                        var last: String? = null
                        filtered.forEachIndexed { i, a ->
                            val k = nameKey(a.name)
                            if (k != last) { add(k to i); last = k }
                        }
                    }
                    FastScroller(
                        totalCount = sections.lastOrNull()?.second?.let { it + 1 } ?: filtered.size,
                        sections = sections,
                        scrollTo = { gridState.scrollToItem(it) },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }
        }
        // 选择模式底部操作条
        if (selectionMode && selectedIds.isNotEmpty()) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { actionsViewModel.playAll(viewModel.songsFor(selectedAlbums)) } },
                    modifier = Modifier.weight(1f)
                ) { Text("播放全部") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            pendingSongs = viewModel.songsFor(selectedAlbums)
                            showPlaylistPicker = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("加入歌单") }
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

/** 专辑网格单元：选择模式下叠加复选框，整卡点击切换勾选。 */
@Composable
private fun AlbumGridCell(
    album: Album,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(Modifier.padding(4.dp)) {
        AlbumCard(album = album, onClick = onClick)
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp)
            )
        }
    }
}

/** 艺术家 tab：名称滑动条 + 选择模式（多选后播放/加入歌单）。 */
@Composable
private fun ArtistsPane(
    navController: NavController?,
    selectionMode: Boolean,
    onSelectionChange: (Boolean) -> Unit
) {
    val viewModel: ArtistsViewModel = hiltViewModel()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val actionsViewModel: SongActionsViewModel = hiltViewModel()
    val playlists by actionsViewModel.playlists.collectAsStateWithLifecycle()
    // F1-4：选择（勾选艺术家）用于加歌单 / 全部播放
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var pendingSongs by remember { mutableStateOf<List<Song>>(emptyList()) }

    val filtered = artists
    val selectedArtists = filtered.filter { it.id in selectedIds }
    LaunchedEffect(selectionMode) { if (!selectionMode) selectedIds = emptySet() }

    fun toggle(artist: Artist) {
        selectedIds = if (artist.id in selectedIds) selectedIds - artist.id else selectedIds + artist.id
    }

    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        HorizontalDivider()
        if (selectionMode) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选 ${selectedIds.size} 位",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(onClick = {
                    selectedIds = if (selectedIds.size == filtered.size) emptySet() else filtered.map { it.id }.toSet()
                }) { Text(if (selectedIds.size == filtered.size) "取消全选" else "全选") }
                OutlinedButton(onClick = { onSelectionChange(false) }) { Text("完成") }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { artist ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectionMode) {
                            Checkbox(
                                checked = artist.id in selectedIds,
                                onCheckedChange = { toggle(artist) }
                            )
                        }
                        ArtistRow(artist = artist, modifier = Modifier.weight(1f), onClick = {
                            if (selectionMode) toggle(artist)
                            else navController?.navigate(Screen.ArtistDetail.createRoute(artist.name))
                        })
                    }
                    HorizontalDivider()
                }
            }
            val sections = buildList {
                var last: String? = null
                filtered.forEachIndexed { i, a ->
                    val k = nameKey(a.name)
                    if (k != last) { add(k to i); last = k }
                }
            }
            FastScroller(
                totalCount = filtered.size,
                sections = sections,
                scrollTo = { listState.scrollToItem(it) },
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
        // 选择模式底部操作条
        if (selectionMode && selectedIds.isNotEmpty()) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { actionsViewModel.playAll(viewModel.songsFor(selectedArtists)) } },
                    modifier = Modifier.weight(1f)
                ) { Text("播放全部") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            pendingSongs = viewModel.songsFor(selectedArtists)
                            showPlaylistPicker = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("加入歌单") }
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
