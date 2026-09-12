package com.shiyinplayer.data.metasync

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shiyinplayer.ui.MainActivity

/**
 * 元数据同步保活前台服务（2026-08-23 需求2）。
 *
 * 自动同步曲库元数据时由 [MetadataSyncManager.syncNow] 拉起本服务，使进程转为前台、
 * 不易被系统杀死，从而保证播放器不在前台时也能正常在线进行元数据同步。
 * 同步结束（[stop]）或服务被系统回收（START_NOT_STICKY）即自行结束，不常驻。
 */
class MetadataSyncService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台化保活：同步期间进程优先级提升，避免被系统回收；Android 14+ 须显式传 FGS 类型
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            // 立即移除占位通知（保留前台优先级），用户端不再看到「正在同步」通知。
            // API24+ 用 STOP_FOREGROUND_REMOVE 移除通知并解除前台状态，
            // 后台协程仍可运行（进程不被杀死即可）。
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                else stopForeground(true)
            }
            runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_ID) }
        } catch (e: Exception) {
            Log.w(TAG, "前台保活通知失败：${e.message}")
        }
        // 后台启动受限时（Android 12+）可能被系统以 onTaskRemoved/onDestroy 处置；同步失败不重启
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // J-通知清理：服务销毁时移除前台状态与占位通知，防止同步结束后通知残留
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else stopForeground(true)
        }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_ID) }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        // 元数据同步为后台保活服务，工作时不希望用户看到通知。
        // 换新 ID（v3）使系统以 MIN 重建，避免旧通道 importance 已锁定无法降低的问题。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, "元数据同步", NotificationManager.IMPORTANCE_MIN
            )
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            channel.enableVibration(false)
            channel.setSound(null, null)
            channel.setShowBadge(false)
            runCatching { NotificationManagerCompat.from(this).createNotificationChannel(channel) }
        } else {
            val channel = NotificationChannelCompat.Builder(
                CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN
            ).setName("元数据同步").setShowBadge(false).build()
            runCatching { NotificationManagerCompat.from(this).createNotificationChannel(channel) }
        }
    }

    private fun buildNotification(): Notification {
        // 后台启动前台服务（Android 12+ 限制）在 App 处于后台时可能被拒绝；尽力而为即可
        val contentIntent = runCatching {
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }.getOrNull()
        // 极简隐藏占位：仅满足前台服务必须持有一通知的约束，用户端近乎不可见。
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("正在同步音乐元数据")
            .setContentText("后台读取/联网补充歌曲信息，兼顾保活")
            // SERVICE 类别：避免被 ColorOS 归入「告警」自动聚合而影响媒体控制卡显示。
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val TAG = "MetadataSyncService"
        // 新通道 ID：旧 metadata_sync_channel_v2 已创建为更高优先级，通道一旦创建不可降低优先级，
        // 换新 ID 使系统以 MIN 重建，同步期间通知几乎不出现、不起聚合。
        private const val CHANNEL_ID = "metadata_sync_channel_v3"
        private const val NOTIF_ID = 2001

        /** 拉起保活前台服务；后台受限时静默降级（同步仍以后台协程继续，尽力而为）。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, MetadataSyncService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MetadataSyncService::class.java)) }
        }
    }
}