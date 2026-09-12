package com.shiyinplayer.lockscreen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ===== 锁屏动态效果：时间演化引擎(L1) + 频谱分层(L2) + 光学合成(L3) =====
// 全部效果由「时间积分 + 频谱分层 + 节拍衰减」驱动，而非静态帧。
// 0 = 光之呼吸 / 1 = 3D 深空穿越 / 2 = 黑胶复古赛博 / 3 = 音频隧道 /
// 4 = 满屏流星雨 / 5 = 满屏宇宙星云 / 6 = 极光流场 / 7 = 罗盘螺旋 / 8 = 银河圆盘

/** 公共效果总数（同步于路由与设置约束） */
const val LOCKSCREEN_EFFECT_COUNT = 9

/** 各索引效果名（与路由一一对应，用于叠加层调试标签） */
val LOCKSCREEN_EFFECT_NAMES = listOf(
    "光之呼吸", "3D深空穿越", "黑胶复古赛博", "音频隧道", "满屏流星雨",
    "满屏星云", "极光流场", "罗盘螺旋", "银河圆盘"
)

private val Cyan = Color(0xFF00E5FF)
private val Magenta = Color(0xFFFF5DFF)
private val Green = Color(0xFF34FFC0)
private val Violet = Color(0xFF8A7BFF)
private val Warm = Color(0xFFFF9A3C)
private val Teal = Color(0xFF39E6B0)

/** 效果路由。0~9 对应上方注释顺序。 */
@Composable
fun LockScreenVisualEffectRouter(
    effectIndex: Int,
    frame: BandFrame,
    timeMs: Long,
    isPlaying: Boolean,
    title: String,
    modifier: Modifier = Modifier
) {
    when (effectIndex) {
        1 -> CSpaceDrift(frame, timeMs, isPlaying, modifier)
        2 -> FVinylNeon(frame, timeMs, isPlaying, modifier)
        3 -> GAudioTunnel(frame, timeMs, isPlaying, modifier)
        4 -> HMeteorShower(frame, timeMs, isPlaying, modifier)
        5 -> INebula(frame, timeMs, isPlaying, modifier)
        6 -> JAuroraFluid(frame, timeMs, isPlaying, modifier)
        7 -> LVortexSpiral(frame, timeMs, isPlaying, modifier)
        8 -> MGalaxyDisc(frame, timeMs, isPlaying, modifier)
        else -> BGlowBreath(frame, timeMs, isPlaying, modifier)
    }
}

// ===== 节拍检测（低频谱通量 + 衰减包络） =====
private class BeatDetector {
    var pulse = 0f; private set
    private var bassEma = 0f

    /** 每帧调用；bass 瞬时冲高即触发脉冲，随后按 dt 衰减（约 0.4s 归零） */
    fun update(bass: Float, dt: Float) {
        bassEma += (bass - bassEma) * (dt * 12f).coerceIn(0f, 1f)
        val flux = bass - bassEma
        if (bass > 0.05f && flux > 0.05f) pulse = 1f
        pulse = (pulse - dt * 2.4f).coerceAtLeast(0f)
    }
}

// ===== 通用光学合成工具（L3） =====

/** 全屏深空渐变底 */
private fun DrawScope.drawDeepSpace() {
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color(0xFF141226), Color(0xFF0A0912), Color(0xFF050408)),
            center = Offset(size.width / 2f, size.height * 0.42f),
            radius = size.width.coerceAtLeast(size.height) * 0.9f
        )
    )
}

/** 边缘暗角 */
private fun DrawScope.drawVignette(strength: Float = 0.6f) {
    // 已按需求停用暗角：不再用径向黑渐变压暗四周，避免锁屏出现
    // 「中间亮圆、四周暗带」的暗区圆，确保全屏效果铺满显示
}

private fun sinF(v: Float): Float = sin(v.toDouble()).toFloat()
private fun cosF(v: Float): Float = cos(v.toDouble()).toFloat()

/**
 * 方案 0：光之呼吸（辉光大气的克制高级感）。
 * 专辑锚色光晕随低频呼吸，中心亮、边缘暗；无顶部信息条，纯大气呼吸。
 */
