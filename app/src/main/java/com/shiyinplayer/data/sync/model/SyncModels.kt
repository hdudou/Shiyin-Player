package com.shiyinplayer.data.sync.model

import com.shiyinplayer.data.local.entity.AlbumEntity
import com.shiyinplayer.data.local.entity.ArtistEntity
import com.shiyinplayer.data.local.entity.MusicSourceEntity
import com.shiyinplayer.data.local.entity.PlaylistEntity
import com.shiyinplayer.data.local.entity.RadioStationEntity
import com.shiyinplayer.data.local.entity.SongEntity
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.sync.SyncCredentialCipher
import com.shiyinplayer.data.util.SongSearchKey
import org.json.JSONArray
import org.json.JSONObject

// ============================================================ 契约常量
//
// 权威来源：PC 主控端仓库的 docs/android-sync-module.md（PC 侧 46 断言联调通过）。
// 本文件是「契约名 ↔ 实体列」的唯一映射点：落库（SyncApplyEngine）与
// 只读快照序列化（/sync/library）共用同一套函数，避免两处字段名漂移。

object SyncContract {
    const val PORT = 23541

    /**
     * 同步协议版本（1.0.16 B4：2 → 3）。
     *
     * **3 相对 2 的增量**：封面 / 歌手头像 / 歌词随库快照下发 ——
     * `song.albumArtUrl`、`album.artUrl`、`artist.artUrl` + `artist.bio`，以及新增的 `lyrics` 节。
     *
     * ⚠️ **两端都不做版本门控**（D1 = 宽松协商）：升到 3 **不会拒连**，旧版对端只是不认这些键、
     * 静默丢弃 ⇒ 表现为「封面 / 头像 / 歌词没同步过来」，而不是报错。故本常量只用于
     * 能力声明（`/sync/hello` + mDNS TXT）与界面提示，不是拦截条件。
     *
     * ⚠️ 这是**同步**协议版本；**离线导包**（`DataTransferManager.SCHEMA_VERSION`）另有自己的
     * 版本号，且那里**有**硬门控（`pkgVer > SCHEMA_VERSION` 直接拒收）—— 两者不可混用。
     */
    const val SCHEMA_VERSION = 3
    /** 常态请求头（/sync/hello 与 /sync/pair 除外）。 */
    const val HEADER_DEVICE_TOKEN = "X-Device-Token"

    // `POST /sync/push-file` 的元数据请求头（契约 §4 两处非 JSON 例外之一：body 是原始二进制分块）。
    // 名字逐字对齐 PC 侧 `SyncPeerClient.PushFileAsync`，改名即收不到。
    const val HEADER_FILE_DEDUP_KEY = "X-Sync-File-DedupKey"
    /** **URL 编码**的相对路径，且已含 `synced/` 前缀（见 [com.shiyinplayer.data.sync.SyncFileStore]）。 */
    const val HEADER_FILE_REL_PATH = "X-Sync-File-RelPath"
    const val HEADER_FILE_OFFSET = "X-Sync-File-Offset"
    const val HEADER_FILE_TOTAL = "X-Sync-File-Total"

    /** 配对码时效（契约 §3 建议 5 分钟）。 */
    const val PIN_TTL_MS = 5 * 60 * 1000L
    /** 未决批次超时（PC 侧轮询 5 分钟后按失败处理，此处同步标记失败防堆积）。 */
    const val TICKET_TIMEOUT_MS = 5 * 60 * 1000L
    /** 只读快照单页条数（契约 §4：单页条数由安卓决定）。 */
    const val LIBRARY_PAGE_SIZE = 200
    /** 单文件接收上限（契约 §10 建议 ≤2GB）。 */
    const val MAX_RECEIVE_FILE_BYTES = 2L * 1024 * 1024 * 1024
    /**
     * 单次 `/sync/push-file` 分块接收上限。
     * PC 侧固定按 4MB 切片（`SyncPeerClient.FileChunkSize`），这里给 8MB 余量并**硬性拒绝**超大 body——
     * `Content-Length` 是不可信输入，不设闸就能被一条请求撑爆内存。
     */
    const val MAX_UPLOAD_CHUNK_BYTES = 8L * 1024 * 1024
}

/** ops[].op */
object SyncOpKind {
    const val UPSERT = "upsert"
    const val DELETE = "delete"
}

