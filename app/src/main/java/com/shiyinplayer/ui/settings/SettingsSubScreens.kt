package com.shiyinplayer.ui.settings

import com.shiyinplayer.data.metasync.MetadataSyncManager
import com.shiyinplayer.data.metasync.ManualSyncProgress
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.ui.navigation.Screen
import com.shiyinplayer.ui.theme.ACCENT_COLORS
import com.shiyinplayer.ui.theme.THEME_STYLES
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

    SettingsScaffold("界面设置", navController) {
        SectionLabel("主题风格")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StyleChip("玄素映彩", prefs.style == 0) { viewModel.setThemeStyle(0) }
            THEME_STYLES.forEachIndexed { idx, style ->
                val value = idx + 1
                StyleChip(style.label, prefs.style == value) { viewModel.setThemeStyle(value) }
            }
        }
        if (prefs.style == 0) {
            Spacer(Modifier.height(12.dp))
            SectionLabel("明暗模式")
            SelectRow(
                listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2),
                prefs.mode
            ) { viewModel.setThemeMode(it) }
            if (prefs.mode == 0) {
                Text("「跟随系统」配合系统的浅色/深色模式。", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel("强调色")
            SwitchRow("动态取色（Material You）", "Android 12+ 跟随系统壁纸自动生成主题色（仅玄素映彩风格生效）", prefs.dynamicColors) { viewModel.setDynamicColors(it) }
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
                "已启用整套「${THEME_STYLES.getOrNull(prefs.style - 1)?.label}」风格，明暗与强调色仅在「玄素映彩」下生效。",
                style = MaterialTheme.typography.bodySmall
            )
        }
        SectionLabel("显示模式")
        SelectRow(
            listOf("自动" to "auto", "竖屏" to "portrait", "横屏" to "landscape"),
            displayMode
        ) { viewModel.setDisplayMode(it) }
        Text("「自动」随手机旋转切换竖/横 UI；「竖屏/横屏」固定屏幕方向。", style = MaterialTheme.typography.bodySmall)
        SectionLabel("防息屏")
        SwitchRow("前台保持屏幕常亮", "播放器在前台显示时屏幕不自动息屏", keepScreenOn) { viewModel.setKeepScreenOn(it) }
        SectionLabel("全屏自动隐藏")
        SwitchRow("全屏页自动隐藏控件", "音乐/收音机正在播放页无操作后自动隐藏控制按钮", autoHideControls) { viewModel.setAutoHideControls(it) }
        if (autoHideControls) {
            SelectRow(
                listOf("2 秒" to 2000, "3 秒" to 3000, "5 秒" to 5000),
                autoHideDelayMs
            ) { viewModel.setAutoHideDelayMs(it) }
            Text("无操作达到选定时长后隐藏控制按钮，点击屏幕任意处恢复。", style = MaterialTheme.typography.bodySmall)
        }
        SectionLabel("车载蓝牙")
        SwitchRow("车载蓝牙歌词", "车载设备屏幕同步显示当前歌词", carBtLyrics) { viewModel.setCarBtLyrics(it) }
        SectionLabel("语言")
        SelectRow(listOf("跟随系统" to "system", "简体中文" to "zh", "English" to "en"), language) {
            viewModel.setLanguage(it)
        }
        SectionLabel("封面与列表外观")
        SwitchRow("显示内嵌封面", "曲库与播放页显示内嵌专辑封面", showEmbedArt) { viewModel.setShowEmbedArt(it) }
        SwitchRow("列表封面缩略图", "列表项显示小封面", listShowArt) { viewModel.setListShowArt(it) }
        SwitchRow("双行显示", "列表项显示「标题 + 艺术家」", listTwoLine) { viewModel.setListTwoLine(it) }
        SelectRow(
            listOf("紧凑" to "compact", "标准" to "standard", "宽松" to "relaxed"),
            listDensity
        ) { viewModel.setListDensity(it) }
        SectionLabel("通知与系统")
        SwitchRow("播放通知", "显示前台播放通知（媒体播放服务需要）", notifyEnabled) { viewModel.setNotifyEnabled(it) }
        SwitchRow("锁屏媒体控制", "锁屏界面显示媒体控制", lockscreenControl) { viewModel.setLockscreenControl(it) }
        SwitchRow("迷你播放条", "底部显示迷你播放条", miniBarEnabled) { viewModel.setMiniBarEnabled(it) }
        SectionLabel("音频焦点")
        SwitchRow(
            "被抢占时自动跳下一曲",
            "系统永久占用音频焦点（如来电/其它播放器）时自动跳到下一曲；关闭则暂停后保留当前曲",
            focusLossAutoSkip
        ) { viewModel.setFocusLossAutoSkip(it) }
        Text("更改即时生效。", style = MaterialTheme.typography.bodySmall)
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

    SettingsScaffold("音乐库设置", navController) {
        SectionLabel("浏览与歌单")
        SwitchRow("退出时自动保存", "关闭时持久化全部歌单与当前队列", playlistAutosave) {
            viewModel.setPlaylistAutosave(it)
        }
        SectionLabel("扫描")
        SwitchRow("实时监控文件夹变化", "监听本机媒体变更并增量更新曲库", watchFolders) { viewModel.setWatchFolders(it) }
        SwitchRow("扫描隐藏文件", "包含以点开头的文件/目录", scanHidden) { viewModel.setScanHidden(it) }
        SwitchRow("隐藏短音频", "过滤短于 30 秒的片段（如铃声）", hideShort) { viewModel.setHideShortClips(it) }
        SectionLabel("扫描文件类型")
        ScanExtensionsSection(
            selected = scanExtensions,
            onToggle = { ext, checked ->
                val next = if (checked) scanExtensions + ext else scanExtensions - ext
                viewModel.setScanExtensions(next)
            }
        )
        SectionLabel("维护")
        ActionRow("清理失效曲目", "删除本地文件已不存在的曲目") { viewModel.pruneMissingSongs() }
        NavRow("重复曲目清理", "一键找出重复曲目并按需合并") { navController.navigate(Screen.Duplicates.route) }
        maintenanceResult?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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

    SettingsScaffold("回放设置", navController) {
        SectionLabel("启动")
        SelectRow(listOf("无操作" to "none", "继续播放" to "play", "恢复上次" to "resume"), startupAction) {
            viewModel.setStartupAction(it)
        }
        SwitchRow("自动续播", "打开应用时继续上次的播放", autoResume) { viewModel.setAutoResume(it) }
        SwitchRow("记忆播放位置", "关闭前保存进度以便续播", rememberPosition) { viewModel.setRememberPosition(it) }
        SectionLabel("播放模式")
        SelectRow(listOf("顺序" to "off", "列表循环" to "all", "单曲循环" to "one"), defaultRepeat) {
            viewModel.setDefaultRepeat(it)
        }
        SwitchRow("无缝播放", "曲目之间无间隙切换", gapless) { viewModel.setGapless(it) }
        SwitchRow("解码失败跳下一首", "出错时自动跳到下一首", skipOnError) { viewModel.setSkipOnError(it) }
        SectionLabel("硬件与定时")
        SwitchRow("耳机断开时暂停", "拔下耳机/蓝牙音频时自动暂停", headsetPause) { viewModel.setHeadsetPause(it) }
        SectionLabel("耳机与音频焦点")
        SwitchRow("耳机按键控制", "用有线/蓝牙耳机按键控制播放与切歌", headsetButtonControl) { viewModel.setHeadsetButtonControl(it) }
        SwitchRow("蓝牙断开时暂停", "蓝牙耳机断开连接时自动暂停播放", btDisconnectPause) { viewModel.setBtDisconnectPause(it) }
        SwitchRow("蓝牙重连时恢复", "蓝牙耳机重新连接时自动恢复播放", btReconnectResume) { viewModel.setBtReconnectResume(it) }
        SwitchRow("仅持音频焦点播放", "获取系统音频焦点才允许播放，切走时自动暂停", playRequiresAudioFocus) { viewModel.setPlayRequiresAudioFocus(it) }
    }
}

