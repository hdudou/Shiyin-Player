package com.shiyinplayer.data.sync

import com.shiyinplayer.data.local.AppDatabase
import com.shiyinplayer.data.local.cache.LyricCacheDao
import com.shiyinplayer.data.local.cache.MetadataCacheDao
import com.shiyinplayer.data.local.entity.MusicSourceEntity
import com.shiyinplayer.data.media.SmbCredentialStore
import com.shiyinplayer.data.metadata.MetadataCacheType
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.remote.webdav.WebDavCredentialStore
import com.shiyinplayer.data.repository.MetadataRepository
import com.shiyinplayer.data.sync.model.MusicSourceCreds
import com.shiyinplayer.data.sync.model.SyncContract
import com.shiyinplayer.data.sync.model.SyncEntityName
import com.shiyinplayer.data.sync.model.SyncMapper
import com.shiyinplayer.data.sync.model.emittableLyricSongIds
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `/sync/library` 的一页（契约 §4：未返回 `nextCursor` 即最后一页）。
 *
 * ⚠️ `musicSources` 是相对契约示例结构**新增的一节**（2026-09-16）：PC 的网络源配置存在
 * settings.json 而非 DB 表，不随快照下发则「拉取为本地」永远补不齐音乐源。
 * PC 侧 `SyncPeerClient.GetLibraryAsync` 已按该键读取，缺失时为空数组（向后兼容旧设备）。
 *
 * **凭据随源一并下发**（2026-09-18）：请求方 token 非空时，每条 `music_source` 附加
 * `record.creds` AES-256-GCM 信封（明文 `{username,password}`，口令 = 该配对设备 token）。
 * 口令两侧同源（PC `SyncPushService.BuildCredentialEnvelope` 用同一个 deviceToken 封），
 * 故 PC 解出的就是本机口令库里的值。**没有它，PC 侧拿到的源是不可访问的空壳** ——
 * 曲目定位符齐了也播不了（WebDAV/SMB 都要鉴权）。
 */
data class LibraryPage(
    val songs: JSONArray,
    val albums: JSONArray,
    val artists: JSONArray,
    val playlists: JSONArray,
    val playlistItems: JSONArray,
    val radioStations: JSONArray,
    val musicSources: JSONArray,
    val nextCursor: String?,
    /**
     * 歌词节（**schemaVersion 3 起**，键 `lyrics`）：`{songDedupKey,lrcText,translatedText,source,updatedAt}`。
     * 旧版对端（PC）不认这一节，但它读键取值时缺失即为空 —— 广发无害（D1 宽松协商）。
     */
    val lyrics: JSONArray = JSONArray()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("songs", songs)
        .put("albums", albums)
        .put("artists", artists)
        .put("playlists", playlists)
        .put("playlistItems", playlistItems)
        .put("radioStations", radioStations)
        .put("musicSources", musicSources)
        .put("lyrics", lyrics)
        .apply { if (nextCursor != null) put("nextCursor", nextCursor) }
}

/**
 * 只读库快照（契约 §4 `/sync/library`）。
 *
 * **游标设计**：`"<entity>:<offset>"` —— 七类实体按固定顺序串成一条流，每页只前进一个实体的偏移；
 * 某个实体取完后同一请求内继续取下一个实体（小库一次就能取完，大库分页）。
 * 这样多页拼接保证无重复、无遗漏，PC 只需把游标原样回传（契约明确游标是不透明字符串）。
 *
 * **禁用 Flow**：Room 2.6.1 下大结果集 Flow 会触发 CursorWindow NO_MEMORY 闪退，此处全部走
 * suspend 分页查询（`getAllPaged` / `getPagedWithNames` 等）。
 *
 * **字段名必须是契约名**：`albumTitle` / `trackNo` / `alternateUrls`（**不是** `albumName` /
 * `trackNumber` / `logoUrl`），映射集中在 [SyncMapper]，写成实体列名 PC 侧会静默丢字段。
 */