/** ops[].entity（契约 §4 实体名，逐字对齐）。 */
object SyncEntityName {
    const val SONG = "song"
    const val ALBUM = "album"
    const val ARTIST = "artist"
    const val PLAYLIST = "playlist"
    const val PLAYLIST_ITEM = "playlist_item"
    const val MUSIC_SOURCE = "music_source"
    const val RADIO_STATION = "radio_station"

    /**
     * 歌词（**schemaVersion 3 起**）：只存在于 `/sync/library` 快照里，**不是** op 实体。
     *
     * 为什么不做成 op：歌词是「按 `songDedupKey` 绑定的一整块文本」，没有独立的增删改语义
     * （曲目删了歌词跟着删），做成 op 只会让 PC 端多一套流水却换不来增量收益。
     * 见 [SyncEntityNames.ALL_ENTITIES] 的说明。
     */
    const val LYRICS = "lyrics"
}

/** /sync/confirm 的 status。 */
object SyncBatchStatus {
    /** 已收到、尚未落地（PC 轮询 `/sync/confirm` 读到它即「正在落地」）。 */
    const val PENDING = "pending"
    /** 已落地（本机唯一终止状态：PC 推来的变更无需确认，直接写入曲库）。 */
    const val ALLOWED = "allowed"
    /**
     * 保留状态：本机不存在「拒绝」路径，但 PC 侧防御性地保留了 `denied` 分支
     * （契约 §10），故常量继续保留以免契约漂移。
     */
    const val DENIED = "denied"
}

/** 全部合法实体名（op 校验用）。 */
object SyncEntityNames {
    /**
     * ⚠️ **刻意不含 [SyncEntityName.LYRICS]**：它是快照独有的一节，两端都不发 lyrics op
     * （PC 侧 `SyncPushService` 的 case 分支里也没有它）。若加进来，等于「接受一个自己无法
     * 应用的 op 类型」，会把契约漂移掩盖成静默跳过 —— 收到 lyrics op 就该按 unknown_op 判失败。
     */
    val ALL_ENTITIES: Set<String> = setOf(
        SyncEntityName.SONG, SyncEntityName.ALBUM, SyncEntityName.ARTIST,
        SyncEntityName.PLAYLIST, SyncEntityName.PLAYLIST_ITEM,
        SyncEntityName.MUSIC_SOURCE, SyncEntityName.RADIO_STATION
    )

    /**
     * `/sync/library` 返回的实体名。
     *
     * ⚠️ 与契约文档 §4 的示例结构（`{songs,albums,artists,playlists,playlistItems,radioStations}`）
     * 相比**多出 `musicSources` 一节**：那是 2026-09-15 审计发现的断链——
     * PC 的网络源配置存在 settings.json（不在 DB 表里），若不快照出去，PC 的「拉取为本地」
     * 永远补不齐音乐源。PC 侧 `SyncPeerClient.GetLibraryAsync` 已按 `musicSources` 键读取。
     *
     * `lyrics`（v3）同理，PC 侧同一个方法按 `lyrics` 键读取；旧版对端不返回该键时为 null。
     */
    val LIBRARY_TYPES = listOf(
        SyncEntityName.SONG, SyncEntityName.ALBUM, SyncEntityName.ARTIST,
        SyncEntityName.PLAYLIST, SyncEntityName.PLAYLIST_ITEM, SyncEntityName.RADIO_STATION,
        SyncEntityName.MUSIC_SOURCE, SyncEntityName.LYRICS
    )
}

// ============================================================ 契约数据类

/**
 * 单条 op。保留 [raw] 原样以便暂存落盘时无损回写（不重新构造 JSON，避免丢字段）。
 *
 * 定位键（契约 §4/§5）：
 * - `song`/`album`/`artist`/`radio_station`/`music_source` 删除 → 顶层 `dedupKey`（同时会写进 `record.dedupKey`）
 * - `playlist` 删除 → 顶层**不带** `dedupKey`，改用 `record.name`
 */
