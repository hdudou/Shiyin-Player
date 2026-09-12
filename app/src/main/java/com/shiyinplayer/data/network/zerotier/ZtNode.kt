package com.shiyinplayer.data.network.zerotier

import android.util.Log
import com.zerotier.sockets.ZeroTierEventListener
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierNode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * libzt 节点事件（架构 §3.2 ZtNode.events()）。
 */
sealed class ZtEvent {
    object NodeReady : ZtEvent()
    object NetworkReady : ZtEvent()
    /** BJ-节点下线：node 网络掉线/重启，上层据此刻度状态、清虚拟地址与端口映射缓存。 */
    object NodeOffline : ZtEvent()
    data class VirtualIp(val ip: String) : ZtEvent()
    /** msg：错误描述；retriable=false 表示人工干预才可恢复（未批准/身份冲突/缺库），不应自动重连。 */
    data class Error(val msg: String, val retriable: Boolean = true) : ZtEvent()
}

/**
 * libzt 节点封装（架构 §1.2.10 / T13）。
 *
 * 使用官方 Java 绑定 [ZeroTierNode]（JNI 库名 "zt" → libzt.so）：
 * initFromStorage + initSetEventHandler + start 启动序列；join(long) 加入网络；
 * onZeroTierEvent 回调解析事件码驱动 [ZtEvent]。网络 ID 为 16 进制字符串，join 时转 long。
 */
@Singleton
class ZtNode @Inject constructor() {
    private val _events = MutableSharedFlow<ZtEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ZtEvent> = _events.asSharedFlow()

    private var node: ZeroTierNode? = null
    /** 生命周期互斥：串行化 start/stop，避免并发 stop/start 触发 libzt 内部 SIGABRT。 */
    private val lock = Any()
    var nativeAvailable: Boolean = false
        private set

    /** 节点是否已上线（收到 NODE_ONLINE）。上线前 join 会返回 ZTS_ERR_SERVICE(-2)。 */
    private var nodeOnline = false

    /** 节点是否在线上。供 ZeroTierManager 做「已连接收敛」判断（nodeOnline=true 且已持有 IP）。 */
    val isOnline: Boolean get() = nodeOnline

    /**
     * 查询当前已分配的网络虚拟 IPv4（供 Manager 在 join 后做「已连接收敛」；
     * 节点早已在线时会直接复用现网 IP，不会重发 ADDR_ADDED_IP4 事件，只能主动查询）。
     * 无此地址返回 null。
     */
    fun currentIp(): String? {
        val n = node ?: return null
        val netId = pendingNetworkId ?: return null
        val ip = runCatching { n.getIPv4Address(netId)?.hostAddress }.getOrNull() ?: return null
        return if (isUsableVirtualIp(ip)) ip else null
    }

    /** 待上线后补加入的网络 ID。 */
    private var pendingNetworkId: Long? = null

