package com.shiyinplayer.ui.radio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.R
import com.shiyinplayer.player.PlayerManager
import com.shiyinplayer.player.radio.RadioPlayer
import com.shiyinplayer.ui.settings.SettingsRepository
import com.shiyinplayer.ui.settings.SettingsViewModel
import com.shiyinplayer.ui.theme.MusicPlayerTheme
import kotlinx.coroutines.launch

/** 三个顶层 tab。 */
private enum class RadioTab { HOME, PLAYING, FAVORITES }

/** 次级页面（push 语义，叠在当前 tab 内容之上）。 */
private sealed class RadioSubPage {
    object Search : RadioSubPage()
    object Import : RadioSubPage()
    object Settings : RadioSubPage()
    object InterfaceSettings : RadioSubPage()
    object SoundSettings : RadioSubPage()
}

/**
 * 收音机模式主界面。
 *
 * 设计要点（2026-09-04 重构）：
 * 1. 三个 tab 之间用 `AnimatedContent` 切换，不再用 `NavHost`。
 *    修复了原 `popUpTo + saveState + restoreState` 在"播放页 → 收藏 tab"时 entry 不重建、
 *    UI 不刷新但 selected 状态变化的 bug。
 * 2. 三个 tab 共享同一个 `RadioViewModel`（同一 Composable 作用域内的 `hiltViewModel()`），
 *    状态天然一致，零延迟。
 * 3. 每个 tab 的滚动/筛选/展开状态用各自内部的 `rememberSaveable` 自管。
 * 4. 次级页面（搜索/设置）用一个轻量栈 `ArrayDeque<RadioSubPage>` 实现 push/pop。
 * 5. 系统返回键优先弹次级栈，栈空时才走"回 tab HOME"的兜底。
 */