data class SyncOp(
    val raw: JSONObject,
    val op: String,
    val entity: String,
    val record: JSONObject,
    val id: String?,
    val tombstone: Boolean,
    val dedupKey: String?,
    val deleteFile: Boolean
) {
    val isDelete: Boolean get() = op == SyncOpKind.DELETE

    /** 该 op 是否可参与应用（未知实体/未知 op 直接判失败，不静默吞掉）。 */
    fun isKnown(): Boolean = entity in SyncEntityNames.ALL_ENTITIES &&
            (op == SyncOpKind.UPSERT || op == SyncOpKind.DELETE)

    companion object {
        fun parse(o: JSONObject): SyncOp {
            val rec = o.optJSONObject("record") ?: JSONObject()
            return SyncOp(
                raw = o,
                op = o.optString("op", SyncOpKind.UPSERT).lowercase(),
                entity = o.optString("entity", "").lowercase(),
                record = rec,
                id = o.scalarOrNull("id"),
                tombstone = o.optBoolean("tombstone", false),
                dedupKey = o.scalarOrNull("dedupKey") ?: rec.scalarOrNull("dedupKey"),
                deleteFile = o.optBoolean("deleteFile", false)
            )
        }

        fun parseList(arr: JSONArray?): List<SyncOp> {
            if (arr == null) return emptyList()
            val out = ArrayList<SyncOp>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(parse(it)) }
            }
            return out
        }
    }
}

/** 单 op 应用结果（/sync/result 元素）。 */
data class OpResult(val opIndex: Int, val ok: Boolean, val error: String? = null) {
    fun toJson(): JSONObject = JSONObject()
        .put("opIndex", opIndex)
        .put("ok", ok)
        .apply { if (error != null) put("error", error) }

    companion object {
        fun fromJson(o: JSONObject): OpResult =
            OpResult(o.optInt("opIndex", 0), o.optBoolean("ok", false), o.scalarOrNull("error"))
    }
}

/**
 * 推送批次（内存 + 落盘 `filesDir/sync/pending/<ticketId>.json`）。
 * 落盘目的：批次在落地前进程被杀时，重启后可补落地（契约 §6 可靠队列）。
 */
class PendingBatch(
    val ticketId: String,
    val receivedAt: Long,
    var status: String,
    val ops: List<SyncOp>,
    val results: MutableList<OpResult> = mutableListOf(),
    /** 发起本批次的 PC 设备名（按 token 反查配对记录得到，落盘以便排查与展示）。 */
    val deviceName: String = ""
) {
    /** 落盘序列化（供进程重启后补落地）。 */
    fun toJson(): JSONObject = JSONObject()
        .put("ticketId", ticketId)
        .put("receivedAt", receivedAt)
        .put("status", status)
        .put("deviceName", deviceName)
        .put("ops", JSONArray().also { a -> ops.forEach { a.put(it.raw) } })
        .put("results", JSONArray().also { a -> results.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): PendingBatch? {
            val ticketId = o.scalarOrNull("ticketId") ?: return null
            val ops = SyncOp.parseList(o.optJSONArray("ops"))
            val results = mutableListOf<OpResult>()
            o.optJSONArray("results")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { results.add(OpResult.fromJson(it)) }
            }
            return PendingBatch(
                ticketId = ticketId,
                receivedAt = o.optLong("receivedAt", 0L),
                status = o.optString("status", SyncBatchStatus.PENDING),
                ops = ops,
                results = results,
                deviceName = o.optString("deviceName", "")
            )
        }
    }
}

/**
 * 音乐源明文凭据（`record.creds` 信封解开后的内容）。
 *
 * 它**不是 DB 实体字段**：落库时写进加密凭据库
 * （SMB → [com.shiyinplayer.data.media.SmbCredentialStore]，
 * WebDAV → [com.shiyinplayer.data.remote.webdav.WebDavCredentialStore]），与源配置分离存储。
 */
data class MusicSourceCreds(val username: String, val password: String)

// ============================================================ 契约名 ↔ 实体列 映射
//
// ⚠️ 字段名差异（契约 §4，改名即 PC 读不到）：
//   albumTitle   ↔ songs.albumName
//   trackNo      ↔ songs.trackNumber
//   lastPlayedAt ↔ songs.lastPlayedMs
//   fileSize     ↔ songs.sizeBytes
//   album.title  ↔ albums.name
//   playlist.createdAt/updatedAt ↔ playlists.dateCreated/dateModified
//   radio.alternateUrls（JSON 数组）↔ radio_station.alternateUrls（JSON 文本列）

object SyncMapper {

