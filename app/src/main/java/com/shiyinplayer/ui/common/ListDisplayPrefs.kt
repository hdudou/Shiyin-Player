package com.shiyinplayer.ui.common

import androidx.compose.runtime.compositionLocalOf
import com.shiyinplayer.data.model.Song

/** 列表外观偏好（设置-界面：封面/双行/评分/密度 + 曲目格式）。通过 CompositionLocal 供列表项读取。 */
data class ListDisplayPrefs(
    val showArt: Boolean = true,
    val twoLine: Boolean = false,
    val density: String = "standard",
    val showEmbedArt: Boolean = true,
    val trackFormat: String = "{artist} – {title}"
) {
    /** 行内边距（紧凑/标准/宽松）。 */
    val verticalPadding: Float get() = when (density) {
        "compact" -> 4f
        "relaxed" -> 14f
        else -> 7f
    }

    /** 副标题（双行时）：按「曲目显示格式」选择艺术家或专辑。 */
    fun subtitleOf(song: Song): String? {
        if (!twoLine) return null
        return when (trackFormat) {
            "{album} – {title}" -> song.albumName
            else -> song.artistName
        } ?: "未知艺术家"
    }
}

val LocalListDisplayPrefs = compositionLocalOf { ListDisplayPrefs() }