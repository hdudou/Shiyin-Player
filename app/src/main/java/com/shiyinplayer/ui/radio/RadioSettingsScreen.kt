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
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.shiyinplayer.R
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
                title = { Text(stringResource(R.string.radio_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
            SectionHeader(stringResource(R.string.radio_settings_sec_interface))
            SubPageCard(
                icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                title = stringResource(R.string.radio_settings_sec_interface),
                description = stringResource(R.string.radio_settings_interface_desc),
                onClick = onNavigateToInterface
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- 声音引擎子页面 ----
            SectionHeader(stringResource(R.string.radio_settings_sec_sound))
            SubPageCard(
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                title = stringResource(R.string.radio_settings_sec_sound),
                description = stringResource(R.string.radio_settings_sound_desc),
                onClick = onNavigateToSound
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- 播放与定时 ----
            SectionHeader(stringResource(R.string.radio_settings_sec_playback))

            SettingSwitch(
                title = stringResource(R.string.radio_settings_sleep_enable),
                description = stringResource(R.string.radio_settings_sleep_enable_desc),
                checked = sleepTimerEnabled,
                onCheckedChange = { viewModel.setSleepTimerEnabled(it) }
            )
            if (sleepTimerEnabled) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.radio_settings_sleep_duration), style = MaterialTheme.typography.bodyMedium)
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
                                text = { Text(stringResource(R.string.setting_minutes, minutes)) }
                            )
                        }
                    }
                }
            }

            SettingSwitch(
                title = stringResource(R.string.radio_settings_alarm_enable),
                description = stringResource(R.string.radio_settings_alarm_enable_desc),
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
                    Text(stringResource(R.string.radio_settings_alarm_time), style = MaterialTheme.typography.bodyMedium)
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
            SectionHeader(stringResource(R.string.radio_settings_sec_connection))

            SettingSwitch(
                title = stringResource(R.string.radio_settings_auto_reconnect),
                description = stringResource(R.string.radio_settings_auto_reconnect_desc),
                checked = autoReconnect,
                onCheckedChange = { viewModel.setAutoReconnect(it) }
            )
            if (autoReconnect) {
                Spacer(Modifier.height(8.dp))
                var sliderValue by remember { mutableFloatStateOf(maxRetryCount.toFloat()) }
                LaunchedEffect(maxRetryCount) { sliderValue = maxRetryCount.toFloat() }
                SettingSlider(
                    title = stringResource(R.string.radio_settings_max_retries),
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { viewModel.setMaxRetryCount(sliderValue.toInt()) },
                    valueRange = 1f..10f,
                    valueText = stringResource(R.string.setting_times, sliderValue.toInt())
                )
                var delaySlider by remember { mutableFloatStateOf(retryDelaySeconds.toFloat()) }
                LaunchedEffect(retryDelaySeconds) { delaySlider = retryDelaySeconds.toFloat() }
                SettingSlider(
                    title = stringResource(R.string.radio_settings_retry_delay),
                    value = delaySlider,
                    onValueChange = { delaySlider = it },
                    onValueChangeFinished = { viewModel.setRetryDelaySeconds(delaySlider.toInt()) },
                    valueRange = 1f..30f,
                    valueText = stringResource(R.string.setting_seconds, delaySlider.toInt())
                )
            }

            SettingSwitch(
                title = stringResource(R.string.radio_settings_wifi_only),
                description = stringResource(R.string.radio_settings_wifi_only_desc),
                checked = wifiOnly,
                onCheckedChange = { viewModel.setWifiOnly(it) }
            )
            if (wifiOnly) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.radio_settings_wifi_only_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- 显示与信息 ----
            SectionHeader(stringResource(R.string.radio_settings_sec_display))

            SettingSwitch(
                title = stringResource(R.string.radio_settings_show_program),
                description = stringResource(R.string.radio_settings_show_program_desc),
                checked = showProgramInfo,
                onCheckedChange = { viewModel.setShowProgramInfo(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- 耳机与蓝牙 ----
            SectionHeader(stringResource(R.string.radio_settings_sec_headset))
            SettingSwitch(
                title = stringResource(R.string.radio_settings_headset_pause),
                description = stringResource(R.string.radio_settings_headset_pause_desc),
                checked = headsetPause,
                onCheckedChange = { viewModel.setHeadsetPause(it) }
            )
            SettingSwitch(
                title = stringResource(R.string.radio_settings_bt_disconnect),
                description = stringResource(R.string.radio_settings_bt_disconnect_desc),
                checked = btDisconnectPause,
                onCheckedChange = { viewModel.setBtDisconnectPause(it) }
            )
            SettingSwitch(
                title = stringResource(R.string.radio_settings_bt_reconnect),
                description = stringResource(R.string.radio_settings_bt_reconnect_desc),
                checked = btReconnectResume,
                onCheckedChange = { viewModel.setBtReconnectResume(it) }
            )
            SettingSwitch(
                title = stringResource(R.string.radio_settings_headset_button),
                description = stringResource(R.string.radio_settings_headset_button_desc),
                checked = headsetButtonControl,
                onCheckedChange = { viewModel.setHeadsetButtonControl(it) }
            )
            SettingSwitch(
                title = stringResource(R.string.radio_settings_lockscreen),
                description = stringResource(R.string.radio_settings_lockscreen_desc),
                checked = lockscreenControl,
                onCheckedChange = { viewModel.setLockscreenControl(it) }
            )

            Spacer(Modifier.height(24.dp))

            // ---- 关于 ----
            SectionHeader(stringResource(R.string.radio_settings_sec_about))
            Text(
                stringResource(R.string.radio_settings_about_text),
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
            title = { Text(stringResource(R.string.radio_settings_alarm_pick_title)) },
            text = {
                androidx.compose.material3.TimePicker(state = timePickerState)
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.setAlarmTime(timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.action_cancel)) }
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
                title = { Text(stringResource(R.string.radio_settings_sec_interface)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
            SectionLabel(stringResource(R.string.radio_settings_theme_style))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StyleChip(stringResource(R.string.theme_zaicai), themeStyle == 0) { viewModel.setThemeStyle(0) }
                THEME_STYLES.forEachIndexed { idx, _ ->
                    val value = idx + 1
                    StyleChip(stringResource(radioThemeStyleLabelRes(idx)), themeStyle == value) { viewModel.setThemeStyle(value) }
                }
            }
            if (themeStyle == 0) {
                Spacer(Modifier.height(12.dp))
                SectionLabel(stringResource(R.string.radio_settings_appearance_mode))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        R.string.set_theme_follow_system to 0,
                        R.string.set_theme_light to 1,
                        R.string.set_theme_dark to 2
                    ).forEach { (labelRes, value) ->
                        FilterChip(
                            selected = themeMode == value,
                            onClick = { viewModel.setThemeMode(value) },
                            label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                if (themeMode == 0) {
                    Text(stringResource(R.string.radio_settings_follow_system_hint), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                SectionLabel(stringResource(R.string.radio_settings_accent_color))
                SwitchRow(
                    stringResource(R.string.radio_settings_dynamic_color),
                    stringResource(R.string.radio_settings_dynamic_color_desc),
                    dynamicColors
                ) { viewModel.setDynamicColors(it) }
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
                    stringResource(R.string.set_theme_active_hint, stringResource(radioThemeStyleLabelRes((themeStyle - 1).coerceIn(0, THEME_STYLES.size - 1)))),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(12.dp))

            // ---- 显示模式 ----
            SectionLabel(stringResource(R.string.set_sec_display_mode))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    R.string.set_display_auto to "auto",
                    R.string.set_display_portrait to "portrait",
                    R.string.set_display_landscape to "landscape"
                ).forEach { (labelRes, value) ->
                    FilterChip(
                        selected = displayMode == value,
                        onClick = { viewModel.setDisplayMode(value) },
                        label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Text(stringResource(R.string.set_display_mode_hint), style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(12.dp))

            // ---- 防息屏 ----
            SectionLabel(stringResource(R.string.radio_settings_sec_keep_screen))
            SwitchRow(
                stringResource(R.string.radio_settings_keep_screen_on),
                stringResource(R.string.radio_settings_keep_screen_on_desc),
                keepScreenOn
            ) { viewModel.setKeepScreenOn(it) }

            Spacer(Modifier.height(12.dp))

            // ---- 通知与系统 ----
            SectionLabel(stringResource(R.string.set_sec_notify_system))
            SwitchRow(
                stringResource(R.string.set_notify_enabled),
                stringResource(R.string.set_notify_enabled_summary),
                notifyEnabled
            ) { viewModel.setNotifyEnabled(it) }
            SwitchRow(
                stringResource(R.string.set_lockscreen_control),
                stringResource(R.string.set_lockscreen_control_summary),
                lockscreenControl
            ) { viewModel.setLockscreenControl(it) }
            SwitchRow(
                stringResource(R.string.set_mini_bar),
                stringResource(R.string.set_mini_bar_summary),
                miniBarEnabled
            ) { viewModel.setMiniBarEnabled(it) }

            Spacer(Modifier.height(12.dp))

            // ---- 音频焦点 ----
            SectionLabel(stringResource(R.string.set_sec_audio_focus))
            SwitchRow(
                stringResource(R.string.set_focus_loss_autoskip),
                stringResource(R.string.set_focus_loss_autoskip_summary),
                focusLossAutoSkip
            ) { viewModel.setFocusLossAutoSkip(it) }
            Text(stringResource(R.string.radio_settings_immediate_effect), style = MaterialTheme.typography.bodySmall)
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
                title = { Text(stringResource(R.string.radio_settings_sec_sound)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
            SectionHeader(stringResource(R.string.radio_settings_sec_output))
            SelectRow(
                listOf(
                    R.string.set_display_auto to "auto",
                    R.string.radio_settings_output_speaker to "speaker",
                    R.string.radio_settings_output_bt to "bt",
                    R.string.radio_settings_output_wired to "wired"
                ).map { (r, v) -> stringResource(r) to v },
                audioRoute
            ) { viewModel.setAudioRoute(it) }
            SettingSwitch(
                title = stringResource(R.string.radio_settings_low_latency),
                description = stringResource(R.string.radio_settings_low_latency_desc),
                checked = lowLatency,
                onCheckedChange = { viewModel.setLowLatency(it) }
            )
            SettingSlider(
                title = stringResource(R.string.set_audio_buffer),
                value = bufferMs.toFloat(),
                onValueChange = {},
                onValueChangeFinished = {},
                valueRange = 50f..500f,
                valueText = "${bufferMs}ms"
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 处理精度
            SectionHeader(stringResource(R.string.radio_settings_sec_precision))
            SettingSwitch(
                title = stringResource(R.string.radio_settings_float32),
                description = stringResource(R.string.radio_settings_float32_desc),
                checked = float32,
                onCheckedChange = { viewModel.setFloat32Processing(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 音量
            SectionHeader(stringResource(R.string.set_sec_volume))
            SelectRow(
                listOf(
                    R.string.radio_settings_vol_log to "log",
                    R.string.radio_settings_vol_loudness to "loudness"
                ).map { (r, v) -> stringResource(r) to v },
                volumeCurve
            ) { viewModel.setVolumeCurve(it) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 声道平衡
            SectionHeader(stringResource(R.string.radio_settings_channel_balance))
            SettingSlider(
                title = stringResource(R.string.radio_settings_channel_balance),
                value = channelBalance,
                onValueChange = {},
                onValueChangeFinished = { viewModel.setChannelBalance(channelBalance) },
                valueRange = -1f..1f,
                valueText = when {
                    channelBalance == 0f -> stringResource(R.string.radio_settings_balance_center)
                    channelBalance < 0f -> stringResource(R.string.radio_settings_balance_left, (-channelBalance * 100).toInt())
                    else -> stringResource(R.string.radio_settings_balance_right, (channelBalance * 100).toInt())
                }
            )

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.radio_settings_mix_hint),
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

/** 10 套主题风格中文名 → 字符串资源 id（与 THEME_STYLES 下标一一对应）。 */
@StringRes
private fun radioThemeStyleLabelRes(index: Int): Int = when (index) {
    0 -> R.string.theme_style_gold
    1 -> R.string.theme_style_azure
    2 -> R.string.theme_style_emerald
    3 -> R.string.theme_style_violet
    4 -> R.string.theme_style_lava
    5 -> R.string.theme_style_rose
    6 -> R.string.theme_style_silver
    7 -> R.string.theme_style_retro
    8 -> R.string.theme_style_citrus
    9 -> R.string.theme_style_wine
    else -> R.string.theme_style_gold
}
