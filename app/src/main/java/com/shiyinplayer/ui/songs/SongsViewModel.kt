package com.shiyinplayer.ui.songs

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.R
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.local.entity.SongEntity
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.MusicSource
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.util.SongSearchKey
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.player.PlayerManager
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SongsViewModel @Inject constructor(
    private val repo: LibraryRepository,
    private val playerManager: PlayerManager,
    private val songDao: SongDao,
    @ApplicationContext private val context: Context,
    settings: SettingsRepository
) : ViewModel() {
    /** 曲库歌曲：固定按歌曲名（title）排序平铺显示（不分组）。 */
    val songs: StateFlow<List<Song>> =
        repo.getSongs()
            .map { list -> list.sortedBy { it.title } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 列表分组（预留状态；曲库现固定不分组，不再按此值分组）。 */
    val listGroupBy: StateFlow<String> = settings.listGroupBy
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "none")

    fun play(song: Song) {
        val list = songs.value
        val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playerManager.playQueue(list, idx)
    }

    /** 从 SAF 选择的本地音频文件导入为单曲（T6：「添加歌曲」）。 */
    fun importUri(uri: Uri) = importUris(listOf(uri))

    /** 「添加歌曲」多选导入（需求 7）：批量读取元数据并入曲库（IO 线程读取，防主线程阻塞）。 */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var changed = false
            for (uri in uris) {
                val name = uri.lastPathSegment?.substringBeforeLast('.') ?: context.getString(R.string.unknown_title)
                val entity = withContext(Dispatchers.IO) { readEntityForUri(uri, fallbackTitle = name) }
                if (entity != null) { songDao.upsert(entity); changed = true }
            }
            if (changed) runCatching { repo.refreshAggregates() }
        }
    }

    private fun readEntityForUri(uri: Uri, fallbackTitle: String): SongEntity? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val title = runCatching {
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: fallbackTitle
            val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val track = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.toIntOrNull() ?: 0
            val genre = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val year = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
            SongEntity(
                title = title,
                artistName = artist,
                albumName = album,
                durationMs = duration,
                uri = uri.toString(),
                sourceType = MediaSourceType.LOCAL,
                path = uri.toString(),
                dateAdded = System.currentTimeMillis(),
                dedupKey = uri.toString(),
                trackNumber = track,
                genre = genre,
                year = year,
                searchKey = SongSearchKey.of(title, artist, album)
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** [5] 批量添加本地文件夹：登记 LOCAL 音乐来源（含 treeUri）并立即递归扫描入库。 */
    fun addLocalFolder(uri: Uri, name: String) {
        viewModelScope.launch {
            val configJson = org.json.JSONObject().apply { put("treeUri", uri.toString()) }.toString()
            val source = MusicSource(
                name = name,
                type = MediaSourceType.LOCAL,
                configJson = configJson,
                enabled = true
            )
            val id = repo.addMusicSource(source)
            runCatching { repo.scanAndPersist(listOf(source.copy(id = id))) }
        }
    }
}
