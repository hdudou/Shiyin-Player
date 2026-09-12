package com.shiyinplayer.data.metadata

/**
 * 统一元数据源接口（参照 music-tag-web 的「平台客户端工厂」范式，重构自原有 5 个硬编码源）。
 * 每个中文/西方平台实现一个实例；[SourceRegistry] 按用户配置的统一顺序做多源兜底。
 */
enum class MetaCapability {
    LYRIC,    // 提供歌词
    COVER,    // 搜索结果携带封面
    ARTIST,   // 提供歌手信息（头像/简介）
    YEAR      // 搜索结果携带发行年份
}

interface MetadataSource {
    /** 源标识：netease/qq/kuwo/migu/kugou/genius/theaudiodb/wikipedia */
    val id: String
    val displayName: String
    val capabilities: Set<MetaCapability>

    /** 按标题 + 歌手（可选）搜索候选曲目。 */
    suspend fun search(title: String, artist: String?): List<SongMatch>

    /** 取某候选的歌词（LRC 或纯文本）。match.extra 可携带源私有参数（如酷狗 accesskey）。 */
    suspend fun lyric(match: SongMatch): LyricDocument?

    /** 取歌手信息；默认返回 null（无此能力的源复用其它源兜底）。 */
    suspend fun artist(name: String): ArtistMetadata? = null
}
