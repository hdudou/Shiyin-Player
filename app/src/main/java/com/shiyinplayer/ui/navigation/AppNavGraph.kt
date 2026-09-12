package com.shiyinplayer.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import android.content.res.Configuration
import android.net.Uri
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.shiyinplayer.ui.albums.AlbumsScreen
import com.shiyinplayer.ui.albumsdetail.AlbumDetailScreen
import com.shiyinplayer.ui.artists.ArtistsScreen
import com.shiyinplayer.ui.artistsdetail.ArtistDetailScreen
import com.shiyinplayer.ui.equalizer.EqualizerScreen
import com.shiyinplayer.ui.folders.FoldersScreen
import com.shiyinplayer.ui.foldersdetail.FolderDetailScreen
import com.shiyinplayer.ui.library.LibraryScreen
import com.shiyinplayer.ui.library.DuplicateCleanupScreen
import com.shiyinplayer.ui.smart.SmartPlaylistScreen
import com.shiyinplayer.ui.landscape.WidescreenScaffold
import com.shiyinplayer.ui.more.MoreScreen
import com.shiyinplayer.ui.more.DataExportScreen
import com.shiyinplayer.ui.more.DataImportScreen
import com.shiyinplayer.ui.network.NetworkScreen
import com.shiyinplayer.ui.nowplaying.NowPlayingScreen
import com.shiyinplayer.ui.player.BottomPlayerBar
import com.shiyinplayer.ui.player.PlayerViewModel
import com.shiyinplayer.ui.playlists.PlaylistsScreen
import com.shiyinplayer.ui.playlistsdetail.PlaylistDetailScreen
import com.shiyinplayer.ui.queue.QueueScreen
import com.shiyinplayer.ui.search.SearchScreen
import com.shiyinplayer.ui.stats.StatsScreen
import com.shiyinplayer.ui.settings.InterfaceSettingsScreen
import com.shiyinplayer.ui.settings.IntegrationSettingsScreen
import com.shiyinplayer.ui.settings.LibrarySettingsScreen
import com.shiyinplayer.ui.settings.MetadataSettingsScreen
import com.shiyinplayer.ui.settings.MetadataSourcesScreen
import com.shiyinplayer.ui.settings.PlaybackSettingsScreen
import com.shiyinplayer.ui.settings.SettingsRepository
import com.shiyinplayer.ui.settings.SettingsViewModel
import com.shiyinplayer.ui.settings.SoundSettingsScreen
import com.shiyinplayer.ui.settings.SourcesSettingsScreen
import com.shiyinplayer.ui.songs.SongsScreen
import com.shiyinplayer.ui.theme.MusicPlayerTheme
import com.shiyinplayer.ui.zerotier.ZeroTierScreen
import com.shiyinplayer.ui.about.AboutScreen
import com.shiyinplayer.ui.common.ListDisplayPrefs
import com.shiyinplayer.ui.common.LocalListDisplayPrefs
import kotlinx.coroutines.launch

internal data class MainNavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

// 底部导航：左侧 曲库/歌单，右侧 队列/更多；中键 ▶ 进全屏正在播放（需求 1/2/6）
internal val leftNavItems = listOf(
    MainNavItem(Screen.Library, "曲库", Icons.Filled.MusicNote),
    MainNavItem(Screen.Playlists, "歌单", Icons.Filled.Star)
)
internal val rightNavItems = listOf(
    MainNavItem(Screen.Queue, "队列", Icons.AutoMirrored.Filled.QueueMusic),
    MainNavItem(Screen.More, "更多", Icons.Filled.MoreHoriz)
)

/** 隐藏底部导航栏的路由（竖屏）：全屏正在播放 + 「更多」及其全部下级页面（设置 7 分类/均衡器/网络/ZeroTier/关于）。 */
private val hideBottomBarRoutes = setOf(
    Screen.NowPlaying.route,
    Screen.More.route,
    Screen.SettingsInterface.route,
    Screen.SettingsLibrary.route,
    Screen.SettingsPlayback.route,
    Screen.SettingsSources.route,
    Screen.SettingsMetadata.route,
    Screen.SettingsMetadataSources.route,
    Screen.SettingsSound.route,
    Screen.SettingsIntegration.route,
    Screen.Equalizer.route,
    Screen.About.route,
    Screen.Stats.route,
    Screen.Duplicates.route,
    Screen.Network.route,
    Screen.ZeroTier.route,
    Screen.DataExport.route,
    Screen.DataImport.route
)

/** 隐藏横屏迷你播放条的路由（横屏）：全屏正在播放 + 设置及下级页面；「更多」页保留迷你条以便快速切回正在播放。 */
private val hideLandscapeMiniBarRoutes = setOf(
    Screen.NowPlaying.route,
    Screen.SettingsInterface.route,
    Screen.SettingsLibrary.route,
    Screen.SettingsPlayback.route,
    Screen.SettingsSources.route,
    Screen.SettingsMetadata.route,
    Screen.SettingsMetadataSources.route,
    Screen.SettingsSound.route,
    Screen.SettingsIntegration.route,
    Screen.Equalizer.route,
    Screen.About.route,
    Screen.Stats.route,
    Screen.Duplicates.route,
    Screen.ZeroTier.route,
    Screen.DataExport.route,
    Screen.DataImport.route
)

