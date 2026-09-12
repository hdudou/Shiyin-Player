package com.shiyinplayer.ui.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.metadata.LrcParser
import com.shiyinplayer.data.metadata.MergedLine
import com.shiyinplayer.data.metasync.MetadataSyncManager
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.repository.LyricCandidate
import com.shiyinplayer.data.repository.MetadataRepository
import com.shiyinplayer.player.LyricLinesStore
import com.shiyinplayer.player.PlayerManager
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LyricsUiState(
    val lines: List<MergedLine> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val source: String? = null,
    val coverUrl: String? = null,
    val artistAvatarUrl: String? = null,
    val artistBio: String? = null,
    // 决策 6：发行年份（在线元数据抓取，best-effort）
    val year: Int? = null,
    // 2026-08-19 需求4：歌词手工匹配搜索
    val lyricSearching: Boolean = false,
    val lyricCandidates: List<LyricCandidate> = emptyList(),
    val lyricSearchError: String? = null,
    // F2-4：译文（多语言/译文切换）
    val hasTranslation: Boolean = false,
    val showTranslation: Boolean = true
)

/**
 * 播放页歌词与元数据 ViewModel：
 * 监听当前曲目 → 多源拉取歌词（LRC 解析 + 翻译对齐）+ 专辑封面 + 歌手信息（头像/简介）。
 *
 * §12 歌词同步修复：
 *  - R3：叠加每曲 [Song.lyricOffsetMs] 手动校正到所有歌词行；
 *  - R4：indexForPosition 全量扫描取最后一个 timeMs<=position 的行（稳健）；
 *  - R5：暴露 ~200ms 稳定 tick 的实时播放位置，驱动当前行平滑滚动。
 */