@Composable
private fun BGlowBreath(frame: BandFrame, timeMs: Long, isPlaying: Boolean, modifier: Modifier) {
    val lastMs = remember { longArrayOf(timeMs) }
    val playSmooth = remember { floatArrayOf(0f) }
    val hueShift = remember { floatArrayOf(0.72f) }

    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h * 0.46f
        val dt = ((timeMs - lastMs[0]) / 1000f).coerceIn(0f, 0.05f)
        lastMs[0] = timeMs

        val target = if (isPlaying) 1f else 0f
        playSmooth[0] += (target - playSmooth[0]) * dt.coerceAtLeast(0.001f) * 3f
        val playing = playSmooth[0]

        drawDeepSpace()

        // 色相随时间和中频轻微漂移（避免色彩呆板）
        hueShift[0] += dt * (0.02f + frame.mid * 0.03f)
        val baseHue = (278f + hueShift[0] * 18f) % 360f
        val accent = if (frame.treble > 0.35f) {
            Color.hsl(baseHue, 0.5f, 0.66f)
        } else {
            Color.hsl((baseHue + 40f) % 360f, 0.5f, 0.62f)
        }
        val core = Color.hsl(baseHue, 0.35f, 0.82f)

        // 中心光晕随低频呼吸
        val breath = 0.80f + 0.32f * frame.bass + 0.05f * sinF(timeMs.toFloat() * 0.0035f)
        val baseR = w.coerceAtMost(h) * 0.22f
        val glowR = baseR * breath

        drawCircle(
            Brush.radialGradient(
                colors = listOf(
                    core.copy(alpha = 0.28f * playing),
                    accent.copy(alpha = 0.16f * playing),
                    Color.Transparent),
                center = Offset(cx, cy)
            ),
            radius = glowR * 1.15f,
            center = Offset(cx, cy)
        )
        drawCircle(
            Brush.radialGradient(
                colors = listOf(
                    core.copy(alpha = 0.55f * playing),
                    accent.copy(alpha = 0.25f * playing),
                    Color.Transparent),
                center = Offset(cx, cy)
            ),
            radius = glowR * 0.7f,
            center = Offset(cx, cy)
        )
        // 核心亮点
        drawCircle(
            Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.85f * playing), Color.Transparent),
                center = Offset(cx, cy)
            ),
            radius = glowR * 0.22f + 4f,
            center = Offset(cx, cy)
        )

        drawVignette(0.6f)
    }
}

// ===== 效果 1：3D 深空穿越（伪 3D 星云 + 视差） =====
private class Star(
    var normX: Float = 0f, // -1..1
    var normY: Float = 0f, // -1..1
    var z: Float = 1f,      // 0(近)..1(远)
    var size: Float = 2f,
    var hue: Float = 0f
)

