package com.shiyinplayer.ui.radio

import com.shiyinplayer.data.radio.MergedRadioStation

/**
 * 标准化 URL 用于比较：去除首尾空格、末尾斜杠，转小写。
 * 注意：不规范化查询参数（电台流的 token 可能在 query 中）。
 */
internal fun normalizeRadioUrl(url: String): String =
    url.trim().trimEnd('/').lowercase()

/**
 * 判断两个电台流 URL 是否指向同一个电台源。
 */
internal fun isSameRadioUrl(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    return normalizeRadioUrl(a) == normalizeRadioUrl(b)
}

/**
 * 判断当前播放的 URL 是否属于某个合并电台（比较其任意线路）。
 */
internal fun isMergedRadioPlaying(streamUrl: String?, merged: MergedRadioStation): Boolean {
    if (streamUrl == null) return false
    return merged.streams.any { isSameRadioUrl(streamUrl, it.url) }
}
