package com.shiyinplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.metasync.MetadataSyncManager
import com.shiyinplayer.data.metasync.ManualSyncProgress
import com.shiyinplayer.data.metadata.MetadataSource
import com.shiyinplayer.data.metadata.SourceRegistry
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.data.repository.MetadataRepository
import com.shiyinplayer.player.PlayerManager
import com.shiyinplayer.ui.theme.ThemePrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置 ViewModel（T11 / SETTINGS_SPEC §8）：聚合各偏好为可观察 StateFlow，并暴露写入方法。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val metadataRepo: MetadataRepository,
    private val libraryRepo: LibraryRepository,
    private val metadataSyncManager: MetadataSyncManager,
    private val playerManager: PlayerManager,
    private val sourceRegistry: SourceRegistry
) : ViewModel() {

    /** 公开 SettingsRepository 供导航图访问（模式切换等）。 */
    val settingsRepository: SettingsRepository get() = repo

    private val _maintenanceResult = MutableStateFlow<String?>(null)
    val maintenanceResult: StateFlow<String?> = _maintenanceResult

    // ===== P3 维护操作 =====

    fun clearMetadataCache() = viewModelScope.launch {
        runCatching { metadataRepo.clearCache() }
        _maintenanceResult.value = "已清空歌词与封面缓存"
    }

    fun pruneOldCache() = viewModelScope.launch {
        runCatching { metadataRepo.pruneCache(7) }
        _maintenanceResult.value = "已清理 7 天前的过期缓存"
    }

    fun pruneMissingSongs() = viewModelScope.launch {
        // F1-3：本地失效文件 + 已删除网络源遗留的孤儿曲目（脏数据）一并清理
        val local = runCatching { libraryRepo.pruneMissingLocal() }.getOrDefault(0)
        val orphan = runCatching { libraryRepo.pruneMissingNetworkOrphans() }.getOrDefault(0)
        _maintenanceResult.value = "已清理 ${local + orphan} 首失效曲目"
    }

    fun clearMaintenanceResult() {
        _maintenanceResult.value = null
    }

    val prefs = combine(repo.themeMode, repo.accent, repo.themeStyle, repo.dynamicColors) { m, a, s, d -> ThemePrefs(m, a, s, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemePrefs(repo.themeModeSync(), repo.accentSync(), repo.themeStyleSync(), repo.dynamicColorsSync()))

    // 界面
    val language = repo.language.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")
    val showEmbedArt = repo.showEmbedArt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val listShowArt = repo.listShowArt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val listTwoLine = repo.listTwoLine.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val listDensity = repo.listDensity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "standard")
    // 注（P1-6）：notify_enabled / lockscreen_control 仅持久化，通知/锁屏链路尚未接线——
    // 前台服务必须常驻 MediaStyle 通知，锁屏控制由 MediaSession 机制承载，改造风险大，标记"暂未生效"，待后续批次。
    val notifyEnabled = repo.notifyEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val lockscreenControl = repo.lockscreenControl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val miniBarEnabled = repo.miniBarEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val keepScreenOn = repo.keepScreenOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val carBtLyrics = repo.carBtLyrics.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val focusLossAutoSkip = repo.focusLossAutoSkip.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val displayMode = repo.displayMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "auto")
    // 全屏页无操作自动隐藏控件（音乐/收音机通用）
    val autoHideControls = repo.autoHideControls.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val autoHideDelayMs = repo.autoHideDelayMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3000)

    // 声音引擎
    val audioRoute = repo.audioRoute.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "auto")
    val lowLatency = repo.lowLatency.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val bufferMs = repo.bufferMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 200)
    val float32Processing = repo.float32Processing.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val volumeNormalize = repo.volumeNormalize.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val replaygainMode = repo.replaygainMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "off")
    val volumeCurve = repo.volumeCurve.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "log")
    val fadeInMs = repo.fadeInMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val fadeOutMs = repo.fadeOutMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    // 注（P1-9）：eq_enabled 由 EqualizerManager 观察 DataStore 流运行时即时切换；
    // replaygain_mode / silence_remover 为构建期音频链开关，由 PlayerManager 热重建按
    // PlayerFactory 重新读取生效。均已接线，非"暂未生效"。
    val silenceRemover = repo.silenceRemover.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val eqEnabled = repo.eqEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val channelBalance = repo.channelBalance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)
    val eqPreset = repo.eqPreset.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Flat")

    // 回放
    val startupAction = repo.startupAction.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "resume")
    val defaultRepeat = repo.defaultRepeat.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "all")
    val skipOnError = repo.skipOnError.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val autoEqByGenre = repo.autoEqByGenre.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val autoResume = repo.autoResume.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    /** 自动同步曲库文件元数据（默认开）。 */
    val autoSyncMetadata = repo.autoSyncMetadata.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    /** 自动同步运行状态（需求2 2026-08-23）：同步进行中时为 true，设置页显示「运行中」。 */
    val metadataSyncing: StateFlow<Boolean> = metadataSyncManager.isSyncing

    // ===== 手动批量同步元数据 =====
    /** 手动批量同步运行状态：true 表示正在手动批量同步。 */
    val manualSyncActive: StateFlow<Boolean> = metadataSyncManager.manualSyncActive
    /** 手动批量同步进度（已处理 / 缺失总数）。 */
    val manualSyncProgress: StateFlow<ManualSyncProgress> = metadataSyncManager.manualProgress

    fun startManualMetadataSync() = metadataSyncManager.startManualSync()
    fun stopManualMetadataSync() = metadataSyncManager.stopManualSync()
    /** 流量保护（默认开）：移动网络下不联网获取元数据/歌词，WiFi 下自动同步。 */
    val dataSaver = repo.dataSaver.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val gapless = repo.gapless.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val rememberPosition = repo.rememberPosition.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // 播放列表
    val playlistAutosave = repo.playlistAutosave.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val trackFormat = repo.trackFormat.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "{artist} – {title}")
    val listGroupBy = repo.listGroupBy.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "none")
    val sortBy = repo.sortBy.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "title")
    val allowDeleteFile = repo.allowDeleteFile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // 音乐库
    val watchFolders = repo.watchFolders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val readEmbedLyrics = repo.readEmbedLyrics.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val hideShortClips = repo.hideShortClips.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val scanHidden = repo.scanHidden.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val scanExtensions = repo.scanExtensions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // 控制
    val headsetPause = repo.headsetPause.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val sleepTimerMin = repo.sleepTimerMin.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    // 2026-08-24：耳机按键 / 蓝牙断开 / 音频焦点
    val headsetButtonControl = repo.headsetButtonControl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val btDisconnectPause = repo.btDisconnectPause.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val btReconnectResume = repo.btReconnectResume.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val playRequiresAudioFocus = repo.playRequiresAudioFocus.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // 整合
    val isDefaultPlayer = repo.isDefaultPlayer.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val acceptExternalOpen = repo.acceptExternalOpen.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // 歌词 / 在线元数据
    val lyricsEnabled = repo.lyricsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val metadataEnabled = repo.metadataEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    /** 元数据获取源启用集合（仅决定启用/禁用，不决定顺序）。 */
    val metadataSourcesEnabled = repo.metadataSourcesEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.defaultEnabledSources)
    /** 元数据获取源排列顺序（全部源有序，仅决定顺序）。 */
    val metadataSourcesOrder = repo.metadataSourcesOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.defaultSourceOrder)

    /** 全部可配置元数据源（按默认统一顺序，供「元数据来源」子屏开关 + 排序展示）。 */
    val allMetadataSources: List<MetadataSource> = sourceRegistry.all

    fun setThemeMode(v: Int) = viewModelScope.launch { repo.setThemeMode(v) }
    fun setAccent(v: Int) = viewModelScope.launch { repo.setAccent(v) }
    fun setThemeStyle(v: Int) = viewModelScope.launch { repo.setThemeStyle(v) }
    fun setDynamicColors(v: Boolean) = viewModelScope.launch { repo.setDynamicColors(v) }
    fun setLanguage(v: String) = viewModelScope.launch { repo.setLanguage(v) }
    fun setShowEmbedArt(v: Boolean) = viewModelScope.launch { repo.setShowEmbedArt(v) }
    fun setListShowArt(v: Boolean) = viewModelScope.launch { repo.setListShowArt(v) }
    fun setListTwoLine(v: Boolean) = viewModelScope.launch { repo.setListTwoLine(v) }
    fun setListDensity(v: String) = viewModelScope.launch { repo.setListDensity(v) }
    fun setNotifyEnabled(v: Boolean) = viewModelScope.launch { repo.setNotifyEnabled(v) }
    fun setLockscreenControl(v: Boolean) = viewModelScope.launch { repo.setLockscreenControl(v) }
    fun setMiniBarEnabled(v: Boolean) = viewModelScope.launch { repo.setMiniBarEnabled(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { repo.setKeepScreenOn(v) }
    fun setCarBtLyrics(v: Boolean) = viewModelScope.launch { repo.setCarBtLyrics(v) }
    fun setFocusLossAutoSkip(v: Boolean) = viewModelScope.launch { repo.setFocusLossAutoSkip(v) }
    fun setDisplayMode(v: String) = viewModelScope.launch { repo.setDisplayMode(v) }
    fun setAutoHideControls(v: Boolean) = viewModelScope.launch { repo.setAutoHideControls(v) }
    fun setAutoHideDelayMs(v: Int) = viewModelScope.launch { repo.setAutoHideDelayMs(v) }

    fun setAudioRoute(v: String) = viewModelScope.launch { repo.setAudioRoute(v) }
    fun setLowLatency(v: Boolean) = viewModelScope.launch { repo.setLowLatency(v) }
    fun setBufferMs(v: Int) = viewModelScope.launch { repo.setBufferMs(v) }
    fun setFloat32Processing(v: Boolean) = viewModelScope.launch { repo.setFloat32Processing(v) }
    fun setVolumeNormalize(v: Boolean) = viewModelScope.launch { repo.setVolumeNormalize(v) }
    fun setReplaygainMode(v: String) = viewModelScope.launch { repo.setReplaygainMode(v) }
    fun setVolumeCurve(v: String) = viewModelScope.launch { repo.setVolumeCurve(v) }
    fun setFadeInMs(v: Int) = viewModelScope.launch { repo.setFadeInMs(v) }
    fun setFadeOutMs(v: Int) = viewModelScope.launch { repo.setFadeOutMs(v) }
    fun setSilenceRemover(v: Boolean) = viewModelScope.launch { repo.setSilenceRemover(v) }
    fun setEqEnabled(v: Boolean) = viewModelScope.launch { repo.setEqEnabled(v) }
    fun setChannelBalance(v: Float) = viewModelScope.launch { repo.setChannelBalance(v) }
    fun setEqPreset(v: String) = viewModelScope.launch { repo.setEqPreset(v) }

    fun setStartupAction(v: String) = viewModelScope.launch { repo.setStartupAction(v) }
    fun setDefaultRepeat(v: String) = viewModelScope.launch { repo.setDefaultRepeat(v) }
    fun setSkipOnError(v: Boolean) = viewModelScope.launch { repo.setSkipOnError(v) }
    fun setAutoEqByGenre(v: Boolean) = viewModelScope.launch { repo.setAutoEqByGenre(v) }
    fun setAutoResume(v: Boolean) = viewModelScope.launch { repo.setAutoResume(v) }
    fun setAutoSyncMetadata(v: Boolean) = viewModelScope.launch {
        repo.setAutoSyncMetadata(v)
        // 开启时立即在后台执行一次同步（独立 IO 线程，不阻塞 UI/播放；流量保护由同步管理器内部判断）
        if (v) metadataSyncManager.syncNow()
    }
    fun setDataSaver(v: Boolean) = viewModelScope.launch {
        repo.setDataSaver(v)
        // 关闭流量保护（或切到 WiFi）时立即尝试同步；开启时若当前在移动网络，同步管理器会跳过
        if (!v) metadataSyncManager.syncNow()
    }
    fun setGapless(v: Boolean) = viewModelScope.launch { repo.setGapless(v) }
    fun setRememberPosition(v: Boolean) = viewModelScope.launch { repo.setRememberPosition(v) }

    fun setPlaylistAutosave(v: Boolean) = viewModelScope.launch { repo.setPlaylistAutosave(v) }
    fun setTrackFormat(v: String) = viewModelScope.launch { repo.setTrackFormat(v) }
    fun setListGroupBy(v: String) = viewModelScope.launch { repo.setListGroupBy(v) }
    fun setSortBy(v: String) = viewModelScope.launch { repo.setSortBy(v) }
    fun setAllowDeleteFile(v: Boolean) = viewModelScope.launch { repo.setAllowDeleteFile(v) }

    fun setWatchFolders(v: Boolean) = viewModelScope.launch { repo.setWatchFolders(v) }
    fun setReadEmbedLyrics(v: Boolean) = viewModelScope.launch { repo.setReadEmbedLyrics(v) }
    fun setHideShortClips(v: Boolean) = viewModelScope.launch { repo.setHideShortClips(v) }
    fun setScanHidden(v: Boolean) = viewModelScope.launch { repo.setScanHidden(v) }
    fun setScanExtensions(exts: Set<String>) = viewModelScope.launch { repo.setScanExtensions(exts) }

    fun setHeadsetPause(v: Boolean) = viewModelScope.launch { repo.setHeadsetPause(v) }

    fun setSleepTimerMin(v: Int) {
        // 2026-08-21 接线：设置页选择睡眠时长立即武装/取消定时器（0=取消），并持久化。
        playerManager.setSleepTimer(v)
        viewModelScope.launch { repo.setSleepTimerMin(v) }
    }

    // 2026-08-24：耳机按键 / 蓝牙断开 / 音频焦点
    fun setHeadsetButtonControl(v: Boolean) = viewModelScope.launch { repo.setHeadsetButtonControl(v) }
    fun setBtDisconnectPause(v: Boolean) = viewModelScope.launch { repo.setBtDisconnectPause(v) }
    fun setBtReconnectResume(v: Boolean) = viewModelScope.launch { repo.setBtReconnectResume(v) }
    fun setPlayRequiresAudioFocus(v: Boolean) = viewModelScope.launch { repo.setPlayRequiresAudioFocus(v) }

    fun setIsDefaultPlayer(v: Boolean) = viewModelScope.launch { repo.setIsDefaultPlayer(v) }
    fun setAcceptExternalOpen(v: Boolean) = viewModelScope.launch { repo.setAcceptExternalOpen(v) }

    fun setLyricsEnabled(v: Boolean) = viewModelScope.launch { repo.setLyricsEnabled(v) }
    fun setMetadataEnabled(v: Boolean) = viewModelScope.launch { repo.setMetadataEnabled(v) }
    /** 写入元数据获取源启用集合（开关：不影响排列顺序）。 */
    fun setMetadataSourcesEnabled(v: List<String>) = viewModelScope.launch { repo.setMetadataSourcesEnabled(v) }
    /** 写入元数据获取源排列顺序（仅手工排序时调用）。 */
    fun setMetadataSourcesOrder(v: List<String>) = viewModelScope.launch { repo.setMetadataSourcesOrder(v) }

    /** 恢复元数据来源默认：仅启用 网易云/QQ/酷我 三源，并恢复默认统一排列顺序。 */
    fun resetMetadataSources() = viewModelScope.launch {
        repo.setMetadataSourcesEnabled(sourceRegistry.defaultEnabled)
        repo.setMetadataSourcesOrder(SettingsRepository.defaultSourceOrder)
    }
}