package com.shiyinplayer.player.radio

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.shiyinplayer.data.local.entity.RadioStationEntity
import com.shiyinplayer.data.radio.IcecastEpgClient
import com.shiyinplayer.data.repository.RadioRepository
import com.shiyinplayer.player.CommonPlaybackState
import com.shiyinplayer.player.PlaybackHost
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers as CoroutinesDispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络电台播放器（独立 ExoPlayer 实例，与音乐 PlayerManager 完全隔离）。
 *
 * 职责：
 * - 播放 HTTP(S) 网络音频流（支持 ICY 元数据自动解析）
 * - 独立状态流（RadioState）
 * - 断流自动重连（1s/2s/4s/8s 指数退避）
 * - 耳机拔出/蓝牙断开自动暂停，重连自动恢复
 * - 实现 PlaybackHost 接口供通知/锁屏/焦点门控统一消费
 * - 跨模式抢占：播放电台时暂停音乐，播放音乐时暂停电台
 */
@Singleton
class RadioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val radioRepository: RadioRepository
) : PlaybackHost {

    companion object {
        private const val TAG = "RadioPlayer"
        private const val MAX_RETRY_ATTEMPTS = 8
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 16_000, 16_000, 16_000)
        /** 单线路重试耗尽后切换到下一线路；所有线路各尝试一轮仍失败则放弃 */
        private const val MAX_LINE_ATTEMPTS_PER_PLAY = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- ExoPlayer 实例（独立于音乐模式） ----
    private var exoPlayer: ExoPlayer? = null

    // ---- 状态 ----
    private val _state = MutableStateFlow(RadioState())
    override val playbackState: StateFlow<RadioState> = _state.asStateFlow()

    private val _currentLyricLine = MutableStateFlow<String?>(null)
    override val currentLyricLine: StateFlow<String?> = _currentLyricLine.asStateFlow()

    override val sessionPlayer: Player
        get() = exoPlayer ?: throw IllegalStateException("RadioPlayer not initialized")

    override val isPlaying: Boolean
        get() = exoPlayer?.isPlaying == true

    // ---- 重连 ----
    private var retryAttempt = 0
    private var retryJob: Job? = null
    private var currentUrl: String? = null

    // ---- 多线路支持 ----
    /** 当前电台的全部线路（流）地址，按播放顺序排列；单线路时为仅含一个元素的列表 */
    private var stationLines: List<String> = emptyList()
    private var lineIndex = 0
    private var linePlayAttempts = 0

    // ---- 跨模式抢占回调 ----
    private val preemptionListeners = CopyOnWriteArrayList<() -> Unit>()

    // ---- 睡眠定时 ----
    private val _sleepTimerEndAt = MutableStateFlow<Long?>(null)
    override val sleepTimerEndAt: StateFlow<Long?> = _sleepTimerEndAt.asStateFlow()
    private var sleepTimerJob: Job? = null

    // ---- EPG 轮询 ----
    private var epgJob: Job? = null
    private val EPG_INTERVAL_MS = 5 * 60_000L

    // ---- 耳机/蓝牙断开自动暂停 ----
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var wasPlayingBeforeDisconnect = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (isPlaying && settingsRepository.headsetPauseSync()) {
                    wasPlayingBeforeDisconnect = true
                    pause()
                    Log.i(TAG, "耳机/蓝牙断开(noisy)，自动暂停")
                }
            }
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (isPlaying && settingsRepository.btDisconnectPauseSync()) {
                        wasPlayingBeforeDisconnect = true
                        pause()
                        Log.i(TAG, "蓝牙断开，自动暂停")
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (wasPlayingBeforeDisconnect
                        && currentUrl != null
                        && settingsRepository.btReconnectResumeSync()
                    ) {
                        wasPlayingBeforeDisconnect = false
                        play()
                        Log.i(TAG, "蓝牙重连，自动恢复播放")
                    }
                }
            }
        }
    }

    private val audioDeviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                val btAdded = addedDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (btAdded != null
                    && wasPlayingBeforeDisconnect
                    && currentUrl != null
                    && settingsRepository.btReconnectResumeSync()
                ) {
                    wasPlayingBeforeDisconnect = false
                    play()
                    Log.i(TAG, "蓝牙音频设备重新加入路由，自动恢复播放")
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                val btRemoved = removedDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                val wiredRemoved = removedDevices.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                // 有线设备拔出走耳机设置；蓝牙走蓝牙设置。任一被启用即暂停。
                val shouldPause = (wiredRemoved && settingsRepository.headsetPauseSync()) ||
                    (btRemoved != null && settingsRepository.btDisconnectPauseSync())
                if (shouldPause && isPlaying) {
                    wasPlayingBeforeDisconnect = true
                    pause()
                    Log.i(TAG, "音频设备移除，自动暂停")
                }
            }
        }
    } else null

    // ---- ExoPlayer 监听（必须在 init 之前声明，确保初始化顺序） ----

    @androidx.annotation.OptIn(UnstableApi::class)
    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _state.update { it.copy(isBuffering = true) }
                }
                Player.STATE_READY -> {
                    _state.update { it.copy(isBuffering = false, error = null) }
                    retryAttempt = 0
                }
                Player.STATE_ENDED -> {
                    _state.update { it.copy(isPlaying = false, isBuffering = false) }
                    scheduleRetry()
                }
                Player.STATE_IDLE -> {
                    _state.update { it.copy(isBuffering = false) }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error: ${error.message}", error)
            _state.update {
                it.copy(
                    error = error.message ?: "播放出错",
                    isPlaying = false,
                    isBuffering = false
                )
            }
            scheduleRetry()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val title = mediaMetadata.title?.toString()
            val artist = mediaMetadata.artist?.toString()
            val artworkUri = mediaMetadata.artworkUri?.toString()

            _state.update {
                it.copy(
                    stationName = title ?: it.stationName,
                    programName = artist ?: it.programName,
                    coverUrl = artworkUri ?: it.coverUrl
                )
            }

            if (title != null) {
                _currentLyricLine.value = title
            }
        }
    }

    // ---- 初始化 ----
    init {
        ensurePlayer()
        registerAudioReceivers()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun ensurePlayer() {
        if (exoPlayer == null) {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 60_000, 2_000, 3_000)
                .build()

            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(8_000)
                .setAllowCrossProtocolRedirects(true)

            val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

            exoPlayer = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                // 关闭 ExoPlayer 内置的自动 pause：由我们自己的 noisyReceiver 统一接管，
                // 避免 ExoPlayer 先 pause 后 receiver 再跑，wasPlayingBeforeDisconnect 设不上的竞态。
                .setHandleAudioBecomingNoisy(false)
                .build()
                .apply {
                    addListener(playerListener)
                }
        }
    }

    private fun registerAudioReceivers() {
        runCatching {
            context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        runCatching {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            }
            context.registerReceiver(btReceiver, filter)
        }
        if (audioDeviceCallback != null) {
            runCatching {
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
            }
        }
    }

    // ---- PlaybackHost 实现 ----

    override fun play() {
        exoPlayer?.play()
    }

    override fun pause() {
        exoPlayer?.pause()
    }

    override fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) pause() else play()
    }

    override fun stop() {
        cancelRetry()
        stopEpgPolling()
        exoPlayer?.stop()
        _state.update { RadioState() }
    }

    override fun releaseAudioFocus() {
        pause()
    }

    override fun livePositionMs(): Long {
        return exoPlayer?.currentPosition ?: 0L
    }

    override fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) {
            _sleepTimerEndAt.value = null
            return
        }
        val endAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepTimerEndAt.value = endAt
        sleepTimerJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            _sleepTimerEndAt.value = null
        }
    }

    // ---- 电台播放 ----

    /**
     * 播放指定 URL 的电台流。
     * @param url 电台流地址
     * @param stationId 电台 Room ID（可选，用于收藏状态）
     * @param stationName 电台名（可选，显示用）
     * @param autoPlay true=立即开始播放；false=只设置 MediaItem 并 prepare（用于切模式恢复但保持暂停态）
     */
    fun playStation(
        url: String,
        stationId: Long? = null,
        stationName: String? = null,
        stationLines: List<String> = emptyList(),
        autoPlay: Boolean = true
    ) {
        // 更新线路集合：显式传入则采用；否则沿用上一次（若 URL 在同组内）或单线路
        val lines = when {
            stationLines.isNotEmpty() -> stationLines
            this.stationLines.contains(url) -> this.stationLines
            else -> listOf(url)
        }
        this.stationLines = lines
        lineIndex = lines.indexOf(url).coerceAtLeast(0)
        linePlayAttempts = 0

        val lineCount = lines.size
        if (currentUrl == url) {
            // 同一 URL：已经在播/正在缓冲 → 跳过（避免无谓的重新缓冲）
            if (exoPlayer?.isPlaying == true || _state.value.isBuffering) {
                _state.update { it.copy(lineCount = lineCount, lineIndex = lineIndex) }
                return
            }
            _state.update { it.copy(lineCount = lineCount, lineIndex = lineIndex) }
            // 同一 URL 但已暂停：按 autoPlay 决定
            if (autoPlay) {
                ensurePlayer()
                val player = exoPlayer ?: return
                // 底层流仍处于 READY（已暂停未断流）：直接 play() 恢复即可，避免无谓的重新缓冲
                if (player.playbackState == Player.STATE_READY) {
                    _state.update { it.copy(isBuffering = true, error = null, lineCount = lineCount, lineIndex = lineIndex) }
                    startNotificationService()
                    startEpgPolling()
                    player.play()
                    return
                }
                // 底层已进入 ENDED/IDLE：直播流在长期 pause 后 exoPlayer 内部可能进入此状态，
                // 此时再调 play() 不会触发 onIsPlayingChanged → UI 卡在缓冲中。
                // 修复：重新 setMediaItem + prepare + play 强制重启流，
                // 让 onPlaybackStateChanged(STATE_READY) 正确把 isBuffering=false。
                _state.update { it.copy(isBuffering = true, error = null, lineCount = lineCount, lineIndex = lineIndex) }
                startStream(url, stationName)
                startNotificationService()
                startEpgPolling()
            }
            return
        }

        currentUrl = url
        retryAttempt = 0
        cancelRetry()
        wasPlayingBeforeDisconnect = false

        _state.update {
            it.copy(
                streamUrl = url,
                stationId = stationId,
                stationName = stationName,
                error = null,
                isBuffering = autoPlay,  // 仅自动播放时显示缓冲
                lineCount = lineCount,
                lineIndex = lineIndex
            )
        }

        // 跨模式抢占：通知音乐侧暂停
        notifyPreemption()

        if (autoPlay) {
            startStream(url, stationName)
            // 启动前台通知服务
            startNotificationService()
            // 启动 EPG 轮询
            startEpgPolling()
        } else {
            // 仅设置 MediaItem，不 prepare/play：保持 PAUSED 状态
            // 状态 isPlaying=false, isBuffering=false, 但 streamUrl/stationName 已有 → UI 正常显示
            ensurePlayer()
            val player = exoPlayer ?: return
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(stationName)
                        .build()
                )
                .build()
            player.setMediaItem(mediaItem)
        }
    }

    /** 统一地开始/切换一条流：prepare + play，当前流保持缓冲中。 */
    private fun startStream(url: String, stationName: String?) {
        ensurePlayer()
        val player = exoPlayer ?: return
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(stationName)
                    .setArtist("FM Radio")
                    .build()
            )
            .build()
        _state.update { it.copy(isBuffering = true, error = null) }
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    /**
     * 收藏列表的"上一首"（循环）。Service 和 ViewModel 都能调。
     * 匹配规则：先按 stationId，再按 streamUrl；都未匹配则播列表最后一首。
     */
    fun playPreviousFavorite() {
        scope.launch { playAdjacentFavorite(forward = false) }
    }

    /**
     * 收藏列表的"下一首"（循环）。匹配规则同 [playPreviousFavorite]。
     */
    fun playNextFavorite() {
        scope.launch { playAdjacentFavorite(forward = true) }
    }

    private suspend fun playAdjacentFavorite(forward: Boolean) {
        val favs = withContext(CoroutinesDispatchers.IO) {
            radioRepository.getFavoriteStationsSync()
        }
        if (favs.isEmpty()) return
        val currentId = _state.value.stationId
        val currentUrl = _state.value.streamUrl
        val currentIdx = favs.indexOfFirst {
            (currentId != null && currentId > 0L && it.id == currentId) ||
                (currentUrl != null && it.url == currentUrl)
        }
        val nextIdx = when {
            currentIdx < 0 -> if (forward) 0 else favs.lastIndex
            else -> ((currentIdx + if (forward) 1 else -1) + favs.size) % favs.size
        }
        val target = favs[nextIdx]
        playStation(url = target.url, stationId = target.id, stationName = target.name)
        // 同步收藏状态（如果目标电台在数据库里已收藏）
        if (target.isFavorite) setFavorite(true)
    }

    private fun startNotificationService() {
        try {
            val intent = android.content.Intent(context, RadioPlaybackService::class.java)
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start notification service", e)
        }
    }

    /**
     * 切换收藏状态（外部调用，UI 层用）。
     */
    fun setFavorite(favorite: Boolean) {
        _state.update { it.copy(isFavorite = favorite) }
    }

    /**
     * 更新当前电台 ID（外部调用，入库后更新）。
     */
    fun setStationId(id: Long) {
        _state.update { it.copy(stationId = id) }
    }

    /** 用户手动切换到指定下标线路，开始播放。 */
    fun switchToLine(index: Int) {
        val lines = stationLines
        if (index !in lines.indices) return
        val url = lines[index]
        val name = _state.value.stationName
        lineIndex = index
        linePlayAttempts = 0
        currentUrl = url
        retryAttempt = 0
        cancelRetry()

        _state.update {
            it.copy(
                streamUrl = url,
                lineIndex = index,
                lineCount = lines.size,
                error = null,
                isBuffering = true
            )
        }
        startStream(url, name)
        startNotificationService()
        startEpgPolling()
    }

    // ---- 重连逻辑 ----

    private fun scheduleRetry() {
        if (retryAttempt >= MAX_RETRY_ATTEMPTS) {
            // 当前线路重试耗尽 → 尝试自动切换到本电台的下一条可用线路
            if (trySwitchToNextLine()) return
            _state.update {
                it.copy(
                    error = "电台暂时不可用（所有线路均无法播放）",
                    isPlaying = false,
                    isBuffering = false
                )
            }
            return
        }

        val delayMs = RETRY_DELAYS_MS[retryAttempt.coerceAtMost(RETRY_DELAYS_MS.size - 1)]
        retryAttempt++

        Log.w(TAG, "Reconnecting in ${delayMs}ms (attempt $retryAttempt)")

        retryJob = scope.launch {
            delay(delayMs)
            currentUrl?.let { url ->
                startStream(url, _state.value.stationName)
            }
        }
    }

    /** 当前线路无法播放时，尝试切换到下一线路。返回 true 表示已切换。 */
    private fun trySwitchToNextLine(): Boolean {
        val lines = stationLines
        if (lines.size <= 1) return false
        // 所有线路各尝试 MAX_LINE_ATTEMPTS_PER_PLAY 轮后仍失败 → 放弃
        if (linePlayAttempts >= lines.size * MAX_LINE_ATTEMPTS_PER_PLAY) return false

        val nextIndex = (lineIndex + 1) % lines.size
        if (nextIndex == lineIndex) return false

        val next = lines[nextIndex]
        val name = _state.value.stationName
        lineIndex = nextIndex
        linePlayAttempts++
        currentUrl = next
        retryAttempt = 0
        cancelRetry()
        Log.w(TAG, "线路 $nextIndex 无法播放，自动切换线路: $next")

        _state.update {
            it.copy(
                streamUrl = next,
                lineIndex = nextIndex,
                error = null,
                isPlaying = false,
                isBuffering = true
            )
        }
        startStream(next, name)
        return true
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    // ---- 睡眠定时 ----

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    // ---- 跨模式抢占 ----

    fun addPreemptionListener(listener: () -> Unit) {
        preemptionListeners.add(listener)
    }

    fun removePreemptionListener(listener: () -> Unit) {
        preemptionListeners.remove(listener)
    }

    private fun notifyPreemption() {
        preemptionListeners.forEach { it() }
    }

    // ---- EPG 轮询 ----

    /**
     * 启动 Icecast EPG 周期性查询（5 分钟间隔）。
     * 首次查询延迟 3 秒等缓冲完成。
     */
    private fun startEpgPolling() {
        stopEpgPolling()
        // 首次查询
        scope.launch {
            delay(3_000)
            fetchAndApplyEpg()
        }
        // 周期性查询
        epgJob = scope.launch {
            while (true) {
                delay(EPG_INTERVAL_MS)
                fetchAndApplyEpg()
            }
        }
    }

    private fun stopEpgPolling() {
        epgJob?.cancel()
        epgJob = null
        _state.update { it.copy(currentTrack = null) }
    }

    private suspend fun fetchAndApplyEpg() {
        val url = _state.value.streamUrl ?: return
        val epg = IcecastEpgClient.fetchEpg(url)
        if (epg != null) {
            _state.update { it.copy(currentTrack = epg.currentTrack) }
        }
    }

    // ---- 清理 ----

    fun release() {
        cancelRetry()
        cancelSleepTimer()
        stopEpgPolling()
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        currentUrl = null
        runCatching { context.unregisterReceiver(noisyReceiver) }
        runCatching { context.unregisterReceiver(btReceiver) }
        if (audioDeviceCallback != null) {
            runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        }
    }
}
