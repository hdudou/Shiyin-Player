package com.shiyinplayer.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.local.entity.RadioHistoryEntity
import com.shiyinplayer.data.local.entity.RadioStationEntity
import com.shiyinplayer.data.radio.MergedRadioStation
import com.shiyinplayer.data.radio.RadioStationMerger
import com.shiyinplayer.data.repository.RadioRepository
import com.shiyinplayer.player.radio.RadioPlayer
import com.shiyinplayer.player.radio.RadioState
import com.shiyinplayer.ui.settings.SettingsRepository
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import com.shiyinplayer.data.radio.RadioOpmlHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 收音机模式 ViewModel：管理电台列表、收藏、播放控制。
 */
/** 待用户确认是否允许移动网络播放的暂存播放请求。 */
data class PendingPlayback(
    val url: String,
    val name: String?,
    val id: Long?
)

@HiltViewModel
class RadioViewModel @Inject constructor(
    private val application: Application,
    private val radioPlayer: RadioPlayer,
    private val radioRepository: RadioRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    /** 公开 RadioPlayer 供导航图访问（模式切换）。 */
    val radioPlayerRef: RadioPlayer get() = radioPlayer

    // ---- 播放状态（直接从 RadioPlayer 观察） ----
    val radioState: StateFlow<RadioState> = radioPlayer.playbackState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadioState())

    // ---- 电台列表（合并：同名/同URL聚合，含多线路）----
    private val _stations = MutableStateFlow<List<MergedRadioStation>>(emptyList())
    val stations: StateFlow<List<MergedRadioStation>> = _stations.asStateFlow()

    // ---- 收藏列表（合并）----
    private val _favorites = MutableStateFlow<List<MergedRadioStation>>(emptyList())
    val favorites: StateFlow<List<MergedRadioStation>> = _favorites.asStateFlow()

    // ---- 最近收听（合并）----
    private val _recentStations = MutableStateFlow<List<MergedRadioStation>>(emptyList())
    val recentStations: StateFlow<List<MergedRadioStation>> = _recentStations.asStateFlow()

    // ---- 当前选中分类 ----
    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre: StateFlow<String?> = _selectedGenre.asStateFlow()

    // ---- 当前选中地区 ----
    private val _selectedRegion = MutableStateFlow<String?>(null)
    val selectedRegion: StateFlow<String?> = _selectedRegion.asStateFlow()

    // ---- 加载状态 ----
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ---- 错误事件（一次性，UI 用 collectLatest 消费） ----
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    // ---- 移动网络播放门控（「仅 WiFi」开启 + 当前为移动数据 → 弹窗征询） ----
    private val _wifiGate = MutableStateFlow<PendingPlayback?>(null)
    val wifiGate: StateFlow<PendingPlayback?> = _wifiGate.asStateFlow()
    private var pendingMobileStation: PendingPlayback? = null

    init {
        loadStations()
        loadFavorites()
        loadRecent()
        // 注意：切模式恢复逻辑不在 init 里跑。
        // 因为 RadioViewModel 绑在 Activity 的 ViewModelStore 上，切到音乐再切回时不会重建。
        // 实际恢复由 RadioNavGraph 的 LaunchedEffect 调 [restoreSessionOnModeSwitch] 触发。
    }

    /**
     * 供 [RadioNavGraph] 在 RadioNavGraph 首次组合时调用（每次切到电台模式都会触发）。
     * - 已经在播放/缓冲中 → 不动
     * - 之前被切到音乐 pause 留下的（streamUrl!=null, !isPlaying, !isBuffering）→ 按 radioWasPlaying 决定是否 resume
     * - 完全 idle（streamUrl==null）→ 读 lastRadioSession，按 radioWasPlaying 决定是否 autoPlay
     */
    fun restoreSessionOnModeSwitch() {
        val current = radioPlayer.playbackState.value
        val url = settingsRepository.lastRadioUrlSync()
        val name = settingsRepository.lastRadioNameSync()
        val id = settingsRepository.lastRadioIdSync()
        val wasPlaying = settingsRepository.radioWasPlayingSync()
        android.util.Log.i(
            "RadioViewModel",
            "restoreSessionOnModeSwitch current.isPlaying=${current.isPlaying} " +
                "isBuffering=${current.isBuffering} streamUrl=${current.streamUrl} " +
                "url=$url wasPlaying=$wasPlaying"
        )

        if (current.isPlaying || current.isBuffering) {
            // 已经在播/缓冲中：不动
            android.util.Log.i("RadioViewModel", "已播放/缓冲中，跳过")
            return
        }

        val restoreId = if (id > 0) id else current.stationId
        if (restoreId != null && restoreId > 0) {
            // 有电台ID：从数据库查合并电台，带着完整多线路恢复（保证自动恢复也有线路切换入口）
            viewModelScope.launch {
                val allStations = try {
                    radioRepository.getAllStations().first()
                } catch (_: Exception) { emptyList() }
                val merged = RadioStationMerger.mergeStations(allStations)
                    .firstOrNull { it.primaryStationId == restoreId }
                if (merged != null) {
                    // 保持用户上次线路播放，仅附带完整线路集合
                    val resumeUrl = url.ifBlank { merged.streams.first().url }
                    android.util.Log.i("RadioViewModel", "合并电台恢复: ${merged.displayName} ${merged.streams.size}线路 url=$resumeUrl")
                    radioPlayer.playStation(
                        url = resumeUrl,
                        stationId = restoreId,
                        stationName = merged.displayName,
                        stationLines = merged.streams.map { it.url },
                        autoPlay = wasPlaying
                    )
                } else {
                    // 找不到合并电台：降级到只恢复 URL
                    android.util.Log.w("RadioViewModel", "没找到ID $restoreId，降级单URL恢复 url=$url")
                    if (url.isNotBlank()) {
                        radioPlayer.playStation(
                            url = url,
                            stationId = restoreId,
                            stationName = name,
                            autoPlay = wasPlaying
                        )
                    }
                }
            }
        } else if (current.streamUrl.isNullOrBlank()) {
            // 完全 idle + 无ID：降级单URL恢复
            if (url.isBlank()) {
                android.util.Log.i("RadioViewModel", "无 lastRadioUrl，跳过")
                return
            }
            android.util.Log.i("RadioViewModel", "从 idle 降级单URL恢复: autoPlay=$wasPlaying")
            radioPlayer.playStation(
                url = url,
                stationId = null,
                stationName = name,
                autoPlay = wasPlaying
            )
        } else {
            // 已有 streamUrl 但暂停了 + 无ID：降级单URL恢复
            if (wasPlaying) {
                android.util.Log.i("RadioViewModel", "从 pause 降级单URL恢复: url=${current.streamUrl}")
                radioPlayer.playStation(
                    url = current.streamUrl,
                    stationId = current.stationId,
                    stationName = current.stationName,
                    autoPlay = true
                )
            } else {
                android.util.Log.i("RadioViewModel", "历史为暂停态，保留内容不自动播")
            }
        }
    }

    // ---- 数据加载 ----

    /** 原始记录缓存：stationId → 原始实体（对话框/编辑用）。每次加载列表时刷新。 */
    private val _rawById = mutableMapOf<Long, RadioStationEntity>()

    /** 供 UI 获取合并电台的主原始记录（编辑/信息对话框底层实体）。 */
    fun stationEntityById(id: Long): RadioStationEntity? = _rawById[id]

    private fun loadStations() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                radioRepository.getAllStations().collect { list ->
                    _rawById.clear()
                    list.forEach { _rawById[it.id] = it }
                    _stations.value = RadioStationMerger.mergeStations(list)
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                _isLoading.value = false
            }
        }
    }

    private fun loadFavorites() {
        viewModelScope.launch {
            try {
                radioRepository.getFavoriteStations().collect { list ->
                    _favorites.value = RadioStationMerger.mergeStations(list)
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadRecent() {
        viewModelScope.launch {
            try {
                radioRepository.getRecentHistory().collect { historyList ->
                    val stationList = historyList.mapNotNull { history ->
                        radioRepository.getStationById(history.stationId)
                    }.distinctBy { it.id }
                    _recentStations.value = RadioStationMerger.mergeStations(stationList)
                }
            } catch (_: Exception) {}
        }
    }

    // ---- 播放控制 ----

    fun playStation(merged: MergedRadioStation) {
        val primaryUrl = merged.streams.firstOrNull()?.url ?: return
        // 移动网络 + 「仅 WiFi」开启 → 先拦截，交由 UI 弹窗征询，同意后才真正播放
        if (settingsRepository.radioWifiOnlySync() && isMobileNetwork()) {
            pendingMobileStation = PendingPlayback(primaryUrl, merged.displayName, merged.primaryStationId)
            _wifiGate.value = pendingMobileStation
            return
        }
        performPlayStation(merged)
    }

    private fun performPlayStation(merged: MergedRadioStation) {
        val primary = merged.streams.firstOrNull() ?: return
        radioPlayer.playStation(
            url = primary.url,
            stationId = merged.primaryStationId,
            stationName = merged.displayName,
            stationLines = merged.streams.map { it.url }
        )
        // 保存上次播放的电台（冷启动续播用）
        viewModelScope.launch {
            settingsRepository.setLastRadioSession(
                url = primary.url,
                name = merged.displayName,
                id = merged.primaryStationId
            )
        }
        viewModelScope.launch {
            try {
                radioRepository.insertHistory(
                    RadioHistoryEntity(
                        stationId = merged.primaryStationId,
                        listenedSeconds = 0,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) {}
        }
    }

    /** 根据原始 ID 重新合并出所在电台分组（用于移动网络确认等场景）。 */
    suspend fun findMergedById(id: Long): MergedRadioStation? {
        val all = radioRepository.getAllStations().first()
        return RadioStationMerger.mergeStations(all)
            .firstOrNull { RadioStationMerger.containsId(it, id) }
    }

    /** 用户手动切换当前播放电台的线路（lineUrls 下标）。 */
    fun switchRadioLine(index: Int) {
        val current = radioState.value
        val id = current.stationId ?: return
        val merged = _stations.value.firstOrNull { it.primaryStationId == id }
            ?: _favorites.value.firstOrNull { it.primaryStationId == id }
            ?: _recentStations.value.firstOrNull { it.primaryStationId == id }
            ?: return
        val target = merged.streams.getOrNull(index) ?: return
        radioPlayer.playStation(
            url = target.url,
            stationId = merged.primaryStationId,
            stationName = current.stationName ?: merged.displayName,
            stationLines = merged.streams.map { it.url }
        )
        viewModelScope.launch {
            settingsRepository.setLastRadioSession(
                url = target.url, name = merged.displayName, id = merged.primaryStationId
            )
        }
    }

    /** 用户同意在移动网络下播放：关闭「仅 WiFi」并继续播放暂存的电台/URL。 */
    fun allowMobilePlayback() {
        val pending = pendingMobileStation ?: return
        _wifiGate.value = null
        pendingMobileStation = null
        viewModelScope.launch {
            settingsRepository.setRadioWifiOnly(false)
            val merged = pending.id?.takeIf { it > 0 }?.let { findMergedById(it) }
            if (merged != null) {
                performPlayStation(merged)
            } else {
                performPlayUrl(pending.url, pending.name)
            }
        }
    }

    /** 用户拒绝：本次不播放，等下次点播。 */
    fun denyMobilePlayback() {
        _wifiGate.value = null
        pendingMobileStation = null
    }

    /** 当前是否处于移动数据网络（非 WiFi / 非离线）。 */
    @Suppress("DEPRECATION")
    private fun isMobileNetwork(): Boolean {
        val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo ?: return false
            return info.type == ConnectivityManager.TYPE_MOBILE
        }
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun playUrl(url: String, name: String? = null) {
        if (settingsRepository.radioWifiOnlySync() && isMobileNetwork()) {
            pendingMobileStation = PendingPlayback(url, name, null)
            _wifiGate.value = pendingMobileStation
            return
        }
        performPlayUrl(url, name)
    }

    private fun performPlayUrl(url: String, name: String?) {
        radioPlayer.playStation(url = url, stationName = name)
        // 保存上次播放的电台（冷启动续播用 + 导航恢复用）+ 尝试匹配已有电台记录
        viewModelScope.launch {
            try {
                val existing = radioRepository.findStationByUrl(url)
                if (existing != null) {
                    radioPlayer.setStationId(existing.id)
                    radioPlayer.setFavorite(existing.isFavorite)
                    radioRepository.insertHistory(
                        RadioHistoryEntity(
                            stationId = existing.id,
                            listenedSeconds = 0,
                            lastPlayedAt = System.currentTimeMillis()
                        )
                    )
                    settingsRepository.setLastRadioSession(url = url, name = name ?: existing.name, id = existing.id)
                } else {
                    settingsRepository.setLastRadioSession(url = url, name = name ?: "未知电台", id = 0)
                }
            } catch (_: Exception) {}
        }
    }

    fun togglePlayPause() {
        radioPlayer.togglePlayPause()
    }

    fun stop() {
        radioPlayer.stop()
    }

    /** 睡眠定时：minutes=0 取消。 */
    fun setRadioSleepTimer(minutes: Int) {
        radioPlayer.setSleepTimer(minutes)
    }

    /** 闹钟启用/禁用，与音乐模式共享 SettingsRepository。 */
    fun setAlarmEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRadioAlarmEnabled(enabled)
            val hour = settingsRepository.radioAlarmHourSync()
            val minute = settingsRepository.radioAlarmMinuteSync()
            com.shiyinplayer.player.radio.RadioAlarmReceiver.schedule(
                application, enabled, hour, minute
            )
        }
    }

    /** 设置闹钟时间，与音乐模式共享 SettingsRepository。 */
    fun setAlarmTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setRadioAlarmHour(hour)
            settingsRepository.setRadioAlarmMinute(minute)
            if (settingsRepository.radioAlarmEnabledSync()) {
                com.shiyinplayer.player.radio.RadioAlarmReceiver.schedule(
                    application, true, hour, minute
                )
            }
        }
    }

    /**
     * 在收藏列表中播放"上一首"（循环）。委托给 [RadioPlayer]，
     * 让 Service 通知栏/媒体按键也走同一条路径，逻辑一致。
     */
    fun playPreviousFavorite() = radioPlayer.playPreviousFavorite()

    /**
     * 在收藏列表中播放"下一首"（循环）。
     */
    fun playNextFavorite() = radioPlayer.playNextFavorite()

    // ---- 分类筛选 ----

    fun selectGenre(genre: String?) {
        _selectedGenre.value = genre
    }

    fun selectRegion(region: String?) {
        _selectedRegion.value = region
    }

    // ---- 收藏 ----

    fun toggleFavorite(station: RadioStationEntity) {
        viewModelScope.launch {
            try {
                var targetId = station.id
                // 如果 id 为 0（来自搜索/URL 播放），先尝试查找或入库
                if (targetId <= 0 && station.url.isNotBlank()) {
                    val existing = radioRepository.findStationByUrl(station.url)
                    if (existing != null) {
                        targetId = existing.id
                    } else {
                        // 入库后获取真实 id
                        targetId = radioRepository.insertStation(
                            RadioStationEntity(
                                name = station.name.ifBlank { "未知电台" },
                                url = station.url,
                                source = "user",
                                isFavorite = true,
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        // 更新 RadioPlayer 状态中的 stationId
                        radioPlayer.setStationId(targetId)
                        radioPlayer.setFavorite(true)
                        return@launch
                    }
                }
                val targetStation = radioRepository.getStationById(targetId)
                val newFav = !(targetStation?.isFavorite ?: false)
                radioRepository.setFavorite(targetId, newFav)
                radioPlayer.setFavorite(newFav)
            } catch (e: Exception) {
                android.util.Log.e("RadioViewModel", "toggleFavorite failed", e)
                _errorEvents.tryEmit("收藏失败：${e.message ?: "未知错误"}")
            }
        }
    }

    /** 切换合并电台的收藏状态：所有流的收藏状态统一（一条收藏 → 全部收藏有效）。 */
    fun toggleFavorite(merged: MergedRadioStation) {
        viewModelScope.launch {
            try {
                val newFav = !merged.isFavorite
                // 遍历所有线路，统一设置收藏状态
                for (stream in merged.streams) {
                    radioRepository.setFavorite(stream.stationId, newFav)
                }
                radioPlayer.setFavorite(newFav)
            } catch (e: Exception) {
                android.util.Log.e("RadioViewModel", "toggleFavorite (merged) failed", e)
                _errorEvents.tryEmit("收藏失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun addFavorite(url: String, name: String, genre: String = "") {
        viewModelScope.launch {
            try {
                val entity = RadioStationEntity(
                    name = name,
                    url = url,
                    genre = genre,
                    source = "user",
                    isFavorite = true,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                radioRepository.insertStation(entity)
            } catch (_: Exception) {}
        }
    }

    /** 新增电台（多条线路）：每个 URL 入库为一行，共用名称/分类/地区。 */
    fun addFavoriteStationLines(name: String, urls: List<String>, genre: String = "") {
        val cleanName = name.trim().ifBlank { "未知电台" }
        val targets = urls.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { normalizeRadioUrl(it) }
        if (targets.isEmpty()) return
        val country = radioCountryOf(genre)
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                for (u in targets) {
                    radioRepository.insertStation(
                        RadioStationEntity(
                            name = cleanName,
                            url = u,
                            genre = genre,
                            country = country,
                            source = "user",
                            isFavorite = true,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
            } catch (_: Exception) {}
        }
    }

    /** 保存编辑后的合并电台：更新保留线路、删除移除线路、插入新增线路，统一写入名称/分类/地区；内置台接管为 user。 */
    fun saveEditedStation(name: String, urls: List<String>, genre: String, merged: MergedRadioStation) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val targets = urls.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { normalizeRadioUrl(it) }
        if (targets.isEmpty()) return
        val newSet = targets.map { normalizeRadioUrl(it) }.toSet()
        val country = radioCountryOf(genre)
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                val existingUrlToId = mutableMapOf<String, Long>()
                val existingUrlToSource = mutableMapOf<String, String>()
                for (s in merged.streams) {
                    val nu = normalizeRadioUrl(s.url)
                    existingUrlToId[nu] = s.stationId
                    existingUrlToSource[nu] = s.source
                }
                for (u in targets) {
                    val nu = normalizeRadioUrl(u)
                    val id = existingUrlToId[nu]
                    if (id != null) {
                        val cur = radioRepository.getStationById(id) ?: continue
                        val src = if (existingUrlToSource[nu] == "builtin") "user" else cur.source
                        radioRepository.updateStation(
                            cur.copy(name = cleanName, genre = genre, country = country, source = src, updatedAt = now)
                        )
                    } else {
                        radioRepository.insertStation(
                            RadioStationEntity(
                                name = cleanName,
                                url = u,
                                genre = genre,
                                country = country,
                                source = "user",
                                isFavorite = false,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    }
                }
                for (s in merged.streams) {
                    if (normalizeRadioUrl(s.url) !in newSet) {
                        val cur = radioRepository.getStationById(s.stationId)
                        if (cur != null) radioRepository.deleteStation(cur)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /** 从 genre 解析 country（港澳台→子类地区，海外→国家；大陆无国家）。 */
    private fun radioCountryOf(genre: String?): String? {
        val parts = genre?.split("/") ?: return null
        val region = parts.getOrNull(1) ?: return null
        val sub = parts.getOrNull(2) ?: return null
        return if ((region == "港澳台" || region == "海外") && sub.isNotBlank()) sub else null
    }

    // ---- 删除 ----

    fun deleteStation(station: RadioStationEntity) {
        viewModelScope.launch {
            try {
                radioRepository.deleteStation(station)
            } catch (_: Exception) {}
        }
    }

    /** 删除合并电台：删除其所有线路记录。 */
    fun deleteStation(merged: MergedRadioStation) {
        viewModelScope.launch {
            try {
                for (stream in merged.streams) {
                    val raw = radioRepository.getStationById(stream.stationId)
                    if (raw != null) radioRepository.deleteStation(raw)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 更新电台信息（名称、URL、分类等）。
     * 用户手动修改内置(builtin)电台时，将其 source 改写为 "user"（用户接管），
     * 使 RadioStationSeeder 下次启动不再按 assets 清单回写覆盖。
     */
    fun updateStation(station: RadioStationEntity) {
        viewModelScope.launch {
            try {
                val takeover = if (station.source == "builtin") station.copy(source = "user") else station
                radioRepository.updateStation(takeover)
            } catch (_: Exception) {}
        }
    }

    /** 更新合并电台：对主线路执行接管修改（其余线路保留原始来源）。 */
    fun updateStation(merged: MergedRadioStation) {
        if (merged.streams.isEmpty()) return
        viewModelScope.launch {
            try {
                val current = radioRepository.getStationById(merged.primaryStationId)
                if (current != null) {
                    val takeover = if (current.source == "builtin") current.copy(source = "user") else current
                    radioRepository.updateStation(takeover)
                }
            } catch (_: Exception) {}
        }
    }

    // ---- OPML 导入导出 ----

    /** OPML 导出结果。成功返回文件 Uri，失败返回 null。 */
    private val _opmlExportResult = MutableSharedFlow<Uri?>(extraBufferCapacity = 1)
    val opmlExportResult: SharedFlow<Uri?> = _opmlExportResult.asSharedFlow()

    /** OPML 导入结果。返回导入的电台数量（0 表示失败或无数据）。 */
    private val _opmlImportResult = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val opmlImportResult: SharedFlow<Int> = _opmlImportResult.asSharedFlow()

    /** 正在执行导入/导出？ */
    private val _isOpmlBusy = MutableStateFlow(false)
    val isOpmlBusy: StateFlow<Boolean> = _isOpmlBusy.asStateFlow()

    /**
     * 导出收藏电台到 Downloads 目录（MediaStore）。
     */
    fun exportFavoritesToOpml() {
        viewModelScope.launch {
            _isOpmlBusy.value = true
            try {
                val favs = radioRepository.getFavoriteStationsSync()
                if (favs.isEmpty()) {
                    _errorEvents.tryEmit("没有收藏电台，无法导出")
                    _opmlExportResult.tryEmit(null)
                    return@launch
                }
                val uri = RadioOpmlHelper.exportToDownloads(application, favs)
                _opmlExportResult.tryEmit(uri)
            } catch (e: Exception) {
                android.util.Log.e("RadioViewModel", "exportFavoritesToOpml failed", e)
                _errorEvents.tryEmit("导出失败：${e.message ?: "未知错误"}")
                _opmlExportResult.tryEmit(null)
            } finally {
                _isOpmlBusy.value = false
            }
        }
    }

    /**
     * 从 OPML Uri 导入电台并自动收藏。
     */
    fun importFavoritesFromOpml(uri: Uri) {
        viewModelScope.launch {
            _isOpmlBusy.value = true
            try {
                val stations = application.contentResolver.openInputStream(uri)?.use { stream ->
                    RadioOpmlHelper.importFromOpml(stream)
                } ?: emptyList()

                if (stations.isEmpty()) {
                    _errorEvents.tryEmit("OPML 文件中没有找到电台")
                    _opmlImportResult.tryEmit(0)
                    return@launch
                }

                var imported = 0
                for (station in stations) {
                    try {
                        // 按 URL 去重：已存在则仅设为收藏
                        val existing = radioRepository.findStationByUrl(station.url)
                        if (existing != null) {
                            if (!existing.isFavorite) {
                                radioRepository.setFavorite(existing.id, true)
                                imported++
                            }
                        } else {
                            // 新电台，插入并收藏
                            radioRepository.insertStation(
                                station.copy(isFavorite = true)
                            )
                            imported++
                        }
                    } catch (_: Exception) {}
                }
                _opmlImportResult.tryEmit(imported)
            } catch (e: Exception) {
                android.util.Log.e("RadioViewModel", "importFavoritesFromOpml failed", e)
                _errorEvents.tryEmit("导入失败：${e.message ?: "未知错误"}")
                _opmlImportResult.tryEmit(0)
            } finally {
                _isOpmlBusy.value = false
            }
        }
    }

    // ---- 跨模式抢占 ----

    fun onMusicPlayerPreempted() {
        radioPlayer.releaseAudioFocus()
    }
}
