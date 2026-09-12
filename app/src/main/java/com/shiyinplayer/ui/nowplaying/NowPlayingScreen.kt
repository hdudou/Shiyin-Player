package com.shiyinplayer.ui.nowplaying

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LyricCandidate
import com.shiyinplayer.player.PlaybackState
import com.shiyinplayer.player.RepeatMode
import com.shiyinplayer.ui.lyrics.LyricsUiState
import com.shiyinplayer.ui.lyrics.LyricsView
import com.shiyinplayer.ui.lyrics.LyricsViewModel
import com.shiyinplayer.ui.settings.SettingsViewModel
import com.shiyinplayer.ui.common.OnlineMatchDialog
import com.shiyinplayer.ui.common.SongFileInfoDialog
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.shiyinplayer.ui.common.SongActionsViewModel
import com.shiyinplayer.util.TimeUtils
import kotlinx.coroutines.delay

/** F3-2：播放页手势层——双击切换播放/暂停，左右横滑快速切歌（位移超过阈值才触发，避免误触；与歌词竖向滚动互不干扰）。 */
private fun Modifier.nowPlayingGestures(
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onInteraction: () -> Unit = {}
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(onTap = { onInteraction() }, onDoubleTap = { onToggle() })
    }
    .pointerInput(Unit) {
        var acc = 0f
        val threshold = 140.dp.toPx()
        detectHorizontalDragGestures(
            onDragStart = { acc = 0f; onInteraction() },
            onHorizontalDrag = { _, a -> acc += a },
            onDragEnd = {
                when {
                    acc >= threshold -> onPrevious()
                    acc <= -threshold -> onNext()
                }
            }
        )
    }

