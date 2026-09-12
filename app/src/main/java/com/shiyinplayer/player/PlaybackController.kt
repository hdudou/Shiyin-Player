package com.shiyinplayer.player

import android.content.BroadcastReceiver
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.media3.exoplayer.ExoPlayer
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 兜底播放路由判定（由 PlayerManager 注入实际策略）。
 * - [READY]   数据源已就绪（本地 APE/WMA 或远端已本地缓存），可直接交给系统 MediaPlayer。
 * - [DOWNLOAD]远端 APE/WMA 未缓存，需先整首下载到本地再交系统解码（FFmpeg 解不动）。
 * - [NO]      非兜底格式，走 ExoPlayer 主链路。
 */
enum class FallbackRoute { READY, DOWNLOAD, NO }

/**
 * 播放控制器（A1 步骤 3：从 PlayerManager 拆出的独立职责，行为等价）。
 *
 * 接管「播放命令 + 音频段状态」：
 * - 命令：play / pause / togglePlayPause / next / previous / seekTo / livePositionMs / seekToIndex / navigateTo
 * - 音频段：音量曲线映射（volume_curve）、淡入淡出（fadeIn/fadeOut）、音量（baseVolume）
 * - 睡眠定时（C-04）、耳机拔出自动暂停（C-02）
 *
 * 运行时挂钩（ExoPlayer 活引用 / MediaPlayer 兜底 / 播放状态 / UI 刷新 / 会话持久化 / 前台服务启动 /
 * 兜底格式判定 / 兜底停止）由 PlayerManager 在 init 经 [bind] 注入，避免构造期耦合可变引擎与回调。
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queueController: QueueController,
    private val queueManager: QueueManager,
    private val settings: SettingsRepository,
    // A2：播放动作单线程串行执行器（消除 ExoPlayer TOCTOU 竞态）
    private val playbackActor: PlaybackActor
) {
    // ===== 运行时挂钩（PlayerManager.init 绑定） =====
    private var playerRef: () -> ExoPlayer = { error("PlaybackController 未绑定 ExoPlayer") }
    private var mediaPlayerFallback: MediaPlayerFallback? = null
    private var state: MutableStateFlow<PlaybackState>? = null
    private var onChanged: () -> Unit = {}
    private var persist: () -> Unit = {}
    private var ensureForeground: () -> Unit = {}
    private var routeFallback: (Song) -> FallbackRoute = { FallbackRoute.NO }
    private var fallbackSourceFor: (Song) -> String = { it.uri }
    private var startFallbackDownload: (Song) -> Unit = {}
    private var stopFallback: () -> Unit = {}

    fun bind(
        playerRef: () -> ExoPlayer,
        mediaPlayerFallback: MediaPlayerFallback,
        state: MutableStateFlow<PlaybackState>,
        onChanged: () -> Unit,
        persist: () -> Unit,
        ensureForeground: () -> Unit,
        routeFallback: (Song) -> FallbackRoute,
        fallbackSourceFor: (Song) -> String,
        startFallbackDownload: (Song) -> Unit,
        stopFallback: () -> Unit
    ) {
        this.playerRef = playerRef
        this.mediaPlayerFallback = mediaPlayerFallback
        this.state = state
        this.onChanged = onChanged
        this.persist = persist
        this.ensureForeground = ensureForeground
        this.routeFallback = routeFallback
        this.fallbackSourceFor = fallbackSourceFor
        this.startFallbackDownload = startFallbackDownload
        this.stopFallback = stopFallback
    }

    private fun exo(): ExoPlayer = playerRef()
    private val fb: MediaPlayerFallback get() = mediaPlayerFallback ?: error("PlaybackController 未绑定 MediaPlayer 兜底")
    private val st: MutableStateFlow<PlaybackState> get() = state ?: error("PlaybackController 未绑定播放状态")

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // P2 音频设置接线（fade / 音量曲线）
    private var headsetPause = true
    private var fadeInMs = 0
    private var fadeOutMs = 0
    private var volumeCurve = "log"
    private var baseVolume = 1f
    private var fadeJob: Job? = null
    private var pendingPauseJob: Job? = null

    // 2026-08-24：耳机按键控制 / 蓝牙断开暂停 / 蓝牙重连恢复 / 仅持音频焦点播放
    private var headsetButtonControl = true
    private var btDisconnectPause = false
    private var btReconnectResume = false
    private var playRequiresAudioFocus = true
    /** 因蓝牙断开而暂停、待重连后恢复的运行时标记。 */
    @Volatile private var resumeOnBtReconnect = false
    /**
     * 断开瞬间各信号（A2DP 广播 / NOISY / AudioDeviceCallback）几乎同时触发；第一个（正在播放）置位
     * resumeOnBtReconnect 并暂停，随后重复信号看到已暂停(wasPlaying=false)会误清标记。用时间戳护栏：
     * 仅当距上次有效置位超过 [RESUME_MARKER_GUARD_MS] 才允许清除，把同一断开事件的多路信号视为一次。
     */
    @Volatile private var lastResumeMarkerAt = Long.MIN_VALUE
    private val RESUME_MARKER_GUARD_MS = 3000L
    /**
     * 中危-F：因蓝牙断开标记待恢复时，绑定当时断开的设备地址。恢复播放前校验仍是同一设备，
     * 避免「断开耳机 A 重连到音箱 B」时把 A 的待恢复误用于 B（多设备切换误自动播放）。
     * null/空代表未知（NOISY 等无地址信号），此时退化为旧行为。
     */
    @Volatile private var pendingResumeBtAddress: String? = null
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var hasFocus = false
    @Volatile private var resumeOnGain = false
    private var focusRequest: AudioFocusRequest? = null
    /** Z-开关：音频焦点被永久抢占(LOSS)时的策略——true 则自动把队列指针移到下一曲；false 保留当前曲待手动续播。 */
    @Volatile private var focusLossAutoSkip = false
    /** Q-音量收敛：可 duck 暂失时经 ADJUST_LOWER 调低系统媒体流的次数，GAIN 时按相同次数 ADJUST_RAISE 恢复。 */
    private var duckLowerCount = 0
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                // Q-音量收敛：恢复 duck 暂失时被调低的系统媒体流音量，避免系统音量长期悬置在低值
                if (duckLowerCount > 0) {
                    repeat(duckLowerCount) {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0
                        )
                    }
                    duckLowerCount = 0
                }
                if (resumeOnGain && playRequiresAudioFocus) {
                    resumeOnGain = false
                    play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                resumeOnGain = false
                // Z-开关：永久被抢占时，开启"自动跳到下一曲"则把队列指针前移到下一曲
                // （始终让权），手动续播即从下一曲开始；关闭则保留当前曲待手动续播。
                if (focusLossAutoSkip) {
                    val ni = queueController.nextIndex()
                    if (ni >= 0) queueController.setCurrentIndex(ni)
                }
                pause()
                abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 电话/通知等短暂焦点丢失：仅当用户有播放意图（playWhenReady）才标记待恢复并暂停，
                // 这样未播放时接听电话挂断后不会误恢复；用 playWhenReady 而非瞬时 isPlaying 判定，
                // 可避免「两首歌切换瞬间的重缓冲空档」或「相邻重复焦点事件」误判为未在播放而放弃恢复。
                // 关键：瞬态丢失绝不在此清除 resumeOnGain——重复描述的事件不能把已置位的待恢复标记抹掉，
                // 否则连播中两次焦点丢失会让恢复失败，表现为“播放一段时间后自动停止”。
                if (runCatching { exo().playWhenReady }.getOrDefault(false)) {
                    resumeOnGain = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 采用回避策略：非持久丢失时降音量播放，保持焦点不中断；GAIN 时按相同次数恢复
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0
                )
                duckLowerCount++
            }
        }
    }
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!btDisconnectPause && !btReconnectResume) return
            when (intent?.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTED)
                    val prev = intent.getIntExtra(BluetoothA2dp.EXTRA_PREVIOUS_STATE, BluetoothA2dp.STATE_DISCONNECTED)
                    if (state == BluetoothA2dp.STATE_DISCONNECTED) {
                        // 部分 ROM（ColorOS 等）断开时 prev 为 CONNECTING/AUDIO_CONNECTING，放宽为「前状态非已断开」即暂停
                        if (prev != BluetoothA2dp.STATE_DISCONNECTED) pauseOnBtDisconnect(btAddressFrom(intent))
                    } else if (state == BluetoothA2dp.STATE_CONNECTED) {
                        resumeOnBtReconnectIfNeeded(btAddressFrom(intent))
                    }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)
                    val prev = intent.getIntExtra(BluetoothHeadset.EXTRA_PREVIOUS_STATE, BluetoothHeadset.STATE_DISCONNECTED)
                    if (state == BluetoothHeadset.STATE_DISCONNECTED) {
                        if (prev != BluetoothHeadset.STATE_DISCONNECTED) pauseOnBtDisconnect(btAddressFrom(intent))
                    } else if (state == BluetoothHeadset.STATE_CONNECTED) {
                        resumeOnBtReconnectIfNeeded(btAddressFrom(intent))
                    }
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    // 2026-08-24：蓝牙断开兜底——连接广播可能被 ROM 裁剪/不匹配，改由音频路由移除事件保证暂停。
    private val audioDeviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                val btAdded = addedDevices.firstOrNull { isBtAudioDevice(it) }
                // 蓝牙重连的可靠信号：设备重新加入路由时恢复（防蓝牙 CONNECTED 广播未被触发）
                if (btAdded != null) resumeOnBtReconnectIfNeeded(btAddressFrom(btAdded))
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                val btRemoved = removedDevices.firstOrNull { isBtAudioDevice(it) }
                val wiredRemoved = removedDevices.any { isWiredAudioDevice(it) }
                if (btRemoved != null && (btDisconnectPause || btReconnectResume)) {
                    Log.i("AudioFocus", "蓝牙音频设备移除，暂停")
                    pauseOnBtDisconnect(btAddressFrom(btRemoved))
                } else if (wiredRemoved && headsetPause) {
                    // 有线/USB-C 耳机拔出：只暂停、不标记重连恢复；同时清残留 BT 标记防后续蓝牙重连误恢复。
                    // 部分设备（尤其平板 + USB转接）不触发 ACTION_NOISY，靠音频路由移除事件兜底。
                    Log.i("AudioFocus", "有线耳机音频设备移除，暂停")
                    clearStaleResumeMarker()
                    pause()
                }
            }
        }
    } else null

    /** 有线音频设备判定：3.5mm 耳机/带麦耳机 + USB-C 转接耳机/设备/配件（平板场景）。 */
    private fun isWiredAudioDevice(device: AudioDeviceInfo): Boolean {
        val t = device.type
        return t == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            t == AudioDeviceInfo.TYPE_USB_DEVICE ||
            t == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            t == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    /** 是否为蓝牙 A2DP/SCO 音频设备。 */
    private fun isBtAudioDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    /**
     * 从连接状态广播取蓝牙设备地址（A2DP/Headset 的 EXTRA_DEVICE）；取不到返回 null。
     * 中危-F：用于断开时绑定设备、恢复时校验同一设备。
     */
    @Suppress("DEPRECATION")
    private fun btAddressFrom(intent: Intent?): String? {
        if (intent == null) return null
        val device = runCatching {
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }.getOrNull() ?: return null
        return device.address?.takeIf { it.isNotBlank() }?.uppercase()
    }

    /** 从 AudioDeviceInfo 取蓝牙设备地址（同网络权限下可用则返回，否则 null）。 */
    private fun btAddressFrom(device: AudioDeviceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            device.address.takeIf { it.isNotBlank() }?.uppercase()
        else null

    // C-04 睡眠定时
    private var sleepTimerJob: Job? = null
    private val _sleepTimerEndAt = MutableStateFlow<Long?>(null)
    val sleepTimerEndAt: StateFlow<Long?> = _sleepTimerEndAt.asStateFlow()

    init {
        scope.launch { settings.fadeInMs.collect { fadeInMs = it } }
        scope.launch { settings.fadeOutMs.collect { fadeOutMs = it } }
        scope.launch { settings.volumeCurve.collect { volumeCurve = it; applyVolume() } }
        scope.launch { settings.headsetPause.collect { headsetPause = it } }
        scope.launch { settings.headsetButtonControl.collect { headsetButtonControl = it } }
        scope.launch { settings.btDisconnectPause.collect { btDisconnectPause = it } }
        scope.launch { settings.btReconnectResume.collect { btReconnectResume = it } }
        scope.launch { settings.playRequiresAudioFocus.collect { value ->
            if (value == playRequiresAudioFocus) return@collect
            playRequiresAudioFocus = value
            // U-状态同步：开关变更时同步运行时焦点状态，避免策略与真实持焦脱节
            if (value) {
                // 打开"仅持焦播放"而正在播放 → 补申请焦点（被拒则维持暂停态，由 play 门控拦截）
                if (wasPlaying()) ensureFocusForPlay()
            } else {
                // 关闭"仅持焦播放" → 释放独占焦点但保留当前播放（不再霸占/不再需要持焦）
                if (hasFocus) abandonFocus()
            }
        } }
        scope.launch { settings.focusLossAutoSkip.collect { focusLossAutoSkip = it } }
        registerNoisyReceiver()
        registerBtReceiver()
    }

    // ===== 耳机拔出自动暂停（C-02） =====

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && headsetPause) {
                // 蓝牙/有线断开多以 ACTION_NOISY 为主信号；若开启重连恢复且正在播放，标记待恢复
                if (btReconnectResume && wasPlaying()) {
                    resumeOnBtReconnect = true
                    pendingResumeBtAddress = null // NOISY 无设备地址，保持 null 走退化恢复
                    lastResumeMarkerAt = SystemClock.elapsedRealtime()
                    Log.i("AudioFocus", "媒体中断(noisy)，标记待蓝牙重连恢复")
                } else {
                    // 未在播放时中断绝不残留待恢复标记，防止后续重连/挂断误恢复（带护栏，避免同断开事件的重复信号互清）
                    clearStaleResumeMarker()
                }
                // Q-音量收敛：统一走 pause()（取消 fadeJob、管理 pendingPauseJob），
                // 不再直接 exo().pause()，避免与淡出/延迟暂停流程并发写播放态。
                pause()
                onChanged()
            }
        }
    }

    private fun registerNoisyReceiver() {
        runCatching {
            context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
    }

    private fun registerBtReceiver() {
        runCatching {
            context.registerReceiver(
                btReceiver,
                IntentFilter().apply {
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                }
            )
            audioDeviceCallback?.let { audioManager.registerAudioDeviceCallback(it, mainHandler) }
        }
    }

    // ===== 蓝牙断开暂停 / 重连恢复（2026-08-24） =====

    private fun wasPlaying() = runCatching { exo().isPlaying }.getOrDefault(false)

    /** 蓝牙断开：若开启重连恢复且当前正在播放，标记待恢复并记录置位时刻+绑定的设备地址；随后暂停。
     *  未播放时仅当无近期有效置位才清除残留标记（防同一断开的重复信号误清 / 未播放时中断误恢复）。 */
    private fun pauseOnBtDisconnect(btAddress: String? = null) {
        if (btReconnectResume && wasPlaying()) {
            resumeOnBtReconnect = true
            pendingResumeBtAddress = btAddress
            lastResumeMarkerAt = SystemClock.elapsedRealtime()
            Log.i("AudioFocus", "蓝牙断开，标记待重连恢复（device=$btAddress）")
        } else {
            clearStaleResumeMarker()
        }
        pause()
    }

    /** 重连恢复标记护栏清除：距上次有效置位过久才真清除（避免同一断开事件的多路信号互清）。 */
    private fun clearStaleResumeMarker() {
        if (SystemClock.elapsedRealtime() - lastResumeMarkerAt > RESUME_MARKER_GUARD_MS) {
            resumeOnBtReconnect = false
            pendingResumeBtAddress = null
        }
    }

    /** 蓝牙重连：若开启重连恢复且因断开被暂停，校验仍是同一设备后恢复播放。 */
    private fun resumeOnBtReconnectIfNeeded(currentAddr: String? = null) {
        if (!btReconnectResume || !resumeOnBtReconnect) return
        // 中危-F：断开已绑定设备地址且本次重连地址确认不同 → 换设备了，不自动恢复并清标记。
        val pending = pendingResumeBtAddress
        if (pending != null && currentAddr != null && pending != currentAddr) {
            Log.i("AudioFocus", "蓝牙重连设备不一致（$currentAddr != $pending），不自动恢复")
            resumeOnBtReconnect = false
            pendingResumeBtAddress = null
            lastResumeMarkerAt = Long.MIN_VALUE
            return
        }
        resumeOnBtReconnect = false
        pendingResumeBtAddress = null
        lastResumeMarkerAt = Long.MIN_VALUE
        Log.i("AudioFocus", "蓝牙重连，恢复播放")
        play()
    }

    // ===== 音频焦点（2026-08-24） =====

    /** 申请音频焦点：仅当「仅持音频焦点播放」开启时请求，拒绝则返回 false（调用方不得开播）。
     *  内部可见：PlayerManager 在播放状态回调中强制复核，堵住未经焦点申请即开播的路径。 */
    internal fun ensureFocusForPlay(): Boolean {
        if (!playRequiresAudioFocus) return true
        if (hasFocus) return true
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
                .build().also { focusRequest = it }
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (granted) hasFocus = true
        Log.i("AudioFocus", "requestAudioFocus granted=$granted playRequiresFocus=$playRequiresAudioFocus")
        return granted
    }

    private fun abandonFocus() {
        hasFocus = false
        resumeOnGain = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
        }
    }

    // ===== 播放命令 =====

    /** Q-音量收敛：统一取消在途的淡入淡出与延迟暂停任务，避免与播放/暂停/停止并发写 ExoPlayer 音量与播放态。 */
    private fun cancelFadeAndPauseTasks() {
        fadeJob?.cancel(); fadeJob = null
        pendingPauseJob?.cancel(); pendingPauseJob = null
    }

    fun play() {
        // CK-开始播放即作废陈旧蓝牙重连恢复标记：用户已手动/经其它路径主动续播后，蓝牙重连不应再触发第二次自动播放
        // （resumeOnBtReconnectIfNeeded 在恢复前已自行清标记，此处对手动 play 同样收敛）。
        resumeOnBtReconnect = false
        pendingResumeBtAddress = null
        lastResumeMarkerAt = Long.MIN_VALUE
        if (!ensureFocusForPlay()) return
        ensureForeground()
        cancelFadeAndPauseTasks()
        if (fb.active) {
            fb.resume()
            return
        }
        exo().play()
        fadeIn()
    }

    fun pause() {
        if (fb.active) {
            cancelFadeAndPauseTasks()
            fb.pause()
            return
        }
        if (fadeOutMs > 0 && exo().isPlaying) {
            fadeTo(0f, fadeOutMs)
            pendingPauseJob?.cancel()
            pendingPauseJob = scope.launch {
                delay(fadeOutMs.toLong())
                exo().pause()
            }
        } else {
            exo().pause()
        }
    }

    fun togglePlayPause() {
        if (fb.active) {
            if (fb.state.value.isPlaying) pause() else play()
            return
        }
        if (exo().isPlaying) pause() else play()
    }

    // ===== 耳机按键控制（2026-08-24） =====

    /** 有线/蓝牙耳机媒体按键分发（由 HeadsetMediaButtonReceiver 调用）。单键播放/暂停切换。 */
    fun handleMediaButton(keyCode: Int) {
        if (!headsetButtonControl) {
            Log.i("PlaybackController", "handleMediaButton keyCode=$keyCode 被开关门控忽略")
            return
        }
        Log.i("PlaybackController", "handleMediaButton keyCode=$keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> play()
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> pause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> togglePlayPause()
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND -> previous()
        }
    }

    fun next() {
        // 2026-08-18：不经过交叉淡化（避免 crossfade 延迟导致切歌卡住；crossfade 仅保留 ExoPlayer 自动连播场景）
        val idx = queueController.nextIndex()
        Log.i("PlaybackController", "next() idx=$idx currentIndex=${queueController.currentIndex} repeat=${queueController.repeatMode} queueSize=${queueController.queue.size}")
        if (idx >= 0) navigateTo(idx) else {
            if (fb.active) fb.stop()
            exo().pause()
        }
    }

    fun previous() {
        // 2026-08-18：上一首不经过交叉淡化（避免 crossfade 延迟导致切歌卡住）
        val idx = queueController.prevIndex()
        if (idx >= 0) navigateTo(idx) else {
            if (fb.active) fb.stop()
            exo().seekTo(0, 0)
        }
    }

    /** 停止播放（P5 需求 15）：回到待机态——暂停、归零、停兜底；**保留当前歌曲**（停止后仍显示当前歌曲，仅进度归零、暂停）；队列保留以便下次播放。 */
    fun stop() {
        // Q-音量收敛：停止同时取消在途 fade 与延迟暂停，避免 fade 协程继续写已停止的播放器音量
        cancelFadeAndPauseTasks()
        if (fb.active) fb.stop()
        runCatching { exo().pause() }
        runCatching { exo().seekTo(0, 0) }
        abandonFocus()
        // 用户主动停止：清除蓝牙重连恢复标记，防止陈旧标记在后续重连时误恢复
        resumeOnBtReconnect = false
        pendingResumeBtAddress = null
        lastResumeMarkerAt = Long.MIN_VALUE
        // 保留当前歌曲（从队列按当前索引取），仅进度归零与播放态回待机；队列与索引不动以便续播。
        val cur = queueController.queue.getOrNull(queueController.currentIndex)
        st.value = PlaybackState(
            currentSong = cur,
            isPlaying = false,
            positionMs = 0,
            durationMs = cur?.durationMs ?: 0,
            repeatMode = queueController.repeatMode,
            shuffle = queueController.shuffle,
            queue = queueController.queue.toList(),
            currentIndex = queueController.currentIndex
        )
        onChanged()
    }

    fun seekTo(ms: Long) {
        if (fb.active) {
            fb.seekTo(ms)
            return
        }
        exo().seekTo(ms)
    }

    /** §12 R5：返回播放位置的实时毫秒值（供 LyricsViewModel 稳定 tick 驱动当前歌词行）。 */
    fun livePositionMs(): Long =
        if (fb.active) fb.state.value.positionMs
        else exo().currentPosition.coerceAtLeast(0)

    /** 跳转到队列指定项（P-09 队列编辑）。 */
    fun seekToIndex(index: Int) {
        if (index in queueController.queue.indices) {
            navigateTo(index)
        }
    }

    /** 导航到全量队列索引：命中当前窗口直接 seek，否则重载窗口后定位。 */
    fun navigateTo(fullIdx: Int) {
        queueManager.altAttempts.clear()
        // 统一先更新逻辑队列索引（ExoPlayer 路径由 onMediaItemTransition 再次确认，兜底路径依赖此值）
        queueController.setCurrentIndex(fullIdx)
        val target = queueController.queue.getOrNull(fullIdx)
        if (target == null) {
            stopFallback()
            runCatching { exo().pause() }
            onChanged()
            return
        }
        when (routeFallback(target)) {
            FallbackRoute.READY -> {
                // 数据源已就绪（本地原 uri / 远端已缓存）：系统 MediaPlayer 兜底
                runCatching { exo().pause() }
                stopFallback()
                if (ensureFocusForPlay()) {
                    fb.play(target, fallbackSourceFor(target))
                    st.value = st.value.copy(
                        currentSong = target, isPlaying = true, positionMs = 0, durationMs = target.durationMs
                    )
                    onChanged()
                    persist()
                }
            }
            FallbackRoute.DOWNLOAD -> {
                // 远端 APE/WMA 未缓存：进入加载态，由回调触发「下载后系统解码」
                runCatching { exo().pause() }
                stopFallback()
                st.value = st.value.copy(
                    currentSong = target, isPlaying = false, positionMs = 0,
                    durationMs = target.durationMs, buffering = true
                )
                onChanged()
                persist()
                startFallbackDownload(target)
            }
            FallbackRoute.NO -> {
                // 非兜底格式：重载窗口（重建 fullToExo 映射，保证精确）后定位。
                stopFallback()
                scope.launch {
                    playbackActor.execute {
                        queueManager.reloadWindowCenteredAt(fullIdx)
                        // reloadWindow 的 setMediaItems 会触发 onMediaItemTransition，把 currentIndex
                        // 覆盖为窗口首项索引，导致下方用 currentIndex校验目标时误判为过期而跳过 seek——
                        // 表现为点击上一首/下一首无法切歌。此处重载后强制复位到本请求目标再定位。
                        queueController.setCurrentIndex(fullIdx)
                        val eIdx = queueManager.fullToExo[fullIdx]
                        if (eIdx != null && eIdx in 0 until exo().mediaItemCount) {
                            exo().seekTo(eIdx, 0)
                            if (ensureFocusForPlay()) exo().play()
                        } else {
                            runCatching { exo().pause() }
                        }
                    }
                    onChanged()
                }
            }
        }
    }

    // ===== 音量 / 淡入淡出 =====

    /** 音量（线性 0..1），按音量曲线（volume_curve）映射后应用。 */
    fun setVolume(linear: Float) {
        baseVolume = linear.coerceIn(0f, 1f)
        applyVolume()
    }

    private fun mappedVolume(): Float =
        if (volumeCurve == "linear") baseVolume else baseVolume * baseVolume

    private fun applyVolume() {
        val v = mappedVolume()
        // X-音量：同时作用于 ExoPlayer 与兜底 MediaPlayer（两套引擎逐一同步，避免"调了不响"）
        if (fadeJob?.isActive != true) exo().volume = v
        if (fb.active) fb.setVolume(v)
    }

    private fun fadeTo(target: Float, rampMs: Int) {
        fadeJob?.cancel()
        if (rampMs <= 0) { exo().volume = target; return }
        val from = exo().volume
        fadeJob = scope.launch {
            val steps = (rampMs / 20).coerceAtLeast(1)
            for (i in 0..steps) {
                exo().volume = from + (target - from) * (i.toFloat() / steps)
                delay(20)
            }
            exo().volume = target
        }
    }

    private fun fadeIn() = fadeTo(mappedVolume(), fadeInMs)

    // ===== 睡眠定时（C-04） =====

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerEndAt.value = null
            onChanged()
            return
        }
        _sleepTimerEndAt.value = System.currentTimeMillis() + minutes * 60_000L
        sleepTimerJob = scope.launch {
            delay(minutes * 60_000L)
            _sleepTimerEndAt.value = null
            pause()
            // EG：睡眠定时自动停止后持久化会话状态，避免进程被杀后下次启动误恢复为播放态。
            persist()
            onChanged()
        }
    }
}