package com.shiyinplayer.update

import org.json.JSONObject

/**
 * 版本更新清单（latest.json）。
 *
 * ZT WebDAV 更新仓库目录下放一个 latest.json，字段：
 *  - versionCode : 整型版本号，Android 单调递增，新旧的**权威裁决**依据
 *  - versionName : 展示用版本名（如 "1.2.3"），二次兜底比较
 *  - apk        : 安装包文件名（相对该目录，如 shiyinplayer-v1.2.3.apk）
 *  - size       : 安装包字节数（可选，>0 时用于下载完整性校验）
 *  - md5        : 安装包 MD5（可选，存在时用于下载后校验）
 *  - releaseNote: 更新说明（可选）
 */
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkName: String,
    val size: Long,
    val md5: String?,
    val releaseNote: String?
) {
    /** 是否新于本地：versionCode 权威判定 */
    fun isNewerThan(localCode: Int): Boolean = versionCode > localCode

    companion object {
        /**
         * 双通道 latest.json：顶层按变体分节 {"debug":{...},"release":{...}}，
         * 按当前构建变体 [sectionKey] 只取对应那一段（debug/release）。
         * 兼容旧版扁平结构：若顶层无该节名则退化为直接解析整体。
         */
        fun fromJson(raw: String, sectionKey: String): AppUpdateInfo {
            val root = JSONObject(raw)
            val o = if (root.has(sectionKey)) root.getJSONObject(sectionKey) else root
            return AppUpdateInfo(
                versionCode = o.optInt("versionCode", 0),
                versionName = o.optString("versionName", ""),
                apkName = o.optString("apk", ""),
                size = o.optLong("size", 0L),
                md5 = o.optString("md5").ifBlank { null },
                releaseNote = o.optString("releaseNote").ifBlank { null }
            )
        }
    }
}

/**
 * 版本比较规则（对照 Android 官方语义）：
 *
 *  - **versionCode（整型）**：单调递增，新旧判定只看它。`remote > local` 即存在更新。
 *  - versionName（点分语义）仅作展示与二次兜底：解析 major.minor.patch 数值比较，
 *    段数不足补 0，仅用于 versionCode 相等时提示“同名不同版本”，不作最终裁决。
 *  - 非数值后缀（如 "1.2.3-beta"）按段截断到首个非数字字符后再比较。
 */
object VersionComparator {
    /** 简洁起见，App 侧直接以 versionCode 裁决（Android 官方机制），
     *  本函数仅供需要在 versionName 层面做展示判断时使用。 */
    fun parseVersionName(name: String): List<Int> {
        if (name.isBlank()) return emptyList()
        return name.split('.').mapNotNull { seg ->
            seg.takeWhile { it.isDigit() }.toIntOrNull()
        }
    }

    /** 两个点分版本名数值比较：>0 表示 a 版本号更高，=0 相等，<0 更低。 */
    fun compareVersionNames(a: String, b: String): Int {
        val av = parseVersionName(a); val bv = parseVersionName(b)
        val n = maxOf(av.size, bv.size)
        for (i in 0 until n) {
            val x = av.getOrElse(i) { 0 }; val y = bv.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}