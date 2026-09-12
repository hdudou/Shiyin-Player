package com.shiyinplayer.data.model

/** F2-2：搜索类型切换——限定歌曲搜索结果匹配的字段（专辑/艺术家/文件名也可作为定向条件）。 */
enum class SearchField {
    ALL,
    TITLE,
    ARTIST,
    ALBUM,
    FILENAME
}