    // -------- song --------

    /**
     * 契约 record → SongEntity。
     *
     * @param existing 本机同 dedupKey 的既有行（无则 null → 新建，id 由 Room 生成）
     * @param artistId/albumId 本机外键（由 albums/artists 落库结果解析，PC 的 id 对本机无意义）
     *
     * 语义：PC 权威、v1 直接覆盖。**键缺失**（而非显式 null）时保留既有值，避免字段不全的
     * payload 把本机数据抹成空。
     */
    fun songFromJson(
        rec: JSONObject,
        existing: SongEntity?,
        artistId: Long? = null,
        albumId: Long? = null
    ): SongEntity {
        val title = rec.stringOr("title", existing?.title) ?: ""
        val artistName = rec.stringOr("artistName", existing?.artistName)
        val albumName = rec.stringOr("albumTitle", existing?.albumName)
        val dedupKey = rec.stringOr("dedupKey", existing?.dedupKey) ?: ""
        val uri = rec.stringOr("uri", existing?.uri)
            ?: rec.stringOr("path", existing?.path)
            ?: existing?.uri
            ?: ""
        val sourceType = rec.stringOr("sourceType", null)
            ?.let { runCatching { MediaSourceType.valueOf(it.uppercase()) }.getOrNull() }
            ?: existing?.sourceType
            ?: MediaSourceType.LOCAL
        return SongEntity(
            id = existing?.id ?: 0L,
            title = title,
            artistId = artistId ?: existing?.artistId,
            albumId = albumId ?: existing?.albumId,
            artistName = artistName,
            albumName = albumName,
            // 需求⑥/v3：PC 传的是**远程地址**（`albumArtUrl`），不是本机路径。
            // 只认 http/https —— 本机 `albumArtUri` 一列混存 file:// 与远程 URL，
            // 若把 PC 的本地路径/空串吞进来，会把一条本来能显示的封面抹成不可加载。
            albumArtUri = remoteUrlOrNull(rec, "albumArtUrl") ?: existing?.albumArtUri,
            durationMs = rec.longOr("durationMs", existing?.durationMs ?: 0L),
            trackNumber = rec.intOr("trackNo", existing?.trackNumber ?: 0),
            uri = uri,
            mimeType = existing?.mimeType,
            sourceType = sourceType,
            path = rec.stringOr("path", existing?.path),
            dateAdded = existing?.dateAdded ?: 0L,
            sizeBytes = rec.longOr("fileSize", existing?.sizeBytes ?: 0L),
            cueId = existing?.cueId,
            trackIndex = existing?.trackIndex,
            clipStartMs = existing?.clipStartMs,
            clipEndMs = existing?.clipEndMs,
            dedupKey = dedupKey,
            genre = rec.stringOr("genre", existing?.genre),
            year = rec.intOrNull("year", existing?.year),
            rating = rec.intOr("rating", existing?.rating ?: 0),
            playCount = rec.intOr("playCount", existing?.playCount ?: 0),
            lastPlayedMs = rec.longOr("lastPlayedAt", existing?.lastPlayedMs ?: 0L),
            formatVerified = existing?.formatVerified ?: true,
            lyricOffsetMs = rec.longOr("lyricOffsetMs", existing?.lyricOffsetMs ?: 0L),
            // 标题/歌手/专辑变了必须重算搜索键，否则拼音搜索匹配不到同步来的曲目
            searchKey = SongSearchKey.of(title, artistName, albumName),
            favorite = rec.boolOr("favorite", existing?.favorite ?: false),
            discNo = rec.intOr("discNo", existing?.discNo ?: 0),
            updatedAt = rec.longOr("updatedAt", existing?.updatedAt ?: 0L)
        )
    }

