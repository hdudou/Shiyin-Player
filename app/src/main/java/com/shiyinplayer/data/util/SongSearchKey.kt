package com.shiyinplayer.data.util

import android.icu.text.Transliterator

/**
 * F6-2：生成歌曲搜索归一化键，支持【原文 / 全拼 / 中文首字母】三类匹配。
 *
 * key 结构（全小写、去所有空白）：
 *   compact(原文, title+artist+album) + compact(全拼) + compact(中文首字母)
 * 例：title=北京天安门 →  北京天安门beijingtiananmenbj
 *
 * 搜索时对用户输入做同样 compact()（小写 + 去空白）后对 searchKey 做子串匹配即可。
 * 使用 Android 系统自带 ICU（android.icu），API>=24 可用，无需新增第三方依赖。
 */
object SongSearchKey {

    private val hanLatin by lazy { Transliterator.getInstance("Han-Latin/Names") }

    /** 构建某歌曲的 searchKey。 */
    fun of(title: String?, artist: String?, album: String?): String {
        val text = listOf(title ?: "", artist ?: "", album ?: "").joinToString(" ")
        if (text.isBlank()) return ""
        val compactText = compact(text)
        if (compactText.isEmpty()) return compactText
        val fullPinyin = compact(hanLatin.transliterate(text).lowercase())
        val initials = chineseInitials(text)
        return compactText + fullPinyin + initials
    }

    /** 归一化输入：全小写并去除所有空白（含 Unicode 空白）。 */
    fun compact(s: String): String = s.lowercase().filter { !it.isWhitespace() }

    /** 逐中文字符取拼音首字母拼接（非中文丢弃，避免英文词产生误报首字母）。 */
    private fun chineseInitials(text: String): String = buildString {
        for (ch in text) {
            val cp = ch.code
            val isCjk = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF
            if (isCjk) {
                val py = hanLatin.transliterate(ch.toString()).lowercase().trim()
                val first = py.firstOrNull { it.isLetter() }
                if (first != null) append(first)
            }
        }
    }
}