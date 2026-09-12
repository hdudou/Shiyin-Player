package com.shiyinplayer.ui.common

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.local.dao.PlaylistItemDao
import com.shiyinplayer.data.local.dao.SongDao
import com.shiyinplayer.data.metadata.SongMatch
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.Playlist
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.data.repository.MetadataRepository
import com.shiyinplayer.data.repository.PlaylistRepository
import com.shiyinplayer.player.PlayerManager
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** 歌曲操作（长按菜单 / 批量操作）：播放、歌单、删除。 */
@HiltViewModel
class SongActionsViewModel @Inject constructor(
    private val playlistRepo: PlaylistRepository,
    private val songDao: SongDao,
    private val playlistItemDao: PlaylistItemDao,
    private val settings: SettingsRepository,
    private val libraryRepository: LibraryRepository,
    private val playerManager: PlayerManager,
    private val metadataRepository: MetadataRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = playlistRepo.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 音乐库来源列表（文件信息弹窗显示网络源名称用）。 */
    val musicSources: StateFlow<List<com.shiyinplayer.data.model.MusicSource>> = libraryRepository.getMusicSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playNext(song: Song) = playerManager.enqueueNext(song)

    /** F2-1「稍后播放」：追加到队尾。 */
    fun enqueueTail(song: Song) = playerManager.enqueueTail(song)

    fun addToPlaylist(playlistId: Long, song: Song) = viewModelScope.launch {
        playlistRepo.addSongToPlaylist(playlistId, song.id)
    }

    fun addSongsToPlaylist(playlistId: Long, songs: List<Song>) = viewModelScope.launch {
        playlistRepo.addSongsToPlaylist(playlistId, songs.map { it.id })
    }

    fun createAndAdd(name: String, song: Song) = viewModelScope.launch {
        val id = playlistRepo.createPlaylist(name)
        playlistRepo.addSongToPlaylist(id, song.id)
    }

    fun createAndAddMany(name: String, songs: List<Song>) = viewModelScope.launch {
        val id = playlistRepo.createPlaylist(name)
        playlistRepo.addSongsToPlaylist(id, songs.map { it.id })
    }

    fun removeFromPlaylist(playlistId: Long, song: Song) = viewModelScope.launch {
        playlistRepo.removePlaylistItem(playlistId, song.id)
    }

    /** 从曲库删除（含歌单引用清理；本地文件按 allow_delete_file 决定是否物理删除）。 */
    fun deleteSongs(songs: List<Song>) = viewModelScope.launch(Dispatchers.IO) {
        val deleteFile = settings.allowDeleteFile.firstOrNull() ?: true
        for (song in songs) {
            playlistItemDao.removeAllForSong(song.id)
            songDao.deleteById(song.id)
            if (deleteFile && song.source == MediaSourceType.LOCAL && !song.uri.startsWith("content://")) {
                val path = Uri.parse(song.uri).path
                if (path != null) runCatching { File(path).delete() }
            }
        }
        if (songs.isNotEmpty()) runCatching { libraryRepository.refreshAggregates() }
    }

    fun playAll(songs: List<Song>) {
        if (songs.isNotEmpty()) playerManager.playQueue(songs, 0)
    }

    fun stop() = playerManager.stop()

    // ===== [7] 元数据手工修正与在线匹配 =====

    /** 手工编辑标题/艺术家/专辑并写回，刷新聚合与当前播放曲目。 */
    fun editMetadata(song: Song, title: String, artist: String?, album: String?) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { songDao.updateMetadata(song.id, title, artist, album) }
        libraryRepository.invalidateSongsSnapshot()
        runCatching { libraryRepository.refreshAggregates() }
        runCatching { playerManager.refreshCurrentSong() }
    }

    /** 在线搜索候选（供用户挑选正确匹配）：§0.1 需求 A 双维度（标题 + 歌手）。 */
    suspend fun searchMatches(title: String, artist: String?): List<SongMatch> =
        runCatching { metadataRepository.searchCandidates(title, artist) }.getOrDefault(emptyList())

    /** 应用用户选定的在线匹配到该曲目。 */
    fun applyMatch(song: Song, match: SongMatch) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { metadataRepository.applyMatch(song.id, match) }
        libraryRepository.invalidateSongsSnapshot()
        runCatching { libraryRepository.refreshAggregates() }
        runCatching { playerManager.refreshCurrentSong() }
    }

    /** 应用在线匹配的挂起版本：等待写库与队列刷新全部完成，供 UI 在确保生效后再关闭/刷新。 */
    suspend fun applyMatchAwait(song: Song, match: SongMatch) {
        runCatching { metadataRepository.applyMatch(song.id, match) }
        libraryRepository.invalidateSongsSnapshot()
        runCatching { libraryRepository.refreshAggregates() }
        runCatching { playerManager.refreshCurrentSong() }
    }
}