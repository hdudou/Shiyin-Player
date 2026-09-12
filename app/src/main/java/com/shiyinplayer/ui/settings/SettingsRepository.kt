package com.shiyinplayer.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.shiyinplayer.lockscreen.LOCKSCREEN_EFFECT_COUNT
import com.shiyinplayer.player.EqualizerManager
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置持久化（T11 + SETTINGS_SPEC §8 全键）：对标 AIMP 七大类。
 * 主题/强调色沿用既有 Int 表示（Theme.kt / ThemePrefs 依赖）；语言、路由、预设等用 String。
 * 续播会话（lastQueueIds/lastIndex/lastPosition）供 PlayerManager 断点续播（R-P1-07）。
 * 均走 DataStore；凭据类仍走 EncryptedSharedPreferences（SmbCredentialStore / WebDavCredentialStore）。
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // P0-4：进程级内存镜像快照。DataStore 流在 IO 线程持续收集到不可变 Preferences，
    // 供构建期/启动期代码（MainActivity/PlaybackService/PlayerFactory/LocalLyricLoader）
    // 同步读取，避免主线程每次 runBlocking 阻塞等磁盘 I/O（ANR 风险）。
    // setter 经 dataStore.edit 会触发 dataStore.data 重新 emit，快照自动保持最新。
    @Volatile
    private var prefsCache: Preferences = emptyPreferences()

    init {
        // 持续镜像 DataStore → prefsCache（@Volatile 保证可见性；Preferences 不可变，先写后 block）。
        initScope.launch { dataStore.data.collect { prefsCache = it } }
    }
    // ===== 界面（I） =====
    private val THEME_MODE = intPreferencesKey("theme_mode")            // 0 system / 1 light / 2 dark
    private val ACCENT = intPreferencesKey("accent_color")              // ACCENT_COLORS 索引
    private val THEME_STYLE = intPreferencesKey("theme_style")          // 0=玄素映彩 1..10=THEME_STYLES
    private val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors") // Material You 动态取色（Android 12+）
    private val LANGUAGE = stringPreferencesKey("language")             // system / zh / en
    private val SHOW_EMBED_ART = booleanPreferencesKey("show_embed_art")
    private val LIST_SHOW_ART = booleanPreferencesKey("list_show_art")
    private val LIST_TWO_LINE = booleanPreferencesKey("list_two_line")
    private val LIST_DENSITY = stringPreferencesKey("list_density")     // compact / standard / relaxed
    private val NOTIFY_ENABLED = booleanPreferencesKey("notify_enabled")
    private val LOCKSCREEN_CONTROL = booleanPreferencesKey("lockscreen_control")
    private val LOCKSCREEN_VISUAL_EFFECT = intPreferencesKey("lockscreen_visual_effect")
    private val LOCKSCREEN_OVERLAY_ENABLED = booleanPreferencesKey("lockscreen_overlay_enabled")
    private val MINI_BAR_ENABLED = booleanPreferencesKey("mini_bar_enabled")
    private val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    private val CAR_BT_LYRICS = booleanPreferencesKey("car_bt_lyrics")
    private val FOCUS_LOSS_AUTOSKIP = booleanPreferencesKey("focus_loss_autoskip")
    private val LAST_DB_VERSION = intPreferencesKey("last_database_version")
    private val DISPLAY_MODE = stringPreferencesKey("display_mode")    // auto / portrait / landscape
    private val AUTO_HIDE_CONTROLS = booleanPreferencesKey("auto_hide_controls")  // 全屏页无操作自动隐藏控件
    private val AUTO_HIDE_DELAY_MS = intPreferencesKey("auto_hide_delay_ms")      // 自动隐藏延迟(ms)：2000/3000/5000

    // ===== 收音机模式（R） =====
    /** 当前播放模式：music / radio。冷启动读取上次模式，首次安装默认 music。 */
    private val APP_MODE = stringPreferencesKey("app_mode")
    /** 是否已完成首次启动标记：为 true 后，冷启动不再强制进「正在播放」全屏，改落曲库。 */
    private val FIRST_LAUNCH_DONE = booleanPreferencesKey("first_launch_done")
    private val MUSIC_WAS_PLAYING = booleanPreferencesKey("music_was_playing")
    /** 切到其他模式前电台是否在播放：true=恢复时自动播；false=只恢复内容不播。 */
    private val RADIO_WAS_PLAYING = booleanPreferencesKey("radio_was_playing")

    // ===== 声音引擎（S） =====
    private val AUDIO_ROUTE = stringPreferencesKey("audio_route")       // auto / speaker / bt / wired
    private val LOW_LATENCY = booleanPreferencesKey("low_latency")
    private val FFMPEG_SOFT_DECODE = booleanPreferencesKey("ffmpeg_soft_decode")
    private val BUFFER_MS = intPreferencesKey("buffer_ms")
    private val FLOAT32_PROCESSING = booleanPreferencesKey("float32_processing")
    private val VOLUME_NORMALIZE = booleanPreferencesKey("volume_normalize")
    private val REPLAYGAIN_MODE = stringPreferencesKey("replaygain_mode") // off / track / album / smart
    private val VOLUME_CURVE = stringPreferencesKey("volume_curve")     // log / loudness
    private val FADE_IN_MS = intPreferencesKey("fade_in_ms")
    private val FADE_OUT_MS = intPreferencesKey("fade_out_ms")
    private val SILENCE_REMOVER = booleanPreferencesKey("silence_remover")
    private val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
    private val EQ_PRESET = stringPreferencesKey("eq_preset")           // Flat / Pop / Rock / Classic / Custom
    private val EQ_GAINS = stringPreferencesKey("eq_gains")             // 逗号分隔的 10 段增益(dB)
    private val CHANNEL_BALANCE = floatPreferencesKey("channel_balance") // F3-1：声道平衡 [-1,1]，0=居中

    // ===== 回放（P） =====
    private val STARTUP_ACTION = stringPreferencesKey("startup_action") // none / play / resume
    private val DEFAULT_REPEAT = stringPreferencesKey("default_repeat") // off / all / one
    private val SKIP_ON_ERROR = booleanPreferencesKey("skip_on_error")
    private val AUTO_EQ_BY_GENRE = booleanPreferencesKey("auto_eq_by_genre")
    private val AUTO_RESUME = booleanPreferencesKey("auto_resume")
    private val GAPLESS = booleanPreferencesKey("gapless")
    private val REMEMBER_POS = booleanPreferencesKey("remember_position")
    private val AUTO_SYNC_METADATA = booleanPreferencesKey("auto_sync_metadata")
    private val LAST_METADATA_SYNC_AT = longPreferencesKey("last_metadata_sync_at")
    /** 2026-08-19 需求3：流量保护——移动网络下停止在线元数据/歌词获取，WiFi 下自动同步。 */
    private val DATA_SAVER = booleanPreferencesKey("data_saver")
