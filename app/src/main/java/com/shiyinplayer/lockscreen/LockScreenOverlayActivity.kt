package com.shiyinplayer.lockscreen

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.shiyinplayer.ui.theme.MusicPlayerTheme

/**
 * 锁屏叠加层 Activity：
 * - API 27+：setShowWhenLocked(true) + setTurnScreenOn(true)
 * - 覆盖在系统锁屏之上，显示动态可视化页面
 * - 通过 isManualMode 区分手动入口（不跟随锁屏状态）和自动唤起
 * - M5b：国产 ROM 首次进入时检测 SYSTEM_ALERT_WINDOW 权限，缺失则弹出引导对话框
 * - M6：RECORD_AUDIO 权限请求（Visualizer 需要）
 */
class LockScreenOverlayActivity : ComponentActivity() {

    private val showPermissionDialog = mutableStateOf(false)
    private val permissionTitle = mutableStateOf("")
    private val permissionMessage = mutableStateOf("")
    private val hasRecordAudioPermission = mutableStateOf(false)

    override fun onStart() {
        super.onStart()
        // 标记叠加层存活，供前台服务判断是否需要重建（进程/Activity 被回收时复位）
        isShowing = true
    }

    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordAudioPermission.value = granted
        if (!granted) {
            // 降级：Visualizer 不可用，界面仍可展示静态效果
            android.util.Log.w("LockScreenOverlay", "RECORD_AUDIO denied, Visualizer will not capture audio data")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // API 27+ 锁屏之上显示 + 点亮屏幕
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // 窗口标志：全屏 + 防止被装饰栏截断 + 保持屏幕常亮
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setTheme(com.shiyinplayer.R.style.Theme_MusicPlayer_LockScreenOverlay)

        val isManualMode = intent.getBooleanExtra(EXTRA_MANUAL_MODE, false)

        // M6：检查 RECORD_AUDIO 权限，缺失则请求
        hasRecordAudioPermission.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasRecordAudioPermission.value) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // M5b：权限检查 —— 仅 API 23+ 且缺少 SYSTEM_ALERT_WINDOW 时弹出引导
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !LockScreenPermissionHelper.canDrawOverlays(this)
        ) {
            val vendor = LockScreenPermissionHelper.detectVendor()
            val (title, msg) = LockScreenPermissionHelper.getPermissionGuideText(vendor)
            permissionTitle.value = title
            permissionMessage.value = msg
            showPermissionDialog.value = true
        }

        setContent {
            MusicPlayerTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    // M5b：权限引导对话框
                    if (showPermissionDialog.value) {
                        LockScreenPermissionGuideDialog(
                            title = permissionTitle.value,
                            message = permissionMessage.value,
                            onOpenSettings = {
                                LockScreenPermissionHelper.openOverlaySettings(this@LockScreenOverlayActivity)
                                showPermissionDialog.value = false
                            },
                            onCancel = {
                                showPermissionDialog.value = false
                                finish()
                            }
                        )
                    }

                    LockScreenOverlayScreen(
                        activity = this@LockScreenOverlayActivity,
                        isManualMode = isManualMode,
                        hasRecordAudioPermission = hasRecordAudioPermission.value
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回后重新检查权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (showPermissionDialog.value && LockScreenPermissionHelper.canDrawOverlays(this)) {
                showPermissionDialog.value = false
            }
        }
        // 重新检查 RECORD_AUDIO 权限（用户可能在设置中授权后返回）
        hasRecordAudioPermission.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        // 叠加层销毁：复位存活标志，供前台服务按需重建
        isShowing = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MANUAL_MODE = "manual_mode"

        /** 叠加层当前是否存活。由前台服务读取，决定是否需要重建（进程/Activity 被回收时）。 */
        @Volatile
        var isShowing: Boolean = false
            private set
    }
}
