package com.shiyinplayer.ui.radio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.ui.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏电台播放页：动态效果 + 台名/节目 + 播放控制。
 * 支持 5 种动态效果方案，点击中央图标区域可切换。
 * 当前效果选择通过 SettingsRepository 持久化。
 */

/** 5 种视觉效果的名称（用于显示） */
private val VISUAL_EFFECT_NAMES = listOf(
    "声波 + 光晕",
    "唱片旋转",
    "粒子浮动",
    "频谱条",
    "纯光晕"
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RadioPlayingScreen(
    onBack: () -> Unit,
    viewModel: RadioViewModel,
    settingsRepository: SettingsRepository
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val radioState by viewModel.radioState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 无操作（时长由界面设置 autoHideDelayMs 决定）后自动隐藏控件（仅保留动态效果与电台标题），任意点击屏幕恢复
    var idle by remember { mutableStateOf(false) }
    var lastInteract by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val autoHideControls by settingsRepository.autoHideControls.collectAsStateWithLifecycle(initialValue = true)
    val autoHideDelayMs by settingsRepository.autoHideDelayMs.collectAsStateWithLifecycle(initialValue = 3000)
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
    // 布局固定不变，无操作时仅淡出非核心控件（动态效果与电台标题始终保留），点击任意屏幕后淡入恢复
    val contentAlpha by animateFloatAsState(
        targetValue = if (idle) 0f else 1f,
        animationSpec = tween(400),
        label = "contentAlpha"
    )

    // 睡眠定时状态（与音乐模式共享 SettingsRepository）
    val sleepEndAt by viewModel.radioPlayerRef.sleepTimerEndAt.collectAsStateWithLifecycle()
    var showTimerDialog by remember { mutableStateOf(false) }
    // 闹钟状态（与音乐模式共享）
    val alarmEnabled by settingsRepository.radioAlarmEnabled.collectAsStateWithLifecycle(initialValue = false)
    val alarmHour by settingsRepository.radioAlarmHour.collectAsStateWithLifecycle(initialValue = 7)
    val alarmMinute by settingsRepository.radioAlarmMinute.collectAsStateWithLifecycle(initialValue = 0)

    // 从设置中读取当前效果类型
    val savedEffect by settingsRepository.radioVisualEffect.collectAsStateWithLifecycle(initialValue = settingsRepository.radioVisualEffectSync())
    var currentEffect by remember { mutableIntStateOf(savedEffect) }
    // 同步设置变化到本地状态
    LaunchedEffect(savedEffect) {
        currentEffect = savedEffect
    }

    val scale by animateFloatAsState(
        targetValue = if (radioState.isPlaying) 1f else 0.85f,
        animationSpec = tween(300),
        label = "scale"
    )

    // 效果名称临时显示（点击切换时显示2秒后隐藏）
    var showEffectLabel by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 任意触控（含导航栏、按钮按下）都算操作，重置自动隐藏计时并恢复显示
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().changes.any { it.pressed }) touchNow()
                    }
                }
            }
    ) {
        // 顶部应用栏：无操作时仅淡出，布局保持不变
        Box(Modifier.fillMaxWidth().graphicsLayer { alpha = contentAlpha }) {
        TopAppBar(
            title = { Text(radioState.stationName ?: "未知电台") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
            },
            actions = {
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
                // 锁屏动态效果入口（M1）
                IconButton(
                    onClick = {
                        context.startActivity(
                            android.content.Intent(
                                context,
                                com.shiyinplayer.lockscreen.LockScreenOverlayActivity::class.java
                            )
                                .putExtra(
                                    com.shiyinplayer.lockscreen.LockScreenOverlayActivity.EXTRA_MANUAL_MODE,
                                    true
                                )
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.Lock,
                        contentDescription = "锁屏动态效果",
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 动态效果区域（可点击切换）
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clickable {
                        val next = (currentEffect + 1) % VISUAL_EFFECT_NAMES.size
                        currentEffect = next
                        showEffectLabel = true
                        scope.launch { settingsRepository.setRadioVisualEffect(next) }
                    },
                contentAlignment = Alignment.Center
            ) {
                when (currentEffect) {
                    0 -> WaveAndHaloEffect(isActive = radioState.isPlaying, isBuffering = radioState.isBuffering, scale = scale)
                    1 -> VinylRotationEffect(isActive = radioState.isPlaying, isBuffering = radioState.isBuffering, scale = scale)
                    2 -> ParticleFloatEffect(isActive = radioState.isPlaying, isBuffering = radioState.isBuffering, scale = scale)
                    3 -> SpectrumBarsEffect(isActive = radioState.isPlaying, isBuffering = radioState.isBuffering, scale = scale)
                    4 -> PureHaloEffect(isActive = radioState.isPlaying, isBuffering = radioState.isBuffering, scale = scale)
                }
            }

            // 效果名称（点击切换时显示2秒后自动隐藏）
            LaunchedEffect(currentEffect) {
                if (showEffectLabel) {
                    kotlinx.coroutines.delay(2000L)
                    showEffectLabel = false
                }
            }
            AnimatedVisibility(
                visible = showEffectLabel && !idle,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = VISUAL_EFFECT_NAMES[currentEffect],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${currentEffect + 1}/${VISUAL_EFFECT_NAMES.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // 台名
            Text(
                text = radioState.stationName ?: "未知电台",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            // 节目名（优先级：currentTrack → programName）
            radioState.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 无操作时仅保留中间动态效果与电台标题，其余组件（收藏/控制/错误/比特率）原地淡出
            Box(
                Modifier.fillMaxWidth().graphicsLayer { alpha = contentAlpha },
                contentAlignment = Alignment.Center
            ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 线路切换（多线路电台）
            if (radioState.lineCount > 1) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .clickable {
                            val next = (radioState.lineIndex + 1) % radioState.lineCount
                            viewModel.switchRadioLine(next)
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "线路 ${radioState.lineIndex + 1}/${radioState.lineCount} · 点击切换",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 收藏按钮（小，置中）
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = {
                    viewModel.toggleFavorite(
                        com.shiyinplayer.data.local.entity.RadioStationEntity(
                            id = radioState.stationId ?: 0,
                            name = radioState.stationName ?: "",
                            url = radioState.streamUrl ?: "",
                            isFavorite = radioState.isFavorite
                        )
                    )
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(
                    if (radioState.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (radioState.isFavorite) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (radioState.isFavorite) "已收藏" else "收藏",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (radioState.isFavorite) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 错误信息
            if (radioState.error != null) {
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = radioState.error!!,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            // 播放控制按钮
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 上一首（收藏列表循环）
                IconButton(
                    onClick = { viewModel.playPreviousFavorite() },
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "上一首",
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(Modifier.width(24.dp))

                // 播放/暂停按钮（大）
                IconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        if (radioState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (radioState.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }

                Spacer(Modifier.width(24.dp))

                // 下一首（收藏列表循环）
                IconButton(
                    onClick = { viewModel.playNextFavorite() },
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // 比特率信息
            if (radioState.bitrate > 0) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "${radioState.bitrate} kbps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            }
            }
        }

        // ---- 定时弹窗：睡眠定时 + 定时播放 合并到一个设置窗口 ----
        if (showTimerDialog) {
            val sleepRemainingText = if (sleepEndAt != null) {
                val remainingMs = sleepEndAt!! - System.currentTimeMillis()
                if (remainingMs > 0) {
                    val mins = remainingMs / 1000 / 60
                    "当前定时：${mins + 1} 分钟后关闭"
                } else "当前定时：即将关闭"
            } else null
            val tpState = rememberTimePickerState(
                initialHour = alarmHour, initialMinute = alarmMinute, is24Hour = true
            )
            AlertDialog(
                onDismissRequest = { showTimerDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("定时") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("睡眠定时", style = MaterialTheme.typography.titleSmall)
                        sleepRemainingText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                        }
                        listOf(15, 30, 60).forEach { min ->
                            TextButton(onClick = {
                                viewModel.setRadioSleepTimer(min)
                            }) { Text("${min} 分钟") }
                        }
                        if (sleepEndAt != null) {
                            TextButton(onClick = {
                                viewModel.setRadioSleepTimer(0)
                            }) { Text("取消定时", color = MaterialTheme.colorScheme.error) }
                        }
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))
                        Text("定时播放", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()) {
                            Text("启用闹钟")
                            Spacer(Modifier.weight(1f))
                            Switch(checked = alarmEnabled, onCheckedChange = {
                                viewModel.setAlarmEnabled(it)
                            })
                        }
                        Spacer(Modifier.height(4.dp))
                        TimePicker(state = tpState)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setAlarmTime(tpState.hour, tpState.minute)
                        showTimerDialog = false
                    }) { Text("完成") }
                },
                dismissButton = {
                    TextButton(onClick = { showTimerDialog = false }) { Text("关闭") }
                }
            )
        }
    }
}

// ====================================================================
// 5 种动态效果实现
// ====================================================================

/** 中央图标组件 */
@Composable
private fun CenterIcon(
    isBuffering: Boolean,
    scale: Float,
    size: androidx.compose.ui.unit.Dp = 140.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp
            )
        } else {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 方案0: 声波律动 + 光晕脉冲 */
@Composable
private fun WaveAndHaloEffect(isActive: Boolean, isBuffering: Boolean, scale: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveHalo")
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloAlpha"
    )
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloScale"
    )
    val waveProgress = if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = tween(800, easing = LinearEasing)),
            label = "wave"
        ).value
    } else 0f

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .scale(haloScale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = haloAlpha))
            )
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .scale(haloScale * 0.95f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = haloAlpha * 0.7f))
            )
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
            ) {
                val centerY = size.height / 2f
                val totalWidth = size.width
                val waveCount = 5
                val spacing = totalWidth / (waveCount + 1)
                val maxBarHeight = size.height * 0.5f
                val barWidth = 6.dp.toPx()
                for (i in 0 until waveCount) {
                    val x = spacing * (i + 1)
                    val phase = (waveProgress * 2f * Math.PI + i * 0.8f).toFloat()
                    val amplitude = (kotlin.math.sin(phase) * 0.5f + 0.5f)
                    val barHeight = maxBarHeight * (0.2f + amplitude * 0.8f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.6f + amplitude * 0.4f),
                        start = Offset(x, centerY - barHeight / 2),
                        end = Offset(x, centerY + barHeight / 2),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        CenterIcon(isBuffering = isBuffering, scale = scale)
    }
}

