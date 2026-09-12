package com.shiyinplayer.data.media

/**
 * 音频文件名 → 歌名/歌手 智能解析（2026-08-19）。
 *
 * 无内嵌元数据标签的文件（APE 等）入库时 title 会回退为文件名，
 * 而文件名常为「歌手 - 歌名」格式（如 "BEYOND - 谁伴我闯荡"、"4inlove.-.一千零一个愿望"）。
 * 该函数将文件名拆出 (artist, title)，供扫描入库与元数据同步复用。
 *
 * v2（2026-08-19 12:20）规则增强：
 * - 路径痕迹清理（含 / 时取最后一段，如 "primary:中文单曲合集/(张靓颖)画心"）
 * - 前导/尾部特殊符号清理（- _ . · 空格，如 "-片片枫叶情" → "片片枫叶情"、"PEGASUS FANTASY-" → "PEGASUS FANTASY"）
 * - "[歌手] 歌名" → ("歌手", "歌名")，如 "[吴紫涵] 一定要爱你"
 * - "(歌手)歌名"（括号在开头）→ ("歌手", "歌名")，如 "(张靓颖)画心"
 * - "《歌名》(说明)" → 取书名号内为歌名，如 "《Superman》(双雄)" → ("", "Superman")
 *
 * @return (歌手, 歌名)；无法解析时歌手为 null、歌名为清洗后的名称。
 */
fun parseFileNameTitle(fileName: String?): Pair<String?, String?> {
    var name = fileName?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
    // 1) 路径痕迹：取最后一段
    if (name.contains('/') || name.contains('\\')) {
        name = name.substringAfterLast('/').substringAfterLast('\\')
    }
    // 2) 前导 / 尾部特殊符号清理
    name = name.trimStart('-', '_', '.', '·', '—', ' ', '\u3000')
    name = name.trimEnd('-', '_', '.', '·', '—', ' ', '\u3000')
    // 2.5) 剥离前导括号噪声（轨道序号 / 空括号），如 "(01) [] Bar style" → "Bar style"
    name = stripLeadingNoise(name)
    if (name.isEmpty()) return null to null

    // 3) "[歌手] 歌名"
    val bracket = Regex("""^\[\s*(.+?)\s*\][\s\-_.·—]*([^\[].+)$""").find(name)
    if (bracket != null) {
        val a = cleanArtist(bracket.groupValues[1].trim())
        val t = cleanTitle(bracket.groupValues[2].trim())
        if (a != null && t.isNotEmpty() && a.length <= 20) return a to t
    }
    // 4) "(歌手)歌名"（括号在开头）
    val paren = Regex("""^\(\s*(.+?)\s*\)[\s\-_.·—]*([^(].+)$""").find(name)
    if (paren != null) {
        val a = cleanArtist(paren.groupValues[1].trim())
        val t = cleanTitle(paren.groupValues[2].trim())
        if (a != null && t.isNotEmpty() && a.length <= 20 && t.length <= a.length * 4) {
            return a to t
        }
    }
    // 5) "《歌名》..." → 书名号内作为歌名（去掉书名号与后续说明）
    if (name.startsWith("《")) {
        val end = name.indexOf("》")
        if (end > 1) {
            return null to name.substring(1, end).trim()
        }
    }

    // 6) 常规分隔符拆分（从明确到模糊）
    val seps = arrayOf(" - ", " — ", " – ", "·", ".-.", " . ", "-", "_")
    for (sep in seps) {
        val idx = name.indexOf(sep)
        if (idx > 0 && idx < name.length - sep.length) {
            val first = name.substring(0, idx).trim()
            val second = name.substring(idx + sep.length).trim()
            val artist = cleanArtist(first)
            if (artist != null && second.isNotEmpty() && artist.length <= 20) {
                val t = cleanTitle(second)
                if (t.isNotEmpty()) return artist to t
            }
        }
    }
    val t = cleanTitle(name)
    return null to t.takeIf { it.isNotEmpty() }
}

/** 两端需要剔除的装饰/分隔特殊符号（元数据标签直喂时也生效）。 */
private val EDGE_SYMBOLS = setOf('-', '_', '.', '·', '—', '~', '～', '、', '。', '！', '？', '!', '?', ' ', '\u3000')

