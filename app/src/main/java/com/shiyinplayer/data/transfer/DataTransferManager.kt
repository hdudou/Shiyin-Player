package com.shiyinplayer.data.transfer

import android.content.Context
import android.util.Base64
import androidx.annotation.StringRes
import androidx.room.withTransaction
import com.shiyinplayer.R
import com.shiyinplayer.data.local.AppDatabase
import com.shiyinplayer.data.local.dao.AlbumDao
import com.shiyinplayer.data.local.dao.ArtistDao
import com.shiyinplayer.data.local.dao.MusicSourceDao
import com.shiyinplayer.data.local.dao.PlaylistDao
import com.shiyinplayer.data.local.dao.PlaylistItemDao
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.local.entity.AlbumEntity
import com.shiyinplayer.data.local.entity.ArtistEntity
import com.shiyinplayer.data.local.entity.MusicSourceEntity
import com.shiyinplayer.data.local.entity.PlaylistEntity
import com.shiyinplayer.data.local.entity.PlaylistItemEntity
import com.shiyinplayer.data.util.SongSearchKey
import com.shiyinplayer.data.local.entity.SongEntity
import com.shiyinplayer.data.media.FolderStructureBuilder
import com.shiyinplayer.data.media.SmbCredentialStore
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.remote.webdav.WebDavCredentialStore
import com.shiyinplayer.ui.settings.SettingEntry
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** 导入数据包的实际内容（供确认对话框展示将覆盖哪些数据及其数量）。 */
data class ImportPreview(
    val hasSettings: Boolean,
    val settingCount: Int,
    val sourceCount: Int,
    val credentialCount: Int,
    val songCount: Int,
    val albumCount: Int,
    val artistCount: Int,
    val playlistCount: Int,
    val itemCount: Int
) {
    val isEmpty: Boolean
        get() = !hasSettings && songCount == 0 && playlistCount == 0
}

/** 用户可读的导入/导出校验错误：携带字符串资源 id 与参数，由 UI 按当前语言渲染。 */
class TransferError(@StringRes val messageRes: Int, vararg val args: Any) : Exception()

/**
 * 播放器数据导出/导入（更多 → 播放器数据导出/导入）。
 * 导出为一个 UTF-8 JSON 单文件，可按勾选范围包含三节：
 * - settings：DataStore 全部设置（含主题/各开关/ZeroTier 网络）+ music_sources 源列表（保留 id）
 * - songs：songs + albums + artists 全表（含元数据，保留主键外键）
 * - playlists：playlists + playlist_items（songId 引用 songs.id，须随 songs 一起导入才完整）
 *
 * 导入时各节覆盖写回对应数据。歌曲导入后重建 folder_entry 目录树（FolderStructureBuilder）。
 * 网络源登录凭据（EncryptedSharedPreferences）默认不导出/导入（敏感数据），源列表与配置随源导出。
 */