/** 方案1: 唱片旋转 */
@Composable
private fun VinylRotationEffect(isActive: Boolean, isBuffering: Boolean, scale: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 黑胶唱片外圈（深色）
        Box(
            modifier = Modifier
                .size(220.dp)
                .rotate(if (isActive) rotation else 0f)
                .clip(CircleShape)
                .background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.Center
        ) {
            // 唱片纹路（细圈）
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (i in 0 until 8) {
                    val r = (size.minDimension / 2) * (0.4f + i * 0.07f)
                    drawCircle(
                        color = Color(0xFF333333),
                        radius = r,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                    )
                }
            }
        }
        // 唱片中心标签
        Box(
            modifier = Modifier
                .size(60.dp)
                .rotate(if (isActive) rotation else 0f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.Black)
            )
        }
        // 中央图标
        CenterIcon(isBuffering = false, scale = scale, size = 100.dp)
    }
}

/** 方案2: 粒子浮动 */
@Composable
private fun ParticleFloatEffect(isActive: Boolean, isBuffering: Boolean, scale: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "particle")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particleProgress"
    )
    val particleColor = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (isActive) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val count = 12
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                for (i in 0 until count) {
                    val baseAngle = (i * 2 * Math.PI / count).toFloat()
                    val speed = 0.7f + (i % 3) * 0.15f
                    val t = ((progress * speed) % 1f)
                    val radius = 40.dp.toPx() + t * 80.dp.toPx()
                    val x = centerX + kotlin.math.cos(baseAngle) * radius
                    val y = centerY + kotlin.math.sin(baseAngle) * radius
                    val alpha = (1f - t) * 0.6f
                    val particleSize = (3.dp.toPx() + (1f - t) * 4.dp.toPx())
                    drawCircle(
                        color = particleColor.copy(alpha = alpha),
                        radius = particleSize,
                        center = Offset(x, y)
                    )
                }
            }
        }
        CenterIcon(isBuffering = isBuffering, scale = scale)
    }
}