@Composable
private fun CSpaceDrift(frame: BandFrame, timeMs: Long, isPlaying: Boolean, modifier: Modifier) {
    val rnd = remember { Random(99) }
    val stars = remember {
        Array(90) {
            Star(rnd.nextFloat() * 2f - 1f, rnd.nextFloat() * 2f - 1f, 0.01f + rnd.nextFloat() * 0.99f, 1f + rnd.nextFloat() * 2.5f, rnd.nextFloat())
        }
    }
    val lastMs = remember { longArrayOf(timeMs) }
    val playSmooth = remember { floatArrayOf(0f) }

    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h * 0.54f
        val dt = ((timeMs - lastMs[0]) / 1000f).coerceIn(0f, 0.05f)
        lastMs[0] = timeMs
        val target = if (isPlaying) 1f else 0f
        playSmooth[0] += (target - playSmooth[0]) * dt.coerceAtLeast(0.001f) * 3f
        val playing = playSmooth[0]

        drawDeepSpace()
        drawCircle(
            Brush.radialGradient(
                listOf(Violet.copy(alpha = 0.30f * playing), Color.Transparent),
                center = Offset(cx, cy)
            ),
            radius = w.coerceAtMost(h) * 0.5f, center = Offset(cx, cy)
        )

        val speed = (0.16f + frame.bass * 0.55f + frame.treble * 0.12f) * playing
        for (s in stars) {
            s.z -= speed * dt
            if (s.z <= 0.008f) {
                s.z = 1f
                s.normX = rnd.nextFloat() * 2f - 1f
                s.normY = rnd.nextFloat() * 2f - 1f
                s.size = 1f + rnd.nextFloat() * 2.5f
                s.hue = rnd.nextFloat()
            }
            val travel = (1f - s.z).coerceIn(0f, 1f)
            val persp = 1f / (s.z * 3f + 1f)
            val px = cx + s.normX * w * 0.7f * persp
            val py = cy + s.normY * h * 0.6f * persp
            val dotR = s.size * (0.04f + travel * 1.15f)
            val alpha = ((0.2f + travel * 0.8f) * playing).coerceIn(0f, 1f)
            val shade = when {
                s.hue < 0.45f -> Color(0xFFBFD8FF)
                s.hue < 0.80f -> Color.White
                else -> Color(0xFF8AB6FF)
            }
            drawCircle(shade.copy(alpha = alpha), dotR, Offset(px, py))
            if (travel > 0.6f) drawCircle(Color.White.copy(alpha = alpha * 0.9f), dotR * 0.4f, Offset(px, py))
        }
        drawVignette(0.55f)
    }
}

// ===== 效果 2：黑胶复古赛博 =====
@Composable
private fun FVinylNeon(frame: BandFrame, timeMs: Long, isPlaying: Boolean, modifier: Modifier) {
    val lastMs = remember { longArrayOf(timeMs) }
    val playSmooth = remember { floatArrayOf(0f) }
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h / 2f
        val dt = ((timeMs - lastMs[0]) / 1000f).coerceIn(0f, 0.05f)
        lastMs[0] = timeMs
        val target = if (isPlaying) 1f else 0f
        playSmooth[0] += (target - playSmooth[0]) * dt.coerceAtLeast(0.001f) * 3f
        val playing = playSmooth[0]

        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF24101c), Color(0xFF0c060a), Color(0xFF050204)),
                center = Offset(cx, cy), radius = w.coerceAtLeast(h) * 0.8f
            )
        )
        val discR = w.coerceAtMost(h) * 0.27f
        val ang = (timeMs.toFloat() * 0.03f) % 360f

        // 频谱环（低/中/高三色段）
        for (i in 0 until 40) {
            val group = when { i < 14 -> frame.bass; i < 27 -> frame.mid; else -> frame.treble }
            val color = when { i < 14 -> Warm; i < 27 -> Cyan; else -> Magenta }
            val len = discR * (0.05f + group * 0.24f)
            rotate(segAng(i) + ang * 0.2f, pivot = Offset(cx, cy)) {
                drawLine(
                    color = color.copy(alpha = (0.25f + group * 0.7f).coerceIn(0f, 1f)),
                    start = Offset(cx + discR * 1.02f, cy),
                    end = Offset(cx + discR * 1.02f + len, cy),
                    strokeWidth = 3f
                )
            }
        }

        // 唱盘 + 凹槽
        drawCircle(Brush.radialGradient(listOf(Color(0xFF1b1b22), Color(0xFF07070a)), center = Offset(cx, cy)), discR, Offset(cx, cy))
        for (i in 1..6) {
            drawCircle(Color.White.copy(alpha = 0.06f), discR * i / 6f, Offset(cx, cy), style = Stroke(1.5f))
        }
        // 旋转可见的反光弧 + 标签
        rotate(ang, pivot = Offset(cx, cy)) {
            drawArc(
                color = Color.White.copy(alpha = 0.14f),
                topLeft = Offset(cx - discR * 0.9f, cy - discR * 0.9f),
                size = Size(discR * 1.8f, discR * 1.8f),
                startAngle = 12f, sweepAngle = 55f,
                useCenter = false, style = Stroke(discR * 0.07f)
            )
        }
        drawCircle(Warm, discR * 0.28f, Offset(cx, cy))
        drawCircle(Warm.copy(alpha = 0.55f), discR * 0.16f, Offset(cx, cy))
        drawCircle(Color(0xFF1a120a), discR * 0.05f, Offset(cx, cy))

        drawVignette(0.5f)
    }
}