@Singleton
class DataTransferManager @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val songDao: SongDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val playlistItemDao: PlaylistItemDao,
    private val musicSourceDao: MusicSourceDao,
    private val smbCredentialStore: SmbCredentialStore,
    private val webDavCredentialStore: WebDavCredentialStore,
    private val folderStructureBuilder: FolderStructureBuilder,
    private val db: AppDatabase,
    @ApplicationContext private val context: Context
) {

    // ============================= 导出 =============================

    suspend fun buildExport(
        includeSettings: Boolean,
        includeSongs: Boolean,
        includePlaylists: Boolean,
        password: String?
    ): ByteArray {
        val root = JSONObject()
        root.put("app", context.packageName)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("schemaVersion", SCHEMA_VERSION)

        if (includeSettings) {
            // 2026-08-28 高危-01：导出含网络源凭据时强制密码保护，杜绝明文凭据落盘/分享。
            val smbCreds = smbCredentialStore.getAll()
            val webdavCreds = webDavCredentialStore.getAll()
            if ((smbCreds.isNotEmpty() || webdavCreds.isNotEmpty()) && password.isNullOrBlank()) {
                throw TransferError(R.string.transfer_err_need_credentials)
            }
            val s = JSONObject()
            val prefs = JSONArray()
            settingsRepository.exportSettings().forEach { e ->
                prefs.put(JSONObject().put("name", e.name).put("type", e.type).put("value", e.value))
            }
            s.put("preferences", prefs)
            val srcs = JSONArray()
            musicSourceDao.observeAll().first().forEach { srcs.put(musicSourceJson(it)) }
            s.put("musicSources", srcs)
            // 网络源登录凭据（随「系统设置/网络和源设置」一并导出）
            val creds = JSONArray()
            smbCreds.forEach { (host, c) ->
                creds.put(JSONObject().put("provider", "smb").put("host", host)
                    .put("user", c.username).put("pass", c.password))
            }
            webdavCreds.forEach { (host, c) ->
                creds.put(JSONObject().put("provider", "webdav").put("host", host)
                    .put("user", c.username).put("pass", c.password))
            }
            s.put("credentials", creds)
            root.put("settings", s)
        }

        if (includeSongs) {
            val sn = JSONObject()
            val songs = JSONArray()
            // 2026-08-28 分页拉取，避免万级曲目一次性消费全表 Flow 触发 CursorWindow 溢出
            collectPaged { songDao.getAllPaged(it, EXPORT_PAGE_SIZE) }.forEach { songs.put(songJson(it)) }
            sn.put("songs", songs)
            val albums = JSONArray()
            collectPaged { albumDao.getAllPaged(it, EXPORT_PAGE_SIZE) }.forEach { albums.put(albumJson(it)) }
            sn.put("albums", albums)
            val artists = JSONArray()
            collectPaged { artistDao.getAllPaged(it, EXPORT_PAGE_SIZE) }.forEach { artists.put(artistJson(it)) }
            sn.put("artists", artists)
            root.put("songs", sn)
        }

        if (includePlaylists) {
            val pl = JSONObject()
            val playlists = JSONArray()
            playlistDao.observeAll().first().forEach { playlists.put(playlistJson(it)) }
            pl.put("playlists", playlists)
            val items = JSONArray()
            playlistItemDao.getAllOnce().forEach { items.put(playlistItemJson(it)) }
            pl.put("items", items)
            root.put("playlists", pl)
        }

        val payload = root.toString().toByteArray(Charsets.UTF_8)
        return if (password.isNullOrBlank()) payload
        else encrypt(payload, password).toString().toByteArray(Charsets.UTF_8)
    }

    // ============================= 加密 / 解密 =============================

    /** 2026-08-28：分页逐批收集全表数据，避免一次性消费大结果集触发 CursorWindow 溢出。
     *  pageLoader 作尾参数以便尾随 lambda 调用（pageSize 走默认）。 */
    private suspend fun <T> collectPaged(
        pageSize: Int = EXPORT_PAGE_SIZE,
        pageLoader: suspend (offset: Int) -> List<T>
    ): List<T> {
        val out = mutableListOf<T>()
        var offset = 0
        while (true) {
            val chunk = pageLoader(offset)
            out.addAll(chunk)
            if (chunk.size < pageSize) break
            offset += chunk.size
        }
        return out
    }

    /** 数据包是否受密码保护（外层含 cipher 字段）。 */
    fun isEncrypted(data: ByteArray): Boolean =
        runCatching { JSONObject(String(data, Charsets.UTF_8)).optBoolean("encrypted", false) }.getOrDefault(false)

    /** 用密码解密受保护数据包，返回内部明文 payload。密码错误抛异常。 */
    fun decrypt(data: ByteArray, password: String): ByteArray {
        val root = JSONObject(String(data, Charsets.UTF_8))
        val crypto = root.getJSONObject("crypto")
        val salt = Base64.decode(crypto.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(crypto.getString("iv"), Base64.NO_WRAP)
        val iter = crypto.getInt("iter")
        val ciphertext = Base64.decode(root.getString("cipher"), Base64.NO_WRAP)

        val key = deriveKey(password, salt, iter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun encrypt(payload: ByteArray, password: String): JSONObject {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(payload)

        return JSONObject()
            .put("app", context.packageName)
            .put("schemaVersion", 2)
            .put("encrypted", true)
            .put("crypto", JSONObject()
                .put("kdf", "pbkdf2-sha256")
                .put("iter", PBKDF2_ITERATIONS)
                .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP)))
            .put("cipher", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
    }

    /** PBKDF2 派生 AES-256 密钥。 */
    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LEN_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    // ============================= 导入 =============================

    /** 读取数据包并返回其包含的内容，供导入前确认展示。 */
    suspend fun parseImport(data: ByteArray): ImportPreview {
        val root = JSONObject(String(data, Charsets.UTF_8))
        val s = root.optJSONObject("settings")
        val sn = root.optJSONObject("songs")
        val pl = root.optJSONObject("playlists")

        val prefCount = s?.optJSONArray("preferences")?.length() ?: 0
        val sourceCount = s?.optJSONArray("musicSources")?.length() ?: 0
        val credentialCount = s?.optJSONArray("credentials")?.length() ?: 0
        val songCount = sn?.optJSONArray("songs")?.length() ?: 0
        val albumCount = sn?.optJSONArray("albums")?.length() ?: 0
        val artistCount = sn?.optJSONArray("artists")?.length() ?: 0
        val playlistCount = pl?.optJSONArray("playlists")?.length() ?: 0
        val itemCount = pl?.optJSONArray("items")?.length() ?: 0

        return ImportPreview(
            hasSettings = s != null && (prefCount > 0 || sourceCount > 0 || credentialCount > 0),
            settingCount = prefCount,
            sourceCount = sourceCount,
            credentialCount = credentialCount,
            songCount = songCount,
            albumCount = albumCount,
            artistCount = artistCount,
            playlistCount = playlistCount,
            itemCount = itemCount
        )
    }

    /** 执行导入（各节覆盖写回）。 */
    suspend fun import(data: ByteArray) {
        val root = JSONObject(String(data, Charsets.UTF_8))
        // CB'-版本门控：数据包 schemaVersion 高于当前应用支持版本时明确拒绝，避免结构变化导致字段被静默误读/丢失
        val pkgVer = root.optInt("schemaVersion", -1)
        if (pkgVer > SCHEMA_VERSION) {
            throw TransferError(R.string.transfer_err_version, pkgVer, SCHEMA_VERSION)
        }

        root.optJSONObject("settings")?.let { s ->
            val prefArr = s.optJSONArray("preferences") ?: JSONArray()
            val entries = List(prefArr.length()) { i ->
                val o = prefArr.getJSONObject(i)
                SettingEntry(o.optString("name"), o.optString("type"), o.optString("value"))
            }
            settingsRepository.importSettings(entries)
            val srcArr = s.optJSONArray("musicSources") ?: JSONArray()
            if (srcArr.length() > 0) {
                musicSourceDao.clearAll()
                for (i in 0 until srcArr.length()) {
                    musicSourceDao.insert(musicSourceFromJson(srcArr.getJSONObject(i)))
                }
            }
            // 还原网络源登录凭据
            val credArr = s.optJSONArray("credentials") ?: JSONArray()
            for (i in 0 until credArr.length()) {
                val c = credArr.getJSONObject(i)
                val host = c.optString("host")
                val user = c.optString("user")
                val pass = c.optString("pass")
                when (c.optString("provider")) {
                    "smb" -> smbCredentialStore.save(host, user, pass)
                    "webdav" -> webDavCredentialStore.saveForUrl("http://$host", user, pass)
                }
            }
        }

        root.optJSONObject("songs")?.let { sn ->
            db.withTransaction {
                songDao.clear()
                val sArr = sn.optJSONArray("songs") ?: JSONArray()
                if (sArr.length() > 0) {
                    songDao.upsertAll(List(sArr.length()) { i -> songFromJson(sArr.getJSONObject(i)) })
                }
                albumDao.clear()
                val aArr = sn.optJSONArray("albums") ?: JSONArray()
                if (aArr.length() > 0) {
                    albumDao.upsertAll(List(aArr.length()) { i -> albumFromJson(aArr.getJSONObject(i)) })
                }
                artistDao.clear()
                val tArr = sn.optJSONArray("artists") ?: JSONArray()
                if (tArr.length() > 0) {
                    artistDao.upsertAll(List(tArr.length()) { i -> artistFromJson(tArr.getJSONObject(i)) })
                }
            }
            // 歌曲导入后重建文件夹目录树（folder_entry 由 FolderStructureBuilder 从 songs 归源生成）
            folderStructureBuilder.rebuild()
        }

        root.optJSONObject("playlists")?.let { pl ->
            db.withTransaction {
                playlistDao.deleteAll()
                val pArr = pl.optJSONArray("playlists") ?: JSONArray()
                for (i in 0 until pArr.length()) {
                    playlistDao.insert(playlistFromJson(pArr.getJSONObject(i)))
                }
                playlistItemDao.deleteAllItems()
                val iArr = pl.optJSONArray("items") ?: JSONArray()
                if (iArr.length() > 0) {
                    playlistItemDao.insertAll(List(iArr.length()) { k -> playlistItemFromJson(iArr.getJSONObject(k)) })
                }
            }
        }
    }

    // ============================= JSON 映射 =============================

    // ---------- Song ----------
    private fun songJson(e: SongEntity): JSONObject {
        val j = JSONObject()
        j.put("id", e.id)
        j.put("title", e.title)
        if (e.artistId != null) j.put("artistId", e.artistId)
        if (e.albumId != null) j.put("albumId", e.albumId)
        if (e.artistName != null) j.put("artistName", e.artistName)
        if (e.albumName != null) j.put("albumName", e.albumName)
        if (e.albumArtUri != null) j.put("albumArtUri", e.albumArtUri)
        j.put("durationMs", e.durationMs)
        j.put("trackNumber", e.trackNumber)
        j.put("uri", e.uri)
        if (e.mimeType != null) j.put("mimeType", e.mimeType)
        j.put("sourceType", e.sourceType.name)
        if (e.path != null) j.put("path", e.path)
        j.put("dateAdded", e.dateAdded)
        j.put("sizeBytes", e.sizeBytes)
        if (e.cueId != null) j.put("cueId", e.cueId)
        if (e.trackIndex != null) j.put("trackIndex", e.trackIndex)
        if (e.clipStartMs != null) j.put("clipStartMs", e.clipStartMs)
        if (e.clipEndMs != null) j.put("clipEndMs", e.clipEndMs)
        j.put("dedupKey", e.dedupKey)
        if (e.genre != null) j.put("genre", e.genre)
        if (e.year != null) j.put("year", e.year)
        j.put("rating", e.rating)
        j.put("playCount", e.playCount)
        j.put("lastPlayedMs", e.lastPlayedMs)
        j.put("formatVerified", e.formatVerified)
        j.put("lyricOffsetMs", e.lyricOffsetMs)
        return j
    }

    private fun songFromJson(j: JSONObject): SongEntity = SongEntity(
        id = j.optLong("id"),
        title = j.optString("title"),
        artistId = j.optLongOrNull("artistId"),
        albumId = j.optLongOrNull("albumId"),
        artistName = j.optStringOrNull("artistName"),
        albumName = j.optStringOrNull("albumName"),
        albumArtUri = j.optStringOrNull("albumArtUri"),
        durationMs = j.optLong("durationMs"),
        trackNumber = j.optInt("trackNumber"),
        uri = j.optString("uri"),
        mimeType = j.optStringOrNull("mimeType"),
        sourceType = mediaTypeOf(j.optString("sourceType")),
        path = j.optStringOrNull("path"),
        dateAdded = j.optLong("dateAdded"),
        sizeBytes = j.optLong("sizeBytes"),
        cueId = j.optStringOrNull("cueId"),
        trackIndex = j.optIntOrNull("trackIndex"),
        clipStartMs = j.optLongOrNull("clipStartMs"),
        clipEndMs = j.optLongOrNull("clipEndMs"),
        dedupKey = j.optString("dedupKey"),
        genre = j.optStringOrNull("genre"),
        year = j.optIntOrNull("year"),
        rating = j.optInt("rating"),
        playCount = j.optInt("playCount"),
        lastPlayedMs = j.optLong("lastPlayedMs"),
        formatVerified = j.optBoolean("formatVerified"),
        lyricOffsetMs = j.optLong("lyricOffsetMs"),
        searchKey = SongSearchKey.of(
            j.optString("title"),
            j.optStringOrNull("artistName"),
            j.optStringOrNull("albumName")
        )
    )

    // ---------- Album ----------
    private fun albumJson(e: AlbumEntity): JSONObject = JSONObject()
        .put("id", e.id)
        .put("name", e.name)
        .apply { if (e.artistName != null) put("artistName", e.artistName) }
        .apply { if (e.albumArtUri != null) put("albumArtUri", e.albumArtUri) }
        .apply { if (e.year != null) put("year", e.year) }
        .put("songCount", e.songCount)

    private fun albumFromJson(j: JSONObject): AlbumEntity = AlbumEntity(
        id = j.optLong("id"),
        name = j.optString("name"),
        artistName = j.optStringOrNull("artistName"),
        albumArtUri = j.optStringOrNull("albumArtUri"),
        year = j.optIntOrNull("year"),
        songCount = j.optInt("songCount")
    )

    // ---------- Artist ----------
    private fun artistJson(e: ArtistEntity): JSONObject = JSONObject()
        .put("id", e.id)
        .put("name", e.name)
        .put("albumCount", e.albumCount)
        .put("songCount", e.songCount)

    private fun artistFromJson(j: JSONObject): ArtistEntity = ArtistEntity(
        id = j.optLong("id"),
        name = j.optString("name"),
        albumCount = j.optInt("albumCount"),
        songCount = j.optInt("songCount")
    )

    // ---------- MusicSource ----------
    private fun musicSourceJson(e: MusicSourceEntity): JSONObject = JSONObject()
        .put("id", e.id)
        .put("name", e.name)
        .put("type", e.type.name)
        .put("configJson", e.configJson)
        .put("enabled", e.enabled)
        .put("lastScanTime", e.lastScanTime)

    private fun musicSourceFromJson(j: JSONObject): MusicSourceEntity = MusicSourceEntity(
        id = j.optLong("id"),
        name = j.optString("name"),
        type = mediaTypeOf(j.optString("type")),
        configJson = j.optString("configJson", "{}"),
        enabled = j.optBoolean("enabled", true),
        lastScanTime = j.optLong("lastScanTime")
    )

    // ---------- Playlist ----------
    private fun playlistJson(e: PlaylistEntity): JSONObject = JSONObject()
        .put("id", e.id)
        .put("name", e.name)
        .put("dateCreated", e.dateCreated)
        .put("dateModified", e.dateModified)

    private fun playlistFromJson(j: JSONObject): PlaylistEntity = PlaylistEntity(
        id = j.optLong("id"),
        name = j.optString("name"),
        dateCreated = j.optLong("dateCreated"),
        dateModified = j.optLong("dateModified")
    )

    // ---------- PlaylistItem ----------
    private fun playlistItemJson(e: PlaylistItemEntity): JSONObject = JSONObject()
        .put("id", e.id)
        .put("playlistId", e.playlistId)
        .put("songId", e.songId)
        .put("position", e.position)

    private fun playlistItemFromJson(j: JSONObject): PlaylistItemEntity = PlaylistItemEntity(
        id = j.optLong("id"),
        playlistId = j.optLong("playlistId"),
        songId = j.optLong("songId"),
        position = j.optInt("position")
    )

    // ---------- helpers ----------
    private fun mediaTypeOf(s: String): MediaSourceType =
        runCatching { MediaSourceType.valueOf(s) }.getOrDefault(MediaSourceType.LOCAL)

    private fun JSONObject.optStringOrNull(k: String): String? =
        if (has(k) && !isNull(k)) getString(k) else null

    private fun JSONObject.optLongOrNull(k: String): Long? =
        if (has(k) && !isNull(k)) getLong(k) else null

    private fun JSONObject.optIntOrNull(k: String): Int? =
        if (has(k) && !isNull(k)) getInt(k) else null

    private companion object {
        const val EXPORT_PAGE_SIZE = 500
        const val SCHEMA_VERSION = 2
        const val KEY_LEN_BITS = 256
        const val GCM_TAG_BITS = 128
        const val PBKDF2_ITERATIONS = 120000
    }
}