/** 全应用导航图（T10）：底部导航（竖屏）或宽屏布局（横屏）+ 全部路由 + 底部播放条。 */
@Composable
fun AppNavGraph() {
    val navController: NavHostController = rememberNavController()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val prefs by settingsViewModel.prefs.collectAsStateWithLifecycle()
    val miniBarEnabled by settingsViewModel.miniBarEnabled.collectAsStateWithLifecycle()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val settingsRepository = settingsViewModel.settingsRepository
    val playerManager = playerViewModel.playerManagerRef
    val radioPlayer = hiltViewModel<com.shiyinplayer.ui.radio.RadioViewModel>().radioPlayerRef

    // 共享「播放/切换」行为：单击进入正在播放页；双击切换音乐↔收音机模式（竖屏/横屏同一 handler）
    val enterNowPlaying: () -> Unit = {
        navController.navigate(Screen.NowPlaying.route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true; restoreState = true
        }
    }
    val switchToRadio: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            // 1. 先保存音乐播放状态（切到电台模式后会读取这个标志）
            settingsRepository.setMusicWasPlaying(playerManager.isPlaying)
            // 2. 切模式：RadioViewModel.init 会自动恢复电台（电台先就位）
            settingsRepository.setAppMode("radio")
            // 3. 电台就位后再停音乐（避免空窗期）
            playerManager.pause()
        }
    }

    // 冷启动：全新安装首次启动落「曲库」并打标记；之后的正常冷启动默认进入「正在播放」界面，
    // 为其预置「歌单 → 正在播放」初始返回栈，使首次从正在播放点返回键退到歌单 tab。
    // rememberSaveable 防旋转重建时重复跳转。
    var coldStartDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(navController) {
        if (coldStartDone) return@LaunchedEffect
        coldStartDone = true
        if (!settingsRepository.firstLaunchDoneSync()) {
            // 全新安装首启：落在 startDestination=Library（曲库），不打「歌单→正在播放」栈
            scope.launch { settingsRepository.setFirstLaunchDone() }
            return@LaunchedEffect
        }
        navController.navigate(Screen.Playlists.route) {
            popUpTo(navController.graph.startDestinationId) { inclusive = true }
        }
        navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
    }

    val listShowArt by settingsViewModel.listShowArt.collectAsStateWithLifecycle()
    val listTwoLine by settingsViewModel.listTwoLine.collectAsStateWithLifecycle()
    val listDensity by settingsViewModel.listDensity.collectAsStateWithLifecycle()
    val showEmbedArt by settingsViewModel.showEmbedArt.collectAsStateWithLifecycle()
    val trackFormat by settingsViewModel.trackFormat.collectAsStateWithLifecycle()

    CompositionLocalProvider(
        LocalListDisplayPrefs provides ListDisplayPrefs(
            showArt = listShowArt,
            twoLine = listTwoLine,
            density = listDensity,
            showEmbedArt = showEmbedArt,
            trackFormat = trackFormat
        )
    ) {
        MusicPlayerTheme(prefs = prefs) {
            if (isLandscape) {
                // 宽屏模式：左导航栏 + 内容 + 底部迷你播放条（曲库/歌单/队列显示，点击进全屏正在播放）
                val lsBackStack by navController.currentBackStackEntryAsState()
                val lsRoute = lsBackStack?.destination?.route
                val showMiniBar = playerState.currentSong != null && miniBarEnabled &&
                    lsRoute != null && !hideLandscapeMiniBarRoutes.contains(lsRoute)
                WidescreenScaffold(
                    navController = navController,
                    playerState = playerState,
                    showMiniBar = showMiniBar,
                    onEnterNowPlaying = enterNowPlaying,
                    onSwitchMode = switchToRadio
                ) {
                    AppNavHost(navController)
                }
            } else {
                val backStack by navController.currentBackStackEntryAsState()
                val currentRoute = backStack?.destination?.route
                // 全屏正在播放、「更多」及其全部下级页面（设置分类/均衡器/网络/ZeroTier/关于）均隐藏底部导航栏（需求 5/10/2）
                val hideBottom = currentRoute == null || hideBottomBarRoutes.contains(currentRoute)
                Scaffold(
                    bottomBar = {
                        if (!hideBottom) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(72.dp)
                                    .background(MaterialTheme.colorScheme.surface),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                leftNavItems.forEach { item -> BottomEntry(item, currentRoute, navController, Modifier.weight(1f)) }
                                Box(Modifier.weight(1f).height(64.dp), contentAlignment = Alignment.Center) {
                                    ModeSwitchFab(
                                        label = "播放/切换",
                                        onClick = enterNowPlaying,
                                        onDoubleClick = switchToRadio
                                    )
                                }
                                rightNavItems.forEach { item -> BottomEntry(item, currentRoute, navController, Modifier.weight(1f)) }
                            }
                        }
                    }
                ) { innerPadding ->
                    Column(Modifier.fillMaxSize().padding(innerPadding)) {
                        // weight(1f) 而非 fillMaxSize()：后者在 Column 内贪婪占满全部高度，
                        // 会把下方播放条挤出可视区。weight 让内容占剩余空间。
                        // innerPadding 施加在 Column 上而非仅内容：否则播放条会落在
                        // 底栏(NavigationBar)下层被遮挡。
                        Box(Modifier.weight(1f)) {
                            AppNavHost(navController, Modifier.fillMaxSize())
                        }
                        if (playerState.currentSong != null && miniBarEnabled && !hideBottom) {
                            HorizontalDivider()
                            BottomPlayerBar(navController)
                        }
                    }
                }
            }
        }
    }
}