private fun segAng(i: Int): Float = i * 360f / 40f

// ===== 效果 3：音频隧道（极坐标纵深，环随低频外炸） =====
@Composable
private fun GAudioTunnel(frame: BandFrame, timeMs: Long, isPlaying: Boolean, modifier: Modifier) {
    val lastMs = remember { longArrayOf(timeMs) }
    val playSmooth = remember { floatArrayOf(0f) }
    val beat = remember { BeatDetector() }
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h / 2f
        val dt = ((timeMs - lastMs[0]) / 1000f).coerceIn(0f, 0.05f)
        lastMs[0] = timeMs
        val target = if (isPlaying) 1f else 0f
        playSmooth[0] += (target - playSmooth[0]) * dt.coerceAtLeast(0.001f) * 3f
        val playing = playSmooth[0]
        beat.update(frame.bass, dt)
        val pulse = beat.pulse

        drawDeepSpace()

        val maxR = w.coerceAtMost(h) * 0.96f
        val sec = timeMs.toFloat() / 1000f
        val speed = 0.10f + frame.energy * 0.34f + pulse * 0.55f
        val rings = 14
        for (i in 0 until rings) {
            // 环从远(小)冲向近(大)，循环
            val t = (sec * speed + i / rings.toFloat()) % 1f
            val travel = t // 0 远 / 1 近
            val r = 6f + (maxR - 6f) * travel
            val alpha = (0.04f + travel * 0.55f) * playing
            val hue = (268f + i * 25f + pulse * 26f) % 360f
            drawCircle(
                Color.hsl(hue, 0.62f, 0.6f, alpha),
                r, Offset(cx, cy),
                style = Stroke(1.2f + travel * 4f)
            )
        }
        // 放射辐条强化纵深
        for (a in 0 until 12) {
            rotate(a * 30f, pivot = Offset(cx, cy)) {
                drawLine(
                    Color.hsl(260f, 0.5f, 0.62f, 0.10f * playing),
                    Offset(cx - 30f, cy), Offset(cx + maxR, cy), strokeWidth = 1.5f
                )
            }
        }
        // 核心光团（节拍爆亮）
        val coreR = w.coerceAtMost(h) * 0.20f * (1f + pulse * 0.55f)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.85f * playing), Violet.copy(alpha = 0.4f * playing), Color.Transparent),
                center = Offset(cx, cy)
            ),
            coreR, Offset(cx, cy)
        )
        drawVignette(0.55f)
    }
}

// ===== 效果 4：满屏流星雨（斜向飞掠 + 拖尾，节拍冲刺） =====
private class Meteor(
    var x: Float = 0f,       // 归一化 0..1
    var y: Float = 0f,
    var vx: Float = 0f,      // 归一化/秒
    var vy: Float = 0f,
    var age: Float = 0f,
    var life: Float = 1f,
    var hue: Float = 0f,
    var active: Boolean = false
)

