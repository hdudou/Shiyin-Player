package com.shiyinplayer.ui.settings

import android.app.Activity
import com.shiyinplayer.data.metasync.MetadataSyncManager
import com.shiyinplayer.data.metasync.ManualSyncProgress
import com.shiyinplayer.util.AppLocaleManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.R
import com.shiyinplayer.ui.navigation.Screen
import com.shiyinplayer.ui.theme.ACCENT_COLORS
import com.shiyinplayer.ui.theme.THEME_STYLES
import com.shiyinplayer.ui.theme.themeStyleLabelRes
import com.shiyinplayer.player.decoder.AudioFormatRegistry

/**
 * 更多 9 分类设置子屏（P3 需求 12/13 归类）：
 * 界面 / 音乐库 / 回放 / 音乐来源 / 元数据 / 声音引擎 / 系统集成。
 * 均衡器与关于为独立屏（Screen.Equalizer / Screen.About），此处不含。
 * 原 7 子屏（声音引擎混均衡器、回放混元数据、控制/播放列表）按 UI_REWORK §二 归类表拆散重排。
 */

// ===== 分类1 · 界面设置（需求 1-14） =====

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterfaceSettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val activity = LocalContext.current as? Activity
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val showEmbedArt by viewModel.showEmbedArt.collectAsStateWithLifecycle()
    val listShowArt by viewModel.listShowArt.collectAsStateWithLifecycle()
    val listTwoLine by viewModel.listTwoLine.collectAsStateWithLifecycle()
    val listDensity by viewModel.listDensity.collectAsStateWithLifecycle()
    val notifyEnabled by viewModel.notifyEnabled.collectAsStateWithLifecycle()
    val lockscreenControl by viewModel.lockscreenControl.collectAsStateWithLifecycle()
    val miniBarEnabled by viewModel.miniBarEnabled.collectAsStateWithLifecycle()
    val displayMode by viewModel.displayMode.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val carBtLyrics by viewModel.carBtLyrics.collectAsStateWithLifecycle()
    val focusLossAutoSkip by viewModel.focusLossAutoSkip.collectAsStateWithLifecycle()
    val autoHideControls by viewModel.autoHideControls.collectAsStateWithLifecycle()
    val autoHideDelayMs by viewModel.autoHideDelayMs.collectAsStateWithLifecycle()

    SettingsScaffold(stringResource(R.string.set_interface), navController) {
        SectionLabel(stringResource(R.string.set_sec_theme))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StyleChip(stringResource(R.string.theme_zaicai), prefs.style == 0) { viewModel.setThemeStyle(0) }
            THEME_STYLES.forEachIndexed { idx, _ ->
                val value = idx + 1
                StyleChip(stringResource(themeStyleLabelRes(idx)), prefs.style == value) { viewModel.setThemeStyle(value) }
            }
        }
        if (prefs.style == 0) {
            Spacer(Modifier.height(12.dp))
            SectionLabel(stringResource(R.string.set_sec_light_dark))
            SelectRow(
                listOf(stringResource(R.string.set_theme_follow_system) to 0, stringResource(R.string.set_theme_light) to 1, stringResource(R.string.set_theme_dark) to 2),
                prefs.mode
            ) { viewModel.setThemeMode(it) }
            if (prefs.mode == 0) {
                Text(stringResource(R.string.set_follow_system_hint), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel(stringResource(R.string.set_sec_accent))
            SwitchRow(stringResource(R.string.set_accent_dynamic), stringResource(R.string.set_accent_dynamic_summary), prefs.dynamicColors) { viewModel.setDynamicColors(it) }
            if (!prefs.dynamicColors) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ACCENT_COLORS.forEachIndexed { idx, color ->
                    val selected = prefs.accent == idx
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
                stringResource(R.string.set_theme_active_hint, stringResource(themeStyleLabelRes((prefs.style - 1).coerceIn(0, THEME_STYLES.size - 1)))),
                style = MaterialTheme.typography.bodySmall
            )
        }
        SectionLabel(stringResource(R.string.set_sec_display_mode))
        SelectRow(
            listOf(stringResource(R.string.set_display_auto) to "auto", stringResource(R.string.set_display_portrait) to "portrait", stringResource(R.string.set_display_landscape) to "landscape"),
            displayMode
        ) { viewModel.setDisplayMode(it) }
        Text(stringResource(R.string.set_display_mode_hint), style = MaterialTheme.typography.bodySmall)
        SectionLabel(stringResource(R.string.set_sec_screen))
        SwitchRow(stringResource(R.string.set_keep_screen_on), stringResource(R.string.set_keep_screen_on_summary), keepScreenOn) { viewModel.setKeepScreenOn(it) }
        SectionLabel(stringResource(R.string.set_sec_auto_hide))
        SwitchRow(stringResource(R.string.set_auto_hide_controls), stringResource(R.string.set_auto_hide_controls_summary), autoHideControls) { viewModel.setAutoHideControls(it) }
        if (autoHideControls) {
            SelectRow(
                listOf(stringResource(R.string.action_seconds, 2) to 2000, stringResource(R.string.action_seconds, 3) to 3000, stringResource(R.string.action_seconds, 5) to 5000),
                autoHideDelayMs
            ) { viewModel.setAutoHideDelayMs(it) }
            Text(stringResource(R.string.set_auto_hide_hint), style = MaterialTheme.typography.bodySmall)
        }
        SectionLabel(stringResource(R.string.set_sec_car_bt))
        SwitchRow(stringResource(R.string.set_car_bt_lyrics), stringResource(R.string.set_car_bt_lyrics_summary), carBtLyrics) { viewModel.setCarBtLyrics(it) }
        SectionLabel(stringResource(R.string.set_sec_language))
        SelectRow(listOf(stringResource(R.string.set_theme_follow_system) to "system", stringResource(R.string.set_language_zh) to "zh", stringResource(R.string.set_language_en) to "en"), language) {
            viewModel.setLanguage(it)
            AppLocaleManager.setLanguage(it)
            activity?.recreate()
        }
        SectionLabel(stringResource(R.string.set_sec_cover_list))
        SwitchRow(stringResource(R.string.set_show_embed_art), stringResource(R.string.set_show_embed_art_summary), showEmbedArt) { viewModel.setShowEmbedArt(it) }
        SwitchRow(stringResource(R.string.set_list_show_art), stringResource(R.string.set_list_show_art_summary), listShowArt) { viewModel.setListShowArt(it) }
        SwitchRow(stringResource(R.string.set_list_two_line), stringResource(R.string.set_list_two_line_summary), listTwoLine) { viewModel.setListTwoLine(it) }
        SelectRow(
            listOf(stringResource(R.string.set_density_compact) to "compact", stringResource(R.string.set_density_standard) to "standard", stringResource(R.string.set_density_relaxed) to "relaxed"),
            listDensity
        ) { viewModel.setListDensity(it) }
        SectionLabel(stringResource(R.string.set_sec_notify_system))
        SwitchRow(stringResource(R.string.set_notify_enabled), stringResource(R.string.set_notify_enabled_summary), notifyEnabled) { viewModel.setNotifyEnabled(it) }
        SwitchRow(stringResource(R.string.set_lockscreen_control), stringResource(R.string.set_lockscreen_control_summary), lockscreenControl) { viewModel.setLockscreenControl(it) }
        SwitchRow(stringResource(R.string.set_mini_bar), stringResource(R.string.set_mini_bar_summary), miniBarEnabled) { viewModel.setMiniBarEnabled(it) }
        SectionLabel(stringResource(R.string.set_sec_audio_focus))
        SwitchRow(
            stringResource(R.string.set_focus_loss_autoskip),
            stringResource(R.string.set_focus_loss_autoskip_summary),
            focusLossAutoSkip
        ) { viewModel.setFocusLossAutoSkip(it) }
        Text(stringResource(R.string.set_changes_immediate), style = MaterialTheme.typography.bodySmall)
    }
}