    fun start(configDir: File): Boolean {
        synchronized(lock) {
        // 已启动（重复点击加入）直接复用，避免重复初始化
        if (nativeAvailable && node != null) return true
        nativeAvailable = runCatching {
            configDir.mkdirs()
            // 触发 ZeroTierNative 静态初始化（System.loadLibrary("zt")）
            Class.forName("com.zerotier.sockets.ZeroTierNative")
            val n = ZeroTierNode()
            n.initFromStorage(configDir.absolutePath)
            n.initSetEventHandler(object : ZeroTierEventListener {
                override fun onZeroTierEvent(id: Long, eventCode: Int) {
                    // 诊断（2026-08-21）：观测 peer 通路建立情况——拿到虚拟 IP 只说明已入网并
                    // 分配地址，能否到达目标 peer 是独立数据面问题，由 PEER_* 事件决定。
                    logEvent(id, eventCode)
                    when (eventCode) {
                        ZeroTierNative.ZTS_EVENT_NODE_ONLINE -> {
                            nodeOnline = true
                            // 上线后再执行补加入（提前 join 会返回 -2）
                            pendingNetworkId?.let { nid -> joinNow(nid) }
                            _events.tryEmit(ZtEvent.NodeReady)
                        }

                        202 -> { // 202 = ZTS_EVENT_NODE_OFFLINE（绑定类常量不可靠，按 EVENT_NAMES 数字码判断）
                            nodeOnline = false
                            // BJ-节点下线：让上层及时置 DISCONNECTED、清虚拟地址与端口映射缓存
                            _events.tryEmit(ZtEvent.NodeOffline)
                        }

                        ZeroTierNative.ZTS_EVENT_NETWORK_READY_IP4,
                        ZeroTierNative.ZTS_EVENT_NETWORK_READY_IP4_IP6,
                        ZeroTierNative.ZTS_EVENT_ADDR_ADDED_IP4 -> {
                            // 事件回调的 id 即 networkId（long），据此取虚拟 IPv4。
                            // 有 IP 时只发 VirtualIp（经理层据此置 CONNECTED），
                            // 避免紧随其后的 NetworkReady 把状态又降回 CONNECTING。
                            val ip = n.getIPv4Address(id)?.hostAddress
                            if (ip != null && isUsableVirtualIp(ip)) {
                                _events.tryEmit(ZtEvent.VirtualIp(ip))
                            } else {
                                _events.tryEmit(ZtEvent.NetworkReady)
                            }
                        }

                        ZeroTierNative.ZTS_EVENT_NETWORK_ACCESS_DENIED ->
                            _events.tryEmit(ZtEvent.Error("ZeroTier 控制台未批准本节点，请到 my.zerotier.com 批准", retriable = false))

                        ZeroTierNative.ZTS_EVENT_NODE_FATAL_ERROR -> {
                            nodeOnline = false
                            _events.tryEmit(ZtEvent.Error("ZeroTier 节点致命错误（身份冲突？请重置身份）", retriable = false))
                        }
                    }
                }
            })
            val rc = n.start()
            // P1-3：仅启动成功（rc==0）才登记节点；失败保持 node=null，nativeAvailable 不谎报。
            if (rc == 0) node = n
            rc == 0
        }.getOrDefault(false) && node != null
        return nativeAvailable
        }
    }

    fun stop() {
        synchronized(lock) {
            runCatching { node?.stop() }
            node = null
            nativeAvailable = false
            nodeOnline = false
            pendingNetworkId = null
        }
    }

    fun join(networkId: String) {
        if (!nativeAvailable) {
            _events.tryEmit(ZtEvent.Error("libzt 原生库缺失，ZeroTier 暂不可用（降级模式）", retriable = false))
            return
        }
        val nid = parseNetworkId(networkId) ?: run {
            _events.tryEmit(ZtEvent.Error("网络 ID 无效：应为 16 位十六进制网络 ID", retriable = false))
            return
        }
        pendingNetworkId = nid
        // 已上线直接加入；未上线则等 NODE_ONLINE 事件后补加入，避免 -2 误报。
        if (nodeOnline) joinNow(nid)
    }

    /** 实际调用 zts_net_join 并上报失败结果。 */
    private fun joinNow(nid: Long) {
        val rc = runCatching { node?.join(nid) ?: ZeroTierNative.ZTS_ERR_SERVICE }.getOrDefault(ZeroTierNative.ZTS_ERR_SERVICE)
        if (rc != ZeroTierNative.ZTS_ERR_OK) {
            pendingNetworkId = null
            _events.tryEmit(ZtEvent.Error("加入网络失败（错误码 $rc）：请确认网络 ID 与网络状态"))
        }
    }

    fun leave(networkId: String) {
        val nid = parseNetworkId(networkId)
        if (nid != null && nid == pendingNetworkId) pendingNetworkId = null
        nid?.let { runCatching { node?.leave(it) } }
    }

