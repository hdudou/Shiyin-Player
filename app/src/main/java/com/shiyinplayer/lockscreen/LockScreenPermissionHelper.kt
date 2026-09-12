package com.shiyinplayer.lockscreen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

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
    fun getPermissionGuideText(vendor: Vendor): Pair<String, String> {
        return when (vendor) {
            Vendor.Xiaomi -> Pair(
                "开启悬浮窗权限",
                "小米/HyperOS 需要手动开启「显示悬浮窗」权限。\n\n" +
                    "步骤：设置 → 应用设置 → 应用管理 → ShiyinPlayer → 权限 → 显示悬浮窗 → 开启\n\n" +
                    "未开启时将降级为通知栏控制。"
            )
            Vendor.Huawei -> Pair(
                "开启悬浮窗权限",
                "华为/荣耀 EMUI 需要手动开启「悬浮窗」权限。\n\n" +
                    "步骤：设置 → 应用和服务 → 权限管理 → ShiyinPlayer → 悬浮窗 → 允许\n\n" +
                    "未开启时将降级为通知栏控制。"
            )
            Vendor.Oppo -> Pair(
                "开启悬浮窗权限",
                "OPPO/OnePlus/Realme 需要手动开启「悬浮窗」权限。\n\n" +
                    "步骤：设置 → 应用管理 → ShiyinPlayer → 权限管理 → 悬浮窗 → 允许\n\n" +
                    "未开启时将降级为通知栏控制。"
            )
            Vendor.Vivo -> Pair(
                "开启悬浮窗权限",
                "vivo/iQOO 需要手动开启「悬浮窗」权限。\n\n" +
                    "步骤：设置 → 应用与权限 → 权限管理 → ShiyinPlayer → 悬浮窗 → 允许\n\n" +
                    "未开启时将降级为通知栏控制。"
            )
            Vendor.Samsung -> Pair(
                "开启悬浮窗权限",
                "三星需要手动开启「在其他应用上层显示」权限。\n\n" +
                    "步骤：设置 → 应用 → ShiyinPlayer → 允许在其他应用上层显示 → 开启\n\n" +
                    "未开启时将降级为通知栏控制。"
            )
            Vendor.Unknown -> Pair(
                "开启悬浮窗权限",
                "您的设备需要手动开启「显示在其他应用上层」权限。\n\n" +
                    "请在系统设置中找到 ShiyinPlayer 的权限设置，开启悬浮窗/显示在其他应用上层。\n\n" +
                    "未开启时将降级为通知栏控制。"
            )
        }
    }
}