    /** SongEntity → 契约 JSON（/sync/library）。键名必须是契约名。 */
    fun songToJson(s: SongEntity): JSONObject = JSONObject()
        .put("id", s.id)
        .put("title", s.title)
        .put("artistId", s.artistId)
        .put("albumId", s.albumId)
        .put("artistName", s.artistName)
        .put("albumTitle", s.albumName)
        .put("path", s.path)
        .put("uri", s.uri)
        .put("durationMs", s.durationMs)
        .put("trackNo", s.trackNumber)
        .put("discNo", s.discNo)
        .put("year", s.year)
        .put("genre", s.genre)
        .put("rating", s.rating)
        .put("playCount", s.playCount)
        .put("lastPlayedAt", s.lastPlayedMs)
        .put("favorite", s.favorite)
        .put("sourceType", s.sourceType.name)
        .put("fileSize", s.sizeBytes)
        .put("dedupKey", s.dedupKey)
        .put("lyricOffsetMs", s.lyricOffsetMs)
        .put("updatedAt", s.updatedAt)
        // v3：只回传**远程**地址。本机扫描落的封面是 `file:///data/.../cacheDir/artwork/...`，
        // 对 PC 是无意义路径（D2：路径只对本机有意义），故非 http(s) 一律写空串。
        // 不写 JSON null：PC 侧 `Str()` 取到 null 会变成空串还好，但契约要求「字符串字段恒为字符串」。
        .put("albumArtUrl", remoteUrlOf(s.albumArtUri))
    // 注：契约 song 键集里的 sourceId 本机无对应列（PC 侧源主键对本机无意义），不输出。

    // -------- album --------

    fun albumFromJson(rec: JSONObject, existing: AlbumEntity?): AlbumEntity = AlbumEntity(
        id = existing?.id ?: 0L,
        name = rec.stringOr("title", existing?.name) ?: "",
        artistName = rec.stringOr("artistName", existing?.artistName),
        // 同 song：只认远程地址（契约键是 `artUrl`，与 song 的 `albumArtUrl` 不同名 —— 逐字对齐）
        albumArtUri = remoteUrlOrNull(rec, "artUrl") ?: existing?.albumArtUri,
        year = rec.intOrNull("year", existing?.year),
        songCount = existing?.songCount ?: 0,
        artistId = existing?.artistId,
        updatedAt = rec.longOr("updatedAt", existing?.updatedAt ?: 0L)
    )

    fun albumToJson(a: AlbumEntity): JSONObject = JSONObject()
        .put("id", a.id)
        .put("title", a.name)
        .put("artistId", a.artistId)
        .put("artistName", a.artistName)
        .put("year", a.year)
        .put("updatedAt", a.updatedAt)
        .put("artUrl", remoteUrlOf(a.albumArtUri))   // v3：同 song，只回传远程地址

    // -------- artist --------

    fun artistFromJson(rec: JSONObject, existing: ArtistEntity?): ArtistEntity = ArtistEntity(
        id = existing?.id ?: 0L,
        name = rec.stringOr("name", existing?.name) ?: "",
        albumCount = existing?.albumCount ?: 0,
        songCount = existing?.songCount ?: 0,
        updatedAt = rec.longOr("updatedAt", existing?.updatedAt ?: 0L)
    )

    /**
     * ArtistEntity → 契约 JSON（`/sync/library`）。
     *
     * v3 新增 `artUrl` / `bio` —— 本机**没有**这两列（`artists` 表只有 id/name/count/updatedAt），
     * 歌手的头像与简介存在缓存库 `metadata` 表（`type=artist`）。故由调用方
     * （[com.shiyinplayer.data.sync.SyncLibraryReader]）查缓存后传入；
     * 查不到（或存量库里没有这条缓存）就传空串。
     *
     * ⚠️ 不写成"从 ArtistEntity 直接映射"是刻意的：那样会诱导后来者给 `artists` 表加列，
     * 而本机歌手资料的**唯一真源**就是 metadata 缓存（在线取源与同步落地都写它）。
     */
    fun artistToJson(a: ArtistEntity, artUrl: String? = null, bio: String? = null): JSONObject = JSONObject()
        .put("id", a.id)
        .put("name", a.name)
        .put("updatedAt", a.updatedAt)
        .put("artUrl", remoteUrlOf(artUrl))
        .put("bio", bio.orEmpty())

    // -------- playlist --------

    fun playlistFromJson(rec: JSONObject, existing: PlaylistEntity?): PlaylistEntity = PlaylistEntity(
        id = existing?.id ?: 0L,
        name = rec.stringOr("name", existing?.name) ?: "",
        dateCreated = rec.longOr("createdAt", existing?.dateCreated ?: 0L),
        dateModified = rec.longOr("updatedAt", existing?.dateModified ?: 0L)
    )