// ===== 分类2 · 音乐库设置（需求 15-24 + 文件删除） =====

@Composable
fun LibrarySettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val scanHidden by viewModel.scanHidden.collectAsStateWithLifecycle()
    val hideShort by viewModel.hideShortClips.collectAsStateWithLifecycle()
    val watchFolders by viewModel.watchFolders.collectAsStateWithLifecycle()
    val maintenanceResult by viewModel.maintenanceResult.collectAsStateWithLifecycle()
    val scanExtensions by viewModel.scanExtensions.collectAsStateWithLifecycle()
    val playlistAutosave by viewModel.playlistAutosave.collectAsStateWithLifecycle()

    SettingsScaffold(stringResource(R.string.set_library), navController) {
        SectionLabel(stringResource(R.string.set_sec_browse_playlist))
        SwitchRow(stringResource(R.string.set_playlist_autosave), stringResource(R.string.set_playlist_autosave_summary), playlistAutosave) {
            viewModel.setPlaylistAutosave(it)
        }
        SectionLabel(stringResource(R.string.set_sec_scan))
        SwitchRow(stringResource(R.string.set_watch_folders), stringResource(R.string.set_watch_folders_summary), watchFolders) { viewModel.setWatchFolders(it) }
        SwitchRow(stringResource(R.string.set_scan_hidden), stringResource(R.string.set_scan_hidden_summary), scanHidden) { viewModel.setScanHidden(it) }
        SwitchRow(stringResource(R.string.set_hide_short_clips), stringResource(R.string.set_hide_short_clips_summary), hideShort) { viewModel.setHideShortClips(it) }
        SectionLabel(stringResource(R.string.set_sec_scan_types))
        ScanExtensionsSection(
            selected = scanExtensions,
            onToggle = { ext, checked ->
                val next = if (checked) scanExtensions + ext else scanExtensions - ext
                viewModel.setScanExtensions(next)
            }
        )
        SectionLabel(stringResource(R.string.set_sec_maintenance))
        ActionRow(stringResource(R.string.set_action_prune_stale), stringResource(R.string.set_action_prune_stale_summary)) { viewModel.pruneMissingSongs() }
        NavRow(stringResource(R.string.set_nav_duplicates), stringResource(R.string.set_nav_duplicates_summary)) { navController.navigate(Screen.Duplicates.route) }
        maintenanceResult?.let { res ->
            Text(stringResource(res.resId, *res.args), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ===== 分类3 · 回放设置（需求 25-32 + 睡眠定时） =====

@Composable
fun PlaybackSettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val autoResume by viewModel.autoResume.collectAsStateWithLifecycle()
    val gapless by viewModel.gapless.collectAsStateWithLifecycle()
    val rememberPosition by viewModel.rememberPosition.collectAsStateWithLifecycle()
    val startupAction by viewModel.startupAction.collectAsStateWithLifecycle()
    val defaultRepeat by viewModel.defaultRepeat.collectAsStateWithLifecycle()
    val skipOnError by viewModel.skipOnError.collectAsStateWithLifecycle()
    val headsetPause by viewModel.headsetPause.collectAsStateWithLifecycle()
    val headsetButtonControl by viewModel.headsetButtonControl.collectAsStateWithLifecycle()
    val btDisconnectPause by viewModel.btDisconnectPause.collectAsStateWithLifecycle()
    val btReconnectResume by viewModel.btReconnectResume.collectAsStateWithLifecycle()
    val playRequiresAudioFocus by viewModel.playRequiresAudioFocus.collectAsStateWithLifecycle()

    SettingsScaffold(stringResource(R.string.set_playback), navController) {
        SectionLabel(stringResource(R.string.set_sec_startup))
        SelectRow(listOf(stringResource(R.string.set_startup_none) to "none", stringResource(R.string.set_startup_play) to "play", stringResource(R.string.set_startup_resume) to "resume"), startupAction) {
            viewModel.setStartupAction(it)
        }
        SwitchRow(stringResource(R.string.set_auto_resume), stringResource(R.string.set_auto_resume_summary), autoResume) { viewModel.setAutoResume(it) }
        SwitchRow(stringResource(R.string.set_remember_position), stringResource(R.string.set_remember_position_summary), rememberPosition) { viewModel.setRememberPosition(it) }
        SectionLabel(stringResource(R.string.set_sec_play_mode))
        SelectRow(listOf(stringResource(R.string.set_repeat_off) to "off", stringResource(R.string.set_repeat_all) to "all", stringResource(R.string.set_repeat_one) to "one"), defaultRepeat) {
            viewModel.setDefaultRepeat(it)
        }
        SwitchRow(stringResource(R.string.set_gapless), stringResource(R.string.set_gapless_summary), gapless) { viewModel.setGapless(it) }
        SwitchRow(stringResource(R.string.set_skip_on_error), stringResource(R.string.set_skip_on_error_summary), skipOnError) { viewModel.setSkipOnError(it) }
        SectionLabel(stringResource(R.string.set_sec_hw_timer))
        SwitchRow(stringResource(R.string.set_headset_pause), stringResource(R.string.set_headset_pause_summary), headsetPause) { viewModel.setHeadsetPause(it) }
        SectionLabel(stringResource(R.string.set_sec_headset_focus))
        SwitchRow(stringResource(R.string.set_headset_button_control), stringResource(R.string.set_headset_button_control_summary), headsetButtonControl) { viewModel.setHeadsetButtonControl(it) }
        SwitchRow(stringResource(R.string.set_bt_disconnect_pause), stringResource(R.string.set_bt_disconnect_pause_summary), btDisconnectPause) { viewModel.setBtDisconnectPause(it) }
        SwitchRow(stringResource(R.string.set_bt_reconnect_resume), stringResource(R.string.set_bt_reconnect_resume_summary), btReconnectResume) { viewModel.setBtReconnectResume(it) }
        SwitchRow(stringResource(R.string.set_play_requires_focus), stringResource(R.string.set_play_requires_focus_summary), playRequiresAudioFocus) { viewModel.setPlayRequiresAudioFocus(it) }
    }
}

// ===== 分类4 · 音乐来源设置（需求 33-35） =====

@Composable
fun SourcesSettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    SettingsScaffold(stringResource(R.string.set_sources), navController) {
        SectionLabel(stringResource(R.string.set_sec_source_mgmt))
        NavRow(stringResource(R.string.set_nav_source_mgmt), stringResource(R.string.set_nav_source_mgmt_summary)) { navController.navigate(Screen.Network.route) }
        NavRow(stringResource(R.string.set_nav_zerotier), stringResource(R.string.set_nav_zerotier_summary)) { navController.navigate(Screen.ZeroTier.route) }
        Text(stringResource(R.string.set_sources_hint), style = MaterialTheme.typography.bodySmall)
    }
}

// ===== 分类5 · 元数据设置（需求 36-41） =====

@Composable
fun MetadataSettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val readEmbedLyrics by viewModel.readEmbedLyrics.collectAsStateWithLifecycle()
    val lyricsEnabled by viewModel.lyricsEnabled.collectAsStateWithLifecycle()
    val metadataEnabled by viewModel.metadataEnabled.collectAsStateWithLifecycle()
    val autoSyncMetadata by viewModel.autoSyncMetadata.collectAsStateWithLifecycle()
    val syncing by viewModel.metadataSyncing.collectAsStateWithLifecycle()
    val dataSaver by viewModel.dataSaver.collectAsStateWithLifecycle()
    val manualActive by viewModel.manualSyncActive.collectAsStateWithLifecycle()
    val manualProgress by viewModel.manualSyncProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ignoringBattery = remember {
        val pm = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    SettingsScaffold(stringResource(R.string.set_metadata), navController) {
        SectionLabel(stringResource(R.string.set_sec_lyrics))
        SwitchRow(stringResource(R.string.set_read_embed_lyrics), stringResource(R.string.set_read_embed_lyrics_summary), readEmbedLyrics) { viewModel.setReadEmbedLyrics(it) }
        SwitchRow(stringResource(R.string.set_online_lyrics), stringResource(R.string.set_online_lyrics_summary), lyricsEnabled) { viewModel.setLyricsEnabled(it) }
        SectionLabel(stringResource(R.string.set_sec_meta_fetch))
        SwitchRow(stringResource(R.string.set_online_metadata), stringResource(R.string.set_online_metadata_summary), metadataEnabled) { viewModel.setMetadataEnabled(it) }
        NavRow(stringResource(R.string.set_nav_metadata_sources), stringResource(R.string.set_nav_metadata_sources_summary)) { navController.navigate(Screen.SettingsMetadataSources.route) }
        ManualBatchSyncRow(
            active = manualActive,
            progress = manualProgress,
            onStart = viewModel::startManualMetadataSync,
            onStop = viewModel::stopManualMetadataSync
        )
        AutoSyncRow(checked = autoSyncMetadata, syncing = syncing) { viewModel.setAutoSyncMetadata(it) }
        SwitchRow(stringResource(R.string.set_data_saver), stringResource(R.string.set_data_saver_summary), dataSaver) { viewModel.setDataSaver(it) }
        BatteryKeepAliveRow(
            ignored = ignoringBattery,
            onClickAuthorize = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            }
        )
        Text(stringResource(R.string.set_metadata_hint), style = MaterialTheme.typography.bodySmall)
    }
}

