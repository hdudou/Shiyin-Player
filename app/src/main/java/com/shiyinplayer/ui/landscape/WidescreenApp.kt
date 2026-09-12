package com.shiyinplayer.ui.landscape

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil.compose.AsyncImage
import com.shiyinplayer.player.PlaybackState
import com.shiyinplayer.ui.navigation.Screen
import com.shiyinplayer.ui.navigation.leftNavItems
import com.shiyinplayer.ui.navigation.rightNavItems
import com.shiyinplayer.ui.player.PlayerViewModel

/**
 * 宽屏（横屏）布局：左导航栏 + 主内容 + 底部迷你播放条。
 * 曲库/歌单/队列等显示迷你播放条（居中紧凑条，不占满整行），点击进入全屏正在播放；
 * 正在播放页占满内容区（隐藏导航栏与迷你播放条），功能入口与竖屏一致。
 */
@Composable
fun WidescreenScaffold(
    navController: NavHostController,
    playerState: PlaybackState,
    showMiniBar: Boolean,
    onEnterNowPlaying: () -> Unit,
    onSwitchMode: () -> Unit,
    content: @Composable () -> Unit
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val isNowPlaying = currentRoute == Screen.NowPlaying.route

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (!isNowPlaying) {
                NavigationRail(Modifier.fillMaxHeight().width(80.dp)) {
                    (leftNavItems + rightNavItems).forEach { item ->
                        NavigationRailItem(
                            selected = currentRoute == item.screen.route,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    HorizontalDivider()
                    // 模式切换：单击 = 播放/进入正在播放页，双击 = 切换音乐↔收音机模式（与竖屏 ModeSwitchFab 一致）
                    LandscapeModeSwitchButton(
                        onEnterNowPlaying = onEnterNowPlaying,
                        onSwitchMode = onSwitchMode
                    )
                }
                VerticalDivider(Modifier.fillMaxHeight())
            }
            // 显式提供标准主体文字色：横屏内容里未显式指定 color 的文字会继承 LocalContentColor，
            // 若不强制，深色主题下会退化为浅色主题的深灰值，出现黑字看不清
            Surface(
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) { content() }
        }
        if (showMiniBar && !isNowPlaying) {
            HorizontalDivider()
            LandscapeMiniPlayerBar(navController)
        }
    }
}

/**
 * 横屏底部迷你播放条：居中紧凑条（左右留空），小封面 + 标题/艺术家 + 上一首/播放暂停/停止/下一首。
 * 点击条区域（非按钮）进入全屏正在播放；图标与竖屏 BottomPlayerBar 一致，保证风格统一。
 */
@Composable
private fun LandscapeMiniPlayerBar(
    navController: NavController,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state = viewModel.state.value
    val song = state.currentSong ?: return

    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clickable { navController.navigate(Screen.NowPlaying.route) }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (song.albumArtUri != null) {
                    AsyncImage(
                        model = song.albumArtUri,
                        contentDescription = "专辑封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Spacer(Modifier.width(44.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        song.artistName ?: "未知艺术家",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = viewModel::prev, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = viewModel::togglePlay, modifier = Modifier.size(44.dp)) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "播放/暂停",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = viewModel::stop, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Stop, contentDescription = "停止", tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = viewModel::next, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "下一首", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/**
 * 横屏左侧导航的「播放/切换」按钮：单击 = 播放/进入正在播放页，双击 = 切换音乐↔收音机模式。
 * 交互与竖屏 ModeSwitchFab 一致，视觉对齐 NavigationRailItem（图标 + 文字标签 + primary 色调）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LandscapeModeSwitchButton(
    onEnterNowPlaying: () -> Unit,
    onSwitchMode: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onEnterNowPlaying,
                onDoubleClick = onSwitchMode
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "播放/切换",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                "播放/切换",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}