    fun playlistToJson(p: PlaylistEntity): JSONObject = JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("createdAt", p.dateCreated)
        .put("updatedAt", p.dateModified)

    // -------- playlist_item --------
    //
    // 契约形态固定为 {playlistId, playlistName, songDedupKey, position}
    // （带 songId/sortOrder 的形态是 PC 未启用的预留代码，本机无需实现）。
    // 两个方向都必须用它：本机落库靠 songDedupKey 解析 songId，/sync/library 也必须按这套键输出。

    fun playlistItemToJson(
        playlistId: Long,
        playlistName: String,
        songDedupKey: String,
        position: Int
    ): JSONObject = JSONObject()
        .put("playlistId", playlistId)
        .put("playlistName", playlistName)
        .put("songDedupKey", songDedupKey)
        .put("position", position)
        .put("addedAt", 0L)

    // -------- music_source --------

    /**
     * 契约 record → MusicSourceEntity。
     *
     * **`configJson` 恒为明文中性 JSON**（§9.3），两种来源都走这一条路径：
     * - DB 实体版 `MusicSourceToJson`：PC 的 `MusicSource.ConfigJson`（凭据不入库，走平台凭据存储）。
     * - 网络源版 `MusicSourceToJson(NetworkSourceInfo, creds)`（2026-09-16 起实际使用）：
     *   WEBDAV → `{url,username,rootPath}`；SMB → `{host,share,port:445,domain,username,rootPath}`。
     *   其中 `username` 是**非敏感**的展示/对齐字段，密码不在这里。
     *
     * **凭据另走 `record.creds`（AES-256-GCM 信封，见 [musicSourceCredsFromJson]）**：
     * 它不属于实体列，故本函数只负责 configJson/type/name 等落库字段，凭据由调用方
     * （[com.shiyinplayer.data.sync.SyncApplyEngine]）解信封后写进对应的加密凭据库。
     *
     * 防御分支：若将来 PC 把信封直接塞进 `configJson`（`encrypted:true`），本函数原样保存
     * 并置 [isCredentialEnvelope] 为真供上层告警——不损坏数据，也不假装解开了。
     */
    fun musicSourceFromJson(rec: JSONObject, existing: MusicSourceEntity?): MusicSourceEntity {
        val rawConfig = rec.opt("configJson")
        val configText = when (rawConfig) {
            is JSONObject -> rawConfig.toString()
            is JSONArray -> rawConfig.toString()
            null, JSONObject.NULL -> existing?.configJson ?: "{}"
            else -> rawConfig.toString()
        }
        val type = rec.stringOr("type", null)
            ?.let { runCatching { MediaSourceType.valueOf(it.uppercase()) }.getOrNull() }
            ?: existing?.type
            ?: MediaSourceType.LOCAL
        return MusicSourceEntity(
            id = existing?.id ?: 0L,
            name = rec.stringOr("name", existing?.name) ?: "",
            type = type,
            configJson = configText,
            enabled = rec.boolOr("enabled", existing?.enabled ?: true),
            lastScanTime = existing?.lastScanTime ?: 0L,
            createdAt = rec.longOr("createdAt", existing?.createdAt ?: 0L),
            updatedAt = rec.longOr("updatedAt", existing?.updatedAt ?: 0L)
        )
    }

    /**
     * `record.creds` 信封 → 明文凭据；无信封 / 口令不符 / 格式非法一律返回 null。
     *
     * 口令 = 该配对设备的 `deviceToken`（PC `SyncPushService.BuildCredentialEnvelope` 同源同口令）。
     * 凭据是**增值信息**：解不开只是少存一个密码，绝不能让整个 op 失败。
     */
    fun musicSourceCredsFromJson(rec: JSONObject, token: String?): MusicSourceCreds? {
        val envelope = rec.stringOr("creds", null) ?: return null
        val plain = SyncCredentialCipher.open(envelope, token) ?: return null
        val node = runCatching { JSONObject(plain) }.getOrNull() ?: return null
        val username = node.optString("username", "")
        val password = node.optString("password", "")
        if (username.isEmpty() && password.isEmpty()) return null // 匿名源
        return MusicSourceCreds(username, password)
    }

    /** 该 configJson 是否为 AES-GCM 信封（见上，仅用于告警判定）。 */
    fun isCredentialEnvelope(configJson: String): Boolean =
        runCatching { JSONObject(configJson).optBoolean("encrypted", false) }.getOrDefault(false)