@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val playerManager: PlayerManager,
    private val repo: MetadataRepository,
    private val settings: SettingsRepository,
    private val lyricLinesStore: LyricLinesStore,
    private val metadataSyncManager: MetadataSyncManager
) : ViewModel() {

    private val _state = MutableStateFlow(LyricsUiState())
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    /** §12 R5：稳定 tick 的实时播放位置（毫秒），供 NowPlaying 驱动当前歌词行。 */
    private val _positionTick = MutableStateFlow(0L)
    val positionTick: StateFlow<Long> = _positionTick.asStateFlow()

    /** 性能优化：当前歌词行索引，仅在行号变化时更新。
     *  NowPlaying 只收集本流，避免每 200ms 的 positionTick 整帧逼使整页重组。 */
    val currentLineIndex: StateFlow<Int> = MutableStateFlow(-1)

    private var loadJob: Job? = null
    private var lastSongKey: String? = null
    /** 当前曲目已应用的歌词时间偏移（R3），adjustOffset 在此基础上叠加。 */
    private var appliedOffsetMs: Long = 0L

    init {
        viewModelScope.launch {
            combine(
                playerManager.playbackState,
                settings.lyricsEnabled,
                settings.metadataEnabled
            ) { playback, enabled, metadata ->
                val song = playback.currentSong
                val key = song?.let { MetadataRepository.songKey(it.title, it.artistName) } ?: ""
                Triple(song, enabled, LyricsPrefs(enabled, metadata)) to key
            }.collect { (info, key) ->
                val (song, enabled, prefs) = info
                if (song == null) {
                    _state.value = LyricsUiState()
                    lyricLinesStore.clear()
                    lastSongKey = null
                    return@collect
                }
                if (key != lastSongKey) {
                    lastSongKey = key
                    load(song, prefs, key)
                }
            }
        }
        // §12 R5：稳定 tick（~200ms）读取播放位置，摆脱 playbackState 500ms 发射粒度。
        viewModelScope.launch {
            while (true) {
                _positionTick.value = playerManager.livePositionMs()
                delay(200)
            }
        }
    }

    private suspend fun load(
        song: Song,
        prefs: LyricsPrefs,
        songKey: String
    ) {
        val title = song.title
        val artist = song.artistName
        val songId = song.id
        val fallbackCover = song.albumArtUri
        appliedOffsetMs = song.lyricOffsetMs
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = LyricsUiState(loading = true, coverUrl = fallbackCover)
            lyricLinesStore.songKey = songKey
            lyricLinesStore.lines = emptyList()

            var lines: List<MergedLine> = emptyList()
            var sourceName: String? = null
            var error: String? = null
            var year: Int? = null
            var hasTranslation = false
            if (prefs.enabled) {
                try {
                    // 本地（内嵌/侧车 .lrc）优先，其次在线多源；统一顺序回退（无单一 lyricsSource）。
                    // 手动批量同步期间抑制在线歌词匹配（只显示本地/缓存），避免与同步争抢在线源。
                    val result = repo.getLyrics(
                        title, artist, song = song,
                        online = !metadataSyncManager.manualSyncActive.value
                    )
                    if (result != null) {
                        val parsed = LrcParser.parse(result.document.lrcText)
                        var primary = parsed.primary
                        // CUE 分轨：整轨歌词按 clipStartMs 校正到子曲目时间轴
                        song.clipStartMs?.let { clip ->
                            primary = primary.mapNotNull { l ->
                                val t = l.timeMs - clip
                                if (t >= 0) l.copy(timeMs = t) else null
                            }
                        }
                        // F2-4：译文集成为整轨独立的 LRC 文本（如网易 tlyric），与原词按时间对齐合并
                        val translatedText = result.document.translatedText?.takeIf { it.isNotBlank() }
                        lines = if (translatedText != null) {
                            var tLines = LrcParser.parse(translatedText).primary
                            song.clipStartMs?.let { clip ->
                                tLines = tLines.mapNotNull { l ->
                                    val t = l.timeMs - clip
                                    if (t >= 0) l.copy(timeMs = t) else null
                                }
                            }
                            LrcParser.merge(primary, tLines)
                        } else {
                            primary.map { MergedLine(it.timeMs, it.text, null) }
                        }
                        hasTranslation = lines.any { it.translated?.isNotBlank() == true }
                        sourceName = result.document.source
                    } else {
                        error = "未找到歌词"
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = "歌词获取失败"
                }
            }

            // §12 R3：叠加每曲手动校正偏移（仅影响展示时间，不改原始 LRC）
            if (appliedOffsetMs != 0L && lines.isNotEmpty()) {
                lines = lines.map { it.copy(timeMs = it.timeMs + appliedOffsetMs) }
            }
            // 通知 / 锁屏共享当前歌词行
            lyricLinesStore.lines = lines

            var coverUrl = fallbackCover
            var avatarUrl: String? = null
            var bio: String? = null
            if (prefs.metadata) {
                // P2-11：runCatching 吞 CancellationException → 改为显式 rethrow，避免旧任务取消后覆盖新任务状态
                try {
                    val info = repo.getSongInfo(title, artist)
                    if (info.song?.coverUrl != null) {
                        coverUrl = info.song.coverUrl
                        repo.applyAlbumArt(songId, coverUrl!!)
                    }
                    // 决策 6：发行年份透传（best-effort）
                    year = info.song?.year
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 元数据获取失败不阻断歌词展示
                }
                if (!artist.isNullOrBlank()) {
                    try {
                        val a = repo.getArtistInfo(artist)
                        avatarUrl = a.artist?.avatarUrl
                        bio = a.artist?.bio
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 歌手信息获取失败不阻断
                    }
                }
            }

            _state.value = LyricsUiState(
                lines = lines,
                loading = false,
                error = error,
                source = sourceName,
                coverUrl = coverUrl,
                artistAvatarUrl = avatarUrl,
                artistBio = bio,
                year = year,
                hasTranslation = hasTranslation,
                showTranslation = _state.value.showTranslation
            )
        }
    }

    fun refresh() {
        val song = playerManager.playbackState.value.currentSong ?: return
        lastSongKey = null
        viewModelScope.launch {
            // EL：runCatching 会吞 CancellationException，这里 first() 是 suspend 调用，
            // 改用 try/catch 显式 rethrow 取消异常，避免协程取消后仍继续执行。
            val enabled = try {
                settings.lyricsEnabled.first()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
            val metadata = try {
                settings.metadataEnabled.first()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
            val key = MetadataRepository.songKey(song.title, song.artistName)
            load(song, LyricsPrefs(enabled, metadata), key)
        }
    }

    /**
     * 2026-08-24：在线匹配元数据选中后，用「新选的标题/歌手」立即重拉歌词，
     * 不依赖播放器当前曲目是否已刷新（refreshCurrentSong 为异步投递），
     * 避免歌词仍按旧元数据加载而迟迟不更新为匹配歌词。
     */
    fun applyMatchedMetadata(song: Song, title: String, artist: String?) {
        val merged = song.copy(title = title, artistName = artist)
        lastSongKey = null
        viewModelScope.launch {
            // EL：runCatching 吞 CancellationException → 改用 try/catch 显式 rethrow
            val enabled = try {
                settings.lyricsEnabled.first()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
            val metadata = try {
                settings.metadataEnabled.first()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
            val key = MetadataRepository.songKey(title, artist)
            load(merged, LyricsPrefs(enabled, metadata), key)
        }
    }

    /**
     * §12 R3：调整当前曲目的歌词时间偏移（增量，单位 ms）。实时重排展示行并持久化到主库。
     * 例：歌词整体偏快 0.5s → 传 +500（把每行展示时间推后 500ms）；偏慢 → 传 -500。
     */
    fun adjustOffset(songId: Long, deltaMs: Long) {
        if (songId <= 0 || deltaMs == 0L) return
        appliedOffsetMs += deltaMs
        val shifted = _state.value.lines.map { it.copy(timeMs = it.timeMs + deltaMs) }
        _state.value = _state.value.copy(lines = shifted)
        lyricLinesStore.lines = shifted
        viewModelScope.launch { repo.setLyricOffset(songId, appliedOffsetMs) }
    }

    /** F2-4：切换译文显隐（仅在有译文的歌词上生效）。 */
    fun toggleTranslation() {
        _state.value = _state.value.copy(showTranslation = !_state.value.showTranslation)
    }

    /** 计算当前播放位置对应的歌词行索引（R4：全量扫描取最后一个 timeMs<=position 的行）。 */
    fun indexForPosition(positionMs: Long): Int {
        val lines = _state.value.lines
        if (lines.isEmpty()) return -1
        var idx = -1
        for (i in lines.indices) {
            if (positionMs >= lines[i].timeMs) idx = i
        }
        return idx
    }

    // ===== 2026-08-19 需求4：歌词手工匹配 =====

    /** 按标题/歌手搜索歌词候选（多源），结果存 state.lyricCandidates 供 UI 列表展示。 */
    fun searchLyrics(query: String, artist: String?) {
        val q = query.trim()
        if (q.isEmpty()) return
        _state.value = _state.value.copy(lyricSearching = true, lyricCandidates = emptyList(), lyricSearchError = null)
        viewModelScope.launch {
            // EL：runCatching 吞 CancellationException → 改用 try/catch 显式 rethrow，
            // 避免搜索协程被取消后仍写 state（旧搜索结果覆盖新状态）。
            val list = try {
                repo.searchLyricCandidates(q, artist)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            _state.value = _state.value.copy(
                lyricSearching = false,
                lyricCandidates = list,
                lyricSearchError = if (list.isEmpty()) "未找到匹配歌词，可尝试调整搜索词" else null
            )
        }
    }

    /** 用户选定候选：保存到当前歌曲（songId 绑定，永不过期）并立即刷新展示。 */
    fun applyLyric(song: Song, candidate: LyricCandidate) {
        viewModelScope.launch {
            // EL：runCatching 吞 CancellationException → 改用 try/catch 显式 rethrow
            try {
                repo.saveLyricForSong(song, candidate.doc)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // 保存失败不影响展示
            }
            // 立即展示新歌词
            val parsed = LrcParser.parse(candidate.doc.lrcText)
            var primary = parsed.primary
            song.clipStartMs?.let { clip ->
                primary = primary.mapNotNull { l ->
                    val t = l.timeMs - clip
                    if (t >= 0) l.copy(timeMs = t) else null
                }
            }
            // F2-4：手动匹配的候选词同样集成译文
            val translatedText = candidate.doc.translatedText?.takeIf { it.isNotBlank() }
            val lines = if (translatedText != null) {
                var tLines = LrcParser.parse(translatedText).primary
                song.clipStartMs?.let { clip ->
                    tLines = tLines.mapNotNull { l ->
                        val t = l.timeMs - clip
                        if (t >= 0) l.copy(timeMs = t) else null
                    }
                }
                LrcParser.merge(primary, tLines)
            } else {
                primary.map { MergedLine(it.timeMs, it.text, null) }
            }
            _state.value = _state.value.copy(
                lines = lines,
                loading = false,
                error = null,
                source = candidate.source,
                hasTranslation = lines.any { it.translated?.isNotBlank() == true },
                lyricCandidates = emptyList()
            )
            lyricLinesStore.lines = lines
        }
    }

    /** 关闭搜索窗口（清空候选）。 */
    fun dismissLyricSearch() {
        _state.value = _state.value.copy(lyricSearching = false, lyricCandidates = emptyList(), lyricSearchError = null)
    }
}

private data class LyricsPrefs(
    val enabled: Boolean,
    val metadata: Boolean
)
