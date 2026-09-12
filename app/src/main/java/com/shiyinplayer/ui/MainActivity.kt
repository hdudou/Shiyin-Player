package com.shiyinplayer.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import com.shiyinplayer.data.repository.PlaylistRepository
import com.shiyinplayer.player.PlayerManager
import com.shiyinplayer.player.radio.RadioPlayer
import com.shiyinplayer.player.decoder.AudioFormatRegistry
import com.shiyinplayer.ui.settings.SettingsRepository
import com.shiyinplayer.util.PermissionsHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.shiyinplayer.ui.navigation.AppNavGraph
import com.shiyinplayer.ui.radio.RadioNavGraph
import com.shiyinplayer.util.toast

/**
 * 应用入口（T10）。承载底部导航 + 全屏导航图；并处理外部「打开音频」Intent（设置-整合：设为默认播放器）。
 * 显示模式（设置-界面）：auto 跟随屏幕方向 / portrait 竖屏 / landscape 横屏。
 * 2026-08-21：accept_external_open 接线——关闭时忽略外部打开 Intent；playlist_autosave 接线——
 * 退出时把当前队列存为「上次播放队列」歌单快照。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playerManager: PlayerManager

    @Inject lateinit var radioPlayer: RadioPlayer

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var zeroTierManager: ZeroTierManager

    @Inject lateinit var playlistRepository: PlaylistRepository

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher = PermissionsHelper.registerPermissionLauncher(this) { /* 已授予后 MediaStore 本地歌曲即可读，无需额外处理 */ }
        setContent {
            val appMode by settingsRepository.appMode.collectAsState(initial = "music")
            Box(Modifier.fillMaxSize()) {
                when (appMode) {
                    "radio" -> RadioNavGraph(radioPlayer = radioPlayer)
                    else -> AppNavGraph()
                }
            }
        }
        lifecycleScope.launch {
            settingsRepository.keepScreenOn.collectLatest { keep ->
                // 前台防息屏：开启时前台保持屏幕常亮（FLAG_KEEP_SCREEN_ON），关闭则允许系统息屏
                if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        lifecycleScope.launch {
            settingsRepository.displayMode.collectLatest { mode ->
                requestedOrientation = when (mode) {
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                }
            }
        }
        val handledExternal = handleIntent(intent)
        if (!handledExternal) {
            // 冷启动续播：根据当前模式决定恢复哪个会话
            // 音乐模式恢复音乐会话，电台模式由 RadioViewModel 自行恢复电台
            val currentMode = settingsRepository.appModeSync()
            if (currentMode != "radio") {
                lifecycleScope.launch { playerManager.restoreLastSession() }
            }
        }
        // 获取本地存储访问：冷启动请求缺省的媒体音频/通知权限（已授予则不弹窗），
        // 否则新安装包下 MediaStore 本地歌曲扫描将因无权限返回空。
        val missingPermissions = missingStoragePermissions()
        if (missingPermissions.isNotEmpty()) {
            lifecycleScope.launch { permissionLauncher.launch(missingPermissions) }
        }
    }

    private fun missingStoragePermissions(): Array<String> =
        PermissionsHelper.requiredStoragePermissions()
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // playlist_autosave：进程内一次性保存当前队列为「上次播放队列」歌单快照（防旋转等重复保存）。
        // 注意：onDestroy 时 lifecycleScope 已取消，必须用独立作用域完成保存。
        if (!queueSnapshotSaved) {
            queueSnapshotSaved = true
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val enabled = runCatching { settingsRepository.playlistAutosave.first() }.getOrDefault(true)
                if (enabled) {
                    runCatching { playlistRepository.saveQueueSnapshot(playerManager.playbackState.value.queue) }
                }
            }
        }
    }

    /** 外部音频 Intent（content/file 或 audio 类型）：直接作为单曲播放（T11 整合）。返回是否已处理。
     *  中危-C 加固：对外部传入的 URI/title 做来源可信校验与字符清洗，防止伪造 content://、
     *  越权 path 或不合法标题注入到播放链路。 */
    private fun handleIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW || intent.data == null) return false
        // accept_external_open：关闭时忽略外部打开请求（不消费，走正常启动流程）。
        // P0-4：改读内存快照，消除主线程 runBlocking 阻塞（ANR）。
        val accept = settingsRepository.acceptExternalOpenSync()
        if (!accept) return false
        val uri = intent.data!!
        when (uri.scheme?.lowercase()) {
            "file" -> {
                // file:// 仅放行本应用可读的本地文件；越权/不存在一律拒绝。
                val f = uri.path?.let { java.io.File(it) }
                if (f == null || !f.isFile || !f.canRead()) {
                    toast("无法访问该本地音频文件")
                    return true
                }
            }
            "content" -> {
                // 阶段1 content:// URI 权限修复：持久化读取授权。文件管理器仅授予临时读权限，
                // 若不持久化，则 Activity 重建/进程被系统回收后 PlaybackService 再打开流会抛
                // SecurityException（无声、进度不动）。与 SongsScreen SAF 导入保持同一模式。
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                // 中危-C：无读取权限即拒绝，防伪造/越权 content:// URI 拖入播放链路。
                val readable = runCatching {
                    contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
                if (!readable) {
                    toast("无权限访问该音频")
                    return true
                }
            }
            else -> {
                toast("不支持的音频来源")
                return true
            }
        }
        // 中危-C：标题字符清洗 + 长度上限（去控制字符/不可见字符，防止异常标题注入界面）。
        val rawTitle = uri.lastPathSegment?.substringBeforeLast('.') ?: "外部音频"
        val title = sanitizeExternalTitle(rawTitle)
        // 解析真实 MIME（content:// 经 ContentResolver；file:// 按扩展名兜底），
        // 供 DecoderAwareMediaSourceFactory 将 exotic 格式路由到 RawFileExtractor 透传。
        val mimeType = runCatching { contentResolver.getType(uri) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('.', "")
                ?.let { AudioFormatRegistry.mimeTypeOf(it) }
        val song = Song(
            title = title,
            uri = uri.toString(),
            source = MediaSourceType.LOCAL,
            mimeType = mimeType
        )
        playerManager.playQueue(listOf(song), 0)
        return true
    }

    /** 外部标题净化：去除控制字符与首尾空白，超长截断。 */
    private fun sanitizeExternalTitle(raw: String): String {
        val cleaned = raw.map { if (it.code < 32) ' ' else it }.joinToString("").trim()
        val max = 200
        return if (cleaned.length > max) cleaned.take(max) else cleaned
    }

    private companion object {
        @Volatile var queueSnapshotSaved = false
    }
}
