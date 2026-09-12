package com.shiyinplayer.ui.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.R

/**
 * 电台模式迷你播放条（仿音乐模式 BottomPlayerBar 风格）。
 * 控件布局：状态图标 | 文案 | 上一首 | 播放/暂停 | 下一首
 * 显示条件：
 *  1. radioState.isPlaying || radioState.isBuffering
 *  2. miniBarEnabled（DataStore mini_bar_enabled）
 *  3. 当前不在播放 tab（播放 tab 全屏显示）
 *  4. 当前不在次级页（搜索/设置）—— 由 NavGraph 控制 onPlaying 切换
 * 点击整条 → 跳到播放 tab
 */
@Composable
fun RadioMiniPlayerBar(
    viewModel: RadioViewModel,
    onTapToOpen: () -> Unit
) {
    val radioState by viewModel.radioState.collectAsStateWithLifecycle()
    val hasContent = radioState.isPlaying || radioState.isBuffering
    if (!hasContent) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTapToOpen)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 状态指示（缓冲中显示转圈；有 logo 时显示电台 logo）
        if (radioState.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            val logoUrl = radioState.coverUrl
            if (!logoUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = logoUrl,
                    contentDescription = stringResource(R.string.radio_now_playing),
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    error = androidx.compose.ui.graphics.painter.ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = stringResource(R.string.radio_now_playing),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = radioState.stationName ?: stringResource(R.string.radio_unknown_station),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (radioState.isBuffering) stringResource(R.string.radio_buffering)
                else radioState.subtitle ?: RadioDictTranslate.text(radioState.genre ?: "").ifEmpty { stringResource(R.string.radio_fallback_subtitle) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 缓冲进度（仅缓冲时可见）
            if (radioState.isBuffering) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        IconButton(onClick = { viewModel.playPreviousFavorite() }) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.action_previous)
            )
        }
        IconButton(onClick = { viewModel.togglePlayPause() }) {
            Icon(
                if (radioState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.action_play_pause)
            )
        }
        IconButton(onClick = { viewModel.playNextFavorite() }) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.action_next)
            )
        }
    }
}

