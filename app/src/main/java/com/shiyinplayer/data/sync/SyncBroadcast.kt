package com.shiyinplayer.data.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.shiyinplayer.data.sync.model.SyncContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mDNS 服务广播（契约 §8/§10）：以 `_shiyin-sync._tcp.local` 注册本机接收端，
 * TXT 带 `deviceId`，使 PC 侧的「扫描设备」能发现本机，而不必手输 IP。
 *
 * PC 侧实现见 `Shiyin.Core/Sync/SyncMdnsDiscovery.cs`：它向组播组发一次性 PTR 查询并按
 * RFC 6762 §6.7 收 legacy 单播应答，解析 PTR/SRV/A/TXT；TXT 里的 `deviceId` 用于与已配对记录对齐。
 *
 * 生命周期：**随接收服务启停**（与 23541 监听同生共死）——开关关 = 服务停 = 不再广播，
 * 与服务随开关启停的 B 方案语义一致。
 *
 * ⚠️ **MulticastLock**：部分 ROM 的省电策略会丢弃组播包（表现为「PC 扫不到，但 IP 直连正常」），
 * 故广播期间持有 WifiManager.MulticastLock；需 manifest 声明 `CHANGE_WIFI_MULTICAST_STATE`。
 */
@Singleton
class SyncBroadcast @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var nsdManager: NsdManager? = null
    private var registered: NsdServiceInfo? = null
    private var listener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** 服务实例名（PC 侧从 PTR 解析实例名，**不能含点**，否则会被按标签截断）。 */
    private var serviceName: String = DEFAULT_SERVICE_NAME

    /**
     * 已成功注册的 (port, deviceId) 快照，用于 [start] 幂等判断。
     *
     * ⚠️ 为什么必须幂等（2026-09-17 真机实测）：`SettingsViewModel.init{}` 每次进设置页都会
     * 调 `SyncAcceptService.start()` → `onStartCommand` 里 **无条件** 调本类的 `start()`，
     * 而本类 start 首行是 `stop()` → 于是每进一次设置页就「注销 + 重注册」一遍 mDNS。
     * 真机 logcat 证据：15:42:59 / 15:43:24 / 15:43:31 / 15:43:35 / 15:43:48 / 15:44:37 反复
     * 「mDNS 已注销 → mDNS 已广播」。危害：① 注销与重注册之间服务名短暂消失，PC 恰在
     * 该窗口扫描会**漏发现**；② 频繁 register/unregister 易触 NsdManager 限流导致
     * `onRegistrationFailed`。故已注册且参数未变时直接返回。
     */
    private var registeredPort: Int = 0
    private var registeredDeviceId: String? = null

    /**
     * 注册广播。**幂等**：已注册且 (port, deviceId) 未变则直接返回（理由见 [registeredPort]）；
     * 参数变化或此前未注册时才先 `stop()` 再注册。任何失败都只记日志，绝不影响接收服务主功能。
     */
    fun start(port: Int, deviceId: String, deviceName: String) {
        if (listener != null && registeredPort == port && registeredDeviceId == deviceId) {
            Log.d(TAG, "mDNS 已在广播且参数未变，跳过重复注册（port=$port）")
            return
        }

        stop()
        registeredPort = port
        registeredDeviceId = deviceId

        runCatching {
            val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
                Log.w(TAG, "本机无 NsdManager，跳过 mDNS 广播（PC 只能手输 IP）")
                return
            }
            nsdManager = manager

            // 实例名用 deviceId 保证唯一（设备名可能重复或含点）
            serviceName = "$SERVICE_PREFIX${deviceId.takeLast(8)}"

            val info = NsdServiceInfo().apply {
                this.serviceName = this@SyncBroadcast.serviceName
                serviceType = SERVICE_TYPE
                this.port = port
                setAttribute("deviceId", deviceId)
                setAttribute("deviceName", deviceName)
                setAttribute("schemaVersion", SCHEMA_VERSION.toString())
            }

            val registration = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    registered = serviceInfo
                    // 端口以传入的 port 为准：Android 14 实测 onServiceRegistered 回调里的
                    // serviceInfo.port 恒为 0（`dumpsys servicediscovery` 显示实际注册的是 23541），
                    // 直接打印回调值会误导排查（历史上曾被误读为「端口没设上」）。
                    Log.i(TAG, "mDNS 已广播：${serviceInfo.serviceName} $SERVICE_TYPE:$port")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS 注册失败（errorCode=$errorCode），PC 需手输 IP")
                    // 异步失败：必须清掉幂等标记，否则后续 start() 会误判为「已注册」而永不重试
                    listener = null
                    registeredPort = 0
                    registeredDeviceId = null
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "mDNS 已注销：${serviceInfo.serviceName}")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS 注销失败：errorCode=$errorCode")
                }
            }

            listener = registration
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            acquireMulticastLock()
        }.onFailure {
            Log.w(TAG, "启动 mDNS 广播失败：${it.message}")
            listener = null
            registeredPort = 0
            registeredDeviceId = null
        }
    }

    /** 注销广播并释放组播锁（幂等）。 */
    fun stop() {
        listener?.let { l ->
            runCatching { nsdManager?.unregisterService(l) }
                .onFailure { Log.w(TAG, "注销 mDNS 异常：${it.message}") }
        }
        listener = null
        registered = null
        registeredPort = 0
        registeredDeviceId = null
        releaseMulticastLock()
    }

    // ------------------------------------------------------------ 组播锁

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            val lock = wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        }.onFailure { Log.w(TAG, "获取组播锁失败（mDNS 可能不稳）：${it.message}") }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private companion object {
        const val TAG = "SyncBroadcast"
        const val SERVICE_TYPE = "_shiyin-sync._tcp"
        const val SERVICE_PREFIX = "shiyin-"
        const val DEFAULT_SERVICE_NAME = "shiyin-android"

        /**
         * TXT 记录里的协议版本 = [SyncContract.SCHEMA_VERSION]。
         *
         * 原先这里另写了一个字面量 `2`（与契约常量各存一份），B4 升 v3 时正是这种重复定义
         * 最容易漏改的地方 —— 一旦两处漂移，发现阶段广播的版本与 `/sync/hello` 回报的版本会互相矛盾。
         * 改为直接引用单一真源：以后升版只需改 `SyncContract`。
         */
        const val SCHEMA_VERSION = SyncContract.SCHEMA_VERSION
        const val MULTICAST_LOCK_TAG = "shiyin-sync-mdns"
    }
}