@Singleton
class SyncLibraryReader @Inject constructor(
    private val db: AppDatabase,
    private val smbCredentialStore: SmbCredentialStore,
    private val webDavCredentialStore: WebDavCredentialStore,
    /** 歌词（`lyrics` 表）与歌手头像/简介（`metadata` 表 `type=artist`）都在缓存库。 */
    private val lyricCacheDao: LyricCacheDao,
    private val metadataCacheDao: MetadataCacheDao
) {

    private val sequence = listOf(
        SyncEntityName.SONG,
        SyncEntityName.ALBUM,
        SyncEntityName.ARTIST,
        SyncEntityName.PLAYLIST,
        SyncEntityName.PLAYLIST_ITEM,
        SyncEntityName.RADIO_STATION,
        // 音乐源放最后：数量极少（个位数），放末尾可保证既有游标语义下先取大表，
        // 也能让「PC 已有本地库、只想补源」的场景尽早拿到它。
        SyncEntityName.MUSIC_SOURCE,
        // 歌词（v3）排在音乐源之后：它是**可选增值内容**，且量级可能不小
        // （本机实测 metadata.db 只有几百行，但取满全库时理论上万级），
        // 放最末可保证「PC 只想拿曲库/源」的场景不被它拖慢或撑爆单页预算。
        SyncEntityName.LYRICS
    )

    /**
     * @param credsToken 请求方（PC）的 deviceToken——`music_source` 凭据信封的口令。
     *   null/空表示不附带凭据（仅结构下发），旧调用方与无 token 场景自然退化。
     */
    suspend fun readPage(cursor: String?, credsToken: String? = null): LibraryPage {
        var typeIndex = cursorTypeIndex(cursor)
        var offset = cursorOffset(cursor)

        var songs = JSONArray()
        var albums = JSONArray()
        var artists = JSONArray()
        var playlists = JSONArray()
        var playlistItems = JSONArray()
        var radioStations = JSONArray()
        var musicSources = JSONArray()
        var lyrics = JSONArray()
        var nextCursor: String? = null

        while (typeIndex < sequence.size) {
            val type = sequence[typeIndex]
            val chunk = readChunk(type, offset, credsToken)
            when (type) {
                SyncEntityName.SONG -> songs = chunk.json
                SyncEntityName.ALBUM -> albums = chunk.json
                SyncEntityName.ARTIST -> artists = chunk.json
                SyncEntityName.PLAYLIST -> playlists = chunk.json
                SyncEntityName.PLAYLIST_ITEM -> playlistItems = chunk.json
                SyncEntityName.RADIO_STATION -> radioStations = chunk.json
                SyncEntityName.MUSIC_SOURCE -> musicSources = chunk.json
                SyncEntityName.LYRICS -> lyrics = chunk.json
            }
            // 取满一页 → 还有下一页；未取满 → 该实体已到末尾，同一请求里继续下一个实体。
            //
            // 🔴 判据必须用**原始行数**（`rawCount`）而不是输出条数：`lyrics` 节会跳过
            // 「曲目已删 / 无 dedupKey」的行，若按输出条数判断，恰好被跳掉几行的整页
            // （200 行里少一条）就会被误判成"该实体已到末尾" ⇒ 后面的歌词整段丢失，
            // 而 PC 侧只会表现为"有些歌没歌词"，极难定位。
            if (chunk.rawCount == SyncContract.LIBRARY_PAGE_SIZE) {
                nextCursor = "$type:${offset + SyncContract.LIBRARY_PAGE_SIZE}"
                break
            }
            typeIndex++
            offset = 0
        }

        return LibraryPage(
            songs, albums, artists, playlists, playlistItems, radioStations, musicSources,
            nextCursor, lyrics
        )
    }

    /**
     * 一页的原始查询结果。
     *
     * @param json 实际输出的条目（可能因过滤少于 [rawCount]）
     * @param rawCount 数据库返回的**原始行数** —— 游标是否推进只看它（见 [readPage] 的说明）
     */
    private class Chunk(val json: JSONArray, val rawCount: Int = json.length())

    private suspend fun readChunk(type: String, offset: Int, credsToken: String?): Chunk {
        val limit = SyncContract.LIBRARY_PAGE_SIZE
        return when (type) {
            SyncEntityName.SONG -> Chunk(JSONArray().also { arr ->
                db.songDao().getAllPaged(offset, limit).forEach { arr.put(SyncMapper.songToJson(it)) }
            })

            SyncEntityName.ALBUM -> Chunk(JSONArray().also { arr ->
                db.albumDao().getAllPaged(offset, limit).forEach { arr.put(SyncMapper.albumToJson(it)) }
            })

            SyncEntityName.ARTIST -> Chunk(JSONArray().also { arr ->
                db.artistDao().getAllPaged(offset, limit).forEach { artist ->
                    // v3：歌手头像 / 简介从缓存库取（本机 `artists` 表没有这两列，
                    // 唯一真源是 `metadata` 表 type=artist —— 在线取源与同步落地都写它）。
                    val cached = metadataCacheDao.get(artistCacheKey(artist.name), MetadataCacheType.ARTIST)
                    val meta = cached?.let { MetadataCacheType.decodeArtist(it.payload) }
                    arr.put(SyncMapper.artistToJson(artist, meta?.avatarUrl, meta?.bio))
                }
            })

            SyncEntityName.PLAYLIST -> Chunk(JSONArray().also { arr ->
                db.playlistDao().getAllPaged(offset, limit).forEach { arr.put(SyncMapper.playlistToJson(it)) }
            })

            SyncEntityName.PLAYLIST_ITEM -> Chunk(JSONArray().also { arr ->
                db.playlistItemDao().getPagedWithNames(offset, limit).forEach {
                    arr.put(
                        SyncMapper.playlistItemToJson(
                            playlistId = it.playlistId,
                            playlistName = it.playlistName,
                            songDedupKey = it.songDedupKey,
                            position = it.position
                        )
                    )
                }
            })

            SyncEntityName.RADIO_STATION -> Chunk(JSONArray().also { arr ->
                db.radioStationDao().getPaged(offset, limit).forEach { arr.put(SyncMapper.radioToJson(it)) }
            })

            // 音乐源：configJson 为中性明文，凭据另走 creds 信封（见 musicSourceJson）
            SyncEntityName.MUSIC_SOURCE -> Chunk(JSONArray().also { arr ->
                db.musicSourceDao().getPaged(offset, limit).forEach {
                    arr.put(musicSourceJson(it, credsToken))
                }
            })

            // 歌词（v3）：只发「已绑定 songId 且该曲目有 dedupKey」的行 —— 见 lyricCacheDao 里的说明
            SyncEntityName.LYRICS -> {
                val rows = lyricCacheDao.getBoundPaged(offset, limit)
                val arr = JSONArray()
                // 歌词在 metadata.db、曲目在 musicplayer.db ⇒ **跨库不能 JOIN**，
                // 只能先按 songId 批量取 dedupKey 再在内存里对上（分块规避 SQLite 变量数上限）。
                val dedupKeyById = HashMap<Long, String>(rows.size * 2)
                for (chunk in rows.mapNotNull { it.songId }.distinct().chunked(SQL_CHUNK)) {
                    db.songDao().getByIds(chunk).forEach { dedupKeyById[it.id] = it.dedupKey }
                }
                val emitted = emittableLyricSongIds(rows.map { it.songId }, dedupKeyById)
                if (emitted.isNotEmpty()) {
                    val rowById = rows.mapNotNull { r -> r.songId?.let { it to r } }.toMap()
                    for (songId in emitted) {
                        val row = rowById[songId] ?: continue
                        arr.put(
                            SyncMapper.lyricToJson(
                                songDedupKey = dedupKeyById.getValue(songId),
                                lrcText = row.lrcText,
                                translatedText = row.translatedText,
                                source = row.source,
                                updatedAt = row.updatedAt
                            )
                        )
                    }
                }
                Chunk(arr, rows.size)
            }

            else -> Chunk(JSONArray())
        }
    }

    /** 歌手缓存的键：与 [MetadataRepository.getArtistInfo] 的写法必须逐字一致，否则查不到。 */
    private fun artistCacheKey(name: String): String =
        "artist|" + MetadataRepository.songKey(name, null)

    private companion object {
        /** 单次 IN 查询的变量数上限（与 SyncApplyEngine 同口径）。 */
        const val SQL_CHUNK = 500
    }

    /**
     * 音乐源 → 契约 JSON，并在 [credsToken] 非空时附加凭据信封。
     *
     * 凭据来源是**本机加密凭据库**（`EncryptedSharedPreferences`），不是 DB 列——按
     * `configJson` 里的地址反查：WEBDAV 用 `url`、SMB 用 `host`，与
     * [com.shiyinplayer.data.sync.SyncApplyEngine.storeMusicSourceCreds] 的写入键严格对称，
     * 否则推过来的凭据存进去、这里又查不到。
     *
     * 查不到凭据（匿名源 / 用户从未填过）只返回中性结构，不报错：凭据是增值信息。
     */
    private fun musicSourceJson(source: MusicSourceEntity, credsToken: String?): JSONObject {
        val json = SyncMapper.musicSourceToJson(source)
        if (credsToken.isNullOrBlank()) return json

        val creds = musicSourceCreds(source) ?: return json
        if (creds.username.isEmpty() && creds.password.isEmpty()) return json

        val plain = JSONObject()
            .put("username", creds.username)
            .put("password", creds.password)
            .toString()
        json.put("creds", SyncCredentialCipher.seal(plain, credsToken))
        return json
    }

    private fun musicSourceCreds(source: MusicSourceEntity): MusicSourceCreds? {
        val config = runCatching { JSONObject(source.configJson) }.getOrNull() ?: return null
        return when (source.type) {
            MediaSourceType.WEBDAV -> config.optString("url").takeIf { it.isNotBlank() }
                ?.let { webDavCredentialStore.getForUrl(it) }
                ?.let { MusicSourceCreds(it.username, it.password) }

            MediaSourceType.SMB -> config.optString("host").takeIf { it.isNotBlank() }
                ?.let { smbCredentialStore.get(it) }
                ?.let { MusicSourceCreds(it.username, it.password) }

            else -> null
        }
    }

    private fun cursorTypeIndex(cursor: String?): Int {
        val type = cursor?.substringBefore(':') ?: return 0
        return sequence.indexOf(type).takeIf { it >= 0 } ?: 0
    }

    private fun cursorOffset(cursor: String?): Int {
        if (cursor.isNullOrEmpty()) return 0
        return cursor.substringAfter(':', "").toIntOrNull()?.takeIf { it >= 0 } ?: 0
    }
}
