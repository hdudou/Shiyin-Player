package com.shiyinplayer.ui.folder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.shiyinplayer.data.local.entity.FolderAttachmentEntity
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.common.SongActionsViewModel
import com.shiyinplayer.ui.common.SongSelectionBar
import com.shiyinplayer.ui.navigation.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 曲库「文件夹」tab：按音乐来源的目录树浏览曲目。
 * - 顶层选择来源（每源一根根目录树；未归属歌曲单独「其他来源」）。
 * - 进入某源后显示路径栏（返回上一级 + 当前路径 + 「立即播放 / 加入歌单」，针对含子文件夹的整目录）。
 * - 目录行点开下级；音乐文件可多选后「播放选中/加入歌单」。
 * - 目录数据全部来自数据库源扫描记录，无子文件夹且无音乐文件的空目录自动隐藏。
 */
@Composable
fun FolderPane(
    navController: androidx.navigation.NavController? = null,
    selectionMode: Boolean = false,
    onSelectionChange: (Boolean) -> Unit = {}
) {
    val viewModel: FolderViewModel = hiltViewModel()
    val actionsViewModel: SongActionsViewModel = hiltViewModel()

    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val hasUnmatched by viewModel.hasUnmatched.collectAsStateWithLifecycle()
    val sourceId by viewModel.sourceId.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val dirSelected by viewModel.selectedDirPaths.collectAsStateWithLifecycle()
    val dirSongs by viewModel.selectedDirRecursiveSongs.collectAsStateWithLifecycle()

    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var previewAtt by remember { mutableStateOf<FolderAttachmentEntity?>(null) }

    // 切换源/路径时清空已选文件、目录勾选与附件预览
    LaunchedEffect(sourceId, segments) { selectedIds = emptySet(); viewModel.clearDirSelection(); previewAtt = null }

    // 退出选择模式时清空全部勾选，避免残留
    LaunchedEffect(selectionMode) { if (!selectionMode) { selectedIds = emptySet(); viewModel.clearDirSelection() } }

    // 文件夹 tab 返回键：选择模式 → 回退路径 → 回到来源列表
    BackHandler(enabled = sourceId != null) {
        when {
            selectionMode -> { onSelectionChange(false) }
            segments.isNotEmpty() -> { viewModel.goUp() }
            else -> { viewModel.backToSources() }
        }
    }

    // 底部选择操作目标 = 勾选目录递归歌曲 ∪ 勾选文件歌曲
    val selectTarget = dirSongs + current.files.filter { it.id in selectedIds }

    if (sourceId == null) {
        FolderSourcePicker(sources, hasUnmatched) { viewModel.selectSource(it) }
    } else {
        val sourceName = sources.firstOrNull { it.id == sourceId }?.name
            ?: (if (sourceId == -1L) "其他来源" else "来源${'#'}$sourceId")

        Column(Modifier.fillMaxSize()) {
            // ===== 第一行：返回键 + 当前路径（完整显示） =====
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回上一级",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { if (segments.isEmpty()) viewModel.backToSources() else viewModel.goUp() }
                        .padding(6.dp)
                )
                Text(
                    buildPathLabel(sourceName, segments),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider()

            // ===== 目录内容（无内联搜索） =====
            val filteredDirs = current.dirs
            val filteredFiles = current.files
            val filteredAtts = attachments
            val listState = rememberLazyListState()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    items(filteredDirs, key = { "d-${it.folderPath}" }) { dir ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    if (selectionMode) viewModel.toggleDirSelected(dir.folderPath)
                                    else viewModel.navigateInto(dir)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                            Text(dir.display, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f))
                            Text(
                                "${dir.songCount} 首",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, end = 8.dp)
                            )
                            if (selectionMode) {
                                Checkbox(
                                    checked = dir.folderPath in dirSelected,
                                    onCheckedChange = { viewModel.toggleDirSelected(dir.folderPath) }
                                )
                            }
                        }
                    }
                    items(filteredAtts, key = { "a-${it.id}" }) { att ->
                        AttachmentRow(att, onClick = { previewAtt = att })
                    }
                    items(filteredFiles, key = { "f-${it.id}" }) { song ->
                        toggleFileRow(song, showCheck = selectionMode, checked = song.id in selectedIds) {
                            selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id
                        }
                    }
                    if (filteredDirs.isEmpty() && filteredFiles.isEmpty() && filteredAtts.isEmpty()) {
                        item {
                            Text(
                                "此目录为空",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                            )
                        }
                    }
                }
                val combined = filteredDirs.map { it.name } + filteredAtts.map { it.name } + filteredFiles.map { it.title }
                if (combined.isNotEmpty()) {
                    val sections = buildList {
                        var last: String? = null
                        combined.forEachIndexed { i, name ->
                            val k = groupKey(name)
                            if (k != last) { add(k to i); last = k }
                        }
                    }
                    FastScroller(
                        totalCount = combined.size,
                        sections = sections,
                        scrollTo = { listState.scrollToItem(it) },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }

            // ===== 选择模式下的底部操作栏（勾选目录递归 ∪ 勾选文件） =====
            if (selectionMode && selectTarget.isNotEmpty()) {
                SongSelectionBar(selectTarget, actionsViewModel)
            }
        }

        // ===== 附件预览对话框（专辑封面图 / 说明 txt） =====
        val previewing = previewAtt
        if (previewing != null) {
            AttachmentPreviewDialog(
                att = previewing,
                onDismiss = { previewAtt = null }
            ) { viewModel.readAttachmentBytes(previewing) }
        }
    }
}