    /**
     * 诊断打点（2026-08-21）：记录 node id / 是否在线 / 网络传输就绪 + 全部事件码的可读名，
     * 用于定位「已入网拿 IP 但到 peer 不通」的数据面问题。
     */
    private fun logEvent(id: Long, eventCode: Int) {
        val name = eventName(eventCode)
        // ip 形如 "0x9f77fc39 0x... /16"，事件 id 在 PEER/ADDR/STORE 事件上含义不同
        val nodeId = runCatching { node?.getId()?.toString(16) }.getOrNull()
        val online = runCatching { node?.isOnline() }.getOrNull()
        Log.i(TAG, "zt[ev] $name (code=$eventCode, id=${id.toString(16)}) nodeId=$nodeId online=$online")
    }

    companion object {
        private const val TAG = "ZtNodeDiag"

        /**
         * 过滤 libzt 上报的「非真实虚拟 IPv4」：节点早期（尚未 join/上线）会先上报 id=0 的
         * 地址，此时 getIPv4Address 返回 IPv6 环回占位 `::1`；若将其当虚拟 IP 会导致冷启动
         * 首屏假 CONNECTED、且 currentIp 收敛误判。要求是普通点分 IPv4 且非环回/未指定。
         */
        private fun isUsableVirtualIp(ip: String): Boolean {
            val t = ip.trim()
            if (t.isEmpty() || t.startsWith("::") || t.startsWith("127.") || t == "0.0.0.0") return false
            val parts = t.split(".")
            if (parts.size != 4) return false
            return parts.all { it.toIntOrNull()?.let { v -> v in 0..255 } == true }
        }
        private val EVENT_NAMES = mapOf(
            200 to "NODE_UP", 201 to "NODE_ONLINE", 202 to "NODE_OFFLINE", 203 to "NODE_DOWN", 204 to "NODE_FATAL_ERROR",
            210 to "NETWORK_NOT_FOUND", 211 to "NETWORK_CLIENT_TOO_OLD", 212 to "NETWORK_REQ_CONFIG",
            213 to "NETWORK_OK", 214 to "NETWORK_ACCESS_DENIED", 215 to "NETWORK_READY_IP4",
            216 to "NETWORK_READY_IP6", 217 to "NETWORK_READY_IP4_IP6", 218 to "NETWORK_DOWN", 219 to "NETWORK_UPDATE",
            220 to "STACK_UP", 221 to "STACK_DOWN",
            230 to "NETIF_UP", 231 to "NETIF_DOWN", 232 to "NETIF_REMOVED", 233 to "NETIF_LINK_UP", 234 to "NETIF_LINK_DOWN",
            240 to "PEER_DIRECT", 241 to "PEER_RELAY", 242 to "PEER_UNREACHABLE",
            243 to "PEER_PATH_DISCOVERED", 244 to "PEER_PATH_DEAD",
            250 to "ROUTE_ADDED", 251 to "ROUTE_REMOVED",
            260 to "ADDR_ADDED_IP4", 261 to "ADDR_REMOVED_IP4", 262 to "ADDR_ADDED_IP6", 263 to "ADDR_REMOVED_IP6",
            270 to "STORE_IDENTITY_SECRET", 271 to "STORE_IDENTITY_PUBLIC", 272 to "STORE_PLANET",
            273 to "STORE_PEER", 274 to "STORE_NETWORK"
        )
    }

    private fun eventName(code: Int): String = EVENT_NAMES[code] ?: "UNKNOWN($code)"

    /**
     * ZeroTier 网络 ID 为无符号 64 位十六进制（16 位）。Kotlin 的 toLongOrNull(16) 在有符号
     * Long 溢出（首字符 >= 8，如 9f77...）时返回 null 导致解析失败，故用 BigInteger 解析后按
     * 2^64 取模转为 long（二进制补码），与 libzt 原生层按 uint64 解读一致。
     */
    private fun parseNetworkId(networkId: String): Long? {
        val clean = networkId.trim()
        if (clean.length != 16) return null
        return runCatching { BigInteger(clean, 16).toLong() }.getOrNull()
    }
}
