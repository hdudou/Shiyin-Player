package com.shiyinplayer.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.R
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.util.TimeUtils

/** F2-3：播放统计界面——概览 + 最常播放柱状图 + 最近播放。 */
@Composable
fun StatsScreen(navController: NavController? = null, viewModel: StatsViewModel = hiltViewModel()) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val top by viewModel.topPlayed.collectAsStateWithLifecycle()
    val recent by viewModel.recentlyPlayed.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = { navController?.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatCard(stringResource(R.string.stats_total_songs), "${stats.totalSongs}", Modifier.weight(1f))
                    StatCard(stringResource(R.string.stats_total_plays), formatCount(stats.totalPlays, stringResource(R.string.stats_count_wan)), Modifier.weight(1f))
                    StatCard(stringResource(R.string.stats_played_songs), "${stats.playedSongs}", Modifier.weight(1f))
                }
            }
            item { SectionTitle(stringResource(R.string.stats_top10)) }
            items(top, key = { it.id }) { song ->
                TopPlayedBar(song = song, maxCount = top.firstOrNull()?.playCount ?: 1)
            }
            if (top.isEmpty()) {
                item { Text(stringResource(R.string.stats_no_record), style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Spacer(Modifier.height(8.dp))
                SectionTitle(stringResource(R.string.smart_recent))
            }
                items(recent, key = { "r${it.id}" }) { song ->
                    RecentRow(song = song, onPlay = {
                        val idx = recent.indexOf(song)
                        if (idx >= 0) viewModel.play(recent, idx)
                    })
            }
            if (recent.isEmpty()) {
                item { Text(stringResource(R.string.stats_no_recent), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 水平柱状图行：歌名 + 按播放次数比例渲染的彩色条 + 次数。 */
@Composable
private fun TopPlayedBar(song: Song, maxCount: Int) {
    val max = maxCount.coerceAtLeast(1)
    val fraction = (song.playCount.toFloat() / max).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            song.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(120.dp)
        )
        Box(
            Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Text(
            "${song.playCount}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp).padding(start = 6.dp)
        )
    }
}

@Composable
private fun RecentRow(song: Song, onPlay: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 8.dp)
    ) {
        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (song.artistName?.ifBlank { null } ?: stringResource(R.string.unknown_artist)),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(TimeUtils.formatDuration(song.durationMs), style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
}

private fun formatCount(n: Long, wanFormat: String): String = when {
    n >= 10000 -> String.format(wanFormat, n / 10000.0)
    else -> "$n"
}