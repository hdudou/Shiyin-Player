package com.shiyinplayer.util

import com.shiyinplayer.BuildConfig

/**
 * 应用自身版本号的统一展示入口（收音机版本分支）。
 * 本分支为「带收音机功能」的独立版本，统一在版本号后追加「 radio version」字样，
 * 使收音机版本与正常音乐播放器版本在任何显示版本号的位置都能直观区分。
 */
object AppVersion {

    /** 收音机版本的展示后缀。 */
    const val RADIO_VERSION_TAG = " radio version"

    /** 展示用版本名：统一为「1.0.x radio version」。 */
    val displayName: String
        get() = BuildConfig.VERSION_NAME + RADIO_VERSION_TAG

    val code: Int
        get() = BuildConfig.VERSION_CODE
}