@Composable
fun RadioNavGraph(
    radioPlayer: RadioPlayer,
    settingsRepository: SettingsRepository = hiltViewModel<SettingsViewModel>().settingsRepository,
    playerManager: PlayerManager = hiltViewModel<com.shiyinplayer.ui.player.PlayerViewModel>().playerManagerRef
) {
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val prefs by settingsViewModel.prefs.collectAsStateWithLifecycle()
    val miniBarEnabled by settingsViewModel.miniBarEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val activity = LocalContext.current as? android.app.Activity

    // 三个 tab 共享同一个 ViewModel：作为父层 hiltViewModel() 拿到同一实例
    val sharedViewModel: RadioViewModel = hiltViewModel()

    // 跨配置变更保留当前 tab
    var currentTab by rememberSaveable { mutableStateOf(RadioTab.HOME) }
    // 次级页面栈（轻量 push/pop）
    val subStack = rememberSaveable(saver = RadioSubStackSaver) {
        mutableStateListOf<RadioSubPage>()
    }

    // 系统返回键处理：弹次级栈 → 回 HOME tab → 退到后台
    BackHandler {
        if (subStack.isNotEmpty()) {
            subStack.removeAt(subStack.lastIndex)
        } else if (currentTab != RadioTab.HOME) {
            currentTab = RadioTab.HOME
        } else {
            activity?.moveTaskToBack(true)
        }
    }

    // 切到电台模式时恢复历史电台：每次 RadioNavGraph 首次组合时跑一次。
    // RadioViewModel 绑在 Activity 的 ViewModelStore 上，切到音乐再切回时它不会重建，
    // 所以不能在 init 里做恢复；改在 NavGraph 重新组合时显式调一次。
    LaunchedEffect(Unit) {
        android.util.Log.i("RadioNavGraph", "RadioNavGraph 首次组合，调 restoreSessionOnModeSwitch")
        sharedViewModel.restoreSessionOnModeSwitch()
    }

    MusicPlayerTheme(prefs = prefs) {
        val mobileGate by sharedViewModel.wifiGate.collectAsStateWithLifecycle()
        mobileGate?.let {
            AlertDialog(
                onDismissRequest = { sharedViewModel.denyMobilePlayback() },
                title = { Text(stringResource(R.string.radio_mobile_data_title)) },
                text = { Text(stringResource(R.string.radio_mobile_data_text)) },
                confirmButton = {
                    TextButton(onClick = { sharedViewModel.allowMobilePlayback() }) { Text(stringResource(R.string.action_allow)) }
                },
                dismissButton = {
                    TextButton(onClick = { sharedViewModel.denyMobilePlayback() }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }
        Scaffold(
            bottomBar = {
                RadioBottomBar(
                    currentTab = currentTab,
                    onTabSelected = { tab ->
                        if (tab != currentTab) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentTab = tab
                            // 切 tab 时清空次级栈，避免"在 A tab 打开搜索 → 切到 B tab 还在显示搜索"
                            subStack.clear()
                        }
                    },
                    onCenterDoubleClick = {
                        // 双击中间按钮：切换到音乐模式
                        // 1) 保存电台模式切走前的播放状态（true=恢复时自动播；false=只恢复内容）
                        val wasPlaying = radioPlayer.isPlaying ||
                            radioPlayer.playbackState.value.isBuffering
                        android.util.Log.i("RadioNavGraph", "双击中间: 保存 radioWasPlaying=$wasPlaying")
                        scope.launch {
                            settingsRepository.setRadioWasPlaying(wasPlaying)
                            android.util.Log.i("RadioNavGraph", "setRadioWasPlaying 写入完成")
                            // 2) 恢复音乐会话（先就位避免空窗）
                            playerManager.restoreSessionForModeSwitch()
                            val musicWasPlaying = settingsRepository.musicWasPlayingSync()
                            if (musicWasPlaying) playerManager.play()
                            // 3) pause 电台（保留 streamUrl/stationName，UI 上能直接看到上次内容）
                            radioPlayer.pause()
                            // 4) 切模式
                            settingsRepository.setAppMode("music")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 主区域：tab 切换动画（占剩余空间，避免迷你条把内容挤出可视区）
                Box(modifier = Modifier.weight(1f)) {
                    // 1) 三个 tab 的内容（共享同一个 RadioViewModel）
                    AnimatedContent(
                        targetState = currentTab,
                        transitionSpec = {
                            val forward = targetState.ordinal > initialState.ordinal
                            ContentTransform(
                                targetContentEnter = slideInHorizontally(
                                    animationSpec = tween(220)
                                ) { fullWidth -> if (forward) fullWidth else -fullWidth } + fadeIn(tween(220)),
                                initialContentExit = slideOutHorizontally(
                                    animationSpec = tween(220)
                                ) { fullWidth -> if (forward) -fullWidth else fullWidth } + fadeOut(tween(220))
                            )
                        },
                        label = "radioTab"
                    ) { tab ->
                        when (tab) {
                            RadioTab.HOME -> RadioHomeScreen(
                                viewModel = sharedViewModel,
                                onNavigateToPlaying = { currentTab = RadioTab.PLAYING },
                                onNavigateToSearch = { subStack.add(RadioSubPage.Search) },
                                onNavigateToImport = { subStack.add(RadioSubPage.Import) },
                                onNavigateToSettings = { subStack.add(RadioSubPage.Settings) }
                            )
                            RadioTab.FAVORITES -> RadioFavoritesScreen(
                                viewModel = sharedViewModel
                            )
                            RadioTab.PLAYING -> RadioPlayingScreen(
                                viewModel = sharedViewModel,
                                settingsRepository = settingsRepository,
                                onBack = {
                                    // 播放页：系统返回键优先弹次级栈，否则回 HOME
                                    if (subStack.isNotEmpty()) subStack.removeAt(subStack.lastIndex)
                                    else if (currentTab != RadioTab.HOME) currentTab = RadioTab.HOME
                                }
                            )
                        }
                    }

                    // 2) 次级页面层（搜索/设置），从右侧推入
                    AnimatedContent(
                        targetState = subStack.lastOrNull(),
                        transitionSpec = {
                            ContentTransform(
                                targetContentEnter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(220)),
                                initialContentExit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(220))
                            )
                        },
                        label = "radioSubPage"
                    ) { subPage ->
                        when (subPage) {
                            RadioSubPage.Search -> RadioSearchScreen(
                                onBack = { subStack.removeAt(subStack.lastIndex) },
                                onStationFound = { url, name ->
                                    sharedViewModel.playUrl(url, name)
                                    subStack.removeAt(subStack.lastIndex)
                                    currentTab = RadioTab.PLAYING
                                },
                                onAddFavorite = { url, name, genre ->
                                    sharedViewModel.addFavorite(url, name, genre)
                                }
                            )
                            RadioSubPage.Import -> RadioImportScreen(
                                onBack = { subStack.removeAt(subStack.lastIndex) },
                                onAddFavorite = { urls, name, genre ->
                                    sharedViewModel.addFavoriteStationLines(name, urls, genre)
                                }
                            )
                            RadioSubPage.Settings -> RadioSettingsScreen(
                                onBack = { subStack.removeAt(subStack.lastIndex) },
                                onNavigateToInterface = { subStack.add(RadioSubPage.InterfaceSettings) },
                                onNavigateToSound = { subStack.add(RadioSubPage.SoundSettings) }
                            )
                            RadioSubPage.InterfaceSettings -> RadioInterfaceSettingsScreen(
                                onBack = { subStack.removeAt(subStack.lastIndex) }
                            )
                            RadioSubPage.SoundSettings -> RadioSoundSettingsScreen(
                                onBack = { subStack.removeAt(subStack.lastIndex) }
                            )
                            null -> Unit
                        }
                    }
                }

                // 3) 电台迷你播放条（仿音乐模式 BottomPlayerBar 风格）
                // 条件：miniBarEnabled 开启 + 正在播放/缓冲中 + 不在播放 tab（播放 tab 全屏）+
                //       不在次级页（避免覆盖搜索/设置页面）— 后者由 Box 内的次级页盖在上面即可
                if (miniBarEnabled && currentTab != RadioTab.PLAYING) {
                    androidx.compose.material3.HorizontalDivider()
                    RadioMiniPlayerBar(
                        viewModel = sharedViewModel,
                        onTapToOpen = { currentTab = RadioTab.PLAYING }
                    )
                }
            }
        }
    }
}

/* ---------- 底部导航 ---------- */

private data class RadioNavItem(
    val tab: RadioTab,
    val label: String,
    val icon: ImageVector
)

@Composable
private fun radioNavItems() = listOf(
    RadioNavItem(RadioTab.HOME, stringResource(R.string.radio_tab_list), Icons.AutoMirrored.Filled.List),
    RadioNavItem(RadioTab.PLAYING, stringResource(R.string.nav_play_toggle), Icons.Filled.PlayArrow),
    RadioNavItem(RadioTab.FAVORITES, stringResource(R.string.radio_tab_favorites), Icons.Filled.Favorite)
)

@Composable
private fun RadioBottomBar(
    currentTab: RadioTab,
    onTabSelected: (RadioTab) -> Unit,
    onCenterDoubleClick: () -> Unit
) {
    NavigationBar {
        radioNavItems().forEachIndexed { index, item ->
            if (index == 1) {
                CenterNavButton(
                    label = item.label,
                    icon = item.icon,
                    selected = currentTab == item.tab,
                    onClick = { onTabSelected(item.tab) },
                    onDoubleClick = onCenterDoubleClick
                )
            } else {
                NavigationBarItem(
                    icon = { Icon(item.icon, contentDescription = item.label) },
                    label = { Text(item.label) },
                    selected = currentTab == item.tab,
                    onClick = { onTabSelected(item.tab) }
                )
            }
        }
    }
}

/* ---------- 中间按钮（单击 = 切 tab，双击 = 切模式） ---------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CenterNavButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, softWrap = false)
    }
}

/* ---------- rememberSaveable 辅助 ---------- */

private val RadioSubStackSaver: androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.snapshots.SnapshotStateList<RadioSubPage>, ArrayList<Int>> =
    androidx.compose.runtime.saveable.Saver(
        save = { list ->
            ArrayList(list.map {
                when (it) {
                    RadioSubPage.Search -> 0
                    RadioSubPage.Import -> 1
                    RadioSubPage.Settings -> 2
                    RadioSubPage.InterfaceSettings -> 3
                    RadioSubPage.SoundSettings -> 4
                }
            })
        },
        restore = { codes ->
            androidx.compose.runtime.mutableStateListOf<RadioSubPage>().apply {
                codes.forEach { add(when (it) {
                    0 -> RadioSubPage.Search
                    1 -> RadioSubPage.Import
                    3 -> RadioSubPage.InterfaceSettings
                    4 -> RadioSubPage.SoundSettings
                    else -> RadioSubPage.Settings
                }) }
            }
        }
    )