@Composable
fun NowPlayingScreen(
    navController: androidx.navigation.NavController,
    viewModel: NowPlayingViewModel = hiltViewModel(),
    lyricsViewModel: LyricsViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val autoHideControls by settingsViewModel.autoHideControls.collectAsStateWithLifecycle()
    val autoHideDelayMs by settingsViewModel.autoHideDelayMs.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lyrics by lyricsViewModel.state.collectAsStateWithLifecycle()
    val sleepEndAt by viewModel.sleepTimerEndAt.collectAsStateWithLifecycle()
    val positionTick by lyricsViewModel.positionTick.collectAsStateWithLifecycle()
    var showMatch by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }
    val alarmEnabled by viewModel.alarmEnabled.collectAsStateWithLifecycle()
    val alarmHour by viewModel.alarmHour.collectAsStateWithLifecycle()
    val alarmMinute by viewModel.alarmMinute.collectAsStateWithLifecycle()
    var showLyricSearch by remember { mutableStateOf(false) }
    var showFileInfo by remember { mutableStateOf(false) }
    val songActionsVm: SongActionsViewModel = hiltViewModel()
    // 切歌动画方向：0=无动画，1=下一首（旧左出/新右入），-1=上一首（旧右出/新左入）
    var animDirection by remember { mutableStateOf(0) }
    val song = state.currentSong ?: run {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("未在播放")
        }
        return
    }

    // §12 R5：基于稳定 tick 驱动当前歌词行，避免依赖 playbackState 发射粒度导致跳行
    val currentLine = lyricsViewModel.indexForPosition(positionTick)
    val coverUrl = lyrics.coverUrl ?: song.albumArtUri

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // 横屏：左信息栏（返回+工具+封面小图+信息）+ 右歌词区（占满）+ 底部通栏控制；
        // 复用竖屏全部 ViewModel/填充/弹窗，功能与控件风格一致
        Box(
            Modifier.fillMaxSize().nowPlayingGestures(
                onToggle = viewModel::toggle,
                onNext = { animDirection = 1; viewModel.next() },
                onPrevious = { animDirection = -1; viewModel.prev() }
            )
        ) {
            LandscapeNowPlayingContent(
            song = song,
            state = state,
            lyrics = lyrics,
            sleepEndAt = sleepEndAt,
            currentLine = currentLine,
            coverUrl = coverUrl,
            animDirection = animDirection,
            navController = navController,
            viewModel = viewModel,
            lyricsViewModel = lyricsViewModel,
            onShowTimerDialog = { showTimerDialog = true },
            onShowMatch = { showMatch = true },
            onShowLyricSearch = { showLyricSearch = true },
            onShowFileInfo = { showFileInfo = true },
            alarmEnabled = alarmEnabled,
            onNextWithAnim = { animDirection = 1; viewModel.next() },
            onPrevWithAnim = { animDirection = -1; viewModel.prev() }
        )
        }
    } else {
        // 无操作（时长由界面设置 autoHideDelayMs 决定）后自动隐藏控件（仅保留封面/标题/歌词），任意点击屏幕恢复
        var idle by remember { mutableStateOf(false) }
        var lastInteract by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(autoHideControls, autoHideDelayMs) {
            while (true) {
                if (autoHideControls && !idle && System.currentTimeMillis() - lastInteract >= autoHideDelayMs) idle = true
                delay(100)
            }
        }
        val touchNow: () -> Unit = {
            lastInteract = System.currentTimeMillis()
            if (idle) idle = false
        }
        // 布局固定不变，无操作时仅淡出非核心控件（按钮位置与歌词区域保持原样），点击任意屏幕后淡入恢复
        val contentAlpha by animateFloatAsState(
            targetValue = if (idle) 0f else 1f,
            animationSpec = tween(400),
            label = "contentAlpha"
        )
        Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .nowPlayingGestures(
                    onToggle = viewModel::toggle,
                    onNext = { animDirection = 1; viewModel.next() },
                    onPrevious = { animDirection = -1; viewModel.prev() },
                    onInteraction = touchNow
                )
                // 任意触控（含歌词滚动、按钮按下）都算操作，重置自动隐藏计时
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            if (awaitPointerEvent().changes.any { it.pressed }) touchNow()
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        // 全屏页返回：收起正在播放，回到原列表（不阻塞其它功能）；无操作时仅淡出，布局保持不变
        Box(Modifier.fillMaxWidth().graphicsLayer { alpha = contentAlpha }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.navigateUp() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.weight(1f))
            // 定时（合并睡眠定时 + 定时播放，统一入口）
            IconButton(onClick = { showTimerDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Timer,
                    contentDescription = "定时",
                    modifier = Modifier.size(20.dp),
                    tint = if (sleepEndAt != null || alarmEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // P5 需求 14：查看当前文件信息
            IconButton(onClick = { showFileInfo = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = "文件信息", modifier = Modifier.size(20.dp))
            }
            // 锁屏动态效果入口（M1）
            IconButton(
                onClick = {
                    context.startActivity(
                        Intent(context, com.shiyinplayer.lockscreen.LockScreenOverlayActivity::class.java)
                            .putExtra(com.shiyinplayer.lockscreen.LockScreenOverlayActivity.EXTRA_MANUAL_MODE, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "锁屏动态效果",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        }
        Spacer(Modifier.height(4.dp))
        // 切歌动画（B 方案：滑动+淡入淡出，方向感知）
        val enterTransition = when (animDirection) {
            1 -> slideInHorizontally(tween(300)) { it } + fadeIn(tween(300))
            -1 -> slideInHorizontally(tween(300)) { -it } + fadeIn(tween(300))
            else -> fadeIn(tween(200))
        }
        val exitTransition = when (animDirection) {
            1 -> slideOutHorizontally(tween(300)) { -it } + fadeOut(tween(300))
            -1 -> slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300))
            else -> fadeOut(tween(200))
        }
        AnimatedContent(
            targetState = song.id,
            transitionSpec = {
                (enterTransition togetherWith exitTransition).using(SizeTransform(clip = false))
            },
            label = "songTransition"
        ) { _ ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 封面（在线元数据 or 内嵌）
                if (coverUrl != null) {
                    val phColor = MaterialTheme.colorScheme.surfaceVariant
                    val placeholder: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(phColor)) }
                    SubcomposeAsyncImage(
                        model = coverUrl,
                        contentDescription = "专辑封面",
                        contentScale = ContentScale.Crop,
                        loading = { placeholder() },
                        error = { placeholder() },
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Text(song.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(song.artistName ?: "未知艺术家", style = MaterialTheme.typography.bodyLarge)
                Text(song.albumName ?: "", style = MaterialTheme.typography.bodyMedium)
                // 发行年份（在线抓取优先，其次主库存储）
                val releaseYear = lyrics.year ?: song.year
                if (releaseYear != null) {
                    Text("发行年份：$releaseYear", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 歌词滚动区（可点击跳转）
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (lyrics.loading) {
                Text(
                    "歌词加载中…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LyricsView(
                    lines = lyrics.lines,
                    currentIndex = currentLine,
                    onSeek = viewModel::seek,
                    modifier = Modifier.fillMaxSize(),
                    emptyText = lyrics.error ?: "暂无歌词",
                    showTranslation = lyrics.showTranslation
                )
            }
        }
        // 来源 + 重试（低调样式：10sp 小字号 + 半透明淡色）；无操作时仅淡出，布局保持不变
        Column(Modifier.fillMaxWidth().graphicsLayer { alpha = contentAlpha }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                lyrics.source?.let { "歌词来源：$it" } ?: "",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            IconButton(onClick = lyricsViewModel::refresh, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "重新获取歌词", modifier = Modifier.size(14.dp))
            }
            // F2-4：译文显隐切换（仅在有译文时可用）
            if (lyrics.hasTranslation) {
                OutlinedButton(
                    onClick = lyricsViewModel::toggleTranslation,
                    modifier = Modifier.height(24.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text(
                        if (lyrics.showTranslation) "译文" else "原词",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            // F2-4：桌面歌词悬浮窗开关
            val desktopShown by viewModel.desktopLyricShown.collectAsStateWithLifecycle()
            OutlinedButton(
                onClick = viewModel::toggleDesktopLyric,
                modifier = Modifier.height(24.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text(
                    if (desktopShown) "关闭桌面" else "桌面歌词",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (desktopShown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // §12 R3：歌词时间校正（按歌曲持久化到主库 lyricOffsetMs），紧凑小按钮
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("歌词校正", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(-500L to "−0.5s", 500L to "+0.5s").forEach { (deltaMs, label) ->
                OutlinedButton(
                    onClick = { lyricsViewModel.adjustOffset(song.id, deltaMs) },
                    modifier = Modifier.height(24.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
            // 在线匹配元数据入口（预填当前曲目）
            OutlinedButton(
                onClick = { showMatch = true },
                modifier = Modifier.height(24.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text("在线匹配", style = MaterialTheme.typography.labelSmall)
            }
            // 2026-08-19 需求4：查找歌词（按标题/歌手搜索候选 → 用户选择 → 更新当前数据库歌词）
            OutlinedButton(
                onClick = { showLyricSearch = true },
                modifier = Modifier.height(24.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text("查找歌词", style = MaterialTheme.typography.labelSmall)
            }
        }

        val safeDuration = state.durationMs.coerceIn(1L, 86_400_000L)
        Slider(
            value = state.positionMs.toFloat().coerceAtMost(safeDuration.toFloat()),
            onValueChange = { viewModel.seek(it.toLong()) },
            valueRange = 0f..safeDuration.toFloat(),
            modifier = Modifier.fillMaxWidth().height(28.dp)
        )
        // 播放时间：左当前 / 右总时长，与进度条同一行级别，小字号节省空间
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                TimeUtils.formatDuration(state.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                TimeUtils.formatDuration(state.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            // 循环/随机：生效态用鲜艳亮色、非生效态用常规主题灰，仅以图标颜色区分（需求 2，不再用背景高亮）
            val repeatActive = state.repeatMode != RepeatMode.OFF
            val activeColor = Color(0xFF00C853)
            IconButton(onClick = viewModel::cycleRepeat, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (state.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = when (state.repeatMode) {
                        RepeatMode.OFF -> "循环关闭"
                        RepeatMode.ALL -> "全部循环"
                        RepeatMode.ONE -> "单曲循环"
                    },
                    tint = if (repeatActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = viewModel::toggleShuffle, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = if (state.shuffle) "随机开启" else "随机关闭",
                    tint = if (state.shuffle) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { animDirection = -1; viewModel.prev() }) { Icon(Icons.Default.SkipPrevious, null) }
            IconButton(onClick = viewModel::toggle) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
            }
            // P5 需求 15：停止播放（播放·暂停 与 下一首 之间）
            IconButton(onClick = viewModel::stop) { Icon(Icons.Default.Stop, null) }
            IconButton(onClick = { animDirection = 1; viewModel.next() }) { Icon(Icons.Default.SkipNext, null) }
        }
        }
        }
    }
    }
    // 定时弹窗：睡眠定时 + 定时播放 合并到一个设置窗口，统一入口触发
    if (showTimerDialog) {
        TimerSettingsDialog(
            sleepEndAt = sleepEndAt,
            alarmEnabled = alarmEnabled,
            alarmHour = alarmHour,
            alarmMinute = alarmMinute,
            onSleepSelect = { viewModel.setSleepTimer(it) },
            onAlarmEnabledChange = { viewModel.setAlarmEnabled(it) },
            onAlarmConfirm = { h, m -> viewModel.setAlarmTime(h, m) },
            onDismiss = { showTimerDialog = false }
        )
    }
    // §0.1 需求 B：一键在线匹配（预填当前曲目），应用后按新选元数据即时重拉歌词与封面
    if (showMatch) {
        OnlineMatchDialog(
            song = song,
            viewModel = songActionsVm,
            onDismiss = { showMatch = false },
            onApplied = { m -> lyricsViewModel.applyMatchedMetadata(song, m.title, m.artist) }
        )
    }
    // 2026-08-19 需求4：修改歌词（搜索候选 → 用户选择 → 更新当前数据库歌词）
    if (showLyricSearch) {
        SearchLyricsDialog(
            song = song,
            viewModel = lyricsViewModel,
            onDismiss = {
                showLyricSearch = false
                lyricsViewModel.dismissLyricSearch()
            }
        )
    }
    // P5 需求 14：文件信息弹窗（2026-08-24 复用曲库统一实现，含 URL 解码，消除网络源 URI/路径乱码）
    if (showFileInfo) {
        SongFileInfoDialog(
            song = song,
            onDismiss = { showFileInfo = false }
        )
    }
}

/**
 * 横屏全屏正在播放：左信息栏（返回 + 工具 + 封面小图 + 信息）+ 右歌词区 + 底部通栏（进度/歌词工具/居中控制）。
 * 图标、控件尺寸与颜色等全部复用竖屏 NowPlaying 的同一套写法，保证横竖屏风格一致；并复用全部 ViewModel/弹窗。
 */
@Composable
private fun LandscapeNowPlayingContent(
    song: Song,
    state: PlaybackState,
    lyrics: LyricsUiState,
    sleepEndAt: Long?,
    currentLine: Int,
    coverUrl: String?,
    animDirection: Int,
    navController: NavController,
    viewModel: NowPlayingViewModel,
    lyricsViewModel: LyricsViewModel,
    onShowTimerDialog: () -> Unit,
    onShowMatch: () -> Unit,
    onShowLyricSearch: () -> Unit,
    onShowFileInfo: () -> Unit,
    alarmEnabled: Boolean,
    onNextWithAnim: () -> Unit,
    onPrevWithAnim: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        // 中间行：左信息栏 + 右歌词区
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // 左信息栏（窄）：顶部工具行 + 封面小图 + 下方歌曲信息
            Column(
                Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .padding(start = 8.dp, end = 8.dp, top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                // 顶部：返回按钮靠左
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { navController.navigateUp() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 封面 + 信息：置于可上下滑动的区域（内容过高时可滚动查看），左右居中、靠上
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    val enterT = when (animDirection) {
                        1 -> slideInHorizontally(tween(300)) { it } + fadeIn(tween(300))
                        -1 -> slideInHorizontally(tween(300)) { -it } + fadeIn(tween(300))
                        else -> fadeIn(tween(200))
                    }
                    val exitT = when (animDirection) {
                        1 -> slideOutHorizontally(tween(300)) { -it } + fadeOut(tween(300))
                        -1 -> slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300))
                        else -> fadeOut(tween(200))
                    }
                    AnimatedContent(
                        targetState = song.id,
                        transitionSpec = { (enterT togetherWith exitT).using(SizeTransform(clip = false)) },
                        label = "landscapeSongTransition"
                    ) { _ ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (coverUrl != null) {
                                val phColor = MaterialTheme.colorScheme.surfaceVariant
                                val placeholder: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(phColor)) }
                                SubcomposeAsyncImage(
                                    model = coverUrl,
                                    contentDescription = "专辑封面",
                                    contentScale = ContentScale.Crop,
                                    loading = { placeholder() },
                                    error = { placeholder() },
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                song.title,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                song.artistName ?: "未知艺术家",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                song.albumName ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val releaseYear = lyrics.year ?: song.year
                            if (releaseYear != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "发行年份：$releaseYear",
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(6.dp))
                // 底部：睡眠定时 / 闹钟定时 / A→B 循环 / 播放速度 / 文件信息 / 锁屏动态效果
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = onShowTimerDialog, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Timer,
                            contentDescription = "定时",
                            modifier = Modifier.size(22.dp),
                            tint = if (sleepEndAt != null || alarmEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onShowFileInfo, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Info, contentDescription = "文件信息", modifier = Modifier.size(20.dp))
                    }
                    // 锁屏动态效果入口（M1）
                    IconButton(
                        onClick = {
                            context.startActivity(
                                Intent(context, com.shiyinplayer.lockscreen.LockScreenOverlayActivity::class.java)
                                    .putExtra(com.shiyinplayer.lockscreen.LockScreenOverlayActivity.EXTRA_MANUAL_MODE, true)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "锁屏动态效果",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            VerticalDivider(Modifier.fillMaxHeight())
            // 右歌词区（占满剩余高度，当前行居中高亮）
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (lyrics.loading) {
                    Text(
                        "歌词加载中…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LyricsView(
                        lines = lyrics.lines,
                        currentIndex = currentLine,
                        onSeek = viewModel::seek,
                        modifier = Modifier.fillMaxSize(),
                        emptyText = lyrics.error ?: "暂无歌词",
                        showTranslation = lyrics.showTranslation
                    )
                }
            }
        }

        // 底部通栏：进度条（居中限宽）+ 歌词工具（控制在上面）+ 居中控制
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 620.dp)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Slider(
                    value = state.positionMs.toFloat().coerceAtMost(state.durationMs.toFloat().coerceAtLeast(1f)),
                    onValueChange = { viewModel.seek(it.toLong()) },
                    valueRange = 0f..(state.durationMs.toFloat().coerceAtLeast(1f)),
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(TimeUtils.formatDuration(state.positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(TimeUtils.formatDuration(state.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 歌词来源 + 校正/修改（低调样式，放控制上面，与竖屏一致）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        lyrics.source?.let { "歌词来源：$it" } ?: "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                    IconButton(onClick = lyricsViewModel::refresh, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新获取歌词", modifier = Modifier.size(14.dp))
                    }
                    // F2-4：译文显隐切换（仅在有译文时可用）
                    if (lyrics.hasTranslation) {
                        OutlinedButton(
                            onClick = lyricsViewModel::toggleTranslation,
                            modifier = Modifier.height(24.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text(
                                if (lyrics.showTranslation) "译文" else "原词",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    // F2-4：桌面歌词悬浮窗开关
                    val desktopShown by viewModel.desktopLyricShown.collectAsStateWithLifecycle()
                    OutlinedButton(
                        onClick = viewModel::toggleDesktopLyric,
                        modifier = Modifier.height(24.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text(
                            if (desktopShown) "关闭桌面" else "桌面歌词",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (desktopShown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("歌词校正", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(-500L to "−0.5s", 500L to "+0.5s").forEach { (deltaMs, label) ->
                        OutlinedButton(
                            onClick = { lyricsViewModel.adjustOffset(song.id, deltaMs) },
                            modifier = Modifier.height(24.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    // 在线匹配元数据入口
                    OutlinedButton(
                        onClick = onShowMatch,
                        modifier = Modifier.height(24.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("在线匹配", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onShowLyricSearch,
                        modifier = Modifier.height(24.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("查找歌词", style = MaterialTheme.typography.labelSmall)
                    }
                }
                // 居中播放控制条（紧凑药丸，左右留空），控件风格与竖屏完全一致
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    // 半透明 surfaceVariant 会让 contentColorFor 无法识别，导致无 tint 图标回退为向量默认暗色、
                    // 深色主题下不可见；显式指定内容色使其随主题适配（与竖屏 onSurface 一致）
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        val repeatActive = state.repeatMode != RepeatMode.OFF
                        val activeColor = Color(0xFF00C853)
                        IconButton(onClick = viewModel::cycleRepeat, modifier = Modifier.size(44.dp)) {
                            Icon(
                                if (state.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = when (state.repeatMode) {
                                    RepeatMode.OFF -> "循环关闭"
                                    RepeatMode.ALL -> "全部循环"
                                    RepeatMode.ONE -> "单曲循环"
                                },
                                tint = if (repeatActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = viewModel::toggleShuffle, modifier = Modifier.size(44.dp)) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = if (state.shuffle) "随机开启" else "随机关闭",
                                tint = if (state.shuffle) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onPrevWithAnim) { Icon(Icons.Default.SkipPrevious, contentDescription = "上一首") }
                        IconButton(onClick = viewModel::toggle) {
                            Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "播放/暂停")
                        }
                        IconButton(onClick = viewModel::stop) { Icon(Icons.Default.Stop, contentDescription = "停止") }
                        IconButton(onClick = onNextWithAnim) { Icon(Icons.Default.SkipNext, contentDescription = "下一首") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerSettingsDialog(
    sleepEndAt: Long?,
    alarmEnabled: Boolean,
    alarmHour: Int,
    alarmMinute: Int,
    onSleepSelect: (Int) -> Unit,
    onAlarmEnabledChange: (Boolean) -> Unit,
    onAlarmConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepEndAt) {
        while (sleepEndAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val remaining = sleepEndAt?.let { it - now }
    val tpState = rememberTimePickerState(initialHour = alarmHour, initialMinute = alarmMinute, is24Hour = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("睡眠定时", style = MaterialTheme.typography.titleSmall)
                if (remaining != null && remaining > 0) {
                    Text(
                        "剩余 ${remaining / 60_000} 分 ${(remaining % 60_000) / 1000} 秒后停止",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(15 to "15分", 30 to "30分", 60 to "60分", 0 to "取消").forEach { (m, label) ->
                        TextButton(
                            onClick = { onSleepSelect(m) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                HorizontalDivider()
                Text("定时播放", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("启用闹钟")
                    Spacer(Modifier.weight(1f))
                    Switch(checked = alarmEnabled, onCheckedChange = onAlarmEnabledChange)
                }
                Spacer(Modifier.height(4.dp))
                TimePicker(state = tpState)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAlarmConfirm(tpState.hour, tpState.minute)
                onDismiss()
            }) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 2026-08-19 需求4：修改歌词对话框——按标题/歌手搜索歌词候选，用户点选即应用（绑定当前歌曲）。 */
@Composable
private fun SearchLyricsDialog(
    song: com.shiyinplayer.data.model.Song,
    viewModel: LyricsViewModel,
    onDismiss: () -> Unit
) {
    val lyrics by viewModel.state.collectAsStateWithLifecycle()
    // 默认只填标题（歌手走独立参数），避免「标题 歌手」整体搜索难以命中
    var query by remember { mutableStateOf(song.title) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改歌词") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索词（标题 歌手）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.searchLyrics(query, song.artistName) }) {
                        Text("搜索")
                    }
                    if (lyrics.lyricSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }
                lyrics.lyricSearchError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (lyrics.lyricCandidates.isNotEmpty()) {
                    Text("共 ${lyrics.lyricCandidates.size} 条候选，点击应用：", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.height(260.dp).fillMaxWidth()) {
                        items(lyrics.lyricCandidates, key = { it.source + it.title }) { c: LyricCandidate ->
                            TextButton(
                                onClick = {
                                    // 选定即生效：写入本地数据 + 更新播放界面，并关闭对话框让新歌词可见
                                    viewModel.applyLyric(song, c)
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "${c.title} · ${c.artist ?: "未知"}（${c.source}）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
