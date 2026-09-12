package com.shiyinplayer.ui.more

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.shiyinplayer.R
import com.shiyinplayer.ui.navigation.Screen

private data class MoreCategory(val label: String, val summary: String, val icon: ImageVector, val route: String)

/** 更多 = 9 分类入口（P3 需求 12/13）：设置门户拆散，各分类直达对应子屏。 */
@Composable
private fun moreCategories(): List<MoreCategory> = listOf(
    MoreCategory(stringResource(R.string.more_interface), stringResource(R.string.more_interface_summary), Icons.Default.Palette, Screen.SettingsInterface.route),
    MoreCategory(stringResource(R.string.more_library), stringResource(R.string.more_library_summary), Icons.Default.LibraryMusic, Screen.SettingsLibrary.route),
    MoreCategory(stringResource(R.string.more_playback), stringResource(R.string.more_playback_summary), Icons.Default.PlayCircle, Screen.SettingsPlayback.route),
    MoreCategory(stringResource(R.string.more_sources), stringResource(R.string.more_sources_summary), Icons.Default.NetworkCheck, Screen.SettingsSources.route),
    MoreCategory(stringResource(R.string.more_metadata), stringResource(R.string.more_metadata_summary), Icons.Default.Tune, Screen.SettingsMetadata.route),
    MoreCategory(stringResource(R.string.more_equalizer), stringResource(R.string.more_equalizer_summary), Icons.Default.Equalizer, Screen.Equalizer.route),
    MoreCategory(stringResource(R.string.more_sound), stringResource(R.string.more_sound_summary), Icons.Default.Hearing, Screen.SettingsSound.route),
    MoreCategory(stringResource(R.string.more_stats), stringResource(R.string.more_stats_summary), Icons.Default.BarChart, Screen.Stats.route),
    MoreCategory(stringResource(R.string.more_integration), stringResource(R.string.more_integration_summary), Icons.Default.SettingsInputComponent, Screen.SettingsIntegration.route),
    MoreCategory(stringResource(R.string.more_export), stringResource(R.string.more_export_summary), Icons.Default.FileDownload, Screen.DataExport.route),
    MoreCategory(stringResource(R.string.more_import), stringResource(R.string.more_import_summary), Icons.Default.FileUpload, Screen.DataImport.route),
    MoreCategory(stringResource(R.string.more_about), stringResource(R.string.more_about_summary), Icons.Default.Info, Screen.About.route)
)

@Composable
fun MoreScreen(navController: NavController) {
    // 横屏按可用宽度自适应分列（平板更宽 → 每行更多项）；竖屏保持纵向列表，控件风格一致。
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (isLandscape) {
        LandscapeMoreGrid(navController)
    } else {
        PortraitMoreList(navController)
    }
}

/** 竖屏纵向分类列表（与竖屏一致）。 */
@Composable
private fun PortraitMoreList(navController: NavController) {
    val categories = moreCategories()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.more_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        items(categories.size) { i ->
            val cat = categories[i]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navController.navigate(cat.route) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(cat.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(cat.label, style = MaterialTheme.typography.bodyLarge)
                    Text(cat.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()
        }
        item {
            HorizontalDivider()
        }
    }
}

/**
 * 横屏自适应九宫格：根据平板的可用宽度自动决定每行项目数量（GridCells.Adaptive）。
 * 项目以卡片呈现，保留图标 + 名称 + 说明，入口与竖屏完全一致。
 */
@Composable
private fun LandscapeMoreGrid(navController: NavController) {
    val categories = moreCategories()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 220.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                stringResource(R.string.more_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        items(categories.size) { i ->
            val cat = categories[i]
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                // 固定卡片高度：说明文字按列宽换行 1~2 行会导致各卡片高度不一（平板上尤甚），
                // 统一高度保证九宫格每一行视觉整齐
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clickable { navController.navigate(cat.route) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(cat.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(cat.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            cat.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
    }
}