@Composable
private fun HMeteorShower(frame: BandFrame, timeMs: Long, isPlaying: Boolean, modifier: Modifier) {
    val pool = remember { Array(40) { Meteor() } }
    val poolHead = remember { intArrayOf(0) }
    val lastMs = remember { longArrayOf(timeMs) }
    val playSmooth = remember { floatArrayOf(0f) }
    val acc = remember { floatArrayOf(0f) }
    val rnd = remember { Random(314) }
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val dt = ((timeMs - lastMs[0]) / 1000f).coerceIn(0f, 0.05f)
        lastMs[0] = timeMs
        val target = if (isPlaying) 1f else 0f
        playSmooth[0] += (target - playSmooth[0]) * dt.coerceAtLeast(0.001f) * 3f
        val playing = playSmooth[0]

        drawDeepSpace()
        // 淡紫氛围底，避免纯黑
        drawCircle(
            Brush.radialGradient(listOf(Violet.copy(alpha = 0.07f * playing), Color.Transparent)),
            w.coerceAtMost(h) * 0.9f, Offset(w / 2f, h * 0.45f)
        )
        // 背景星点
        for (i in 0 until 30) {
            val sx = ((i * 37 % 199) / 199f) * w
            val sy = ((i * 53 % 199) / 199f) * h
            val tw = if (frame.treble > 0.4f) 0.9f else 0.4f
            drawCircle(Color.White.copy(alpha = (0.15f + 0.25f * tw) * playing), 1f + (i % 3) * 0.4f, Offset(sx, sy))
        }

        // 恒定基础流速 + 能量/节拍加量（始终有流星，避免黑屏）
        val rate = playing * (0.55f + frame.energy * 12f + frame.bass * 12f)
        acc[0] += rate * dt
        if (playing < 0.01f) acc[0] = 0f
        while (acc[0] >= 1f) {
            acc[0] -= 1f
            if (acc[0] > 4f) acc[0] = 0f
            val m = pool[poolHead[0]]; poolHead[0] = (poolHead[0] + 1) % pool.size
            m.active = true
            m.age = 0f
            m.life = 0.9f + rnd.nextFloat() * 0.9f
            m.hue = rnd.nextFloat()
            val speed = (0.3f + rnd.nextFloat() * 0.4f) * (1f + frame.energy * 0.7f)
            // 全部向左下飞；出身多为「右侧」与「上方」两路并进
            m.vx = -speed * 1.15f
            m.vy = speed * (0.9f + rnd.nextFloat() * 0.35f)
            if (rnd.nextBoolean()) { // 从右缘进，向左下扫
                m.x = 1.03f + rnd.nextFloat() * 0.6f
                m.y = rnd.nextFloat() * 0.95f
            } else {                 // 从顶缘进，向左下扫
                m.x = rnd.nextFloat() * 1.05f
                m.y = -0.03f - rnd.nextFloat() * 0.6f
            }
        }

        // 推进 + 绘制
        for (m in pool) {
            if (!m.active) continue
            m.age += dt
            if (m.age >= m.life || m.x < -0.4f || m.x > 1.4f || m.y > 1.4f) { m.active = false; continue }
            m.x += m.vx * dt
            m.y += m.vy * dt
            val head = Offset(m.x * w, m.y * h)
            val tailLen = (0.12f + m.life * 0.10f) * w
            val tail = Offset(head.x - m.vx * tailLen, head.y - m.vy * tailLen)
            val shade = when {
                m.hue < 0.34f -> Cyan
                m.hue < 0.67f -> Magenta
                else -> Teal
            }
            val k = (m.life - m.age).coerceIn(0f, 1f)
            drawLine(shade.copy(alpha = 0.20f * k * playing), head, tail, strokeWidth = 3.5f)
            drawLine(shade.copy(alpha = 0.62f * k * playing), head, tail, strokeWidth = 1.6f)
            drawCircle(Color.White.copy(alpha = 0.9f * playing), 2.2f + 1.5f * k, head)
        }
        drawVignette(0.5f)
    }
}

