package com.shiyinplayer

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.shiyinplayer.data.metasync.MetadataSyncManager
import com.shiyinplayer.data.network.NetworkMonitor
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import com.shiyinplayer.data.radio.RadioStationSeeder
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。Hilt 在此初始化依赖图。
 * 注意：ZeroTier 节点（libzt 用户态）为进程级单例，由 [com.shiyinplayer.data.network.zerotier.ZeroTierManager]
 * 懒加载，不在此 onCreate 中强制启动，避免无网络授权时也拉起虚拟网。
 *
 * 2026-08-19：启动时触发一次「自动同步曲库文件元数据」检查（开关开启且当前网络允许才执行，
 * 独立 IO 线程，不阻塞冷启动与播放）；并启动网络状态监听——连接 WiFi 时自动触发后台同步（需求3）。
 *
 * 2026-08-22：首启播种（IO 协程，不阻塞冷启动）。
 * 2026-09-11（开源版）：去除默认 ZT 网络 ID / 默认 ZT WebDAV 源播种与更新检查、内置电台远程拉取，
 * 不内嵌任何私有网络与更新源，仅保留内置电台本地清单播种。
 */
@HiltAndroidApp
class MusicPlayerApplication : Application() {

    @Inject lateinit var metadataSyncManager: MetadataSyncManager

    @Inject lateinit var networkMonitor: NetworkMonitor

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var libraryRepository: LibraryRepository

    @Inject lateinit var zeroTierManager: ZeroTierManager

    @Inject lateinit var radioStationSeeder: RadioStationSeeder

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 前台追踪回调：任一 Activity STARTED 视为前台；全部 STOPPED（真正退到后台）时
     * 置为后台，并立即触发一轮自动同步（兑现「前台暂缓、退后台继续」）。避免冷启竞态。
     */
    private val foregroundCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            isAppInForeground = true
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities--
            if (startedActivities <= 0) {
                startedActivities = 0
                if (isAppInForeground) {
                    isAppInForeground = false
                    metadataSyncManager.syncNow()
                }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onCreate() {
        super.onCreate()
        // 2026-08-23：用 ActivityLifecycleCallbacks 精确追踪应用前台/后台状态（无冷启竞态）。
        // 供 MetadataSyncManager 判断「前台避让」——前台时跳过完整同步，退到后台后再执行，
        // 避免整库元数据同步与曲库首屏查询在冷启/浏览时争抢 IO。不用 ProcessLifecycleOwner（lifecycle-process
        // 冷启动时状态未及时到 STARTED，且需额外依赖）。
        registerActivityLifecycleCallbacks(foregroundCallbacks)
        // P0-4：提前触发 SettingsRepository 依赖图构建，使内存快照（prefsCache）尽早开始
        // 后台收集 DataStore，缩短主线程同步读设置的冷启动空窗。
        settingsRepository.notifyEnabledSync()
        networkMonitor.start()
        metadataSyncManager.start()
        // 2026-08-19 需求2：播放中预取下一首元数据（常驻监听，切歌时自动触发）
        metadataSyncManager.startPlaybackPrefetch()
        // 2026-08-23 需求4：删去冷启立即执行的 [maybeRunSync]，首轮同步统一由「周期自动同步」承担，
        // 并延迟数秒执行——避免整库元数据同步与曲库首屏查询在冷启时争抢 IO，造成「打开加载慢」。
        metadataSyncManager.startContinuousSync()
        // 预热库快照，确保首屏加载有缓存数据
        appScope.launch { libraryRepository.preloadSnapshot() }
        // 2026-09-02：首启播种内置电台清单（幂等、不阻塞启动）
        // 2026-09-11：去除「启动时从更新服务器自动拉取内置电台清单」功能（开源版，不依赖远程更新源）。
        appScope.launch {
            radioStationSeeder.seedIfNeeded()
        }
        // ZeroTier 自动连接：进程启动即恢复虚拟网，不再依赖 MainActivity
        appScope.launch { zeroTierManager.autoConnect() }
    }

    companion object {
        /** 已 STARTED 的 Activity 数（含后台可见的 Activity），用于推算应用是否退回后台。 */
        private var startedActivities = 0

        /** 应用是否在前台（任一 Activity STARTED 且未全部 STOPPED）。默认 true（视为前台）：
         * 冷启动/未知状态下假定前台——避免整库同步在首屏查询阶段启动争抢 IO；仅当全部 Activity
         * 确认 STOPPED（真正退到后台）才降为 false 放行后台同步。线程安全读取。 */
        @Volatile internal var isAppInForeground = true
    }
}
