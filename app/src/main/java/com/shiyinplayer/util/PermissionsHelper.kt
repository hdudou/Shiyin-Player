package com.shiyinplayer.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 存储 / 通知权限辅助。本地 SAF 目录扫描走 DocumentTree 授权（不依赖 READ_EXTERNAL_STORAGE），
 * 但媒体读取权限仍用于部分兜底路径与媒体库兜底。
 */
object PermissionsHelper {

    fun requiredStoragePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** 是否已授予「所有文件访问」（Android 11+，MANAGE_EXTERNAL_STORAGE）。 */
    fun hasAllFilesAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** 跳转到「所有文件访问」的特殊权限设置页（仅 Android 11+ 生效；更低版本回退到安全设置页）。 */
    fun allFilesAccessSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }

    fun registerPermissionLauncher(
        activity: ComponentActivity,
        onResult: (Map<String, Boolean>) -> Unit
    ): ActivityResultLauncher<Array<String>> {
        return activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
            onResult
        )
    }
}