    fun musicSourceToJson(m: MusicSourceEntity): JSONObject = JSONObject()
        .put("id", m.id)
        .put("name", m.name)
        .put("type", m.type.name)
        .put("configJson", runCatching { JSONObject(m.configJson) }.getOrElse { JSONObject() })
        .put("enabled", m.enabled)
        .put("createdAt", m.createdAt)
        .put("updatedAt", m.updatedAt)

    // -------- radio_station --------

    fun radioFromJson(rec: JSONObject, existing: RadioStationEntity?, now: Long): RadioStationEntity {
        val urls = rec.optJSONArray("alternateUrls")
        val alternateUrls = when {
            urls != null -> urls.toString()
            rec.has("alternateUrls") -> existing?.alternateUrls
            else -> existing?.alternateUrls
        }
        return RadioStationEntity(
            id = existing?.id ?: 0L,
            name = rec.stringOr("name", existing?.name) ?: (rec.stringOr("url", null) ?: ""),
            url = rec.stringOr("url", existing?.url) ?: "",
            logoUrl = existing?.logoUrl,
            genre = rec.stringOr("genre", existing?.genre),
            country = rec.stringOr("country", existing?.country),
            source = rec.stringOr("source", existing?.source ?: "sync") ?: "sync",
            // 收藏是用户本机数据，PC 推送不覆盖（PC 侧同一字段由用户在其端操作）
            isFavorite = existing?.isFavorite ?: rec.boolOr("isFavorite", false),
            createdAt = existing?.createdAt ?: rec.longOr("createdAt", now),
            updatedAt = rec.longOr("updatedAt", now),
            alternateUrls = alternateUrls,
            bitrate = rec.intOr("bitrate", existing?.bitrate ?: 0),
            listenCount = rec.intOr("listenCount", existing?.listenCount ?: 0),
            lastPlayedAt = rec.longOr("lastPlayedAt", existing?.lastPlayedAt ?: 0L)
        )
    }

    fun radioToJson(r: RadioStationEntity): JSONObject = JSONObject()
        .put("id", r.id)
        .put("name", r.name)
        .put("url", r.url)
        .put("alternateUrls", runCatching { JSONArray(r.alternateUrls ?: "[]") }.getOrElse { JSONArray() })
        .put("genre", r.genre)
        .put("country", r.country)
        .put("source", r.source)
        .put("isFavorite", r.isFavorite)
        .put("listenCount", r.listenCount)
        .put("lastPlayedAt", r.lastPlayedAt)
        .put("bitrate", r.bitrate)
        .put("createdAt", r.createdAt)
        .put("updatedAt", r.updatedAt)

    // -------- v3：歌词（快照 `lyrics` 节）--------

    /**
     * 一条歌词 → 契约 JSON。
     *
     * 键名逐字对齐 PC 侧 `SyncPayloadBuilder.LyricToJson`（`songDedupKey/lrcText/translatedText/
     * source/updatedAt`）；`source` 用规范 provider id（`netease` 等，见 1.0.16 B1 的来源标识真源），
     * 两端因此可直接比对。
     *
     * 刻意**不接收 LyricCacheEntity**：`SyncMapper` 不引 cache 包，保持「契约名 ↔ 值」的纯粹映射，
     * 也让 JVM 单测不必构造 Room 实体。
     */
    fun lyricToJson(
        songDedupKey: String,
        lrcText: String,
        translatedText: String?,
        source: String,
        updatedAt: Long
    ): JSONObject = JSONObject()
        .put("songDedupKey", songDedupKey)
        .put("lrcText", lrcText)
        .put("translatedText", translatedText.orEmpty())   // 契约：字符串字段恒为字符串
        .put("source", source)
        .put("updatedAt", updatedAt)

    // -------- v3：媒体地址（封面 / 头像）--------
    //
    // 为什么两端只在「远程地址」上对齐：本机 `songs.albumArtUri` / `albums.albumArtUri` 一列
    // **混存 file:// 与远程 https**（扫描内嵌图画在 `cacheDir/artwork/`，在线补全只写 URL）。
    // 而 PC 侧对应列（`AlbumArtUrl` / `ArtUrl`）**只存远程地址**。若把 file:// 原样回传，
    // 对端拿到的是一条它机器上不存在的路径（D2：路径只对本机有意义）——比"没有"更糟。

