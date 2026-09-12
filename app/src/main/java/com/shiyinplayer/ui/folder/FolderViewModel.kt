package com.shiyinplayer.ui.folder

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.local.dao.FolderAttachmentDao
import com.shiyinplayer.data.local.dao.FolderEntryDao
import com.shiyinplayer.data.local.dao.MusicSourceDao
import com.shiyinplayer.data.local.entity.FolderAttachmentEntity
import com.shiyinplayer.data.local.entity.FolderEntryEntity
import com.shiyinplayer.data.mapper.EntityMappers.toModel
import com.shiyinplayer.data.media.SmbBrowser
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.MusicSource
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.remote.webdav.WebDavBrowser
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** 目录节点。长单链路径做「折叠压缩」，仅从分叉/含文件层开始展示，中间过渡以 `...\` 省略。 */
data class FolderDir(
    /** 折叠后实际展示名（`...\` + 终点名；未折叠则等于 [name]）。 */
    val display: String,
    /** 折叠终点目录的真实相对路径（点击进入与勾选 key）。 */
    val folderPath: String,
    /** 折叠终点目录名。 */
    val name: String,
    /** 点击进入时应一次性入栈的相对路径段（折叠时含多段）。 */
    val navSegments: List<String>,
    /** 该目录（含子文件夹）递归歌曲数。 */
    val songCount: Int
)

/** 当前路径的目录模型：直接子文件夹、直接音乐文件、递归全部文件。 */
data class FolderModel(
    val dirs: List<FolderDir>,
    val files: List<Song>,
    val songsRecursive: List<Song>
) {
    val isEmpty: Boolean get() = dirs.isEmpty() && files.isEmpty()
}

/**
 * 曲库「文件夹」tab：数据来自扫描时预生成的 folder_entry 表（FolderStructureBuilder），
 * 直接按 (sourceId, parentPath) 查询加载，不做任何运行时遍历。目录层级与空文件夹隐藏
 * 均由预生成阶段保证。
 */
@HiltViewModel
class FolderViewModel @Inject constructor(
    private val repo: LibraryRepository,
    private val musicSourceDao: MusicSourceDao,
    private val folderEntryDao: FolderEntryDao,
    private val folderAttachmentDao: FolderAttachmentDao,
    private val smbBrowser: SmbBrowser,
    private val webDavBrowser: WebDavBrowser,
    @ApplicationContext private val context: Context,
    private val playerManager: PlayerManager
) : ViewModel() {

    private val sourcesFlow = musicSourceDao.observeAll().map { list -> list.map { it.toModel() } }

    /** 全部音乐来源（供来源选择层）。 */
    val sources: StateFlow<List<MusicSource>> = sourcesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 是否有「其他来源」内容（folder_entry 中 sourceId=-1 有记录）。 */
    val hasUnmatched: StateFlow<Boolean> =
        folderEntryDao.observeCount(OTHER)
            .map { it > 0 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _sourceId = MutableStateFlow<Long?>(null)
    /** 当前选中的来源 id；null = 处在来源选择层。 */
    val sourceId: StateFlow<Long?> = _sourceId.asStateFlow()

    private val _segments = MutableStateFlow<List<String>>(emptyList())
    /** 当前所在目录的相对路径段（相对该源根，空 = 源根目录）。 */
    val segments: StateFlow<List<String>> = _segments.asStateFlow()

    /** 歌曲主键 → Song 索引（解析文件行与递归播放）。 */
    private val idIndex: StateFlow<Map<Long, Song>> = repo.getSongs()
        .map { songs -> songs.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 当前选中源、路径下的目录直接子项（由查库流程驱动）。 */
    private val entriesFlow: kotlinx.coroutines.flow.Flow<List<com.shiyinplayer.data.local.entity.FolderEntryEntity>> =
        combine(_sourceId, _segments) { id, seg -> id to seg.joinToString("/") }
            .flatMapLatest { (id, path) ->
                if (id == null) flowOf(emptyList())
                else folderEntryDao.observeByParent(id, path)
            }

    /** 当前选中源、路径下含子文件夹的全部歌曲（「立即播放 / 加入歌单」目标，未勾选目录时）。 */
    private val recursiveIds: MutableStateFlow<List<Long>> = MutableStateFlow(emptyList())

    /** 当前选中源、路径下的直接子文件夹，已做单链折叠压缩。 */
    private val dirsFlow: kotlinx.coroutines.flow.Flow<List<FolderDir>> =
        combine(_sourceId, entriesFlow) { id, entries -> id to entries }
            .flatMapLatest { (id, entries) ->
                if (id == null) flowOf(emptyList())
                else flow {
                    val dirs = mutableListOf<FolderDir>()
                    entries.forEach { if (it.isDir) dirs += foldChain(id, it) }
                    emit(dirs)
                }
            }
            .flowOn(Dispatchers.Default)

    /** 当前选中源、路径下的直接「音乐文件」。 */
    private val filesFlow: kotlinx.coroutines.flow.Flow<List<Song>> =
        combine(entriesFlow, idIndex) { entries, index ->
            entries.asSequence().filter { !it.isDir }
                .mapNotNull { it.songId?.let { id -> index[id] } }
                .toList()
        }.distinctUntilChanged()

    /** 已勾选的目录集合（key = 折叠终点 folderPath）。 */
    private val _selectedDirPaths = MutableStateFlow<Set<String>>(emptySet())
    /** 已勾选目录集合（文件夹多选状态）。 */
    val selectedDirPaths: StateFlow<Set<String>> = _selectedDirPaths.asStateFlow()

    /** 勾选目录各自递归的全部文件歌曲主键（合并去重）。 */
    private val selectedDirRecursiveIds: kotlinx.coroutines.flow.Flow<List<Long>> =
        combine(_sourceId, _selectedDirPaths) { id, paths -> id to paths }
            .flatMapLatest { (id, paths) ->
                if (id == null || paths.isEmpty()) flowOf(emptyList())
                else flow {
                    val ids = mutableListOf<Long>()
                    paths.forEach { p -> ids += folderEntryDao.fileSongIdsRecursive(id, "$p/%") }
                    emit(ids.distinct())
                }
            }
            .flowOn(Dispatchers.Default)

    /** 已勾选目录对应的全部歌曲（供立即播放 / 加入歌单）。 */
    val selectedDirRecursiveSongs: StateFlow<List<Song>> =
        combine(selectedDirRecursiveIds, idIndex) { ids, index -> ids.mapNotNull { index[it] } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前目录模型。 */
    val current: StateFlow<FolderModel> =
        combine(dirsFlow, filesFlow, recursiveIds, idIndex) { dirs, files, recIds, index ->
            val rec = recIds.mapNotNull { index[it] }
            FolderModel(dirs, files, rec)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderModel(emptyList(), emptyList(), emptyList()))

    /** 当前选中源、路径下的直接附件（专辑封面图 / 说明 txt），取自扫描收集的 folder_attachment 表。 */
    val attachments: StateFlow<List<FolderAttachmentEntity>> =
        combine(_sourceId, _segments) { id, seg -> id to seg.joinToString("/") }
            .flatMapLatest { (id, path) ->
                if (id == null) flowOf(emptyList())
                else folderAttachmentDao.observeByParent(id, path)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 选中源 / 路径变化时，查询该目录（含子文件夹）的全部文件歌曲主键（后台线程）。
        combine(_sourceId, _segments) { id, seg -> id to seg.joinToString("/") }
            .distinctUntilChanged()
            .onEach { (id, path) ->
                val ids = if (id == null) emptyList()
                else if (path.isEmpty()) folderEntryDao.allFileSongIds(id)
                else folderEntryDao.fileSongIdsRecursive(id, "$path/%")
                recursiveIds.value = ids
            }
            .flowOn(Dispatchers.Default)
            .launchIn(viewModelScope)
    }

    /** 选择来源：自动下探源根的单链过渡到「首个内容层」，直达其下内容（略过中间过渡）。 */
    fun selectSource(id: Long) {
        viewModelScope.launch {
            _sourceId.value = id
            _segments.value = initialPathSegments(id)
            _selectedDirPaths.value = emptySet()
        }
    }

    /** 从源根沿「唯一子目录」单链下探，遇到分叉或没有子目录（含文件）即停，返回直达该层的相对路径段。 */
    private suspend fun initialPathSegments(sourceId: Long): List<String> {
        val segs = mutableListOf<String>()
        var parent = ""
        var guard = 0
        while (guard++ < 200) {
            val subs = folderEntryDao.directDirs(sourceId, parent)
            if (subs.size == 1) {
                val sub = subs.first()
                segs += sub.name
                parent = sub.folderPath
            } else break
        }
        return segs
    }

    fun backToSources() {
        _sourceId.value = null
        _segments.value = emptyList()
        _selectedDirPaths.value = emptySet()
    }

    /** 进入目录：折叠节点一次性入栈 [FolderDir.navSegments]。 */
    fun navigateInto(dir: FolderDir) {
        _segments.value = _segments.value + dir.navSegments
        _selectedDirPaths.value = emptySet()
    }

    fun goUp() {
        if (_segments.value.isNotEmpty()) {
            _segments.value = _segments.value.dropLast(1)
            _selectedDirPaths.value = emptySet()
        }
    }

    /** 勾选 / 取消勾选一个目录。 */
    fun toggleDirSelected(folderPath: String) {
        _selectedDirPaths.value =
            if (folderPath in _selectedDirPaths.value) _selectedDirPaths.value - folderPath
            else _selectedDirPaths.value + folderPath
    }

    fun clearDirSelection() {
        _selectedDirPaths.value = emptySet()
    }

    /**
     * 单链折叠：某目录只有唯一子文件夹（无分叉）时沿其下探至分叉点或含文件层。
     * 列表项显示「只略去路径前端的过渡路径、中间保留」：折叠时 `...\` + 完整相对路径段。
     */
    private suspend fun foldChain(sourceId: Long, start: FolderEntryEntity): FolderDir {
        var cur = start
        val nav = mutableListOf(start.name)
        var collapsed = false
        while (true) {
            val subs = folderEntryDao.directDirs(sourceId, cur.folderPath)
            if (subs.size == 1) {
                cur = subs.first()
                nav += cur.name
                collapsed = true
            } else break
        }
        val display = if (collapsed) "$ELLIPSIS\\${nav.joinToString("/")}" else cur.name
        return FolderDir(display, cur.folderPath, cur.name, nav, cur.songCount)
    }

    /** 立即播放给定歌曲列表（替换队列）。 */
    fun play(songs: List<Song>) {
        if (songs.isEmpty()) return
        viewModelScope.launch { playerManager.playQueue(songs, 0) }
    }

    /** songId -> Song 查询（供外部按 id 组装需要的歌曲列表）。 */
    suspend fun songsFor(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val index = idIndex.first()
        return ids.mapNotNull { index[it] }
    }

    /**
     * 读取某个文件夹附件的完整字节（封面图 / 说明 txt 预览）。
     * 按附件所属来源类型路由到对应读取器；读取失败返回 null（UI 提示无法加载）。
     */
    suspend fun readAttachmentBytes(att: FolderAttachmentEntity): ByteArray? {
        val src = sources.value.firstOrNull { it.id == att.sourceId } ?: return null
        return withContext(Dispatchers.IO) {
            when (src.type) {
                MediaSourceType.LOCAL -> readLocalAttachment(att.uri)
                MediaSourceType.SMB -> smbBrowser.readAll(att.uri)
                MediaSourceType.WEBDAV -> webDavBrowser.readFull(att.uri)
                else -> null
            }
        }
    }

    /** 本地附件：folderPath 源为绝对路径，SAF 源为 content:// uri。 */
    private fun readLocalAttachment(uriStr: String): ByteArray? {
        return try {
            if (uriStr.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() }
            } else {
                File(uriStr).takeIf { it.exists() }?.readBytes()
            }
        } catch (_: Throwable) { null }
    }

    private companion object {
        const val OTHER = -1L
        /** 折叠压缩路径时省略过渡层的记号。 */
        const val ELLIPSIS = "..."
    }
}