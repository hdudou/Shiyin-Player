package com.shiyinplayer.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shiyinplayer.data.metadata.MergedLine

/**
 * 滚动歌词视图：当前行加粗高亮并精确垂直居中（上下留空），随播放进度自动滚动，
 * 点击行跳转播放位置；滚动区域上下边缘渐隐/渐显（竖屏与横屏共用）。
 *
 * 实现说明：此 Compose 版本的 LazyListState 缺少按像素 scrollBy/animateScrollBy，
 * 无法把当前行精确移至视口正中，故改用 Column + verticalScroll(ScrollState)，
 * 并预计算每行累计高度，用 `animateScrollTo` 精确定位居中。
 */
@Composable
fun LyricsView(
    lines: List<MergedLine>,
    currentIndex: Int,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "",
    showTranslation: Boolean = true
) {
    if (lines.isEmpty()) {
        Text(
            emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.fillMaxSize(),
            textAlign = TextAlign.Center
        )
        return
    }
    BoxWithConstraints(modifier) {
        val bg = MaterialTheme.colorScheme.background
        val density = LocalDensity.current
        // 上下留空 = 半个视口高度，保证首末两行也能滚到垂直居中
        val topPad = remember(constraints.maxHeight) {
            with(density) { (constraints.maxHeight / 2).toDp() }
        }
        val baseH = 40.dp
        val transH = 22.dp
        // F2-4：译文关闭时每行按单行高度计算，无需保留翻译行高
        val rowHeights = remember(lines, showTranslation) {
            lines.map { line ->
                baseH + if (showTranslation && line.translated?.takeIf { it.isNotBlank() } != null) transH else 0.dp
            }
        }
        // 每行在内容中的起始累计高度（px），scroll = cum[i] 即当前行顶边贴视口正中
        val cumPx = remember(rowHeights) {
            with(density) {
                var acc = 0
                rowHeights.map { h -> acc.also { acc += h.roundToPx() } }
            }
        }
        val scrollState = rememberScrollState()
        // BT-自动滚动与手动滚动解耦：用户手动滚动进行中暂停自动跟随（不强制拉回）；
        // keyed 于 currentIndex，行未变不重复触发；行高/累计高度已 remember 复用，免重复计算。
        LaunchedEffect(currentIndex) {
            if (!scrollState.isScrollInProgress && currentIndex >= 0 && currentIndex < cumPx.size) {
                scrollState.animateScrollTo(cumPx[currentIndex])
            }
        }
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(topPad))
                lines.forEachIndexed { index, line ->
                    val active = index == currentIndex
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeek(line.timeMs) }
                            .height(rowHeights[index]),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                        line.translated?.takeIf { showTranslation && it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                        }
                    }
                }
                Spacer(Modifier.height(topPad))
            }
            // 歌词滚动时上下边缘渐隐/渐显
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0f))))
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg)))
            )
        }
    }
}