/** 歌名清洗：去前导/尾部特殊符号与多余空白，并去掉 "01. " 式裸轨道序号前缀。 */
private fun cleanTitle(t: String): String {
    val cleaned = stripLeadingNoise(
        t.trim()
            .replaceFirst(TRACK_NO_PREFIX_RE, "").trim()
            .replaceFirst(STRIP_TRACK_RE, "").trim()
            .trimStart { it in EDGE_SYMBOLS }
    )
        .replaceFirst(TRACK_NO_PREFIX_RE, "").trim()
        .replaceFirst(STRIP_TRACK_RE, "").trim()
        .trimStart { it in EDGE_SYMBOLS }
        .trimEnd { it in EDGE_SYMBOLS }
    // 纯数字/序号结果视为无效，返回空串由调用方回退原文（需求：清 "01 02 03" 之类）。
    return if (cleaned.isNotBlank() && cleaned.all(Char::isDigit)) "" else cleaned
}

/** 供扫描器对元数据标签 / CUE 曲名也清洗前导序号；清洗后为空且原文为纯数字序号时返回 null，由调用方回退。 */
fun cleanTitleKeep(raw: String?): String? {
    if (raw.isNullOrBlank()) return raw
    val c = cleanTitle(raw)
    if (c.isNotBlank()) return c
    val t = raw.trim()
    // 清洗后为空（多为纯数字序号，如 "000/001"）且原文亦为纯数字/空白时，视为无效返回 null，
    // 避免 "000" 这类垃圾编号残留入库；否则回退原文防止字段丢失。
    return if (t.isNotEmpty() && t.all { it.isDigit() || it.isWhitespace() }) null else t.takeIf { it.isNotEmpty() }
}

/**
 * 艺术家清洗：剥前导裸轨道序号（"01 - 歌手" → "歌手"）与 CD/DISC 标记、两端特殊符号，
 * 并去掉纯数字序号（"000/001"），避免无效编号并入歌手名。整段为纯数字时返回 null。
 */
fun cleanArtist(s: String?): String? {
    if (s.isNullOrBlank()) return s
    var r = s.trim().trimStart { it in EDGE_SYMBOLS }.trimEnd { it in EDGE_SYMBOLS }
    while (true) {
        val n = r.replaceFirst(TRACK_NO_PREFIX_RE, "").trim()
        if (n == r) break
        r = n
    }
    r = stripDiscMarkers(r) ?: return null
    r = r.trimStart { it in EDGE_SYMBOLS }.trimEnd { it in EDGE_SYMBOLS }
    if (r.isEmpty()) return null
    // 纯数字（000/001…）视为无效
    return r.takeIf { !it.all(Char::isDigit) }
}

/**
 * 专辑清洗：剥前导序号/两端特殊符号（cleanTitleKeep），再去 cd/disc 标记；
 * 纯数字序号（000/001…）视为无效返回 null，避免专辑名残留垃圾编号。
 */
fun cleanAlbum(raw: String?): String? {
    if (raw.isNullOrBlank()) return raw
    val c = cleanTitleKeep(raw) ?: return null
    return stripDiscMarkers(c)?.takeIf { it.isNotBlank() }
}

/** 前导"数字+空白/分隔"式序号（需求：清 000 001 002）：如 "01 " "05. " "3 - "；限 3 位避免误删 4 位年份。 */
private val TRACK_NO_PREFIX_RE = Regex("""^\d{1,3}(?:[\.\-_:·.—]\s*|\s+)""")

/** 裸轨道序号前缀：如 "01. " "02-" "03_ 07：" → 歌名前的序号编号（需求3：入库歌名去掉这类前缀）。 */
private val STRIP_TRACK_RE = Regex("^\\d{1,4}\\s*[\\.\\-_:·—]")

/** 去除 artist/album 中的 "cd01 / disc 2 / disk3" 这类音轨媒体标记（需求3）。 */
private val CD_DISC_TOKEN_RE = Regex("(?i)\\b(?:cd|disc|disk)\\s*\\d{1,3}\\b")
private val MULTI_SPACE_RE = Regex("\\s{2,}")

fun stripDiscMarkers(s: String?): String? {
    if (s.isNullOrBlank()) return s
    return s.replace(CD_DISC_TOKEN_RE, " ").replace(MULTI_SPACE_RE, " ").trim()
}

/** 前导括号噪声：轨道序号（"(01) "、"（02）"、" [12] "）或空括号（"[] "、"() "），循环剥离后由后续规则继续解析。 */
private val LEADING_BRACKET_RE =
    Regex("^[\\[(（]\\s*(\\d{1,4})?\\s*[\\])）]\\s*[\\s\\-_.·—]+(.*)$")

private fun stripLeadingNoise(name: String): String {
    var n = name
    var guard = 0
    while (guard++ < 5) {
        val m = LEADING_BRACKET_RE.find(n) ?: break
        n = m.groupValues[2].trim()
    }
    return n
}
