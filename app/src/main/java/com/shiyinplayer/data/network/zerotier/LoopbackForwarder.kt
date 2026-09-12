package com.shiyinplayer.data.network.zerotier

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地回环转发器（架构 §1.2.10 / T13）：对每个「远程 host:port」在 127.0.0.1 开随机端口，
 * accept 后经 [ZtSocketFactory] 建 libzt TCP 连接并双向泵转发。
 * 工厂不可用时（返回 null）直接关闭本地连接（降级，不对外抛错）。
 */
@Singleton
class LoopbackForwarder @Inject constructor(
    private val socketFactory: ZtSocketFactory
) {
    companion object {
        private const val TAG = "ZtLoopbackFwd"
        /** 最大并发转发连接数：保护 libzt 用户态栈不被高频连接压垮（连续切歌场景核心防护）。
         *  超过限制的新连接直接关闭本地端（降级），等旧连接释放后下次请求再建。 */
        private const val MAX_ACTIVE = 8
        /**
         * 转发连接总空闲超时（双向均无流量）：超过后主动关闭该连接，回收长期失活连接占用的
         * libzt 资源与并发槽位。播放缓冲慢/远程失活时，旧 SO_RCVTIMEO 空转会让连接无限滞留，
         * 长跑后槽位与 libzt 连接累积 → 数据面承压 → 节点不稳甚至掉线（"正常播放也偶尔 disconnect"）。
         */
        private const val IDLE_TIMEOUT_MS = 120_000L
    }

    private val servers = LinkedHashMap<Int, ForwarderServer>()
    private val active = CopyOnWriteArrayList<Pump>()

    @Synchronized
    fun open(remoteHost: String, remotePort: Int): Int {
        val ss = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val port = ss.localPort
        val server = ForwarderServer(ss, remoteHost, remotePort)
        servers[port] = server
        server.start()
        return port
    }

    @Synchronized
    fun close(localPort: Int) {
        servers.remove(localPort)?.close()
    }

    /**
     * 指定本地转发端口对应的 accept 服务是否仍存活。
     * 2026-08-29 修复（连续切歌 ECONNREFUSED 定位）：ForwarderServer.run 的 accept 循环一旦因
     * accept() 抛异常退出，finally 会关闭 ServerSocket，但 [servers]/PortMappingRegistry 缓存不会
     * 自动失效——此后 mapToLocal 持续返回一个已不监听的端口，OkHttp 连回环端口直接 Connection refused，
     * 播放器误判网络断开、连续跳歌。这里对外暴露「端口实际是否还在监听」判据，
     * 供 PortMappingRegistry 每次返回映射前校验，失效则重建，杜绝僵尸端口。
     */
    @Synchronized
    fun isAlive(localPort: Int): Boolean = servers.containsKey(localPort)

    /** accept 服务线程退出时把自身从 [servers] 移除（僵尸端口失效判据），幂等安全。 */
    @Synchronized
    private fun removeServer(localPort: Int) {
        servers.remove(localPort)
    }

    @Synchronized
    fun closeAll() {
        servers.values.forEach { runCatching { it.close() } }
        servers.clear()
        active.forEach { runCatching { it.close() } }
        active.clear()
    }

    /**
     * 单连接的转发泵：双向拷贝，任一端结束即关闭两端。
     * 2026-08-19 修复（真机 SIGSEGV）：libzt 的 read/shutdown 并发不安全——read 阻塞在
     * zts_bsd_read 时另一线程 close()（zts_bsd_shutdown）会释放内部 mutex，read 访问野指针崩溃。
     * 缓解：①remote 的 read 与 close 经 [readCloseLock] 互斥（配合 LibztZtSocketFactory 的
     * SO_RCVTIMEO=5s，read 不无限阻塞，close 等锁最多 5s）；②**write 用独立 [writeLock]**——
     * 绝不能与 read 共用锁（否则播放流 remote 读持续持锁会饿死 HTTP 请求写入 → 播放无响应）；
     * ③SocketTimeoutException 视为空闲继续循环（不退出连接，避免播放缓冲满时被误断）。
     */
    private inner class Pump(
        private val local: Socket,
        private val remote: ZtSocket
    ) : Thread("zt-forward"), Closeable {
        private val readCloseLock = Any()
        private val writeLock = Any()
        private val localIn: InputStream = local.getInputStream()
        private val localOut: OutputStream = local.getOutputStream()
        private val remoteIn: InputStream = remote.inputStream
        private val remoteOut: OutputStream = remote.outputStream
        @Volatile private var closed = false
        /** 最后一次双向任意方向流量的时间戳，用于空闲超时回收失活连接。 */
        @Volatile private var lastActiveMs = System.currentTimeMillis()

        private fun touch() {
            lastActiveMs = System.currentTimeMillis()
        }

        init {
            isDaemon = true
            active.add(this)
        }

        override fun run() {
            // 本地 -> libzt（HTTP 请求方向：本地读 + ZT 写）
            threadPump(localIn, remoteOut, fromRemote = false)
            // libzt -> 本地（响应方向：ZT 读 + 本地写）
            threadPump(remoteIn, localOut, fromRemote = true)
        }

        private fun threadPump(from: InputStream, to: OutputStream, fromRemote: Boolean) {
            val t = Thread {
                try {
                    val buf = ByteArray(64 * 1024)
                    while (!closed) {
                        val n = if (fromRemote) {
                            // ZT 读必须与 close 互斥（防 libzt SIGSEGV）；写走独立锁
                            try {
                                synchronized(readCloseLock) { from.read(buf) }
                            } catch (_: java.net.SocketTimeoutException) {
                                // 空闲读超时（播放缓冲满时 read 会空闲）：
                                // 双向仍活跃 → 继续循环不退出；超过 IDLE_TIMEOUT 无任何流量
                                // → 视为失活 stale 连接，主动退出回收 libzt 资源与并发槽位。
                                if (System.currentTimeMillis() - lastActiveMs > IDLE_TIMEOUT_MS) {
                                    android.util.Log.i(LoopbackForwarder.TAG,
                                        "转发连接空闲超时（>${IDLE_TIMEOUT_MS}ms 无流量），回收 libzt 连接")
                                    break
                                }
                                continue
                            }
                        } else {
                            from.read(buf)
                        }
                        if (n < 0) break
                        touch()
                        if (fromRemote) {
                            // 响应方向写本地（单写者无锁），避免与对向 ZT 写共享锁互相饿死
                            to.write(buf, 0, n); to.flush()
                        } else {
                            synchronized(writeLock) { to.write(buf, 0, n); to.flush() }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    close()
                }
            }
            t.isDaemon = true
            t.start()
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { local.close() }
            // remote 的 shutdown/close 与 read 串行化：等当前 read（≤5s 超时）释放锁后执行，杜绝并发
            synchronized(readCloseLock) {
                runCatching { remote.close() }
            }
            active.remove(this)
        }
    }

    /** 每个本地端口的 accept 循环。 */
    private inner class ForwarderServer(
        private val ss: ServerSocket,
        private val remoteHost: String,
        private val remotePort: Int
    ) : Thread("zt-accept-${ss.localPort}"), Closeable {

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                while (!ss.isClosed) {
                    val local = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    handle(local)
                }
            } finally {
                runCatching { ss.close() }
                // 2026-08-29 修复（连续切歌 ECONNREFUSED）：accept 循环因异常/关闭而退出后，
                // 该本地端口已不再监听。把它从 servers 缓存移除，使 isAlive(localPort) 返回 false，
                // PortMappingRegistry 下次返回前能识别该僵尸端口并重建，而不是一直给调用方一个
                // 已死端口触发 Connection refused。主动 close() 触发的退出同样走到这里，幂等安全。
                removeServer(ss.localPort)
            }
        }

        private fun handle(local: Socket) {
            // 2026-08-28 修复：并发连接数限流——连续切歌会在短时间内创建大量 ZT 连接，
            // libzt 用户态栈承载能力有限，过多并发会导致节点不稳定甚至掉线。
            // 超过 MAX_ACTIVE 时直接关闭本地端，调用方（OkHttp/播放器）会按普通网络错误处理，
            // 稍后重试时旧连接已释放，即可新建。
            if (active.size >= MAX_ACTIVE) {
                android.util.Log.w(LoopbackForwarder.TAG,
                    "fwd 超过并发上限 active=${active.size}/$MAX_ACTIVE，拒绝新连接 $remoteHost:$remotePort")
                runCatching { local.close() }
                return
            }
            val remote = socketFactory.connect(remoteHost, remotePort)
            if (remote == null) {
                android.util.Log.w(LoopbackForwarder.TAG, "fwd connect 失败 remoteHost=$remoteHost:$remotePort （ZT 通路未就绪）")
                runCatching { local.close() }
                return
            }
            android.util.Log.i(LoopbackForwarder.TAG, "fwd connect 成功 remoteHost=$remoteHost:$remotePort（已走 libzt 通路）")
            Pump(local, remote).start()
        }

        override fun close() {
            runCatching { ss.close() }
        }
    }
}