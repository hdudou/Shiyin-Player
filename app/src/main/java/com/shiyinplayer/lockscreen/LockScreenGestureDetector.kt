package com.shiyinplayer.lockscreen

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private const val TAP_TIMEOUT_MS = 250L
private const val TAP_MAX_MOVE_DP = 20f
private const val SWIPE_VERTICAL_DP = 120f
private const val SWIPE_HORIZONTAL_DP = 100f

/**
 * 锁屏叠加层手势检测：
 * - 整屏任意点检测
 * - 单击：切换效果
 * - 上滑 (>120dp)：触发系统解锁
 * - 左/右滑 (>100dp)：切歌/切台
 *
 * 使用 awaitFirstDown() 等待真正的手指 DOWN 事件，避免 MOVE/UP 残留事件误触发 tap。
 */
@Composable
fun LockScreenGestureDetector(
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    content: @Composable () -> Unit = {}
) {
    val density = LocalDensity.current
    val thresholdVerticalPx = with(density) { SWIPE_VERTICAL_DP.dp.toPx() }
    val thresholdHorizontalPx = with(density) { SWIPE_HORIZONTAL_DP.dp.toPx() }
    val tapMaxMovePx = with(density) { TAP_MAX_MOVE_DP.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // 使用 Compose 内置 awaitFirstDown()，阻塞直到真正的 DOWN 事件
                        val down = awaitFirstDown(requireUnconsumed = false)

                        val downTime = System.currentTimeMillis()
                        var totalDx = 0f
                        var totalDy = 0f
                        var decided = false

                        while (!decided) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                // 手指抬起：判定是否为 tap
                                val elapsed = System.currentTimeMillis() - downTime
                                if (elapsed < TAP_TIMEOUT_MS
                                    && abs(totalDx) < tapMaxMovePx
                                    && abs(totalDy) < tapMaxMovePx
                                ) {
                                    onTap()
                                }
                                decided = true
                            } else {
                                // 手指仍在移动：判定滑动
                                val dx = change.position.x - change.previousPosition.x
                                val dy = change.position.y - change.previousPosition.y
                                totalDx += dx
                                totalDy += dy
                                if (abs(totalDy) > thresholdVerticalPx && totalDy < 0) {
                                    onSwipeUp()
                                    decided = true
                                } else if (abs(totalDx) > thresholdHorizontalPx) {
                                    if (totalDx < 0) onSwipeLeft() else onSwipeRight()
                                    decided = true
                                }
                                change.consume()
                            }
                        }
                    }
                }
            }
    ) {
        content()
    }
}
