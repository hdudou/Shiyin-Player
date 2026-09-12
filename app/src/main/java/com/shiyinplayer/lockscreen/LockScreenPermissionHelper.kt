package com.shiyinplayer.lockscreen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.shiyinplayer.R

/**
 * 厂商 ROM 检测 + SYSTEM_ALERT_WINDOW 权限检查。
 * 小米/Huawei 等国产 ROM 需要用户手动授权"显示在其他应用上层"。
 */
object LockScreenPermissionHelper {

    /** 已知需要特殊权限引导的厂商 */
    enum class Vendor { Xiaomi, Huawei, Oppo, Vivo, Samsung, Unknown }

    /** 检测当前设备厂商 */
    fun detectVendor(): Vendor {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> Vendor.Xiaomi
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> Vendor.Huawei
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> Vendor.Oppo
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> Vendor.Vivo
            manufacturer.contains("samsung") -> Vendor.Samsung
            else -> Vendor.Unknown
        }
    }

    /** 检查是否已有 SYSTEM_ALERT_WINDOW 权限 */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // API 22 及以下默认允许
        }
    }

    /** 跳转到系统"显示在其他应用上层"设置页 */
    fun openOverlaySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** 获取厂商特定的权限引导提示文案 */
    fun getPermissionGuideText(context: Context, vendor: Vendor): Pair<String, String> {
        val title = context.getString(R.string.overlay_permission_title)
        val bodyRes = when (vendor) {
            Vendor.Xiaomi -> R.string.overlay_permission_body_xiaomi
            Vendor.Huawei -> R.string.overlay_permission_body_huawei
            Vendor.Oppo -> R.string.overlay_permission_body_oppo
            Vendor.Vivo -> R.string.overlay_permission_body_vivo
            Vendor.Samsung -> R.string.overlay_permission_body_samsung
            Vendor.Unknown -> R.string.overlay_permission_body_unknown
        }
        return title to context.getString(bodyRes)
    }
}