// ===== 分类4 · 音乐来源设置（需求 33-35） =====

@Composable
fun SourcesSettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    SettingsScaffold("音乐来源设置", navController) {
        SectionLabel("来源管理")
        NavRow("音乐库来源管理", "添加本地 / SMB / WebDAV 来源并扫描") { navController.navigate(Screen.Network.route) }
        NavRow("ZeroTier 虚拟网络", "加入虚拟网络访问局域网外音源") { navController.navigate(Screen.ZeroTier.route) }
        Text("音乐来源统一在此分为本地存储与网络源；网络源（SMB/WebDAV）与 ZeroTier 虚拟接入在此集中管理。", style = MaterialTheme.typography.bodySmall)
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

    SettingsScaffold("元数据设置", navController) {
        SectionLabel("歌词")
        SwitchRow("读取内嵌歌词", "优先读取音频标签中的歌词", readEmbedLyrics) { viewModel.setReadEmbedLyrics(it) }
        SwitchRow("在线歌词", "播放页显示来自在线数据源的滚动歌词", lyricsEnabled) { viewModel.setLyricsEnabled(it) }
        SectionLabel("元数据获取")
        SwitchRow("在线元数据", "获取专辑封面 / 歌手头像与简介", metadataEnabled) { viewModel.setMetadataEnabled(it) }
        NavRow("元数据来源", "选择在线歌词/元数据获取源并设置优先级") { navController.navigate(Screen.SettingsMetadataSources.route) }
        ManualBatchSyncRow(
            active = manualActive,
            progress = manualProgress,
            onStart = viewModel::startManualMetadataSync,
            onStop = viewModel::stopManualMetadataSync
        )
        AutoSyncRow(checked = autoSyncMetadata, syncing = syncing) { viewModel.setAutoSyncMetadata(it) }
        SwitchRow("流量保护", "连接移动网络时不联网获取元数据/歌词，仅 WiFi 下自动同步", dataSaver) { viewModel.setDataSaver(it) }
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
        Text("在线歌词与元数据优先使用歌曲已抓取的内嵌标签；缺失部分联网补充。", style = MaterialTheme.typography.bodySmall)
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

    SettingsScaffold("元数据来源", navController) {
        Text(
            "点击开关仅启用/禁用对应源，不改变排列顺序；播放器按下方顺序从上到下获取并跳过关闭的源。如需调整优先级请使用每个源的 ▲/▼ 按钮。",
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
                            Text("（关）", style = MaterialTheme.typography.labelMedium,
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
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
                }
                IconButton(onClick = {
                    if (rowIndex < orderedIds.size - 1) {
                        val m = orderedIds.toMutableList()
                        m.removeAt(rowIndex); m.add(rowIndex + 1, id)
                        sort(m)
                    }
                }, enabled = rowIndex < orderedIds.size - 1) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
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
        ) { Text("恢复默认（网易云 / QQ / 酷我 三源及官方排列顺序）") }
        Spacer(Modifier.height(8.dp))
        Text("默认仅启用 网易云 / QQ / 酷我 三个中文源，其余源关闭以节省流量与请求；可按需点击开关启用，并用 ▲/▼ 调整排列顺序。", style = MaterialTheme.typography.bodySmall)
    }
}

private fun capabilityText(src: com.shiyinplayer.data.metadata.MetadataSource): String {
    val parts = mutableListOf<String>()
    if (com.shiyinplayer.data.metadata.MetaCapability.LYRIC in src.capabilities) parts += "歌词"
    if (com.shiyinplayer.data.metadata.MetaCapability.COVER in src.capabilities) parts += "封面"
    if (com.shiyinplayer.data.metadata.MetaCapability.YEAR in src.capabilities) parts += "年份"
    if (com.shiyinplayer.data.metadata.MetaCapability.ARTIST in src.capabilities) parts += "歌手信息"
    return if (parts.isEmpty()) "仅作兜底" else "支持：${parts.joinToString(" / ")}"
}

/** 自动同步行：附带「运行中」实时状态显示（2026-08-23 需求2）。 */
@Composable
private fun AutoSyncRow(checked: Boolean, syncing: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val active = Color(0xFF00C853)
    val idle = Color(0xFF9E9E9E)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动同步音乐元数据", style = MaterialTheme.typography.bodyLarge)
                when {
                    syncing -> {
                        Box(Modifier.padding(start = 8.dp).size(8.dp).background(active, CircleShape))
                        Text(
                            "运行中", style = MaterialTheme.typography.labelMedium,
                            color = active, modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                    checked -> {
                        Box(Modifier.padding(start = 8.dp).size(8.dp).background(idle, CircleShape))
                        Text(
                            "待机（每 ${MetadataSyncManager.AUTO_SYNC_INTERVAL_MINUTES} 分钟自动同步）", style = MaterialTheme.typography.labelMedium,
                            color = idle, modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
            Text(
                "自动读取本地音频标签 / 在线补充缺失的歌曲信息；每轮最多匹配 ${MetadataSyncManager.NETWORK_MATCH_BATCH} 首（独立线程，不影响播放）",
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
                Text("批量同步元数据", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "手动批量更新全库（任意来源）曲目缺失的元数据；多线程并发多源采集，全程限速不打挂在线服务器。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (active) {
                OutlinedButton(onClick = onStop, modifier = Modifier.height(40.dp)) { Text("停止同步") }
            } else {
                OutlinedButton(onClick = { showConfirm = true }, modifier = Modifier.height(40.dp)) { Text("开始") }
            }
        }
        if (active) {
            Text(
                if (progress.total > 0) {
                    "正在同步，已同步${progress.done}首，剩余${(progress.total - progress.done).coerceAtLeast(0)}首"
                } else {
                    "正在扫描缺失曲目…"
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
            title = { Text("开启批量同步元数据？") },
            text = {
                Text(
                    "启用后将在同步期间暂停后台自动同步与播放时自动匹配歌词，且会有较密集的网络请求，可能影响歌曲的正常播放。建议在空闲、不播放歌曲时进行此操作。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onStart() }) { Text("开始同步") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
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
            Text("后台同步保活", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (ignored) "已开启：后台也能持续同步元数据，不易被系统终止"
                else "未开启：后台同步可能被系统终止，建议授权",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (ignored) {
            Text("已授权", style = MaterialTheme.typography.labelMedium, color = active)
        } else {
            OutlinedButton(onClick = onClickAuthorize) { Text("授权") }
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

    SettingsScaffold("声音引擎设置", navController) {
        SectionLabel("输出")
        SelectRow(listOf("自动" to "auto", "扬声器" to "speaker", "蓝牙" to "bt", "有线耳机" to "wired"), audioRoute) {
            viewModel.setAudioRoute(it)
        }
        SwitchRow("低延迟音频", "使用 AAudio 低延迟路径", lowLatency) { viewModel.setLowLatency(it) }
        SliderRow("音频缓冲", "${bufferMs}ms", bufferMs.toFloat(), 50f..500f) {
            viewModel.setBufferMs(it.toInt())
        }
        SectionLabel("处理精度")
        SwitchRow("32 位浮点处理", "音频链上使用 float PCM 处理", float32) { viewModel.setFloat32Processing(it) }
        SectionLabel("音量")
        SwitchRow("音量归一化", "按峰值归一化音量", volumeNormalize) { viewModel.setVolumeNormalize(it) }
        SelectRow(listOf("禁用" to "off", "按曲目" to "track", "按专辑" to "album", "智能" to "smart"), replaygainMode) {
            viewModel.setReplaygainMode(it)
        }
        SelectRow(listOf("对数" to "log", "响度补偿" to "loudness"), volumeCurve) { viewModel.setVolumeCurve(it) }
        SectionLabel("混音")
        SliderRow("淡入淡出", "${fadeInMs}ms", fadeInMs.toFloat(), 0f..3000f) { viewModel.setFadeInMs(it.toInt()) }
        SwitchRow("静音消除", "自动跳过首尾静音段", silenceRemover) { viewModel.setSilenceRemover(it) }
        Text(
            "播放内核基于 AndroidX Media3 ExoPlayer；高规格无损与扩展格式经 media3-exoplayer-ffmpeg 解码。",
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

    SettingsScaffold("系统集成", navController) {
        SectionLabel("系统默认")
        SwitchRow("设为默认播放器", "请求系统「音乐与音频」默认角色（Android 9+）", roleHeld) {
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
        SwitchRow("接收外部打开", "从文件管理器/其他 App 打开音频直接播放", acceptExternalOpen) {
            viewModel.setAcceptExternalOpen(it)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "本播放器已在系统「默认应用 / 打开方式」中注册音频类型，可从文件管理器或其它应用直接「用本播放器打开」音频。",
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
                Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "返回")
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
        OutlinedButton(onClick = onClick, modifier = Modifier.height(40.dp)) { Text("执行") }
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
        AudioFormatRegistry.Phase.NATIVE to "原生（ExoPlayer 内置）",
        AudioFormatRegistry.Phase.P0 to "P0 系统直通",
        AudioFormatRegistry.Phase.P2A to "P2A libffmpeg_all 解码"
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
            Text("待 ${phase.name} 交付", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("当前未勾选限制，扫描全部已交付格式。勾选任一格式后将仅扫描勾选集合。", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("已限制扫描为勾选的 ${selected.size} 个格式。取消全部勾选恢复扫描全部。", style = MaterialTheme.typography.bodySmall)
    }
}