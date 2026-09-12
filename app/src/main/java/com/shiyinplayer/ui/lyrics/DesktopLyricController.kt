package com.shiyinplayer.ui.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.shiyinplayer.R
import com.shiyinplayer.player.LyricLinesStore
import com.shiyinplayer.player.PlayerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F2-4 桌面歌词悬浮窗：基于 WindowManager.TYPE_APPLICATION_OVERLAY 的全局悬浮歌词条。
 * 显示当前曲目 + 实时歌词行；可拖动定位（按住移动）、单击隐藏；歌词行由 [LyricLinesStore]
 * 提供，播放位置经 [PlayerManager.livePositionMs] 以低频 tick 轮询命中，内容未变化不重绘。
 *
 * 注：悬浮窗随进程宿主存活——播放期间由前台播放服务保活进程，故切到其他应用仍持续显示。
 */
@Singleton
class DesktopLyricController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerManager: PlayerManager,
    private val lyricLinesStore: LyricLinesStore
) {
    private val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var root: LinearLayout? = null
    private var lyricView: TextView? = null
    private var ticker: Job? = null
    private var lastShownText: String? = null

    // 拖动状态
    private var startRawX = 0f
    private var startRawY = 0f
    private var startX = 0
    private var startY = 0
    private var lastTapAt = 0L

    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    /** 悬浮窗权限是否已授予。 */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /** 打开系统「显示在其他应用上层」授权页。 */
    fun openOverlaySettings() {
        val pkg = context.packageName
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$pkg")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ContextCompat.startActivity(context, intent, null)
    }

    fun toggle() = if (_shown.value) hide() else show()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (!canDrawOverlays() || _shown.value) return
        scope.launch {
            val container = buildView()
            val width = (context.resources.displayMetrics.widthPixels * 0.94f).toInt()
            val params = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (context.resources.displayMetrics.heightPixels * 0.08f).toInt()
            }

            container.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = ev.rawX; startRawY = ev.rawY
                        startX = params.x; startY = params.y
                        params.y = startY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (ev.rawX - startRawX).toInt()
                        params.y = startY + (ev.rawY - startRawY).toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                        true
                    }
                    else -> false
                }
            }
            container.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTapAt < 300) hide() else lastTapAt = now
            }

            runCatching { wm.addView(container, params) }.onFailure {
                root = null
                lyricView = null
                return@launch
            }
            root = container
            _shown.value = true
            ticker = scope.launch {
                while (isActive) {
                    updateLine()
                    delay(150)
                }
            }
        }
    }

    fun hide() {
        scope.launch {
            ticker?.cancel()
            ticker = null
            _shown.value = false
            root?.let {
                runCatching { wm.removeView(it) }
                root = null
                lyricView = null
            }
        }
    }

    /** 轮询播放位置并刷新歌词行（内容未变化不重绘）。 */
    private fun updateLine() {
        val v = lyricView ?: return
        val line = lyricLinesStore.lineAt(playerManager.livePositionMs())?.takeIf { it.isNotBlank() }
        val text = line ?: context.getString(R.string.lyrics_loading)
        if (text == lastShownText) return
        lastShownText = text
        v.text = text
    }

    /** 纯代码构建悬浮条：圆角半透明卡片 = 曲名（小字淡化）+ 歌词行（大字加粗）。 */
    private fun buildView(): LinearLayout {
        val dp = context.resources.displayMetrics.density
        fun dip(v: Int) = (v * dp).toInt()

        val title = playerManager.playbackState.value.currentSong
            ?.let { listOfNotNull(it.title, it.artistName).filter(String::isNotBlank).joinToString(" - ") }
            ?: ""

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(16), dip(10), dip(16), dip(10))
            background = GradientDrawable().apply {
                cornerRadius = dip(12).toFloat()
                setColor(0xCC000000.toInt())
            }
        }

        if (title.isNotBlank()) {
            container.addView(TextView(context).apply {
                text = title
                textSize = 12f
                setTextColor(0xCCFFFFFF.toInt())
                maxLines = 1
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val tv = TextView(context).apply {
            text = context.getString(R.string.lyrics_loading)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 2
            setPadding(0, dip(2), 0, 0)
        }
        lyricView = tv
        container.addView(tv)
        return container
    }
}