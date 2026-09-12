package com.shiyinplayer.data.network.zerotier

import android.content.Context
import android.util.Log
import com.shiyinplayer.data.network.NetworkKind
import com.shiyinplayer.data.network.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

import javax.inject.Inject
import javax.inject.Singleton

/**
 * ZeroTier 门面（架构 §3.2 / T13）：持有 ZtNode + 转发器 + 端口注册表，
 * 对外暴露 join/leave/status/virtualIp/mapToLocal。
 *
 * 虚拟地址 → 127.0.0.1 本地端口映射（仅对 10.x 虚拟网段生效），普通公网/局域网原样返回。
 * 无 VpnService；转发端口仅绑回环。缺 libzt.so 时走降级（见 ZtNode）。
 */
@Singleton
class ZeroTierManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val node: ZtNode,
    @Suppress("unused") private val config: ZeroTierConfig,
    private val forwarder: LoopbackForwarder,
    private val registry: PortMappingRegistry,
    private val networkMonitor: NetworkMonitor
) {
    private val _status = MutableStateFlow(ZtStatus.DISCONNECTED)
    val status: StateFlow<ZtStatus> = _status.asStateFlow()

    private val _virtualIp = MutableStateFlow<String?>(null)
    val virtualIp: StateFlow<String?> = _virtualIp.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "ZeroTierMgr"
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L
        /** 2026-08-19：join 后若此时间内仍未 CONNECTED 则强制重连一次（启动瞬间网络/planet 未就绪的兜底）。 */
        private const val CONNECT_TIMEOUT_MS = 30_000L
        /** P2-3：本机子网表缓存时长（网络切换后最多 30s 内刷新）。 */
        private const val SUBNET_CACHE_TTL_MS = 30_000L
        /** 最大并发转发连接数：保护 libzt 不被高频连接压垮（连续切歌场景的关键防护）。 */
        private const val MAX_CONCURRENT_FORWARDS = 8
        /**
         * 致命错误冷却窗口：retriable=false（身份冲突/致命错误等需人工干预）后这段时间内
         * 不自动重连，避免提示「请人工处理」后立刻又猛连；窗口期满恢复自愈，杜绝永久卡死。
         */
        private const val FATAL_COOLDOWN_MS = 90_000L
    }

    // 致命错误冷却截止时间戳：进入冷却窗口（=now+FATAL_COOLDOWN_MS）期间跳过自动重连
    //（尊重「需人工干预」的提示），窗口期满后自动恢复自愈能力，避免一次非重试错误永久卡死。
    @Volatile private var fatalCooldownUntilMs = 0L
    private val canAutoReconnectNow: Boolean
        get() = System.currentTimeMillis() >= fatalCooldownUntilMs

    init {
        scope.launch { config.autoReconnect.collect { autoReconnect = it } }
        // 网络切换感知：WiFi↔Cellular 切换时立即触发重连 + 清子网缓存，消除 60s 盲区
        scope.launch {
            var lastKind: NetworkKind? = null
            networkMonitor.network.collect { kind ->
                if (lastKind != null && lastKind != kind && kind != NetworkKind.NONE) {
                    Log.i(TAG, "网络切换 $lastKind → $kind，触发 ZT 重连 + 清子网缓存")
                    invalidateSubnetCache()
                    if (currentNetworkId != null && _status.value != ZtStatus.CONNECTING) {
                        scheduleReconnect()
                    }
                }
                lastKind = kind
            }
        }
        // 保活巡检：缩短到 15 秒，快速发现掉线（原 60 秒太慢）
        var connectingSinceMs = 0L
        scope.launch {
            while (true) {
                delay(15_000)
                val st = _status.value
                if (currentNetworkId == null || !canAutoReconnectNow) continue
                when {
                    st == ZtStatus.DISCONNECTED -> {
                        Log.i(TAG, "15s 巡检发现掉线，触发自动重连")
                        scheduleReconnect()
                    }
                    st == ZtStatus.CONNECTING -> {
                        val now = System.currentTimeMillis()
                        if (connectingSinceMs == 0L) {
                            connectingSinceMs = now
                        } else if (now - connectingSinceMs > 120_000L) {
                            Log.w(TAG, "15s 巡检：CONNECTING 已卡死 >120s，触发重建")
                            connectingSinceMs = 0L
                            scheduleReconnect()
                        }
                    }
                    st == ZtStatus.CONNECTED -> {
                        connectingSinceMs = 0L
                    }
                }
            }
        }
        scope.launch {
            node.events.collect { ev ->
                when (ev) {
                    // 2026-08-21 修复：NodeReady/NetworkReady 仅在 DISCONNECTED 时置 CONNECTING，
                    // 避免已 CONNECTED 后事件重发（重连/网络波动）把状态降级为 CONNECTING 而
                    // 无后续 VirtualIp 事件 → 永久 CONNECTING（mapToLocal 曾以此门控导致 ZT 源不可用）。
                    //
                    // 2026-08-28 修复：节点从离线自动恢复（NodeOffline → NodeReady）的场景下，
                    // IP 没变则 ADDR_ADDED_IP4 事件不重发，状态会卡 CONNECTING。
                    // 因此在置 CONNECTING 后立即启动主动轮询 + 看门狗双保险，
                    // 确保 IP 就绪后（即使事件丢失）也能及时收敛为 CONNECTED。
                    ZtEvent.NodeReady -> {
                        if (_status.value == ZtStatus.DISCONNECTED) {
                            _status.value = ZtStatus.CONNECTING
                            launchConvergeWatchdog()
                        } else if (_status.value == ZtStatus.CONNECTING) {
                            // 2026-08-30 修复：节点从离线恢复到在线（NodeOffline→NodeReady），
                            // 状态已为 CONNECTING（由 scheduleReconnect 置入），但其发起的收敛看门狗
                            // 可能已被后续 scheduleReconnect 调用取消。此处主动重新启动收敛轮询，
                            // 确保节点就绪后即使事件丢失也能及时拿到 IP 并收敛为 CONNECTED。
                            launchConvergeWatchdog()
                        }
                    }
                    ZtEvent.NetworkReady -> {
                        if (_status.value == ZtStatus.DISCONNECTED) {
                            _status.value = ZtStatus.CONNECTING
                            launchConvergeWatchdog()
                        } else if (_status.value == ZtStatus.CONNECTING) {
                            launchConvergeWatchdog()
                        }
                    }
                    is ZtEvent.VirtualIp -> {
                        _virtualIp.value = ev.ip
                        _status.value = ZtStatus.CONNECTED
                        _error.value = null
                        fatalCooldownUntilMs = 0 // 连接成功 → 清除致命错误冷却
                        reconnectBackoffMs = RECONNECT_DELAY_MS // 连接成功 → 重置退避
                        Log.i(TAG, "CONNECTED ip=${ev.ip}（重建节点后已收敛）")
                    }
                    ZtEvent.NodeOffline -> {
                        // 关键回归修复（对比 8/27 基线定位）：libzt 在当前环境会周期性上报 NODE_OFFLINE，
                        // 但**数据面不影响**——实测节点离线后新的 fwd 转发连接仍持续建立成功，libzt 本身
                        // 会自行维持/恢复内部栈。基线（8/27 前）**完全不处理**该事件：不置 DISCONNECTED、
                        // 不清 virtualIp/映射/转发器、从不重建节点 → 播放无缝、UI 稳定，正是"前两天 ZT
                        // 正常、这两天不正常"所对应的健康行为。8/28 起新增的破坏性处理（置 DISCONNECTED
                        // + 清映射 + stop/重建节点）把底层上报"放大"成反复掉线、打断播放；重建节点还会
                        // 在转发泵运行中 stop libzt 引发更严重问题。
                        // 修复：彻底恢复基线语义——此处对 NODE_OFFLINE 完全不采取动作，让数据面自行维持，
                        // 绝不能在这里触发任何重建。
                        _error.value = null
                        Log.i(TAG, "NODE_OFFLINE（数据面不受影响，保持 CONNECTED 不干预，交由 libzt 内部自愈）")
                    }
                    is ZtEvent.Error -> {
                        _status.value = ZtStatus.DISCONNECTED
                        _virtualIp.value = null
                        _error.value = ev.msg
                        Log.w(TAG, "ZT Error: ${ev.msg} retriable=${ev.retriable}")
                        // 瞬时故障（网络掉线/临时失败）自动重连；需人工干预的错误进入冷却窗口，
                        // 期间不重连，窗口期满后恢复自愈（避免一次非重试错误永久卡死）。
                        if (ev.retriable) {
                            fatalCooldownUntilMs = 0
                            scheduleReconnect()
                        } else {
                            fatalCooldownUntilMs = System.currentTimeMillis() + FATAL_COOLDOWN_MS
                        }
                    }
                }
            }
        }
    }

    private var currentNetworkId: String? = null
    // 初始值与配置默认值（true）保持一致，避免 DataStore 首次发射前的窗口内
    // scheduleReconnect() 误判为 false 导致看门狗重连失效（升级后首次启动卡 CONNECTING 的根因之一）。
    private var autoReconnect = true
    private var reconnectJob: Job? = null
    /** 指数退避：连续重连失败时延迟递增，避免频繁重连加剧 libzt 压力导致震荡。 */
    private var reconnectBackoffMs = RECONNECT_DELAY_MS

    /** 启动自动连接（R2-03）：自动加入已保存网络，免手动操作。不再预置默认测试网络 ID（用户手动输入）。 */
    suspend fun autoConnect() {
        val id = runCatching { config.networkId.first() }.getOrNull()
        if (!id.isNullOrBlank()) joinNetwork(id)
    }

    /** 保活重连（R2-03）：指数退避延迟后重新 start + join，避免反复失败时忙等/震荡。 */
    private fun scheduleReconnect() {
        if (!autoReconnect) return
        val id = currentNetworkId ?: return
        reconnectJob?.cancel()
        val delayMs = reconnectBackoffMs
        // 指数退避：下次重连延迟翻倍，封顶 60s
        reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectJob = scope.launch {
            delay(delayMs)
            _error.value = null
            // 先检查节点是否已在线且持有虚拟 IP——如果节点已自动恢复
            // （如 NodeOffline→NodeReady 自动重连），直接收敛为 CONNECTED 即可，
            // 无需再调 join。对已入网节点重复 join 会返回错误码，触发 Error 事件
            // → DISCONNECTED → 又一次 scheduleReconnect → 收敛置 CONNECTED → 循环震荡。
            val ip = node.currentIp()
            if (node.isOnline && ip != null) {
                _virtualIp.value = ip
                _status.value = ZtStatus.CONNECTED
                fatalCooldownUntilMs = 0
                reconnectBackoffMs = RECONNECT_DELAY_MS // 成功 → 重置退避
                Log.i(TAG, "scheduleReconnect 已在线并持有 IP=$ip，直接收敛 CONNECTED")
                return@launch
            }
            // 2026-08-30 修复：节点在线但无 IP 的中间态——
            // 可能是 join 后 IP 尚未分配（libzt 内部 DHCP/地址分配延迟），
            // 此时不应 stop+start（会打断正在建立的连接），而是重新 join 并启动收敛轮询，
            // 让 libzt 重新尝试地址分配。
            if (node.isOnline && ip == null) {
                Log.w(TAG, "重连：节点在线但无 IP，重新 join 并等待地址分配")
                node.join(id)
                launchConvergeWatchdog()
                return@launch
            }
            // 节点离线状态下重连：先 stop 再 start——
            // 如果 NodeOffline 是 libzt 内部崩溃导致的（如高频连接压垮），
            // node.start() 的幂等保护（nativeAvailable && node != null → 直接返回）
            // 会让重连失效：节点实例还在但实际上已经死了，永远不会再上线。
            // 必须 stop 清理旧实例，让 start 重建一个全新的节点。
            Log.w(TAG, "重连：节点离线，stop 重建 libzt（清理死节点实例）")
            forwarder.closeAll()
            registry.clear()
            node.stop()
            val ok = node.start(configDir())
            if (ok) {
                node.join(id)
                launchConvergeWatchdog()
            } else {
                Log.e(TAG, "重连失败：libzt start 返回异常")
            }
        }
    }

    /** 统一启动「主动轮询收敛 + 看门狗兜底」双保险。
     *  在任何进入 CONNECTING 状态的路径上都应调用，确保 IP 就绪后（即使事件丢失）能及时收敛。
     *
     *  2026-08-30 修复：原轮询仅 5 次×1s=5 秒，节点冷启动/重建后拿 IP 通常需要 8-15 秒，
     *  5 秒内拿不到 → 看门狗 30s 超时 → scheduleReconnect → stop+start 重建节点 → 又 5 秒拿不到
     *  → 循环震荡（正是「播放几分钟后 ZT 卡 CONNECTING 无法恢复」的根因）。
     *  改为：主动轮询 30 次×1s=30 秒（覆盖节点冷启动最大延迟）；看门狗超时后不再盲目重建节点，
     *  而是先检查节点是否已在线持有 IP（可能事件丢失但节点实际已就绪），仅在确认未就绪时才重连，
     *  并用渐进退避避免震荡。 */
    private var pollJob: Job? = null
    private var watchdogJob: Job? = null
    private fun launchConvergeWatchdog() {
        pollJob?.cancel()
        pollJob = scope.launch {
            // 30 次×1s：覆盖节点冷启动/重建后获取 IP 的最大延迟（实测 8-15 秒，留足余量）
            repeat(30) {
                delay(1_000L)
                if (_status.value == ZtStatus.CONNECTED) return@launch
                // 前置检查：节点未上线时不盲目取 IP（减少无效 JNI 调用）
                if (!node.isOnline) return@repeat
                val ip = node.currentIp()
                if (ip != null) {
                    _virtualIp.value = ip
                    _status.value = ZtStatus.CONNECTED
                    _error.value = null
                    fatalCooldownUntilMs = 0
                    reconnectBackoffMs = RECONNECT_DELAY_MS
                    Log.i(TAG, "收敛轮询：已获取 IP=$ip，CONNECTED")
                    return@launch
                }
            }
            // 30 秒轮询结束仍未拿到 IP，进入看门狗阶段
            Log.w(TAG, "收敛轮询 30s 未拿到 IP，进入看门狗兜底")
        }
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            // 看门狗延迟：给轮询 30 秒 + 额外 30 秒缓冲 = 60 秒后检查
            delay(CONNECT_TIMEOUT_MS + 30_000L)
            if (_status.value != ZtStatus.CONNECTED && currentNetworkId != null) {
                // 关键修复：不盲目重建节点，先做最终检查——
                // 节点可能在看门狗等待期间已拿到 IP（事件丢失但数据面已就绪），
                // 此时直接收敛即可，stop+start 反而会打断已建立的连接。
                if (node.isOnline) {
                    val ip = node.currentIp()
                    if (ip != null) {
                        _virtualIp.value = ip
                        _status.value = ZtStatus.CONNECTED
                        _error.value = null
                        fatalCooldownUntilMs = 0
                        reconnectBackoffMs = RECONNECT_DELAY_MS
                        Log.i(TAG, "看门狗兜底：节点已在线并持有 IP=$ip，直接收敛 CONNECTED")
                        return@launch
                    }
                    // 节点在线但无 IP：可能是 libzt 内部状态异常（事件通道断裂），
                    // 需要重建节点。但先等退避窗口，避免频繁重建加剧震荡。
                    Log.w(TAG, "看门狗：节点在线但无 IP（事件通道可能断裂），等待退避后重建")
                }
                if (canAutoReconnectNow) {
                    Log.w(TAG, "收敛看门狗 60s 未 CONNECTED，触发重连")
                    scheduleReconnect()
                }
            }
        }
    }

    fun joinNetwork(networkId: String) {
        // node.start() 在 libzt 内可能阻塞/长时运行，必须在 IO 协程执行，避免 ANR
        scope.launch {
            _error.value = null
            fatalCooldownUntilMs = 0   // 用户手动操作 → 立即恢复可重试，清致命错误冷却
            currentNetworkId = networkId
            // 收敛（2026-08-23 修复）：节点二次 join 时已在线且现网已分配到虚拟 IP，libzt 不会
            // 重发 ADDR_ADDED_IP4 事件，若仍先置 CONNECTING 会永久卡死在该状态（用户已确认控制台
            // 授权过，拿到 IP 后却在 UI 一直显示「连接中」）。故启动/复用前先主动查询一次收敛。
            val ip = node.currentIp()
            if (node.isOnline && ip != null) {
                _virtualIp.value = ip
                _status.value = ZtStatus.CONNECTED
                return@launch
            }
            _status.value = ZtStatus.CONNECTING
            val ok = node.start(configDir())
            if (ok) {
                node.join(networkId)
                launchConvergeWatchdog()
            } else {
                // libzt 原生库缺失（降级）：不能停在 CONNECTING 谎报已连接，
                // 必须复位为 DISCONNECTED 并给出明确错误，避免 UI 永久「连接中」。
                _status.value = ZtStatus.DISCONNECTED
                currentNetworkId = null
                _error.value = "libzt 原生库缺失，ZeroTier 暂不可用（降级模式）"
            }
        }
    }

    fun leaveNetwork() {
        scope.launch {
            watchdogJob?.cancel()
            pollJob?.cancel()
            node.leave(currentNetworkId ?: "")
            _status.value = ZtStatus.DISCONNECTED
            _virtualIp.value = null
            _error.value = null
            currentNetworkId = null
            registry.clear()
        }
    }

    /**
     * 虚拟地址 → 127.0.0.1 本地端口映射（仅对 ZeroTier 虚拟网段目标生效）：
     * 当节点已连接时，把「与当前虚拟 IP 同 /24 网段且不在本机物理子网内」的目标经回环转发器走 libzt 私网路由；
     * 本机局域网 / 回环 / 公网地址原样返回（走系统网络栈）。
     *
     * 注（2026-08-17 修复）：原 isPrivateIp 把所有 RFC1918 私网（10.x/192.168.x/172.16-31.x）视为 ZT 目标，
     * 会把局域网内其他设备（如 192.168.1.100 的 NAS）误经 ZT 转发导致连接失败。
     * 现收窄为 isZeroTierSubnet：仅当目标与虚拟 IP 同 /24 网段才转发。详见 PROJECT_STATUS §五 P1 #9。
     */
    fun mapToLocal(host: String, port: Int): InetSocketAddress {
        // L1（2026-08-22，AIMP 对照）系统路由优先：
        // 目标是本机物理子网，或系统 ZeroTier 客户端（VpnService）持有该网段时，操作系统内核
        // 自己就能路由到它（AIMP 即靠系统 ZT 虚拟网卡/内核路由直连）。此时必须**原样返回 host、
        // 走系统路由栈**，绝不能包给内嵌 libzt 回环转发器去竞争/绕路——否则内嵌节点数据面不稳
        // 会把本来直连可通的 ZT 源整成超时（"ZT 源无法播放"）。
        // 注意：内嵌 libzt 是用户态栈、不建内核网卡，故「系统是否已有网卡持有该网段」是区分
        // 「走系统路由」与「必须经内嵌转发」的唯一可靠判据（isLocalSubnetHost 即扫描内核网卡）。
        if (isLocalSubnetHost(host)) return InetSocketAddress(host, port)
        // 内嵌 libzt 兜底：仅当系统无该网段路由、且目标是 ZT 虚拟网段时，才映射到本机回环转发器。
        // 以「是否已分配虚拟 IP」为准（状态 CONNECTING 也应转发）：虚拟 IP 在即 libzt 已 join 且地址可用。
        if (virtualIp.value == null) return InetSocketAddress(host, port)
        if (isZeroTierSubnet(host)) {
            // createUnresolved 避免 getHostName() 反向解析回 "localhost"
            return InetSocketAddress.createUnresolved("127.0.0.1", registry.getOrOpen(host, port))
        }
        return InetSocketAddress(host, port)
    }

    /** 判断目标是否属于 ZeroTier 虚拟网段（2026-08-19 修复：/8 太宽会误伤局域网）。
     *  libzt 用户态绑定未暴露网络前缀长度、也无虚拟网卡可扫掩码，故按 /24 精确匹配：
     *  仅当目标与虚拟 IP 同前三段（/24）才视为 ZT 网段。
     *  旧实现按第一段（/8）匹配，会把同段局域网设备（如同网段的 NAS vs ZT 虚拟网 IP）
     *  误映射到 127.0.0.1 回环转发 → WebDAV/SMB 连接全部超时卡死。 */
    private fun isZeroTierSubnet(host: String): Boolean {
        val vip = virtualIp.value ?: return false
        val vParts = vip.split(".")
        val hParts = host.split(".")
        if (vParts.size != 4 || hParts.size != 4) return false
        if (hParts.any { it.toIntOrNull() == null }) return false
        return vParts[0] == hParts[0] && vParts[1] == hParts[1] && vParts[2] == hParts[2]
    }

    // P2-3：本机子网表缓存（(地址, 掩码) 列表），TTL 30s，避免每次 open 都做 DNS + 网卡枚举
    private val subnetLock = Any()
    @Volatile private var cachedSubnets: List<Pair<Int, Int>>? = null
    @Volatile private var cachedSubnetsAt = 0L

    /** 网络切换时立即清空子网缓存，确保下一次 mapToLocal 用最新网卡信息判断 */
    private fun invalidateSubnetCache() {
        synchronized(subnetLock) {
            cachedSubnets = null
            cachedSubnetsAt = 0L
        }
    }

    private fun localSubnets(): List<Pair<Int, Int>> {
        val now = System.currentTimeMillis()
        cachedSubnets?.let { if (now - cachedSubnetsAt < SUBNET_CACHE_TTL_MS) return it }
        synchronized(subnetLock) {
            cachedSubnets?.let { if (System.currentTimeMillis() - cachedSubnetsAt < SUBNET_CACHE_TTL_MS) return it }
            val list = runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { nif ->
                        nif.interfaceAddresses.mapNotNull { ia ->
                            val addr = ia.address?.address ?: return@mapNotNull null
                            if (addr.size != 4) return@mapNotNull null
                            val prefix = ia.networkPrefixLength.toInt().coerceAtMost(32)
                            val mask = if (prefix == 32) -1 else ((1 shl prefix) - 1) shl (32 - prefix)
                            ipToInt(addr) to mask
                        }
                    }
            }.getOrDefault(emptyList())
            cachedSubnets = list
            cachedSubnetsAt = System.currentTimeMillis()
            return list
        }
    }

    private fun isLocalSubnetHost(host: String): Boolean {
        // P2-3：IP 字面量直接解析（不触发 DNS），主机名才走 getByName
        val target = parseIpLiteral(host)
            ?: runCatching { InetAddress.getByName(host).address }.getOrNull()
            ?.takeIf { it.size == 4 } ?: return false
        val t = ipToInt(target)
        return localSubnets().any { (addr, mask) -> (t and mask) == (addr and mask) }
    }

    /** 纯 IPv4 字面量解析；非字面量返回 null。 */
    private fun parseIpLiteral(host: String): ByteArray? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            bytes[i] = v.toByte()
        }
        return bytes
    }

    private fun ipToInt(a: ByteArray): Int =
        ((a[0].toInt() and 0xFF) shl 24) or ((a[1].toInt() and 0xFF) shl 16) or
            ((a[2].toInt() and 0xFF) shl 8) or (a[3].toInt() and 0xFF)

    fun stop() {
        reconnectJob?.cancel(); reconnectJob = null
        watchdogJob?.cancel()
        pollJob?.cancel()
        node.stop()
        forwarder.closeAll()
        registry.clear()
        _status.value = ZtStatus.DISCONNECTED
        _virtualIp.value = null
    }

    private fun configDir(): File = File(context.filesDir, "zerotier")
}
