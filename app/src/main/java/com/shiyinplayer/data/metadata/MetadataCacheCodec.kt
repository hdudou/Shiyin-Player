package com.shiyinplayer.data.metadata

import org.json.JSONObject

/** 元数据缓存编解码（type 与 JSON 载荷）。 */
object MetadataCacheType {
    const val SONG = "song"
    const val ALBUM = "album"
    const val ARTIST = "artist"

    fun encodeSong(meta: SongMetadata): String =
        JSONObject().apply {
            put("albumName", meta.albumName ?: "")
            put("coverUrl", meta.coverUrl ?: "")
            // 决策 6：发行年份（Int?，缺失则不写该键，解码侧还原为 null）
            if (meta.year != null) put("year", meta.year)
        }.toString()

    fun decodeSong(payload: String): SongMetadata? = try {
        val o = JSONObject(payload)
        val year = if (o.has("year")) o.optInt("year", 0).takeIf { it != 0 } else null
        SongMetadata(
            albumName = o.optString("albumName").takeIf { it.isNotBlank() },
            coverUrl = o.optString("coverUrl").takeIf { it.isNotBlank() },
            year = year
        )
    } catch (_: Exception) {
        null
    }

    fun encodeArtist(meta: ArtistMetadata): String =
        JSONObject()
            .put("name", meta.name)
            .put("avatarUrl", meta.avatarUrl ?: "")
            .put("bio", meta.bio ?: "")
            .toString()

    fun decodeArtist(payload: String): ArtistMetadata? = try {
        val o = JSONObject(payload)
        ArtistMetadata(
            name = o.optString("name", ""),
            avatarUrl = o.optString("avatarUrl").takeIf { it.isNotBlank() },
            bio = o.optString("bio").takeIf { it.isNotBlank() }
        )
    } catch (_: Exception) {
        null
    }
}