// ===== 效果 5：满屏宇宙星云（多色云团流动重叠 + 节拍核心） =====
@Composable
private fun INebula(frame: BandFrame, timeMs: Long, isPlaying: Boolean, modifier: Modifier) {
    val phase = remember { FloatArray(12) { it * 0.61f } }
    val lastMs = remember { longArrayOf(timeMs) }
    val playSmooth = remember { floatArrayOf(0f) }
    val beat = remember { BeatDetector() }
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val dt = ((timeMs - lastMs[0]) / 1000f).coerceIn(0f, 0.05f)
        lastMs[0] = timeMs
        val target = if (isPlaying) 1f else 0f
        playSmooth[0] += (target - playSmooth[0]) * dt.coerceAtLeast(0.001f) * 3f
        val playing = playSmooth[0]
        beat.update(frame.bass, dt)
        val pulse = beat.pulse
        val sec = timeMs.toFloat() / 1000f

        // 1) 整屏饱和彩色雾底：径向渐变覆盖屏幕对角线，四角也有明显的彩色，
        //    锁屏下一眼即可见全屏有颜色，绝不发黑
        val diag = sqrt(w * w + h * h)
        val hazeH = sec * 11f
        drawRect(
            Brush.radialGradient(
                colors = listOf(
                    Color.hsl(hazeH % 360f, 0.75f, 0.50f).copy(alpha = 0.62f * playing),
                    Color.hsl((hazeH + 60f) % 360f, 0.70f, 0.40f).copy(alpha = 0.44f * playing),
                    Color.hsl((hazeH + 120f) % 360f, 0.70f, 0.30f).copy(alpha = 0.26f * playing),
                    Color.Transparent
                ),
                center = Offset(w * 0.5f, h * 0.46f),
                radius = diag * 0.6f
            )
        )
        // 2) 用均匀整屏网格铺满全屏：每个光团只覆盖其所在格，所有区域亮度一致，
        //    保证不再是「居中一个圆形」
        val cell = w.coerceAtMost(h) * 0.18f
        val nx = (w / cell).toInt().coerceAtLeast(4)
        val ny = (h / cell).toInt().coerceAtLeast(4)
        val breath = 1f + frame.bass * 0.45f + pulse * 0.55f
        for (iy in 0 until ny) {
            for (ix in 0 until nx) {
                val i = iy * nx + ix
                val ph = phase[i % phase.size]
                val bx = (ix + 0.5f) * (w / nx)
                val by = (iy + 0.5f) * (h / ny)
                val sp = 0.5f + (i % 5) * 0.12f
                val px = bx + sinF(sec * sp + ph) * w * 0.02f
                val py = by + cosF(sec * sp * 0.85f + ph * 1.3f) * h * 0.02f
                // 半径由低频与节拍脉冲共同驱动，节拍权重显著增大，
                // 让每一下鼓点都有明显的「膨大 — 回落」跳动
                val r = cell * 0.92f * breath
                // 云团做成椭圆（非正圆）并各带一个固定旋转角，降下正圆感
                val rx = r * (0.72f + (i % 3) * 0.12f)         // 长短轴差异化
                val ry = r * (1.28f - (i % 3) * 0.16f)
                val rot = ph * 9f
                // 颜色随时间连续流转，中频推动整体漂移（所有云团颜色都可变）
                val hue = (i * 26f + sec * (16f + (i % 4) * 7f) + frame.mid * 46f) % 360f
                val col = Color.hsl(hue, 0.62f, 0.58f)
                // 节拍脉冲对亮度的贡献从 0.14 提升到 0.34，每下鼓点更明显地闪亮
                val a = (0.42f + 0.10f * sinF(sec * 0.7f + ph * 2f) + pulse * 0.34f).coerceIn(0f, 0.8f) * playing
                rotate(rot, pivot = Offset(px, py)) {
                    drawOval(
                        Brush.radialGradient(
                            colors = listOf(
                                col.copy(alpha = a),
                                col.copy(alpha = a * 0.45f),
                                Color.Transparent
                            )
                        ),
                        topLeft = Offset(px - rx, py - ry),
                        size = Size(rx * 2f, ry * 2f)
                    )
                }
            }
        }
        // 高频星闪
        for (i in 0 until 22) {
            val sx = ((i * 43 % 199) / 199f) * w
            val sy = ((i * 71 % 199) / 199f) * h
            drawCircle(Color.White.copy(alpha = (0.25f + frame.treble * 0.7f).coerceAtMost(0.95f) * playing), 1.3f, Offset(sx, sy))
        }
        // 不加暗角覆盖层，星云亮区全屏显示
    }
}