// ===== 元数据来源子屏（需求：恢复获取源优先级设置，默认仅启用网易/QQ/酷我） =====

@Composable
fun MetadataSourcesScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val enabledIds by viewModel.metadataSourcesEnabled.collectAsStateWithLifecycle()
    val orderIds by viewModel.metadataSourcesOrder.collectAsStateWithLifecycle()
    val allSources = viewModel.allMetadataSources

    // 显示顺序完全由「排列顺序」决定（用户手工排序）；开关只影响启用/禁用。
    // 忽略已不存在的源 id，保证与可用源全集对齐。
    val orderedIds = remember(allSources, orderIds) {
        allSources.map { it.id }.filter { it in orderIds } + allSources.map { it.id }.filter { it !in orderIds }
    }

    fun sort(list: List<String>) = viewModel.setMetadataSourcesOrder(list)

    SettingsScaffold(stringResource(R.string.set_metadata_sources), navController) {
        Text(
            stringResource(R.string.set_metadata_sources_hint),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        orderedIds.forEachIndexed { rowIndex, id ->
            val src = allSources.firstOrNull { it.id == id } ?: return@forEachIndexed
            val enabled = id in enabledIds
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(src.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "  #${rowIndex + 1}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (!enabled) {
                            Text(stringResource(R.string.set_source_disabled), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Text(
                        capabilityText(src),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // ▲/▼ 仅调整排列顺序，不影响启用状态
                IconButton(onClick = {
                    if (rowIndex > 0) {
                        val m = orderedIds.toMutableList()
                        m.removeAt(rowIndex); m.add(rowIndex - 1, id)
                        sort(m)
                    }
                }, enabled = rowIndex > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.action_move_up))
                }
                IconButton(onClick = {
                    if (rowIndex < orderedIds.size - 1) {
                        val m = orderedIds.toMutableList()
                        m.removeAt(rowIndex); m.add(rowIndex + 1, id)
                        sort(m)
                    }
                }, enabled = rowIndex < orderedIds.size - 1) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.action_move_down))
                }
                // 开关仅切换启用状态，不改变排列顺序
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        val next = if (on) enabledIds + id else enabledIds.filterNot { it == id }
                        viewModel.setMetadataSourcesEnabled(next)
                    }
                )
            }
            HorizontalDivider()
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { viewModel.resetMetadataSources() },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.set_reset_sources)) }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.set_metadata_sources_hint2), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun capabilityText(src: com.shiyinplayer.data.metadata.MetadataSource): String {
    val parts = mutableListOf<String>()
    if (com.shiyinplayer.data.metadata.MetaCapability.LYRIC in src.capabilities) parts += stringResource(R.string.set_cap_lyric)
    if (com.shiyinplayer.data.metadata.MetaCapability.COVER in src.capabilities) parts += stringResource(R.string.set_cap_cover)
    if (com.shiyinplayer.data.metadata.MetaCapability.YEAR in src.capabilities) parts += stringResource(R.string.set_cap_year)
    if (com.shiyinplayer.data.metadata.MetaCapability.ARTIST in src.capabilities) parts += stringResource(R.string.set_cap_artist)
    return if (parts.isEmpty()) stringResource(R.string.set_cap_fallback) else stringResource(R.string.set_cap_support, parts.joinToString(" / "))
}

