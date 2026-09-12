package com.shiyinplayer.lockscreen

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import com.shiyinplayer.R

/**
 * 锁屏解锁辅助：调用系统原生解锁验证界面。
 * - API 26+：requestDismissKeyguard 唤起整屏系统锁屏
 * - API < 26 降级：createConfirmDeviceCredentialIntent 弹窗式凭据确认
 * 成功/失败/取消 三个回调
 */
object LockScreenUnlockHelper {

    interface UnlockCallback {
        fun onDismissSucceeded()
        fun onDismissError()
        fun onDismissCancelled()
    }

    fun requestUnlock(activity: Activity, callback: UnlockCallback) {
        val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = callback.onDismissSucceeded()
                override fun onDismissError() = callback.onDismissError()
                override fun onDismissCancelled() = callback.onDismissCancelled()
            })
        } else {
            try {
                val intent = km.createConfirmDeviceCredentialIntent(
                    activity.getString(R.string.lockscreen_unlock_title),
                    activity.getString(R.string.lockscreen_unlock_subtitle)
                )
                if (intent != null) {
                    activity.startActivityForResult(intent, 1001)
                } else {
                    callback.onDismissError()
                }
            } catch (e: Exception) {
                callback.onDismissError()
            }
        }
    }
}