/** 文件夹附件行（专辑封面图 / 说明 txt）。点击打开预览；不参与多选。 */
@Composable
private fun AttachmentRow(att: FolderAttachmentEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (att.isCover) Icons.Filled.Image else Icons.AutoMirrored.Filled.TextSnippet,
            contentDescription = null,
            tint = if (att.isCover) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp).size(24.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(att.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                if (att.isCover) "专辑封面" else "专辑说明",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 附件预览对话框：封面图以图像展示；说明 txt 以滚动文本展示。加载失败给出提示。 */
@Composable
private fun AttachmentPreviewDialog(
    att: FolderAttachmentEntity,
    onDismiss: () -> Unit,
    load: suspend () -> ByteArray?
) {
    var data by remember { mutableStateOf<ByteArray?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(att.id) {
        data = load()
        failed = data == null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text(att.name, style = MaterialTheme.typography.titleSmall, maxLines = 1) },
        text = {
            when {
                data == null && !failed -> {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                data == null -> {
                    Text(
                        "无法加载该附件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    )
                }
                att.isCover -> {
                    AsyncImage(
                        model = data,
                        contentDescription = att.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(360.dp)
                    )
                }
                else -> {
                    Text(
                        String(data!!, Charsets.UTF_8),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    )
}

/** 来源选择层：列出全部音乐来源（含「其他来源」）。 */
@Composable
private fun FolderSourcePicker(
    sources: List<com.shiyinplayer.data.model.MusicSource>,
    hasUnmatched: Boolean,
    onSelect: (Long) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "选择来源",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        items(sources) { src ->
            SourceRow(name = src.name, subtitle = src.type.name, onClick = { onSelect(src.id) })
        }
        if (hasUnmatched) {
            item {
                SourceRow(name = "其他来源", subtitle = "未能匹配到源的曲目", onClick = { onSelect(-1) })
            }
        }
        if (sources.isEmpty() && !hasUnmatched) {
            item {
                Text(
                    "暂无来源，请先在「音乐库来源」中添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }
        }
    }
}

/** 来源选择行。 */
@Composable
private fun SourceRow(name: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 10.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 音乐文件行（选择模式下复选框在右侧，点行勾选/取消勾选）。 */
@Composable
private fun toggleFileRow(song: Song, showCheck: Boolean, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            song.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            song.durationMsText(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showCheck) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}

private fun buildPathLabel(sourceName: String, segments: List<String>): String {
    // 显示风格：来源名/ …\终点（略掉全部中间过渡路径）
    return when {
        segments.isEmpty() -> sourceName
        segments.size == 1 -> "$sourceName/ ${segments[0]}"
        else -> "$sourceName/ $ELLIPSIS\\${segments.last()}"
    }
}

private const val ELLIPSIS = "..."

/** 分组键：拉丁字母取大写首字母，其余归入 #。 */
private fun groupKey(name: String): String {
    val c = name.trim().firstOrNull() ?: return "#"
    return if (c.isLetter() && c.code < 128) c.uppercaseChar().toString() else "#"
}

/** 右侧快速滑动条：拖动/点击跳转到对应首字母分组首项，并显示当前字母气泡（与曲库页一致）。 */
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

    fun handlePointer(fingerY: Float, height: Int) {
        if (height <= 0 || totalCount <= 0) return
        val target = ((fingerY / height) * totalCount).coerceIn(0f, (totalCount - 1).toFloat()).roundToInt()
        val label = sections.lastOrNull { it.second <= target }?.first ?: sections.first().first
        bubble = label
        thumbY = fingerY
        val idx = sections.firstOrNull { it.first == label }?.second ?: return
        scope.launch { scrollTo(idx.coerceAtLeast(0)) }
    }

    // 点击字母栏：按字母位置精确跳转到该分组首项（需求 1）
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
        // 首字母分组竖排
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
        // 字母气泡
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
        // 手势层②：点击字母跳转到对应分组首项（需求 1）
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

private fun Song.durationMsText(): String {
    val s = durationMs / 1000
    return "%d:%02d".format(s / 60, s % 60)
}