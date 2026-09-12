package com.shiyinplayer.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.ui.theme.ACCENT_COLORS
import com.shiyinplayer.ui.theme.THEME_STYLES

// ===== 主设置页 =====

/**
 * 电台专用设置页。
 * 界面设计 / 声音引擎 为子页面导航；播放与定时 / 连接与数据 / 显示与信息 / 耳机与蓝牙 直接内联。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSettingsScreen(
    onBack: () -> Unit,
    onNavigateToInterface: () -> Unit = {},
    onNavigateToSound: () -> Unit = {},
    viewModel: RadioSettingsViewModel = hiltViewModel()
) {
    val maxRetryCount by viewModel.maxRetryCount.collectAsStateWithLifecycle()
    val retryDelaySeconds by viewModel.retryDelaySeconds.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val showProgramInfo by viewModel.showProgramInfo.collectAsStateWithLifecycle()
    val autoReconnect by viewModel.autoReconnect.collectAsStateWithLifecycle()
    val sleepTimerEnabled by viewModel.sleepTimerEnabled.collectAsStateWithLifecycle()
    val sleepTimerMinutes by viewModel.sleepTimerMinutes.collectAsStateWithLifecycle()
    val alarmEnabled by viewModel.alarmEnabled.collectAsStateWithLifecycle()
    val alarmHour by viewModel.alarmHour.collectAsStateWithLifecycle()
    val alarmMinute by viewModel.alarmMinute.collectAsStateWithLifecycle()
    // 耳机/蓝牙共享设置
    val headsetPause by viewModel.headsetPause.collectAsStateWithLifecycle()
    val btDisconnectPause by viewModel.btDisconnectPause.collectAsStateWithLifecycle()
    val btReconnectResume by viewModel.btReconnectResume.collectAsStateWithLifecycle()
    val headsetButtonControl by viewModel.headsetButtonControl.collectAsStateWithLifecycle()
    val lockscreenControl by viewModel.lockscreenControl.collectAsStateWithLifecycle()

    var sleepTimerExpanded by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("电台设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ---- 界面设计子页面 ----
            SectionHeader("界面设计")
            SubPageCard(
                icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                title = "界面设计",
                description = "主题风格、显示模式、防息屏、通知与系统、音频焦点",
                onClick = onNavigateToInterface
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- 声音引擎子页面 ----
            SectionHeader("声音引擎")
            SubPageCard(
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                title = "声音引擎",
                description = "输出路由、低延迟、缓冲、处理精度、音量曲线、均衡器",
                onClick = onNavigateToSound
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- 播放与定时 ----
            SectionHeader("播放与定时")

            SettingSwitch(
                title = "启用睡眠定时",
                description = "设定时间后自动停止播放",
                checked = sleepTimerEnabled,
                onCheckedChange = { viewModel.setSleepTimerEnabled(it) }
            )
            if (sleepTimerEnabled) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("定时时长", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    DropdownMenu(
                        expanded = sleepTimerExpanded,
                        onDismissRequest = { sleepTimerExpanded = false },
                    ) {
                        listOf(15, 30, 60, 90, 120).forEach { minutes ->
                            DropdownMenuItem(
                                onClick = {
                                    viewModel.setSleepTimerMinutes(minutes)
                                    sleepTimerExpanded = false
                                },
                                text = { Text("${minutes} 分钟") }
                            )
                        }
                    }
                }
            }

            SettingSwitch(
                title = "启用闹钟",
                description = "到点自动播放上次收听的电台",
                checked = alarmEnabled,
                onCheckedChange = { viewModel.setAlarmEnabled(it) }
            )
            if (alarmEnabled) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("闹钟时间", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { showTimePicker = true }) {
                        Text(
                            text = String.format("%02d:%02d", alarmHour, alarmMinute),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- 连接与数据 ----
            SectionHeader("连接与数据")

            SettingSwitch(
                title = "启用自动重连",
                description = "断流后自动尝试重新连接",
                checked = autoReconnect,
                onCheckedChange = { viewModel.setAutoReconnect(it) }
            )
            if (autoReconnect) {
                Spacer(Modifier.height(8.dp))
                var sliderValue by remember { mutableFloatStateOf(maxRetryCount.toFloat()) }
                LaunchedEffect(maxRetryCount) { sliderValue = maxRetryCount.toFloat() }
                SettingSlider(
                    title = "最大重试次数",
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { viewModel.setMaxRetryCount(sliderValue.toInt()) },
                    valueRange = 1f..10f,
                    valueText = "${sliderValue.toInt()} 次"
                )
                var delaySlider by remember { mutableFloatStateOf(retryDelaySeconds.toFloat()) }
                LaunchedEffect(retryDelaySeconds) { delaySlider = retryDelaySeconds.toFloat() }
                SettingSlider(
                    title = "重试间隔",
                    value = delaySlider,
                    onValueChange = { delaySlider = it },
                    onValueChangeFinished = { viewModel.setRetryDelaySeconds(delaySlider.toInt()) },
                    valueRange = 1f..30f,
                    valueText = "${delaySlider.toInt()} 秒"
                )
            }

            SettingSwitch(
                title = "仅 Wi-Fi 下播放",
                description = "移动数据下不自动播放网络电台",
                checked = wifiOnly,
                onCheckedChange = { viewModel.setWifiOnly(it) }
            )
            if (wifiOnly) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "提示：移动数据下打开电台时会提示您切换到 Wi-Fi。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- 显示与信息 ----
            SectionHeader("显示与信息")

            SettingSwitch(
                title = "显示节目信息",
                description = "在播放页和通知栏显示当前节目名称（ICY 元数据）",
                checked = showProgramInfo,
                onCheckedChange = { viewModel.setShowProgramInfo(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- 耳机与蓝牙 ----
            SectionHeader("耳机与蓝牙")
            SettingSwitch(
                title = "耳机断开时暂停",
                description = "拔下有线耳机时自动暂停播放",
                checked = headsetPause,
                onCheckedChange = { viewModel.setHeadsetPause(it) }
            )
            SettingSwitch(
                title = "蓝牙断开时暂停",
                description = "蓝牙耳机断开连接时自动暂停",
                checked = btDisconnectPause,
                onCheckedChange = { viewModel.setBtDisconnectPause(it) }
            )
            SettingSwitch(
                title = "蓝牙重连时恢复",
                description = "蓝牙耳机重新连接后自动恢复播放",
                checked = btReconnectResume,
                onCheckedChange = { viewModel.setBtReconnectResume(it) }
            )
            SettingSwitch(
                title = "耳机按键控制",
                description = "用有线/蓝牙耳机按键控制播放与切歌",
                checked = headsetButtonControl,
                onCheckedChange = { viewModel.setHeadsetButtonControl(it) }
            )
            SettingSwitch(
                title = "锁屏控制",
                description = "在锁屏界面显示播放控制",
                checked = lockscreenControl,
                onCheckedChange = { viewModel.setLockscreenControl(it) }
            )

            Spacer(Modifier.height(24.dp))

            // ---- 关于 ----
            SectionHeader("关于")
            Text(
                "电台数据来自 RadioBrowser 开放目录。仅供聚合播放使用，不缓存不录制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 闹钟时间选择器
    if (showTimePicker) {
        val timePickerState = androidx.compose.material3.rememberTimePickerState(
            initialHour = alarmHour,
            initialMinute = alarmMinute,
            is24Hour = true
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("设置闹钟时间") },
            text = {
                androidx.compose.material3.TimePicker(state = timePickerState)
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.setAlarmTime(timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            }
        )
    }
}

// ===== 界面设计子页面 =====

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RadioInterfaceSettingsScreen(
    onBack: () -> Unit,
    viewModel: RadioSettingsViewModel = hiltViewModel()
) {
    val themeStyle by viewModel.themeStyle.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColors by viewModel.dynamicColors.collectAsStateWithLifecycle()
    val accent by viewModel.accent.collectAsStateWithLifecycle()
    val displayMode by viewModel.displayMode.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val notifyEnabled by viewModel.notifyEnabled.collectAsStateWithLifecycle()
    val lockscreenControl by viewModel.lockscreenControl.collectAsStateWithLifecycle()
    val miniBarEnabled by viewModel.miniBarEnabled.collectAsStateWithLifecycle()
    val focusLossAutoSkip by viewModel.focusLossAutoSkip.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("界面设计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ---- 主题风格（与音乐模式完全一致） ----
            SectionLabel("主题风格")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StyleChip("玄素映彩", themeStyle == 0) { viewModel.setThemeStyle(0) }
                THEME_STYLES.forEachIndexed { idx, style ->
                    val value = idx + 1
                    StyleChip(style.label, themeStyle == value) { viewModel.setThemeStyle(value) }
                }
            }
            if (themeStyle == 0) {
                Spacer(Modifier.height(12.dp))
                SectionLabel("明暗模式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2).forEach { (label, value) ->
                        FilterChip(
                            selected = themeMode == value,
                            onClick = { viewModel.setThemeMode(value) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                if (themeMode == 0) {
                    Text("「跟随系统」配合系统的浅色/深色模式。", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                SectionLabel("强调色")
                SwitchRow("动态取色（Material You）", "Android 12+ 跟随系统壁纸自动生成主题色（仅玄素映彩风格生效）", dynamicColors) { viewModel.setDynamicColors(it) }
                if (!dynamicColors) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ACCENT_COLORS.forEachIndexed { idx, color ->
                            val selected = accent == idx
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(color))
                                    .border(
                                        width = if (selected) 3.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.setAccent(idx) }
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    "已启用整套「${THEME_STYLES.getOrNull(themeStyle - 1)?.label}」风格，明暗与强调色仅在「玄素映彩」下生效。",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(12.dp))

            // ---- 显示模式 ----
            SectionLabel("显示模式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("自动" to "auto", "竖屏" to "portrait", "横屏" to "landscape").forEach { (label, value) ->
                    FilterChip(
                        selected = displayMode == value,
                        onClick = { viewModel.setDisplayMode(value) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Text("「自动」随手机旋转切换竖/横 UI；「竖屏/横屏」固定屏幕方向。", style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(12.dp))

            // ---- 防息屏 ----
            SectionLabel("防息屏")
            SwitchRow("前台保持屏幕常亮", "播放器在前台显示时屏幕不自动息屏", keepScreenOn) { viewModel.setKeepScreenOn(it) }

            Spacer(Modifier.height(12.dp))

            // ---- 通知与系统 ----
            SectionLabel("通知与系统")
            SwitchRow("播放通知", "显示前台播放通知（媒体播放服务需要）", notifyEnabled) { viewModel.setNotifyEnabled(it) }
            SwitchRow("锁屏媒体控制", "锁屏界面显示媒体控制", lockscreenControl) { viewModel.setLockscreenControl(it) }
            SwitchRow("迷你播放条", "底部显示迷你播放条", miniBarEnabled) { viewModel.setMiniBarEnabled(it) }

            Spacer(Modifier.height(12.dp))

            // ---- 音频焦点 ----
            SectionLabel("音频焦点")
            SwitchRow(
                "被抢占时自动跳下一曲",
                "系统永久占用音频焦点（如来电/其它播放器）时自动跳到下一曲；关闭则暂停后保留当前曲",
                focusLossAutoSkip
            ) { viewModel.setFocusLossAutoSkip(it) }
            Text("更改即时生效。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ===== 声音引擎子页面 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSoundSettingsScreen(
    onBack: () -> Unit,
    viewModel: RadioSettingsViewModel = hiltViewModel()
) {
    val audioRoute by viewModel.audioRoute.collectAsStateWithLifecycle()
    val lowLatency by viewModel.lowLatency.collectAsStateWithLifecycle()
    val bufferMs by viewModel.bufferMs.collectAsStateWithLifecycle()
    val float32 by viewModel.float32Processing.collectAsStateWithLifecycle()
    val volumeCurve by viewModel.volumeCurve.collectAsStateWithLifecycle()
    val channelBalance by viewModel.channelBalance.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("声音引擎") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 输出
            SectionHeader("输出")
            SelectRow(
                listOf("自动" to "auto", "扬声器" to "speaker", "蓝牙" to "bt", "有线耳机" to "wired"),
                audioRoute
            ) { viewModel.setAudioRoute(it) }
            SettingSwitch(
                title = "低延迟音频",
                description = "使用 AAudio 低延迟路径",
                checked = lowLatency,
                onCheckedChange = { viewModel.setLowLatency(it) }
            )
            SettingSlider(
                title = "音频缓冲",
                value = bufferMs.toFloat(),
                onValueChange = {},
                onValueChangeFinished = {},
                valueRange = 50f..500f,
                valueText = "${bufferMs}ms"
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 处理精度
            SectionHeader("处理精度")
            SettingSwitch(
                title = "32 位浮点处理",
                description = "音频链上使用 float PCM 处理",
                checked = float32,
                onCheckedChange = { viewModel.setFloat32Processing(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 音量
            SectionHeader("音量")
            SelectRow(
                listOf("对数" to "log", "响度补偿" to "loudness"),
                volumeCurve
            ) { viewModel.setVolumeCurve(it) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 声道平衡
            SectionHeader("声道平衡")
            SettingSlider(
                title = "声道平衡",
                value = channelBalance,
                onValueChange = {},
                onValueChangeFinished = { viewModel.setChannelBalance(channelBalance) },
                valueRange = -1f..1f,
                valueText = if (channelBalance == 0f) "居中" else if (channelBalance < 0f) "偏左 %.0f%%".format(-channelBalance * 100) else "偏右 %.0f%%".format(channelBalance * 100)
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "混音（淡入淡出/静音消除）和音量归一化仅在音乐模式生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ===== 通用组件 =====

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(bottom = 4.dp, top = 4.dp)
    )
}

/** 主题风格胶囊：选中以强调色填充，未选中为表面淡化底。与音乐模式 StyleChip 完全一致。 */
@Composable
private fun StyleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

/** Switch 设置行，与音乐模式 SwitchRow 完全一致。 */
@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

@Composable
private fun SubPageCard(
    icon: @Composable (() -> Unit)? = null,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            icon?.invoke()
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

@Composable
private fun SettingSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun SelectRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@Composable
private fun SelectRow(
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}
