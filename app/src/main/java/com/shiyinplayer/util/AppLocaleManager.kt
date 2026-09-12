package com.shiyinplayer.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用级运行时语言管理（I-语言：跟随系统/简体中文/English）。
 *
 * 运行时切换实现：MainActivity 在 attachBaseContext（早于字段注入）阶段读取本管理器
 * 保存的 [remembered] 语言码套一层 Configuration 上下文，切换后由设置页调用
 * [setLanguage] 更新 [remembered] 并重建当前 Activity，语言即时生效。
 * 冷启动时 [remembered] 由 Application 从 SettingsRepository 内存快照初始化。
 */
object AppLocaleManager {

    /** 当前生效语言码：system / zh / en。进程级 @Volatile 供 attachBaseContext 同步读取。 */
    @Volatile
    var remembered: String = "system"

    /** 语言码 → Locale。system 跟随系统默认区域。 */
    fun localeOf(code: String): Locale = when (code) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.ENGLISH
        else -> Locale.getDefault()
    }

    /** 为 [base] 套一层指定语言的 Configuration 上下文。资源解析按 Locale 命中 values-xx 目录。 */
    fun wrap(base: Context, code: String): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(localeOf(code))
        return base.createConfigurationContext(config)
    }

    /** 语言切换入口：更新记忆值（下次 attachBaseContext 生效）。 */
    fun setLanguage(code: String) {
        remembered = code
    }
}