// ===== 效果 6：极光流场（整屏流光涌动 + 漂色） =====
@Composable
private fun JAuroraFluid(frame: BandFrame, timeMs: Long, isPlaying: Boolean, modifier: Modifier) {
    val lastMs = remember { longArrayOf(timeMs) }
    val playSmooth = remember { floatArrayOf(0f) }
    val beat = remember { BeatDetector() }
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val dt = ((timeMs - lastMs[0]) / 1000f).coerceIn(0f, 0.05f)
        lastMs[0] = timeMs
        val target = if (isPlaying) 1f else 0f
        playSmooth[0] += (target - playSmooth[0]) * dt.coerceAtLeast(0.001f) * 3f
        val playing = playSmooth[0]
        beat.update(frame.bass, dt)
        val pulse = beat.pulse
        val sec = timeMs.toFloat() / 1000f

        drawDeepSpace()

        val colors = listOf(Violet, Green, Cyan, Magenta)
        val bands = 4
        val seg = 16
        for (b in 0 until bands) {
            val baseY = h * (0.16f + b * 0.20f)
            val amp = h * (0.05f + frame.bass * 0.16f + pulse * 0.08f)
            val phase = sec * (1.1f + b * 0.4f) + b * 1.7f
            // 上边缘采样
            val topPts = FloatArray(seg + 1)
            val botPts = FloatArray(seg + 1)
            for (i in 0..seg) {
                val x = i / seg.toFloat()
                val wob = sinF(x * 5.2f + phase * 2f) * 0.5f + cosF(x * 2.3f + phase * 1.2f) * 0.4f
                topPts[i] = baseY + wob * amp
                botPts[i] = baseY + wob * amp + h * (0.018f + frame.mid * 0.03f)
            }
            val path = Path()
            path.moveTo(0f, topPts[0])
            for (i in 1..seg) path.lineTo(w * i / seg.toFloat(), topPts[i])
            for (i in seg downTo 0) path.lineTo(w * i / seg.toFloat(), botPts[i])
            path.close()
            val hue = (250f + b * 34f + frame.mid * 60f + sec * 6f) % 360f
            drawPath(path, Color.hsl(hue, 0.62f, 0.60f, (0.30f + pulse * 0.12f) * playing))
        }
        // 高频星闪
        for (i in 0 until 12) {
            val sx = ((i * 47 % 199) / 199f) * w
            val sy = ((i * 83 % 199) / 199f) * h
            drawCircle(Color.White.copy(alpha = (0.15f + frame.treble * 0.5f) * playing), 1.4f, Offset(sx, sy))
        }
        drawVignette(0.55f)
    }
}

// ===== 效果 8：罗盘螺旋（低频越强螺旋越紧越亮） =====
@Composable
private fun LVortexSpiral(frame: BandFrame, timeMs: Long, isPlaying: Boolean, modifier: Modifier) {
    val lastMs = remember { longArrayOf(timeMs) }
    val playSmooth = remember { floatArrayOf(0f) }
    val beat = remember { BeatDetector() }
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h / 2f
        val dt = ((timeMs - lastMs[0]) / 1000f).coerceIn(0f, 0.05f)
        lastMs[0] = timeMs
        val target = if (isPlaying) 1f else 0f
        playSmooth[0] += (target - playSmooth[0]) * dt.coerceAtLeast(0.001f) * 3f
        val playing = playSmooth[0]
        beat.update(frame.bass, dt)
        val pulse = beat.pulse
        val sec = timeMs.toFloat() / 1000f

        drawDeepSpace()
        drawCircle(
            Brush.radialGradient(listOf(Cyan.copy(alpha = 0.25f * playing), Color.Transparent), center = Offset(cx, cy)),
            w.coerceAtMost(h) * 0.4f, Offset(cx, cy)
        )

        // 多臂对数螺旋
        val arms = 3
        val turns = 3.5f
        val maxR = w.coerceAtMost(h) * 0.48f
        val spin = sec * (0.6f + frame.bass * 1.4f)
        val tight = 1f + frame.bass * 1.6f
        val dotsPerArm = 90
        for (arm in 0 until arms) {
            val armOff = arm * (2f * PI / arms).toFloat()
            for (i in 0 until dotsPerArm) {
                val t = i / dotsPerArm.toFloat()
                val rr = maxR * t
                val theta = armOff + spin * (1f + t) + t * turns * (2f * PI).toFloat() * tight
                val px = cx + rr * cosF(theta)
                val py = cy + rr * sinF(theta)
                val k = (1f - t).coerceIn(0f, 1f) // 靠近中心更亮
                val size = 3.2f * k + 0.6f
                val hue = (255f + arm * 40f + t * 200f + sec * 40f) % 360f
                drawCircle(Color.hsl(hue, 0.7f, 0.6f, (0.35f + 0.4f * k + pulse * 0.25f) * playing), size, Offset(px, py))
            }
        }
        // 中心亮核
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = (0.7f + pulse * 0.3f) * playing), Color.Transparent), center = Offset(cx, cy)),
            w.coerceAtMost(h) * 0.03f + 3f, Offset(cx, cy)
        )
        drawVignette(0.5f)
    }
}