/** 方案3: 频谱条 */
@Composable
private fun SpectrumBarsEffect(isActive: Boolean, isBuffering: Boolean, scale: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "spectrum")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600, easing = LinearEasing)),
        label = "spectrumProgress"
    )
    val spectrumColor = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (isActive) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp)
            ) {
                val centerY = size.height / 2f
                val barCount = 16
                val totalWidth = size.width
                val spacing = totalWidth / (barCount + 1)
                val maxBarHeight = size.height * 0.55f
                val barWidth = 4.dp.toPx()
                for (i in 0 until barCount) {
                    val x = spacing * (i + 1)
                    val phase = (progress * 2f * Math.PI + i * 0.5f).toFloat()
                    val amplitude = (kotlin.math.sin(phase) * 0.5f + 0.5f)
                    val barHeight = maxBarHeight * (0.15f + amplitude * 0.85f)
                    drawLine(
                        color = spectrumColor.copy(alpha = 0.5f + amplitude * 0.5f),
                        start = Offset(x, centerY - barHeight / 2),
                        end = Offset(x, centerY + barHeight / 2),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        CenterIcon(isBuffering = false, scale = scale, size = 110.dp)
    }
}

/** 方案4: 纯光晕 */
@Composable
private fun PureHaloEffect(isActive: Boolean, isBuffering: Boolean, scale: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "pureHalo")
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pureHaloAlpha"
    )
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pureHaloScale"
    )
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (isActive) {
            repeat(3) { idx ->
                val layer = idx + 1
                Box(
                    modifier = Modifier
                        .size((220 - idx * 20).dp)
                        .scale(haloScale - idx * 0.05f)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = haloAlpha * (1f - idx * 0.2f) / layer
                            )
                        )
                )
            }
        }
        CenterIcon(isBuffering = isBuffering, scale = scale)
    }
}
