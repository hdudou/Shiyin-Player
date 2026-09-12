package com.shiyinplayer.ui

import android.content.Context
import androidx.activity.ComponentActivity
import com.shiyinplayer.util.AppLocaleManager

/**
 * 应用级 Activity 基类：在 attachBaseContext（早于 Hilt 字段注入）阶段套用当前运行时语言，
 * 实现「切语言 → recreate」即时生效。Hilt 对未注解的 ComponentActivity 子类基类兼容。
 */
abstract class LocalizedComponentActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(newBase, AppLocaleManager.remembered))
    }
}