// ===== 效果 9：银河圆盘（倾斜粒子盘，节拍迸发回吸） =====
private class GalParticle(var r: Float = 0f, var a: Float = 0f, var size: Float = 2f, var via: Float = 0f)

@Composable
private fun MGalaxyDisc(frame: BandFrame, timeMs: Long, isPlaying: Boolean, modifier: Modifier) {
    val rnd = remember { Random(727) }
    val parts = remember {
        Array(240) {
            GalParticle(
                r = 0.05f + rnd.nextFloat() * 0.95f,
                a = rnd.nextFloat() * 2f * PI.toFloat(),
                size = 0.8f + rnd.nextFloat() * 2.4f,
                via = rnd.nextFloat()
            )
        }
    }
    val rot = remember { floatArrayOf(0f) }
    val lastMs = remember { longArrayOf(timeMs) }
    val playSmooth = remember { floatArrayOf(0f) }
    val beatPulse = remember { floatArrayOf(0f) }
    val lastBass = remember { floatArrayOf(0f) }
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h * 0.54f
        val dt = ((timeMs - lastMs[0]) / 1000f).coerceIn(0f, 0.05f)
        lastMs[0] = timeMs
        val target = if (isPlaying) 1f else 0f
        playSmooth[0] += (target - playSmooth[0]) * dt.coerceAtLeast(0.001f) * 3f
        val playing = playSmooth[0]
        // 简易节拍脉冲（低通 + 突变）
        beatPulse[0] = (beatPulse[0] - dt * 2.2f).coerceAtLeast(0f)
        if (frame.bass > 0.05f && frame.bass > lastBass[0] + 0.05f) beatPulse[0] = 1f
        lastBass[0] += (frame.bass - lastBass[0]) * (dt * 12f).coerceIn(0f, 1f)
        val pulse = beatPulse[0]

        drawDeepSpace()
        // 核心星云光晕
        drawCircle(
            Brush.radialGradient(listOf(Violet.copy(alpha = 0.22f * playing), Color.Transparent), center = Offset(cx, cy)),
            w.coerceAtMost(h) * 0.55f, Offset(cx, cy)
        )

        // 银河自转（低音加速）
        rot[0] += dt * (0.35f + frame.bass * 1.4f + pulse * 1.2f) * playing
        val maxR = w.coerceAtMost(h) * 0.5f
        for (p in parts) {
            val a = p.a + rot[0] * (0.4f + p.via * 1.2f) // 越靠外转越快（微扰）
            val r = p.r * maxR * (1f + pulse * 0.15f * (1f - p.r))
            val px = cx + r * cosF(a)
            val py = cy + r * sinF(a) * 0.55f // 纵向压扁模拟倾斜
            val shade = if (p.via < 0.5f) Cyan else Violet
            val glowAlpha = ((0.25f + (1f - p.r) * 0.5f) * playing).coerceIn(0f, 1f)
            drawCircle(shade.copy(alpha = glowAlpha * 0.7f), p.size * 1.8f + (pulse * 1.5f), Offset(px, py))
            drawCircle(Color.White.copy(alpha = glowAlpha * 0.9f), p.size * 0.6f + (pulse * 0.8f), Offset(px, py))
        }
        drawVignette(0.55f)
    }
}