    /** 非 http(s) 的一律归一成空串（含 null）；契约要求字符串字段恒为字符串，绝不写 JSON null。 */
    private fun remoteUrlOf(value: String?): String = syncRemoteUrl(value)

    /** 入方向：键存在且是远程地址才返回该值；否则 null（由调用方回退本机既有值）。 */
    private fun remoteUrlOrNull(rec: JSONObject, key: String): String? =
        remoteUrlOf(rec.stringOr(key, null)).takeIf { it.isNotEmpty() }
}

/**
 * 「只传地址」口径的落地点（v3）：**只有 http(s) 才算对端可达的地址**，其余归一成空串。
 *
 * 为什么必须归一而不是原样透传：本机 `albumArtUri` 一列混存 file:// 与远程 https，
 * 而 file:// 路径（`/data/user/0/com.shiyinplayer/cacheDir/artwork/xxx.jpg`）对 PC 是
 * 一条不存在的路径。传过去比"没有"更糟 —— 对端会拿它去下载/显示并失败。
 *
 * 抽成顶层 `internal` 纯函数是为了可单测：JVM 单测里 `org.json` 是 stub
 * （`isReturnDefaultValues = true`），凡碰 JSONObject 的路径都断言不了。
 */
internal fun syncRemoteUrl(value: String?): String {
    val v = value?.trim().orEmpty()
    return if (v.startsWith("http://", ignoreCase = true) ||
        v.startsWith("https://", ignoreCase = true)
    ) v else ""
}

/**
 * 快照 `lyrics` 节该发哪些曲目（纯函数，可离线单测）。
 *
 * @param songIds 本页歌词行的 `songId`（保持 DAO 的稳定顺序；未绑定的行为 null）
 * @param dedupKeyById `songs.id → dedupKey`（调用方按批查好）
 * @return 应下发的 `songId` 顺序列表 —— 已剔除「曲目已删 / 无 dedupKey / 同曲目重复」的行。
 *
 * 三条剔除规则各有其因：
 * - `null`：未绑定曲目的旧缓存行（按标题+艺术家存），两端归一化不同 ⇒ 对端对不上人（D3）。
 * - 查不到 / 空 `dedupKey`：曲目已被删（歌词清理可能还没跑到），或键尚未回填 ⇒ 对端落不了地。
 * - 重复：同一曲目多行歌词，展示侧本来只取最新一条（见 `LyricCacheDao.getBySongId`）；
 *   全发出去会让对端反复覆盖同一曲目，最终留下哪条取决于传输顺序。
 */
internal fun emittableLyricSongIds(
    songIds: List<Long?>,
    dedupKeyById: Map<Long, String>
): List<Long> {
    val seen = HashSet<Long>(songIds.size)
    val out = ArrayList<Long>(songIds.size)
    for (id in songIds) {
        if (id == null) continue
        if (dedupKeyById[id].isNullOrEmpty()) continue
        if (!seen.add(id)) continue
        out.add(id)
    }
    return out
}

// ============================================================ JSON 取值助手
//
// 语义约定：**键缺失** → 回退 fallback；**键显式为 null** → 视为 null（PC 权威，允许清空字段）。
// 与 DataTransferManager 内的私有同名扩展同义（那份 private，无法复用）。

internal fun JSONObject.scalarOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key) ?: return null
    return when (v) {
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> v.toString()
    }
}

internal fun JSONObject.stringOr(key: String, fallback: String?): String? =
    if (!has(key)) fallback else if (isNull(key)) null else optString(key, fallback ?: "")

internal fun JSONObject.longOr(key: String, fallback: Long): Long =
    if (!has(key) || isNull(key)) fallback else optLong(key, fallback)

internal fun JSONObject.intOr(key: String, fallback: Int): Int =
    if (!has(key) || isNull(key)) fallback else optInt(key, fallback)

internal fun JSONObject.intOrNull(key: String, fallback: Int?): Int? =
    if (!has(key)) fallback else if (isNull(key)) null else optInt(key, fallback ?: 0)

internal fun JSONObject.boolOr(key: String, fallback: Boolean): Boolean =
    if (!has(key) || isNull(key)) fallback else optBoolean(key, fallback)