// ===== 网络源本地缓存（离网播放，原始格式保真） =====
    private val CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
    private val CACHE_WATER_LEVEL_GB = intPreferencesKey("cache_water_level_gb") // 水位：可用空间低于此值开始清理 LRU 缓存
    private val CACHE_CAP_GB = intPreferencesKey("cache_cap_gb")                 // 缓存总量硬上限（防止无限膨胀）

    // ===== 播放列表（L） =====
    private val PLAYLIST_AUTOSAVE = booleanPreferencesKey("playlist_autosave")
    private val TRACK_FORMAT = stringPreferencesKey("track_format")     // {artist} - {title} / {title} / {album} - {title}
    private val LIST_GROUP_BY = stringPreferencesKey("list_group_by")   // none / album / artist / folder
    private val SORT_BY = stringPreferencesKey("sort_by")               // title / artist / album / duration / date_added / random
    private val ALLOW_DELETE_FILE = booleanPreferencesKey("allow_delete_file")

    // ===== 音乐库（M） =====
    private val WATCH_FOLDERS = booleanPreferencesKey("watch_folders")
    private val READ_EMBED_LYRICS = booleanPreferencesKey("read_embed_lyrics")
    private val HIDE_SHORT = booleanPreferencesKey("hide_short_clips")
    private val SCAN_HIDDEN = booleanPreferencesKey("scan_hidden")
    private val SCAN_EXTENSIONS = stringPreferencesKey("scan_extensions") // 逗号分隔的扩展名白名单，空=不限制（全部 active）

    // ===== 控制（C） =====
    private val HEADSET_PAUSE = booleanPreferencesKey("headset_pause")
    private val SLEEP_TIMER_MIN = intPreferencesKey("sleep_timer_min")  // 0 = off
    // 2026-08-24：耳机按键控制 / 蓝牙断开暂停 / 蓝牙重连恢复 / 仅持音频焦点播放
    private val HEADSET_BUTTON_CONTROL = booleanPreferencesKey("headset_button_control")
    private val BT_DISCONNECT_PAUSE = booleanPreferencesKey("bt_disconnect_pause")
    private val BT_RECONNECT_RESUME = booleanPreferencesKey("bt_reconnect_resume")
    private val PLAY_REQUIRES_AUDIO_FOCUS = booleanPreferencesKey("play_requires_audio_focus")

    // ===== 整合（G） =====
    private val IS_DEFAULT_PLAYER = booleanPreferencesKey("is_default_player")
    private val ACCEPT_EXTERNAL_OPEN = booleanPreferencesKey("accept_external_open")

    // ===== 断点续播会话（R-P1-07） =====
    private val LAST_QUEUE_IDS = stringPreferencesKey("last_queue_ids") // 逗号分隔的歌曲主键
    private val LAST_INDEX = intPreferencesKey("last_index")
    private val LAST_POSITION = longPreferencesKey("last_position")

    // ===== 歌词 / 在线元数据（LyricFinder + 163MusicLyrics 数据源整合） =====
    private val LYRICS_ENABLED = booleanPreferencesKey("lyrics_enabled")
    private val METADATA_ENABLED = booleanPreferencesKey("metadata_enabled")
    /** 元数据获取源优先级排序（全部源的有序 CSV，仅决定排列顺序，与开关无关）。 */
    private val METADATA_SOURCES_ORDER = stringPreferencesKey("metadata_sources_order")
    /** 元数据获取源启用集合（CSV；仅决定启用/禁用，不决定顺序）。默认仅网易/QQ/酷我。 */
    private val METADATA_SOURCES_ENABLED = stringPreferencesKey("metadata_sources_enabled")

    // ===== 音乐库（M-06 搜索历史） =====
    private val SEARCH_HISTORY = stringPreferencesKey("search_history") // 分隔符 | 连接

    // ===== 收音机设置（Phase 2 · 2-3） =====
    private val RADIO_MAX_RETRY_COUNT = intPreferencesKey("radio_max_retry_count")
    private val RADIO_RETRY_DELAY_SECONDS = intPreferencesKey("radio_retry_delay_seconds")
    private val RADIO_WIFI_ONLY = booleanPreferencesKey("radio_wifi_only")
    private val RADIO_SHOW_PROGRAM_INFO = booleanPreferencesKey("radio_show_program_info")
    private val RADIO_AUTO_RECONNECT = booleanPreferencesKey("radio_auto_reconnect")
    private val RADIO_SLEEP_TIMER_ENABLED = booleanPreferencesKey("radio_sleep_timer_enabled")
    private val RADIO_SLEEP_TIMER_MINUTES = intPreferencesKey("radio_sleep_timer_minutes")

    // ===== 闹钟定时播放 =====
    private val RADIO_ALARM_ENABLED = booleanPreferencesKey("radio_alarm_enabled")
    private val RADIO_ALARM_HOUR = intPreferencesKey("radio_alarm_hour")
    private val RADIO_ALARM_MINUTE = intPreferencesKey("radio_alarm_minute")

    // ===== 播放页动态效果类型 =====
    private val RADIO_VISUAL_EFFECT = intPreferencesKey("radio_visual_effect")

    // ===== 冷启动续播：上次电台会话 =====
    private val LAST_RADIO_URL = stringPreferencesKey("last_radio_url")
    private val LAST_RADIO_NAME = stringPreferencesKey("last_radio_name")
    private val LAST_RADIO_ID = longPreferencesKey("last_radio_id")

    // ===== 内置电台清单远程版本（RadioBuiltInUpdater 每启动比对更新） =====
    private val RADIO_BUILTIN_VERSION = intPreferencesKey("radio_builtin_version")

    // ===== 界面（I） =====
    val themeMode: Flow<Int> = dataStore.data.map { it[THEME_MODE] ?: 0 }
    val accent: Flow<Int> = dataStore.data.map { it[ACCENT] ?: 0 }
    val themeStyle: Flow<Int> = dataStore.data.map { it[THEME_STYLE] ?: 0 }
    val dynamicColors: Flow<Boolean> = dataStore.data.map { it[DYNAMIC_COLORS] ?: true }
    /** 内存同步读主题模式 / 强调色 / 风格（prefsCache 快照）。供首帧构图的 stateIn 初始值使用，
     * 避免首帧先用默认「跟随系统」渲染（系统浅色时闪白），再异步切回已保存的深色主题。 */
    fun themeModeSync(): Int = prefsCache[THEME_MODE] ?: 0
    fun accentSync(): Int = prefsCache[ACCENT] ?: 0
    fun themeStyleSync(): Int = prefsCache[THEME_STYLE] ?: 0
    fun dynamicColorsSync(): Boolean = prefsCache[DYNAMIC_COLORS] ?: true
    val language: Flow<String> = dataStore.data.map { it[LANGUAGE] ?: "system" }
    /** 当前播放模式（music / radio），冷启动恢复上次模式；首次安装默认 music。 */
    val appMode: Flow<String> = dataStore.data.map { it[APP_MODE] ?: "music" }
    fun appModeSync(): String = prefsCache[APP_MODE] ?: "music"
    val firstLaunchDone: Flow<Boolean> = dataStore.data.map { it[FIRST_LAUNCH_DONE] ?: false }
    fun firstLaunchDoneSync(): Boolean = prefsCache[FIRST_LAUNCH_DONE] ?: false
    suspend fun setFirstLaunchDone() = dataStore.edit { it[FIRST_LAUNCH_DONE] = true }
    val showEmbedArt: Flow<Boolean> = dataStore.data.map { it[SHOW_EMBED_ART] ?: true }
    val listShowArt: Flow<Boolean> = dataStore.data.map { it[LIST_SHOW_ART] ?: true }
    val listTwoLine: Flow<Boolean> = dataStore.data.map { it[LIST_TWO_LINE] ?: false }
    val listDensity: Flow<String> = dataStore.data.map { it[LIST_DENSITY] ?: "standard" }
    val notifyEnabled: Flow<Boolean> = dataStore.data.map { it[NOTIFY_ENABLED] ?: true }
    val lockscreenControl: Flow<Boolean> = dataStore.data.map { it[LOCKSCREEN_CONTROL] ?: true }
    val lockscreenVisualEffect: Flow<Int> = dataStore.data.map { it[LOCKSCREEN_VISUAL_EFFECT] ?: 0 }
    val lockscreenOverlayEnabled: Flow<Boolean> = dataStore.data.map { it[LOCKSCREEN_OVERLAY_ENABLED] ?: false }
    val miniBarEnabled: Flow<Boolean> = dataStore.data.map { it[MINI_BAR_ENABLED] ?: true }
    val keepScreenOn: Flow<Boolean> = dataStore.data.map { it[KEEP_SCREEN_ON] ?: true }
    val carBtLyrics: Flow<Boolean> = dataStore.data.map { it[CAR_BT_LYRICS] ?: true }
    val focusLossAutoSkip: Flow<Boolean> = dataStore.data.map { it[FOCUS_LOSS_AUTOSKIP] ?: false }
    val displayMode: Flow<String> = dataStore.data.map { it[DISPLAY_MODE] ?: "auto" }
    val autoHideControls: Flow<Boolean> = dataStore.data.map { it[AUTO_HIDE_CONTROLS] ?: true }
    val autoHideDelayMs: Flow<Int> = dataStore.data.map { it[AUTO_HIDE_DELAY_MS] ?: 3000 }

    // ===== 声音引擎（S） =====
    val audioRoute: Flow<String> = dataStore.data.map { it[AUDIO_ROUTE] ?: "auto" }
    val lowLatency: Flow<Boolean> = dataStore.data.map { it[LOW_LATENCY] ?: false }
    val bufferMs: Flow<Int> = dataStore.data.map { it[BUFFER_MS] ?: 200 }
    val float32Processing: Flow<Boolean> = dataStore.data.map { it[FLOAT32_PROCESSING] ?: true }
    val volumeNormalize: Flow<Boolean> = dataStore.data.map { it[VOLUME_NORMALIZE] ?: true }
    val replaygainMode: Flow<String> = dataStore.data.map { it[REPLAYGAIN_MODE] ?: "track" }
    val volumeCurve: Flow<String> = dataStore.data.map { it[VOLUME_CURVE] ?: "log" }
    val fadeInMs: Flow<Int> = dataStore.data.map { it[FADE_IN_MS] ?: 0 }
    val fadeOutMs: Flow<Int> = dataStore.data.map { it[FADE_OUT_MS] ?: 0 }
    val silenceRemover: Flow<Boolean> = dataStore.data.map { it[SILENCE_REMOVER] ?: true }
    val eqEnabled: Flow<Boolean> = dataStore.data.map { it[EQ_ENABLED] ?: true }
    val eqPreset: Flow<String> = dataStore.data.map { it[EQ_PRESET] ?: "Flat" }
    /** F3-1：声道平衡 [-1,1]，0=居中。 */
    val channelBalance: Flow<Float> = dataStore.data.map { it[CHANNEL_BALANCE] ?: 0f }
    val eqGains: Flow<FloatArray> = dataStore.data.map { prefs ->
        prefs[EQ_GAINS]?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
            ?: FloatArray(EqualizerManager.NUM_BANDS)
    }

    // ===== 回放（P） =====
    val startupAction: Flow<String> = dataStore.data.map { it[STARTUP_ACTION] ?: "resume" }
    val defaultRepeat: Flow<String> = dataStore.data.map { it[DEFAULT_REPEAT] ?: "all" }
    val skipOnError: Flow<Boolean> = dataStore.data.map { it[SKIP_ON_ERROR] ?: true }
    val autoEqByGenre: Flow<Boolean> = dataStore.data.map { it[AUTO_EQ_BY_GENRE] ?: true }
    val autoResume: Flow<Boolean> = dataStore.data.map { it[AUTO_RESUME] ?: true }
    val gapless: Flow<Boolean> = dataStore.data.map { it[GAPLESS] ?: true }
    val rememberPosition: Flow<Boolean> = dataStore.data.map { it[REMEMBER_POS] ?: true }
    /** 自动同步曲库文件元数据（默认开启；fill-in 补缺，不覆盖已有数据）。 */
    val autoSyncMetadata: Flow<Boolean> = dataStore.data.map { it[AUTO_SYNC_METADATA] ?: true }
    /** 上次自动同步完成时间戳（毫秒），用于 24h 节流。 */
    val lastMetadataSyncAt: Flow<Long> = dataStore.data.map { it[LAST_METADATA_SYNC_AT] ?: 0L }
    /** 流量保护（默认开启）：移动网络下不联网获取元数据/歌词，仅 WiFi 自动同步。 */
    val dataSaver: Flow<Boolean> = dataStore.data.map { it[DATA_SAVER] ?: true }
    val cacheEnabled: Flow<Boolean> = dataStore.data.map { it[CACHE_ENABLED] ?: true }
    val cacheWaterLevelGb: Flow<Int> = dataStore.data.map { it[CACHE_WATER_LEVEL_GB] ?: 5 }
    val cacheCapGb: Flow<Int> = dataStore.data.map { it[CACHE_CAP_GB] ?: 20 }

    // ===== 播放列表（L） =====
    val playlistAutosave: Flow<Boolean> = dataStore.data.map { it[PLAYLIST_AUTOSAVE] ?: true }
    val trackFormat: Flow<String> = dataStore.data.map { it[TRACK_FORMAT] ?: "{artist} – {title}" }
    val listGroupBy: Flow<String> = dataStore.data.map { it[LIST_GROUP_BY] ?: "none" }
    val sortBy: Flow<String> = dataStore.data.map { it[SORT_BY] ?: "title" }
    val allowDeleteFile: Flow<Boolean> = dataStore.data.map { it[ALLOW_DELETE_FILE] ?: true }

    // ===== 音乐库（M） =====
    val watchFolders: Flow<Boolean> = dataStore.data.map { it[WATCH_FOLDERS] ?: true }
    val readEmbedLyrics: Flow<Boolean> = dataStore.data.map { it[READ_EMBED_LYRICS] ?: true }
    val hideShortClips: Flow<Boolean> = dataStore.data.map { it[HIDE_SHORT] ?: false }
    val scanHidden: Flow<Boolean> = dataStore.data.map { it[SCAN_HIDDEN] ?: false }
    /** 扫描扩展名白名单（空集合表示不限制，取全部 active）。 */
    val scanExtensions: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[SCAN_EXTENSIONS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    // ===== 控制（C） =====
    val headsetPause: Flow<Boolean> = dataStore.data.map { it[HEADSET_PAUSE] ?: true }
    val sleepTimerMin: Flow<Int> = dataStore.data.map { it[SLEEP_TIMER_MIN] ?: 0 }
    // 2026-08-24 新增：耳机按键 / 蓝牙断开 / 蓝牙重连恢复 / 音频焦点
    val headsetButtonControl: Flow<Boolean> = dataStore.data.map { it[HEADSET_BUTTON_CONTROL] ?: true }
    val btDisconnectPause: Flow<Boolean> = dataStore.data.map { it[BT_DISCONNECT_PAUSE] ?: true }
    val btReconnectResume: Flow<Boolean> = dataStore.data.map { it[BT_RECONNECT_RESUME] ?: true }
    val playRequiresAudioFocus: Flow<Boolean> = dataStore.data.map { it[PLAY_REQUIRES_AUDIO_FOCUS] ?: true }

    // ===== 整合（G） =====
    val isDefaultPlayer: Flow<Boolean> = dataStore.data.map { it[IS_DEFAULT_PLAYER] ?: false }
    val acceptExternalOpen: Flow<Boolean> = dataStore.data.map { it[ACCEPT_EXTERNAL_OPEN] ?: true }

    // ===== 断点续播会话 =====
    val lastQueueIds: Flow<List<Long>> = dataStore.data.map { prefs ->
        prefs[LAST_QUEUE_IDS].orEmpty().split(",").mapNotNull { it.trim().toLongOrNull() }
    }
    val lastIndex: Flow<Int> = dataStore.data.map { it[LAST_INDEX] ?: -1 }
    val lastPosition: Flow<Long> = dataStore.data.map { it[LAST_POSITION] ?: 0L }

    // ===== 歌词 / 在线元数据（LyricFinder + 163MusicLyrics 数据源整合） =====
    val lyricsEnabled: Flow<Boolean> = dataStore.data.map { it[LYRICS_ENABLED] ?: true }
    val metadataEnabled: Flow<Boolean> = dataStore.data.map { it[METADATA_ENABLED] ?: true }
    /** 元数据获取源启用集合（仅决定启用/禁用，不决定顺序）。空/缺省为默认三源。 */
    val metadataSourcesEnabled: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[METADATA_SOURCES_ENABLED]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() } ?: defaultEnabledSources
    }
    /** 元数据获取源排列顺序（全部源有序，仅决定顺序）。空/缺省为默认统一顺序。 */
    val metadataSourcesOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[METADATA_SOURCES_ORDER]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() } ?: defaultSourceOrder
    }
    /** 当前生效源（按顺序过滤启用）：供 SourceRegistry.orderedEnabled 使用。 */
    val orderedMetadataSourcesEnabled: Flow<List<String>> = combine(
        metadataSourcesOrder, metadataSourcesEnabled
    ) { order, enabled -> order.filter { it in enabled } }

    // ===== 搜索历史（M-06） =====
    val searchHistory: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[SEARCH_HISTORY].orEmpty().split("|").filter { it.isNotBlank() }.distinct()
    }

    // ===== 收音机设置（Phase 2 · 2-3） =====
    val radioMaxRetryCount: Flow<Int> = dataStore.data.map { it[RADIO_MAX_RETRY_COUNT] ?: 5 }
    val radioRetryDelaySeconds: Flow<Int> = dataStore.data.map { it[RADIO_RETRY_DELAY_SECONDS] ?: 2 }
    val radioWifiOnly: Flow<Boolean> = dataStore.data.map { it[RADIO_WIFI_ONLY] ?: true }
    val radioShowProgramInfo: Flow<Boolean> = dataStore.data.map { it[RADIO_SHOW_PROGRAM_INFO] ?: true }
    val radioAutoReconnect: Flow<Boolean> = dataStore.data.map { it[RADIO_AUTO_RECONNECT] ?: true }
    val radioSleepTimerEnabled: Flow<Boolean> = dataStore.data.map { it[RADIO_SLEEP_TIMER_ENABLED] ?: false }
    val radioSleepTimerMinutes: Flow<Int> = dataStore.data.map { it[RADIO_SLEEP_TIMER_MINUTES] ?: 30 }

    // ===== 播放页动态效果（0=声波+光晕, 1=唱片旋转, 2=粒子浮动, 3=频谱条, 4=纯光晕） =====
    val radioVisualEffect: Flow<Int> = dataStore.data.map { it[RADIO_VISUAL_EFFECT] ?: 0 }
    fun radioVisualEffectSync(): Int = prefsCache[RADIO_VISUAL_EFFECT] ?: 0
    suspend fun setRadioVisualEffect(v: Int) = dataStore.edit { it[RADIO_VISUAL_EFFECT] = v }

    fun radioSleepTimerEnabledSync(): Boolean = prefsCache[RADIO_SLEEP_TIMER_ENABLED] ?: false
    suspend fun setRadioSleepTimerEnabled(v: Boolean) = dataStore.edit { it[RADIO_SLEEP_TIMER_ENABLED] = v }
    fun radioSleepTimerMinutesSync(): Int = prefsCache[RADIO_SLEEP_TIMER_MINUTES] ?: 30
    suspend fun setRadioSleepTimerMinutes(v: Int) = dataStore.edit { it[RADIO_SLEEP_TIMER_MINUTES] = v }

    // ===== 闹钟定时播放 =====
    val radioAlarmEnabled: Flow<Boolean> = dataStore.data.map { it[RADIO_ALARM_ENABLED] ?: false }
    val radioAlarmHour: Flow<Int> = dataStore.data.map { it[RADIO_ALARM_HOUR] ?: 7 }
    val radioAlarmMinute: Flow<Int> = dataStore.data.map { it[RADIO_ALARM_MINUTE] ?: 0 }
    fun radioAlarmEnabledSync(): Boolean = prefsCache[RADIO_ALARM_ENABLED] ?: false
    fun radioAlarmHourSync(): Int = prefsCache[RADIO_ALARM_HOUR] ?: 7
    fun radioAlarmMinuteSync(): Int = prefsCache[RADIO_ALARM_MINUTE] ?: 0
    suspend fun setRadioAlarmEnabled(v: Boolean) = dataStore.edit { it[RADIO_ALARM_ENABLED] = v }
    suspend fun setRadioAlarmHour(v: Int) = dataStore.edit { it[RADIO_ALARM_HOUR] = v }
    suspend fun setRadioAlarmMinute(v: Int) = dataStore.edit { it[RADIO_ALARM_MINUTE] = v }

    // ===== 冷启动续播：上次电台会话 =====
    val lastRadioUrl: Flow<String> = dataStore.data.map { it[LAST_RADIO_URL] ?: "" }
    val lastRadioName: Flow<String> = dataStore.data.map { it[LAST_RADIO_NAME] ?: "" }
    val lastRadioId: Flow<Long> = dataStore.data.map { it[LAST_RADIO_ID] ?: 0L }
    fun lastRadioUrlSync(): String = prefsCache[LAST_RADIO_URL] ?: ""
    fun lastRadioNameSync(): String = prefsCache[LAST_RADIO_NAME] ?: ""
    fun lastRadioIdSync(): Long = prefsCache[LAST_RADIO_ID] ?: 0L

    // ===== 界面（I） =====
    suspend fun setThemeMode(v: Int) = dataStore.edit { it[THEME_MODE] = v }
    suspend fun setAccent(v: Int) = dataStore.edit { it[ACCENT] = v }
    suspend fun setThemeStyle(v: Int) = dataStore.edit { it[THEME_STYLE] = v }
    suspend fun setDynamicColors(v: Boolean) = dataStore.edit { it[DYNAMIC_COLORS] = v }
    suspend fun setLanguage(v: String) = dataStore.edit { it[LANGUAGE] = v }
    suspend fun setAppMode(v: String) = dataStore.edit { it[APP_MODE] = v }
    suspend fun setMusicWasPlaying(v: Boolean) = dataStore.edit { it[MUSIC_WAS_PLAYING] = v }
    fun musicWasPlayingSync(): Boolean = prefsCache[MUSIC_WAS_PLAYING] ?: false
    suspend fun setRadioWasPlaying(v: Boolean) = dataStore.edit { it[RADIO_WAS_PLAYING] = v }
    fun radioWasPlayingSync(): Boolean = prefsCache[RADIO_WAS_PLAYING] ?: false
    suspend fun setShowEmbedArt(v: Boolean) = dataStore.edit { it[SHOW_EMBED_ART] = v }
    suspend fun setListShowArt(v: Boolean) = dataStore.edit { it[LIST_SHOW_ART] = v }
    suspend fun setListTwoLine(v: Boolean) = dataStore.edit { it[LIST_TWO_LINE] = v }
    suspend fun setListDensity(v: String) = dataStore.edit { it[LIST_DENSITY] = v }
    suspend fun setNotifyEnabled(v: Boolean) = dataStore.edit { it[NOTIFY_ENABLED] = v }
    suspend fun setLockscreenControl(v: Boolean) = dataStore.edit { it[LOCKSCREEN_CONTROL] = v }
    suspend fun setLockscreenVisualEffect(v: Int) = dataStore.edit { it[LOCKSCREEN_VISUAL_EFFECT] = v.coerceIn(0, LOCKSCREEN_EFFECT_COUNT - 1) }
    suspend fun setLockscreenOverlayEnabled(v: Boolean) = dataStore.edit { it[LOCKSCREEN_OVERLAY_ENABLED] = v }
    suspend fun setMiniBarEnabled(v: Boolean) = dataStore.edit { it[MINI_BAR_ENABLED] = v }
    suspend fun setKeepScreenOn(v: Boolean) = dataStore.edit { it[KEEP_SCREEN_ON] = v }
    suspend fun setCarBtLyrics(v: Boolean) = dataStore.edit { it[CAR_BT_LYRICS] = v }
    suspend fun setFocusLossAutoSkip(v: Boolean) = dataStore.edit { it[FOCUS_LOSS_AUTOSKIP] = v }
    suspend fun setDisplayMode(v: String) = dataStore.edit { it[DISPLAY_MODE] = v }
    suspend fun setAutoHideControls(v: Boolean) = dataStore.edit { it[AUTO_HIDE_CONTROLS] = v }
    suspend fun setAutoHideDelayMs(v: Int) = dataStore.edit { it[AUTO_HIDE_DELAY_MS] = v }

    // ===== 声音引擎（S） =====
    suspend fun setAudioRoute(v: String) = dataStore.edit { it[AUDIO_ROUTE] = v }
    suspend fun setLowLatency(v: Boolean) = dataStore.edit { it[LOW_LATENCY] = v }
    suspend fun setFfmpegSoftDecode(v: Boolean) = dataStore.edit { it[FFMPEG_SOFT_DECODE] = v }
    suspend fun setBufferMs(v: Int) = dataStore.edit { it[BUFFER_MS] = v }
    suspend fun setFloat32Processing(v: Boolean) = dataStore.edit { it[FLOAT32_PROCESSING] = v }
    suspend fun setVolumeNormalize(v: Boolean) = dataStore.edit { it[VOLUME_NORMALIZE] = v }
    suspend fun setReplaygainMode(v: String) = dataStore.edit { it[REPLAYGAIN_MODE] = v }
    suspend fun setVolumeCurve(v: String) = dataStore.edit { it[VOLUME_CURVE] = v }
    suspend fun setFadeInMs(v: Int) = dataStore.edit { it[FADE_IN_MS] = v }
    suspend fun setFadeOutMs(v: Int) = dataStore.edit { it[FADE_OUT_MS] = v }
    suspend fun setSilenceRemover(v: Boolean) = dataStore.edit { it[SILENCE_REMOVER] = v }
    suspend fun setEqEnabled(v: Boolean) = dataStore.edit { it[EQ_ENABLED] = v }
    suspend fun setEqPreset(v: String) = dataStore.edit { it[EQ_PRESET] = v }
    suspend fun setChannelBalance(v: Float) = dataStore.edit { it[CHANNEL_BALANCE] = v }
    suspend fun setEqGains(gains: FloatArray) = dataStore.edit {
        it[EQ_GAINS] = gains.joinToString(",")
    }

    // ===== 回放（P） =====
    suspend fun setStartupAction(v: String) = dataStore.edit { it[STARTUP_ACTION] = v }
    suspend fun setDefaultRepeat(v: String) = dataStore.edit { it[DEFAULT_REPEAT] = v }
    suspend fun setSkipOnError(v: Boolean) = dataStore.edit { it[SKIP_ON_ERROR] = v }
    suspend fun setAutoEqByGenre(v: Boolean) = dataStore.edit { it[AUTO_EQ_BY_GENRE] = v }
    suspend fun setAutoResume(v: Boolean) = dataStore.edit { it[AUTO_RESUME] = v }
    suspend fun setGapless(v: Boolean) = dataStore.edit { it[GAPLESS] = v }
    suspend fun setRememberPosition(v: Boolean) = dataStore.edit { it[REMEMBER_POS] = v }
    suspend fun setAutoSyncMetadata(v: Boolean) = dataStore.edit { it[AUTO_SYNC_METADATA] = v }
    suspend fun setLastMetadataSyncAt(t: Long) = dataStore.edit { it[LAST_METADATA_SYNC_AT] = t }
    suspend fun setDataSaver(v: Boolean) = dataStore.edit { it[DATA_SAVER] = v }
    suspend fun setCacheEnabled(v: Boolean) = dataStore.edit { it[CACHE_ENABLED] = v }
    suspend fun setCacheWaterLevelGb(v: Int) = dataStore.edit { it[CACHE_WATER_LEVEL_GB] = v }
    suspend fun setCacheCapGb(v: Int) = dataStore.edit { it[CACHE_CAP_GB] = v }

    // ===== 播放列表（L） =====
    suspend fun setPlaylistAutosave(v: Boolean) = dataStore.edit { it[PLAYLIST_AUTOSAVE] = v }
    suspend fun setTrackFormat(v: String) = dataStore.edit { it[TRACK_FORMAT] = v }
    suspend fun setListGroupBy(v: String) = dataStore.edit { it[LIST_GROUP_BY] = v }
    suspend fun setSortBy(v: String) = dataStore.edit { it[SORT_BY] = v }
    suspend fun setAllowDeleteFile(v: Boolean) = dataStore.edit { it[ALLOW_DELETE_FILE] = v }

    // ===== 音乐库（M） =====
    suspend fun setWatchFolders(v: Boolean) = dataStore.edit { it[WATCH_FOLDERS] = v }
    suspend fun setReadEmbedLyrics(v: Boolean) = dataStore.edit { it[READ_EMBED_LYRICS] = v }
    suspend fun setHideShortClips(v: Boolean) = dataStore.edit { it[HIDE_SHORT] = v }
    suspend fun setScanHidden(v: Boolean) = dataStore.edit { it[SCAN_HIDDEN] = v }
    suspend fun setScanExtensions(exts: Set<String>) = dataStore.edit {
        it[SCAN_EXTENSIONS] = exts.joinToString(",")
    }

    // ===== 控制（C） =====
    suspend fun setHeadsetPause(v: Boolean) = dataStore.edit { it[HEADSET_PAUSE] = v }
    suspend fun setSleepTimerMin(v: Int) = dataStore.edit { it[SLEEP_TIMER_MIN] = v }
    // 2026-08-24 新增
    suspend fun setHeadsetButtonControl(v: Boolean) = dataStore.edit { it[HEADSET_BUTTON_CONTROL] = v }
    suspend fun setBtDisconnectPause(v: Boolean) = dataStore.edit { it[BT_DISCONNECT_PAUSE] = v }
    suspend fun setBtReconnectResume(v: Boolean) = dataStore.edit { it[BT_RECONNECT_RESUME] = v }
    suspend fun setPlayRequiresAudioFocus(v: Boolean) = dataStore.edit { it[PLAY_REQUIRES_AUDIO_FOCUS] = v }

    // ===== 整合（G） =====
    /** 上次成功迁移到的数据版本（首次冷启动或升级时由 VersionUpgradeCoordinator 判定/更新）。 */
    suspend fun lastDatabaseVersion(): Int = dataStore.data.first()[LAST_DB_VERSION] ?: 0

    suspend fun setLastDatabaseVersion(v: Int) = dataStore.edit { it[LAST_DB_VERSION] = v }

    suspend fun setIsDefaultPlayer(v: Boolean) = dataStore.edit { it[IS_DEFAULT_PLAYER] = v }
    suspend fun setAcceptExternalOpen(v: Boolean) = dataStore.edit { it[ACCEPT_EXTERNAL_OPEN] = v }

    // ===== 歌词 / 在线元数据 =====
    suspend fun setLyricsEnabled(v: Boolean) = dataStore.edit { it[LYRICS_ENABLED] = v }
    suspend fun setMetadataEnabled(v: Boolean) = dataStore.edit { it[METADATA_ENABLED] = v }
    /** 写入元数据获取源启用集合（仅决定启用/禁用，不改变排列顺序）。 */
    suspend fun setMetadataSourcesEnabled(enabled: List<String>) = dataStore.edit {
        it[METADATA_SOURCES_ENABLED] = enabled.joinToString(",")
    }
    /** 写入元数据获取源排列顺序（全部源有序，仅决定顺序）。 */
    suspend fun setMetadataSourcesOrder(order: List<String>) = dataStore.edit {
        it[METADATA_SOURCES_ORDER] = order.joinToString(",")
    }
    suspend fun setLastSession(ids: List<Long>, index: Int, position: Long) = dataStore.edit {
        it[LAST_QUEUE_IDS] = ids.joinToString(",")
        it[LAST_INDEX] = index
        it[LAST_POSITION] = position
    }

    // ===== 收音机设置（Phase 2 · 2-3） =====
    suspend fun setRadioMaxRetryCount(v: Int) = dataStore.edit { it[RADIO_MAX_RETRY_COUNT] = v }
    suspend fun setRadioRetryDelaySeconds(v: Int) = dataStore.edit { it[RADIO_RETRY_DELAY_SECONDS] = v }
    suspend fun setRadioWifiOnly(v: Boolean) = dataStore.edit { it[RADIO_WIFI_ONLY] = v }
    suspend fun setRadioShowProgramInfo(v: Boolean) = dataStore.edit { it[RADIO_SHOW_PROGRAM_INFO] = v }
    suspend fun setRadioAutoReconnect(v: Boolean) = dataStore.edit { it[RADIO_AUTO_RECONNECT] = v }

    // ===== 冷启动续播：上次电台会话 =====
    suspend fun setLastRadioSession(url: String, name: String, id: Long) = dataStore.edit {
        it[LAST_RADIO_URL] = url
        it[LAST_RADIO_NAME] = name
        it[LAST_RADIO_ID] = id
    }

    // ===== 内置电台清单远程版本（RadioBuiltInUpdater：已应用的最新远程版本，缺省 0） =====
    suspend fun radioBuiltinVersion(): Int = dataStore.data.first()[RADIO_BUILTIN_VERSION] ?: 0
    suspend fun setRadioBuiltinVersion(v: Int) = dataStore.edit { it[RADIO_BUILTIN_VERSION] = v }

    // ===== 搜索历史（M-06）：保留最近 8 条 =====
    suspend fun addSearchQuery(q: String) {
        val clean = q.trim()
        if (clean.isBlank()) return
        dataStore.edit { prefs ->
            val current = prefs[SEARCH_HISTORY].orEmpty().split("|").filter { it.isNotBlank() && it != clean }
            prefs[SEARCH_HISTORY] = (listOf(clean) + current).take(8).joinToString("|")
        }
    }

    suspend fun clearSearchHistory() = dataStore.edit { it[SEARCH_HISTORY] = "" }

    // ===== P0-4：同步读取内存快照（主线程安全，无磁盘 I/O 阻塞） =====
    fun notifyEnabledSync(): Boolean = prefsCache[NOTIFY_ENABLED] ?: true
    fun acceptExternalOpenSync(): Boolean = prefsCache[ACCEPT_EXTERNAL_OPEN] ?: true
    fun readEmbedLyricsSync(): Boolean = prefsCache[READ_EMBED_LYRICS] ?: true
    fun lockscreenControlSync(): Boolean = prefsCache[LOCKSCREEN_CONTROL] ?: true
    fun lockscreenOverlayEnabledSync(): Boolean = prefsCache[LOCKSCREEN_OVERLAY_ENABLED] ?: false
    fun carBtLyricsSync(): Boolean = prefsCache[CAR_BT_LYRICS] ?: true
    fun headsetButtonControlSync(): Boolean = prefsCache[HEADSET_BUTTON_CONTROL] ?: true
    // 耳机/蓝牙断开/重连行为同步读（RadioPlayer 在 BroadcastReceiver 里不能挂协程）
    fun headsetPauseSync(): Boolean = prefsCache[HEADSET_PAUSE] ?: true
    fun btDisconnectPauseSync(): Boolean = prefsCache[BT_DISCONNECT_PAUSE] ?: true
    fun btReconnectResumeSync(): Boolean = prefsCache[BT_RECONNECT_RESUME] ?: true

    // 播放器构建参数（PlayerFactory 构建期一次性读取）
    fun bufferMsSync(): Int = prefsCache[BUFFER_MS] ?: 200
    fun lowLatencySync(): Boolean = prefsCache[LOW_LATENCY] ?: false
    // 2026-08-22：FFmpeg 软解开关。默认开启：ffmpeg_jni.c 已加固（swr 输出改用栈上固定缓冲 +
    // 单帧样本数上限 + nb_samples 越界写防护），解决此前真机原生段错误崩溃；同时接管 flac 软解
    // （系统 c2.android.flac.decoder 对 metadata 损坏的 flac 报 CORRUPTED，FFmpeg 按帧容错解码）。
    fun ffmpegSoftDecodeSync(): Boolean = prefsCache[FFMPEG_SOFT_DECODE] ?: true
    fun float32ProcessingSync(): Boolean = prefsCache[FLOAT32_PROCESSING] ?: true
    fun volumeNormalizeSync(): Boolean = prefsCache[VOLUME_NORMALIZE] ?: true
    fun silenceRemoverSync(): Boolean = prefsCache[SILENCE_REMOVER] ?: true
    fun replaygainModeSync(): String = prefsCache[REPLAYGAIN_MODE] ?: "track"
    fun radioWifiOnlySync(): Boolean = prefsCache[RADIO_WIFI_ONLY] ?: true

    // ===== P0-4 附加：元数据获取源同步快照（SourceRegistry.orderedEnabled 主线程安全读取） =====
    /** 启用集合快照。 */
    fun metadataSourcesEnabledSync(): List<String> =
        prefsCache[METADATA_SOURCES_ENABLED]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() } ?: defaultEnabledSources
    /** 排列顺序快照。 */
    fun metadataSourcesOrderSync(): List<String> =
        prefsCache[METADATA_SOURCES_ORDER]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() } ?: defaultSourceOrder
    /** 当前生效源（按排列顺序过滤启用）。 */
    fun orderedMetadataSourcesEnabledSync(): List<String> =
        metadataSourcesOrderSync().filter { it in metadataSourcesEnabledSync() }

    // ===== 播放器数据导出/导入（设置部分） =====
    /**
     * 导出当前全部已写入的设置项（含核心设置/主题/各开关，以及同 DataStore 内的
     * ZeroTier 网络 ID 等）。值为类型化序列化（[SettingEntry.type]），导入时可按类型还原。
     * 注意：默认值未显式写入的 key 不在返回结果中（还原后行为与备份时等价）。
     */
    suspend fun exportSettings(): List<SettingEntry> {
        val prefs = dataStore.data.first().asMap()
        return prefs.mapNotNull { (key, value) ->
            val name = key.name
            // 仅导出当前版本已知的设置键，跳过形如 scan_hidden_files 等历史残留/未知键（G-镜像收敛）
            if (name !in knownSettingKeys) return@mapNotNull null
            when (value) {
                is Boolean -> SettingEntry(name, "bool", value.toString())
                is Int -> SettingEntry(name, "int", value.toString())
                is Long -> SettingEntry(name, "long", value.toString())
                is Float -> SettingEntry(name, "float", value.toString())
                is String -> SettingEntry(name, "string", value)
                is Set<*> -> SettingEntry(name, "stringSet", JSONArray(value.filterIsInstance<String>()).toString())
                else -> null
            }
        }
    }

    /**
     * 按导出类型还原并覆盖写入设置项。排除内部数据门控键（zt_init_done / last_database_version），
     * 避免备份覆盖影响目标设备「全新安装 vs 升级」与版本迁移的判定。
     */
    suspend fun importSettings(entries: List<SettingEntry>) {
        // M-隔离：续播会话三键属运行时态（对应当前设备曲库），导入不覆盖，避免跨设备续播错乱
        val excluded = setOf(
            "zt_init_done", "last_database_version",
            "last_queue_ids", "last_index", "last_position"
        )
        dataStore.edit { prefs ->
            entries.forEach { e ->
                // 白名单门控：仅写入当前版本已知的设置键，拒绝历史残留/未知/内部门控键（G-越权镜像收敛）
                if (e.name in excluded || e.name !in knownSettingKeys) return@forEach
                when (e.type) {
                    "bool" -> prefs[booleanPreferencesKey(e.name)] = e.value.toBoolean()
                    "int" -> prefs[intPreferencesKey(e.name)] = e.value.toIntOrNull() ?: return@forEach
                    "long" -> prefs[longPreferencesKey(e.name)] = e.value.toLongOrNull() ?: return@forEach
                    "float" -> prefs[floatPreferencesKey(e.name)] = e.value.toFloatOrNull() ?: return@forEach
                    "string" -> prefs[stringPreferencesKey(e.name)] = e.value
                    "stringSet" -> {
                        val set = runCatching {
                            val arr = JSONArray(e.value)
                            List(arr.length()) { arr.getString(it) }.toSet()
                        }.getOrNull() ?: return@forEach
                        prefs[stringSetPreferencesKey(e.name)] = set
                    }
                }
            }
        }
    }

    companion object {
        /** 当前版本全部已注册设置键白名单（G-镜像收敛：导出/导入均只认这些键，拒绝未知/残留键写入）。 */
        val knownSettingKeys: Set<String> = setOf(
            // 界面
            "theme_mode", "accent_color", "theme_style", "language", "show_embed_art",
            "list_show_art", "list_two_line", "list_density", "notify_enabled",
            "lockscreen_control", "mini_bar_enabled", "keep_screen_on", "car_bt_lyrics",
            "focus_loss_autoskip",
            "last_database_version", "display_mode",
            // 声音引擎
            "audio_route", "low_latency", "ffmpeg_soft_decode", "buffer_ms",
            "float32_processing", "volume_normalize", "replaygain_mode", "volume_curve",
            "fade_in_ms", "fade_out_ms", "silence_remover", "eq_enabled", "eq_preset", "eq_gains",
            "channel_balance",
            // 回放
            "startup_action", "default_repeat", "skip_on_error", "auto_eq_by_genre",
            "auto_resume", "gapless", "remember_position", "auto_sync_metadata",
            "last_metadata_sync_at", "data_saver",
            // 网络源本地缓存
            "cache_enabled", "cache_water_level_gb", "cache_cap_gb",
            // 播放列表
            "playlist_autosave", "track_format", "list_group_by", "sort_by", "allow_delete_file",
            // 音乐库
            "watch_folders", "read_embed_lyrics", "hide_short_clips", "scan_hidden", "scan_extensions",
            // 控制
            "headset_pause", "sleep_timer_min", "headset_button_control",
            "bt_disconnect_pause", "bt_reconnect_resume", "play_requires_audio_focus",
            // 整合
            "is_default_player", "accept_external_open",
            // 断点续播会话
            "last_queue_ids", "last_index", "last_position",
            // 歌词 / 元数据
            "lyrics_enabled", "metadata_enabled", "metadata_sources_order",
            "metadata_sources_enabled", "search_history",
            // 收音机冷启动续播
            "last_radio_url", "last_radio_name", "last_radio_id"
        )

        /** 默认启用源（仅前 3 个中文核心源；其余源默认不启用，用户在「元数据来源」设置中手动开启）。 */
        val defaultEnabledSources: List<String> = listOf("netease", "qq", "kuwo", "migu", "kugou")
        /** 默认排列顺序（全部源统一序）：网易→QQ→酷我→咪咕→酷狗→Genius→TheAudioDB→维基。 */
        val defaultSourceOrder: List<String> = listOf(
            "netease", "qq", "kuwo", "migu", "kugou", "genius", "theaudiodb", "wikipedia"
        )
    }
}

/** 设置项的导出载体：type 为 bool/int/long/float/string/stringSet，value 为类型化序列化后的字符串。 */
data class SettingEntry(
    val name: String,
    val type: String,
    val value: String
)