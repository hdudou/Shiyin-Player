package com.shiyinplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题偏好：mode 0=跟随系统 / 1=浅色 / 2=深色；accent 为强调色索引（见 [ACCENT_COLORS]）；
 * style 为主题风格（0=玄素映彩·明暗+强调色引擎；1..10=固定深色整套风格，见 [THEME_STYLES]）。
 * 由设置模块持久化，AppNavGraph 读取后注入 [MusicPlayerTheme]。
 */
data class ThemePrefs(
    val mode: Int = 0,
    val accent: Int = 0,
    val style: Int = 0,
    /** 是否启用 Material You 动态取色（Android 12+）。style=0 才生效（固定风格覆盖）。 */
    val dynamicColors: Boolean = true
)

/** 强调色板（与 res/values/arrays.xml 的 accent_colors 对应）。 */
val ACCENT_COLORS: List<Long> = listOf(
    0xFF6750A4, // 0 默认紫
    0xFF2196F3, // 1 蓝
    0xFF009688, // 2 青
    0xFF4CAF50, // 3 绿
    0xFFFF9800, // 4 橙
    0xFFF44336  // 5 红
)

/** 一套完整深色主题风格的色板（取自 THEMES_REGISTRY.md 存档）。 */
data class ThemeStyle(
    val key: String,
    val label: String,
    val primary: Long,
    val bg: Long,
    val surface: Long,
    val surfaceAlt: Long,
    val onPrimary: Long,
    val text: Long,
    val mut: Long,
    val line: Long
)

/** 10 套新增主题风格（prefs.style = 列表下标 + 1）。 */
val THEME_STYLES: List<ThemeStyle> = listOf(
    ThemeStyle("gold", "鎏金玄夜", 0xFFE6B455, 0xFF191613, 0xFF221E18, 0xFF2B251C, 0xFF1A150B, 0xFFF5F1E8, 0xFFA89F8C, 0xFF3A332A),
    ThemeStyle("azure", "沧溟湛澜", 0xFF4F9CF0, 0xFF0F1D2E, 0xFF16283D, 0xFF1C3350, 0xFF08131F, 0xFFE9F2FB, 0xFF8FB0D4, 0xFF2A4260),
    ThemeStyle("emerald", "翠微森罗", 0xFF3FCE9C, 0xFF10221A, 0xFF173026, 0xFF1D3A2E, 0xFF08140F, 0xFFE7F5EE, 0xFF93C6B2, 0xFF295139),
    ThemeStyle("violet", "霞蔚霓紫", 0xFFA678F2, 0xFF1A1030, 0xFF241244, 0xFF2C1651, 0xFF170B2C, 0xFFF1ECFF, 0xFFA9A0C2, 0xFF33256B),
    ThemeStyle("lava", "熔金夕照", 0xFFFF8A5C, 0xFF241411, 0xFF2E1A14, 0xFF381F16, 0xFF1F0E08, 0xFFFFF2EB, 0xFFE2B7A4, 0xFF4B2A1F),
    ThemeStyle("rose", "绯樱凝霞", 0xFFF58FC0, 0xFF24131D, 0xFF2E1A27, 0xFF382031, 0xFF200E19, 0xFFFFF0F6, 0xFFE3B3C9, 0xFF4A2B37),
    ThemeStyle("silver", "霜月流银", 0xFFD7DDE3, 0xFF17191C, 0xFF1F2226, 0xFF282C31, 0xFF101316, 0xFFECF0F4, 0xFFA2A9B2, 0xFF33383F),
    ThemeStyle("retro", "沉香遗韵", 0xFFCF9D5F, 0xFF201711, 0xFF2A1D14, 0xFF342418, 0xFF190F07, 0xFFF3E7D8, 0xFFC2A687, 0xFF3E2C1E),
    ThemeStyle("citrus", "金乌曜曦", 0xFFF0C23A, 0xFF1D1A10, 0xFF272315, 0xFF302A18, 0xFF191403, 0xFFFDF6DD, 0xFFCDBE8A, 0xFF3D351F),
    ThemeStyle("wine", "绛雾醺夜", 0xFFD0576F, 0xFF1D1013, 0xFF28151A, 0xFF331A21, 0xFF1D0810, 0xFFFCEEF1, 0xFFD9A9B3, 0xFF432129)
)

private fun lighten(color: Long, ratio: Float): Long {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val nr = (r + (255 - r) * ratio).toInt().coerceIn(0, 255)
    val ng = (g + (255 - g) * ratio).toInt().coerceIn(0, 255)
    val nb = (b + (255 - b) * ratio).toInt().coerceIn(0, 255)
    return (0xFFL shl 24) or (nr.toLong() shl 16) or (ng.toLong() shl 8) or nb.toLong()
}

/** 由整套风格色板构造固定深色 ColorScheme（封面后作为 style 的渲染模板）。 */
private fun schemeForStyle(s: ThemeStyle): ColorScheme = darkColorScheme(
    primary = Color(s.primary),
    onPrimary = Color(s.onPrimary),
    primaryContainer = Color(s.surfaceAlt),
    onPrimaryContainer = Color(s.text),
    background = Color(s.bg),
    onBackground = Color(s.text),
    surface = Color(s.surface),
    onSurface = Color(s.text),
    surfaceVariant = Color(s.surfaceAlt),
    onSurfaceVariant = Color(s.mut),
    outline = Color(s.line),
    outlineVariant = Color(s.line),
    secondaryContainer = Color(s.surfaceAlt),
    onSecondaryContainer = Color(s.text)
)

private fun schemeFor(accent: Long, dark: Boolean): ColorScheme {
    val primary = if (dark) Color(lighten(accent, 0.45f)) else Color(accent)
    val onPrimary = if (dark) Color.Black else Color.White
    return if (dark) darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        background = Color(0xFF1A1A2E),
        surface = Color(0xFF211F33),
        surfaceVariant = Color(0xFF2E2A41),
        onBackground = Color(0xFFE6E0E9),
        onSurface = Color(0xFFE6E0E9),
        onSurfaceVariant = Color(0xFFDDD7E3),
        outline = Color(0xFFB0AAB6),
        outlineVariant = Color(0xFF4A4657),
        secondaryContainer = Color(0xFF3A3650),
        onSecondaryContainer = Color(0xFFECE6F2)
    ) else lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        background = Color(0xFFFFFBFE),
        surface = Color(0xFFFEF7FF),
        onBackground = Color(0xFF1C1B1F),
        onSurface = Color(0xFF1C1B1F)
    )
}

@Composable
fun MusicPlayerTheme(
    prefs: ThemePrefs = ThemePrefs(),
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (prefs.mode) {
        0 -> systemDark
        1 -> false
        2 -> true
        else -> systemDark
    }
    val accent = ACCENT_COLORS.getOrElse(prefs.accent) { ACCENT_COLORS[0] }
    // prefs.style：0=玄素映彩（明暗+强调色引擎）；1..10=THEME_STYLES 中整套固定深色风格。
    val styleIdx = prefs.style - 1
    val colorScheme = if (styleIdx in THEME_STYLES.indices) {
        schemeForStyle(THEME_STYLES[styleIdx])
    } else {
        // 玄素映彩风格：Android 12+ 用户启用动态取色则用系统壁纸取色，否则用强调色板
        if (prefs.dynamicColors && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (dark) {
                androidx.compose.material3.dynamicDarkColorScheme(context)
            } else {
                androidx.compose.material3.dynamicLightColorScheme(context)
            }
        } else {
            schemeFor(accent, dark)
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
