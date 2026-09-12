package com.shiyinplayer.ui.queue

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.R
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.common.SongActionsViewModel
import com.shiyinplayer.ui.common.SongMenuHost
import com.shiyinplayer.ui.common.SongSelectionBar
import com.shiyinplayer.ui.player.PlayerViewModel
import com.shiyinplayer.util.TimeUtils
import kotlin.math.roundToInt

/**
 * 队列页：点击播放；长按弹菜单；当前播放歌曲加粗并自动滚动到可见（需求 5）；
 * 行首序号 + 行尾时长；选择模式选择框恒占位（F1-5）；
 * F2-1：新增左滑移除（SwipeToDismissBox）+ 行尾拖拽手柄长按拖拽排序。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val actionsViewModel: SongActionsViewModel = hiltViewModel()
    val selectedSongs = state.queue.filter { it.id in selectedIds }
    val listState = rememberLazyListState()

    fun toggleSelect(song: Song) {
        selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id
    }

    // 切歌（队列索引变化）时自动滚动当前播放项到可见区域（需求 5）
    LaunchedEffect(state.currentIndex) {
        val idx = state.currentIndex
        if (idx in state.queue.indices) listState.animateScrollToItem(idx)
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Text(
                    stringResource(R.string.songs_selected_count, selectedIds.size),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(onClick = {
                    selectedIds = if (selectedIds.size == state.queue.size) emptySet() else state.queue.map { it.id }.toSet()
                }) { Text(if (selectedIds.size == state.queue.size) stringResource(R.string.action_deselect_all) else stringResource(R.string.action_select_all)) }
                OutlinedButton(onClick = { selectionMode = false; selectedIds = emptySet() }) { Text(stringResource(R.string.action_done)) }
            } else {
                Row(
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.queue_total, state.queue.size),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { selectionMode = true }) { Text(stringResource(R.string.action_select)) }
                IconButton(
                    onClick = { if (state.queue.isNotEmpty()) showClearConfirm = true },
                    enabled = state.queue.isNotEmpty()
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = stringResource(R.string.action_clear_queue),
                        tint = if (state.queue.isNotEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            itemsIndexed(state.queue, key = { _, song -> song.id }) { index, song ->
                val isCurrent = index == state.currentIndex
                // F2-1：左滑移除（非选择态生效），红色底衬显示删除意图
                SwipeToDismissBox(
                    state = rememberSwipeToDismissBoxState(
                        confirmValueChange = { v ->
                            if (v == SwipeToDismissBoxValue.EndToStart && !selectionMode) {
                                viewModel.removeFromQueue(index)
                                true
                            } else false
                        }
                    ),
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_remove),
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) toggleSelect(song)
                                    else viewModel.seekToIndex(index)
                                },
                                onLongClick = { if (!selectionMode) menuSong = song }
                            )
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 行首序号（需求 4）
                        Text(
                            "${index + 1}",
                            modifier = Modifier.width(28.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 信息列：标题（当前播放加粗，需求 5）+ 艺术家
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.Bold else null,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (song.artistName?.isNotBlank() == true) {
                                Text(
                                    song.artistName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // 行尾时长（需求 4）
                        Text(
                            TimeUtils.formatDuration(song.durationMs),
                            modifier = Modifier.padding(end = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 勾选列（F1-5）：两种状态都恒占固定宽——选择模式显示 Checkbox，
                        // 普通模式用等宽占位。保证进入选择模式时行首序号 / 信息列 / 时长的
                        // 排列位置完全不变，列表排列样式不因选择态启停而位移。
                        if (selectionMode) {
                            Checkbox(checked = song.id in selectedIds, onCheckedChange = { toggleSelect(song) })
                        } else {
                            // F2-1：非选择态显示拖拽手柄（长按上下拖动排序）
                            val indexRef = rememberUpdatedState(index)
                            val listSizeRef = rememberUpdatedState(state.queue.size)
                            val rowHeightPx = with(LocalDensity.current) { 56.dp.toPx() }
                            Box(
                                Modifier
                                    .width(36.dp)
                                    .fillMaxHeight()
                                    .pointerInput(rowHeightPx) {
                                        var dragFrom = indexRef.value
                                        var acc = 0f
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { acc = 0f; dragFrom = indexRef.value },
                                            onDragEnd = { acc = 0f },
                                            onDragCancel = { acc = 0f },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                acc += amount.y
                                                val step = (acc / rowHeightPx).roundToInt()
                                                if (step != 0) {
                                                    val target = (dragFrom + step)
                                                        .coerceIn(0, listSizeRef.value - 1)
                                                    if (target != dragFrom) {
                                                        viewModel.moveQueueItem(dragFrom, target)
                                                        dragFrom = target
                                                        acc = 0f
                                                    }
                                                }
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = stringResource(R.string.action_drag_sort),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        SongSelectionBar(selectedSongs = selectedSongs, actionsViewModel = actionsViewModel)
    }
    SongMenuHost(menuSong = menuSong, onDismiss = { menuSong = null }, enableDelete = false)

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.action_clear_queue)) },
            text = { Text(stringResource(R.string.clear_queue_confirm, state.queue.size)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearQueue()
                    selectionMode = false
                    selectedIds = emptySet()
                }) { Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}