/** 全部路由（竖/横屏共用）。 */
@Composable
private fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Screen.Library.route,
        modifier = modifier
    ) {
        composable(Screen.Library.route) { LibraryScreen(navController) }
        composable(Screen.Songs.route) { SongsScreen() }
        composable(Screen.Albums.route) { AlbumsScreen(navController) }
        composable(Screen.Artists.route) { ArtistsScreen(navController) }
        composable(Screen.Playlists.route) { PlaylistsScreen(navController) }
        composable(Screen.More.route) { MoreScreen(navController) }
        composable(Screen.NowPlaying.route) { NowPlayingScreen(navController) }
        composable(Screen.Queue.route) { QueueScreen() }
        composable(Screen.Folders.route) { FoldersScreen(navController) }
        composable(Screen.Search.route) { SearchScreen(navController) }
        composable(Screen.ZeroTier.route) { ZeroTierScreen() }
        composable(Screen.Network.route) { NetworkScreen() }
        composable(Screen.Equalizer.route) { EqualizerScreen() }
        composable(Screen.About.route) { AboutScreen(navController) }
        composable(Screen.Stats.route) { StatsScreen(navController) }
        composable(Screen.Duplicates.route) { DuplicateCleanupScreen(navController) }
        composable(
            Screen.AlbumDetail.route,
            arguments = listOf(
                navArgument("albumName") { type = NavType.StringType },
                navArgument("artist") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val name = Uri.decode(entry.arguments?.getString("albumName").orEmpty())
            val artist = Uri.decode(entry.arguments?.getString("artist").orEmpty()).ifEmpty { null }
            AlbumDetailScreen(albumName = name, artistName = artist, navController = navController)
        }
        composable(
            Screen.ArtistDetail.route,
            arguments = listOf(navArgument("artistName") { type = NavType.StringType })
        ) { entry ->
            val name = Uri.decode(entry.arguments?.getString("artistName").orEmpty())
            ArtistDetailScreen(artistName = name, navController = navController)
        }
        composable(
            Screen.PlaylistDetail.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("playlistId") ?: 0L
            PlaylistDetailScreen(playlistId = id, navController = navController)
        }
        composable(
            Screen.FolderDetail.route,
            arguments = listOf(navArgument("folderName") { type = NavType.StringType })
        ) { entry ->
            val name = Uri.decode(entry.arguments?.getString("folderName").orEmpty())
            FolderDetailScreen(folderName = name, navController = navController)
        }
        composable(
            Screen.SmartPlaylist.route,
            arguments = listOf(navArgument("type") { type = NavType.StringType })
        ) { entry ->
            val type = entry.arguments?.getString("type") ?: "recent"
            SmartPlaylistScreen(type = type, navController = navController)
        }
        composable(Screen.SettingsInterface.route) { InterfaceSettingsScreen(navController) }
        composable(Screen.SettingsLibrary.route) { LibrarySettingsScreen(navController) }
        composable(Screen.SettingsPlayback.route) { PlaybackSettingsScreen(navController) }
        composable(Screen.SettingsSources.route) { SourcesSettingsScreen(navController) }
        composable(Screen.SettingsMetadata.route) { MetadataSettingsScreen(navController) }
        composable(Screen.SettingsMetadataSources.route) { MetadataSourcesScreen(navController) }
        composable(Screen.SettingsSound.route) { SoundSettingsScreen(navController) }
        composable(Screen.SettingsIntegration.route) { IntegrationSettingsScreen(navController) }
        composable(Screen.DataExport.route) { DataExportScreen(navController) }
        composable(Screen.DataImport.route) { DataImportScreen(navController) }

    }
}

@Composable
private fun BottomEntry(
    item: MainNavItem,
    currentRoute: String?,
    navController: NavHostController,
    modifier: Modifier
) {
    val selected = currentRoute == item.screen.route
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .clickable {
                navController.navigate(item.screen.route) {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(24.dp))
        Text(item.label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun PlayFab(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "播放",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(30.dp)
        )
    }
}

/**
 * 可切换模式的播放按钮：单击 = 播放/进入正在播放页，双击 = 切换模式。
 * 样式与收音机模式 CenterNavButton 一致：无圆形背景，仅图标+文字+色调。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeSwitchFab(
    label: String,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick
            )
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false
        )
    }
}