/** 自动同步行：附带「运行中」实时状态显示（2026-08-23 需求2）。 */
@Composable
private fun AutoSyncRow(checked: Boolean, syncing: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val active = Color(0xFF00C853)
    val idle = Color(0xFF9E9E9E)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.set_auto_sync_metadata), style = MaterialTheme.typography.bodyLarge)
                when {
                    syncing -> {
                        Box(Modifier.padding(start = 8.dp).size(8.dp).background(active, CircleShape))
                        Text(
                            stringResource(R.string.set_sync_running), style = MaterialTheme.typography.labelMedium,
                            color = active, modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                    checked -> {
                        Box(Modifier.padding(start = 8.dp).size(8.dp).background(idle, CircleShape))
                        Text(
                            stringResource(R.string.set_sync_idle, MetadataSyncManager.AUTO_SYNC_INTERVAL_MINUTES), style = MaterialTheme.typography.labelMedium,
                            color = idle, modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.set_auto_sync_summary, MetadataSyncManager.NETWORK_MATCH_BATCH),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider()
}

/** 手动批量同步元数据：开始（先弹提醒）/停止 + 实时进度（正在同步，已同步XX首，剩余XX首）。 */
@Composable
private fun ManualBatchSyncRow(active: Boolean, progress: ManualSyncProgress, onStart: () -> Unit, onStop: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.set_manual_sync_metadata), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.set_manual_sync_summary),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (active) {
                OutlinedButton(onClick = onStop, modifier = Modifier.height(40.dp)) { Text(stringResource(R.string.set_stop_sync)) }
            } else {
                OutlinedButton(onClick = { showConfirm = true }, modifier = Modifier.height(40.dp)) { Text(stringResource(R.string.set_start)) }
            }
        }
        if (active) {
            Text(
                if (progress.total > 0) {
                    stringResource(R.string.set_manual_progress, progress.done, (progress.total - progress.done).coerceAtLeast(0))
                } else {
                    stringResource(R.string.set_manual_scanning)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    HorizontalDivider()

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.set_manual_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.set_manual_confirm_body)
                )
            },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onStart() }) { Text(stringResource(R.string.set_start_sync)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** 后台同步保活授权引导（2026-08-23 需求2）：提示 / 引导开启「忽略电池优化」。 */
@Composable
private fun BatteryKeepAliveRow(ignored: Boolean, onClickAuthorize: () -> Unit) {
    val active = Color(0xFF00C853)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.set_battery_keepalive), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (ignored) stringResource(R.string.set_battery_config_on)
                else stringResource(R.string.set_battery_config_off),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (ignored) {
            Text(stringResource(R.string.set_battery_authorized), style = MaterialTheme.typography.labelMedium, color = active)
        } else {
            OutlinedButton(onClick = onClickAuthorize) { Text(stringResource(R.string.action_authorize)) }
        }
    }
    HorizontalDivider()
}

