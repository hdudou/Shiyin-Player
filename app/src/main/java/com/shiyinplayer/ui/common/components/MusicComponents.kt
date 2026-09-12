package com.shiyinplayer.ui.common.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.shiyinplayer.data.model.Album
import com.shiyinplayer.data.model.Artist
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.common.LocalListDisplayPrefs
import com.shiyinplayer.util.TimeUtils

/** 歌曲封面：内嵌/在线封面优先，无封面时用字母占位。 */
@Composable
fun SongCover(
    song: Song,
    size: Int = 40,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(6.dp)
    val prefs = LocalListDisplayPrefs.current
    val letter = song.title.firstOrNull()?.uppercase() ?: "♪"
    if (prefs.showEmbedArt && song.albumArtUri != null) {
        // AR-封面加载失败/加载中回退到字母占位，避免破图/空白。
        val placeholder: @Composable () -> Unit = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { Text(letter, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        SubcomposeAsyncImage(
            model = song.albumArtUri,
            contentDescription = "封面",
            contentScale = ContentScale.Crop,
            loading = { placeholder() },
            error = { placeholder() },
            modifier = modifier.size(size.dp).clip(shape)
        )
    } else {
        Box(
            modifier = modifier
                .size(size.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                letter,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 歌曲列表项：封面（可选）+ 标题/艺术家（双行可选）+ 时长/评分。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    showCheckbox: Boolean = false,
    checked: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null
) {
    val prefs = LocalListDisplayPrefs.current
    val clickable = if (onLongPress != null && !showCheckbox) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(horizontal = 12.dp, vertical = prefs.verticalPadding.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (prefs.showArt) {
            SongCover(song)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                fontWeight = if (isCurrent) FontWeight.Bold else null,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            // 2026-08-19：始终在歌名下用小字显示歌手名（不依赖 twoLine 设置）
            Text(
                song.artistName?.takeIf { it.isNotBlank() } ?: "未知艺术家",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            TimeUtils.formatDuration(song.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showCheckbox && onCheckedChange != null) {
            Spacer(Modifier.width(4.dp))
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** 专辑卡片：封面优先，无封面用字母占位。 */
@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clickable(onClick = onClick).padding(4.dp)) {
        // 封面容器：右下角叠加「在库歌曲数」徽标（需求 2026-08-23）
        Box(
            Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            if (album.albumArtUri != null) {
                // AR-封面加载失败/加载中回退到纯色占位，避免破图/空白。
                val phSurface = MaterialTheme.colorScheme.surfaceVariant
                SubcomposeAsyncImage(
                    model = album.albumArtUri,
                    contentDescription = "专辑封面",
                    contentScale = ContentScale.Crop,
                    loading = { Box(Modifier.fillMaxSize().background(phSurface)) },
                    error = { Box(Modifier.fillMaxSize().background(phSurface)) },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(phSurface)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(album.name.firstOrNull()?.toString() ?: "?", style = MaterialTheme.typography.headlineMedium)
                }
            }
            Text(
                "${album.songCount} 首",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Text(album.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        Text(album.artistName ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
fun ArtistRow(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f))
        Text(
            "${artist.songCount} 首",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** F3-3：艺术家卡片（图片墙/网格视图用）——以首字头像着色占位 + 名称 + 曲目数。 */
@Composable
fun ArtistActionCard(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary
    )
    val index = Math.floorMod(artist.name.hashCode(), palette.size)
    val bg = palette[index]
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(bg.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                artist.name.firstOrNull()?.toString()?.uppercase() ?: "?",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            artist.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${artist.songCount} 首",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}