// ===== 分类6 · 声音引擎设置（需求 42-51） =====

@Composable
fun SoundSettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val audioRoute by viewModel.audioRoute.collectAsStateWithLifecycle()
    val lowLatency by viewModel.lowLatency.collectAsStateWithLifecycle()
    val bufferMs by viewModel.bufferMs.collectAsStateWithLifecycle()
    val float32 by viewModel.float32Processing.collectAsStateWithLifecycle()
    val volumeNormalize by viewModel.volumeNormalize.collectAsStateWithLifecycle()
    val replaygainMode by viewModel.replaygainMode.collectAsStateWithLifecycle()
    val volumeCurve by viewModel.volumeCurve.collectAsStateWithLifecycle()
    val fadeInMs by viewModel.fadeInMs.collectAsStateWithLifecycle()
    val silenceRemover by viewModel.silenceRemover.collectAsStateWithLifecycle()

    SettingsScaffold(stringResource(R.string.set_sound), navController) {
        SectionLabel(stringResource(R.string.set_sec_output))
        SelectRow(listOf(
            stringResource(R.string.set_out_auto) to "auto",
            stringResource(R.string.set_out_speaker) to "speaker",
            stringResource(R.string.set_out_bt) to "bt",
            stringResource(R.string.set_out_wired) to "wired"
        ), audioRoute) {
            viewModel.setAudioRoute(it)
        }
        SwitchRow(stringResource(R.string.set_low_latency), stringResource(R.string.set_low_latency_summary), lowLatency) { viewModel.setLowLatency(it) }
        SliderRow(stringResource(R.string.set_audio_buffer), "${bufferMs}ms", bufferMs.toFloat(), 50f..500f) {
            viewModel.setBufferMs(it.toInt())
        }
        SectionLabel(stringResource(R.string.set_sec_precision))
        SwitchRow(stringResource(R.string.set_float32), stringResource(R.string.set_float32_summary), float32) { viewModel.setFloat32Processing(it) }
        SectionLabel(stringResource(R.string.set_sec_volume))
        SwitchRow(stringResource(R.string.set_volume_normalize), stringResource(R.string.set_volume_normalize_summary), volumeNormalize) { viewModel.setVolumeNormalize(it) }
        SelectRow(listOf(
            stringResource(R.string.set_replaygain_off) to "off",
            stringResource(R.string.set_replaygain_track) to "track",
            stringResource(R.string.set_replaygain_album) to "album",
            stringResource(R.string.set_replaygain_smart) to "smart"
        ), replaygainMode) {
            viewModel.setReplaygainMode(it)
        }
        SelectRow(listOf(stringResource(R.string.set_curve_log) to "log", stringResource(R.string.set_curve_loudness) to "loudness"), volumeCurve) { viewModel.setVolumeCurve(it) }
        SectionLabel(stringResource(R.string.set_sec_mix))
        SliderRow(stringResource(R.string.set_fade_in_out), "${fadeInMs}ms", fadeInMs.toFloat(), 0f..3000f) { viewModel.setFadeInMs(it.toInt()) }
        SwitchRow(stringResource(R.string.set_silence_remover), stringResource(R.string.set_silence_remover_summary), silenceRemover) { viewModel.setSilenceRemover(it) }
        Text(
            stringResource(R.string.set_sound_core_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ===== 分类8 · 系统集成设置（需求 57-58） =====

@Composable
fun IntegrationSettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val isDefaultPlayer by viewModel.isDefaultPlayer.collectAsStateWithLifecycle()
    val acceptExternalOpen by viewModel.acceptExternalOpen.collectAsStateWithLifecycle()

    // 2026-08-21 接线：设为默认播放器 → Android 系统「音乐与音频」角色（ROLE_MUSIC）。
    // 开关状态以系统实际角色为准（而非仅持久化值），切换时拉起系统角色授权页。
    // 注：离线 SDK 桩 jar 缺 ROLE_MUSIC 常量，此处用官方字符串值 "android.app.role.MUSIC"。
    val role = "android.app.role.MUSIC"
    val context = LocalContext.current
    val roleManager = remember(context) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getSystemService(RoleManager::class.java) else null }
    var roleHeld by remember { mutableStateOf(roleManager?.isRoleHeld(role) ?: false) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        roleHeld = roleManager?.isRoleHeld(role) ?: false
        viewModel.setIsDefaultPlayer(roleHeld)
    }

    SettingsScaffold(stringResource(R.string.set_integration), navController) {
        SectionLabel(stringResource(R.string.set_sec_system_default))
        SwitchRow(stringResource(R.string.set_default_player), stringResource(R.string.set_default_player_summary), roleHeld) {
            val intent = roleManager?.createRequestRoleIntent(role)
            if (intent != null) {
                roleLauncher.launch(intent)
            } else {
                // MUSIC 角色不可用（部分 OEM 未登记该角色，如 OnePlus）：
                // 退回应用系统详情页，用户可手动设置「默认打开方式」。
                roleLauncher.launch(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        }
        SwitchRow(stringResource(R.string.set_accept_external), stringResource(R.string.set_accept_external_summary), acceptExternalOpen) {
            viewModel.setAcceptExternalOpen(it)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.set_integration_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ===== 通用组件 =====

@Composable
private fun SettingsScaffold(
    title: String,
    navController: NavController,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = stringResource(R.string.set_return))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            content = content
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SwitchRow(label: String, summary: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider()
}

/** 主题风格胶囊：选中以强调色填充，未选中为表面淡化底（供「主题风格」选择使用）。 */
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

@Composable
private fun NavRow(label: String, summary: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
    HorizontalDivider()
}

@Composable
private fun ActionRow(label: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onClick, modifier = Modifier.height(40.dp)) { Text(stringResource(R.string.action_execute)) }
    }
}

/**
 * 设置子项多选按钮组（需求 10：样式/尺寸/对齐统一）。
 * 根据屏幕可用宽度自适应每行按钮数量：平板/横屏更宽时每行更多项，手机更窄时逐行回绕，
 * 保证每行按钮等宽等高、文字单行不换行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdaptiveSelectOptions(options: List<Triple<String, Boolean, () -> Unit>>) {
    // 每个按钮约 120dp，按当前宽度推算可并列数量，自动适配平板与手机。
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val columns = (maxWidth / 120.dp).toInt().coerceIn(1, options.size)
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            options.chunked(columns).forEach { rowOptions ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    rowOptions.forEach { (label, selected, onClick) ->
                        SelectButton(label, selected, Modifier.weight(1f)) { onClick() }
                    }
                }
            }
        }
    }
}

/** 统一尺寸/样式的单选按钮（单行文字，超长省略号，选中高亮）。 */
@Composable
private fun SelectButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(40.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 多选按钮组（String 值）。 */
@Composable
private fun SelectRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    AdaptiveSelectOptions(options.map { Triple(it.first, selected == it.second) { onSelect(it.second) } })
}

/** 多选按钮组（Int 值）。 */
@Composable
private fun SelectRow(
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    AdaptiveSelectOptions(options.map { Triple(it.first, selected == it.second) { onSelect(it.second) } })
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
    HorizontalDivider()
}

/** 扫描文件类型多选清单（P0 解码扩展 §3.2）：按格式族分组展示，未交付阶段置灰。 */
@Composable
private fun ScanExtensionsSection(
    selected: Set<String>,
    onToggle: (ext: String, checked: Boolean) -> Unit
) {
    val currentPhase = AudioFormatRegistry.CURRENT_PHASE
    val phaseLabels = mapOf(
        AudioFormatRegistry.Phase.NATIVE to stringResource(R.string.set_phase_native),
        AudioFormatRegistry.Phase.P0 to stringResource(R.string.set_phase_p0),
        AudioFormatRegistry.Phase.P2A to stringResource(R.string.set_phase_p2a)
    )
    AudioFormatRegistry.Phase.values().forEach { phase ->
        val formats = AudioFormatRegistry.allFormats.filter { it.phase == phase }
        if (formats.isEmpty()) return@forEach
        val delivered = phase.ordinal <= currentPhase.ordinal
        Text(
            phaseLabels[phase] ?: phase.name,
            style = MaterialTheme.typography.titleSmall,
            color = if (delivered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!delivered) {
            Text(stringResource(R.string.set_pending_delivery, phase.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        formats.forEach { spec ->
            val isChecked = selected.isEmpty() || spec.extension in selected

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .let { if (delivered) it.clickable { onToggle(spec.extension, !isChecked) } else it },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ".${spec.extension}  ${spec.description}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (delivered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(spec.mimeType, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = isChecked,
                    onCheckedChange = if (delivered) { v -> onToggle(spec.extension, v) } else null
                )
            }
        }
        HorizontalDivider()
    }
    if (selected.isEmpty()) {
        Text(stringResource(R.string.set_scan_no_limit), style = MaterialTheme.typography.bodySmall)
    } else {
        Text(stringResource(R.string.set_scan_limited, selected.size), style = MaterialTheme.typography.bodySmall)
    }
}