package com.shiyinplayer.data.network.zerotier

import com.zerotier.sockets.ZeroTierSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * libzt 私有 TCP socket 工厂（架构 §3.2）：经 ZeroTierSocket 连接虚拟网内地址。
 * 连接失败返回 null（调用方降级）。构造时无上下文依赖，可直接由 Hilt 提供。
 *
 * 2026-08-19 修复（真机 SIGSEGV：libzt lwip_recvfrom→sys_arch_mbox_fetch→pthread_mutex_lock 野指针）：
 * libzt 的 read/shutdown 并发不安全——播放流阻塞在 zts_bsd_read 时对端 close()（zts_bsd_shutdown）
 * 会释放内部 mbox mutex，read 访问已释放 mutex → 崩溃（fault addr 0xffffffffffffed00）。
 * 缓解：setSoTimeout(SO_RCVTIMEO=5s) 让 read 不无限阻塞，配合 LoopbackForwarder 的 per-socket 锁
 * 串行化 read/write/close，消除 close 与 read 的并发窗口。
 */
class LibztZtSocketFactory : ZtSocketFactory {

    companion object {
        /** 读超时（ms）：正常播放数据持续到达不会触发；空闲 5s 让 read 释放锁供 close 安全执行。 */
        private const val SO_RCVTIMEO_MS = 5_000
    }

    override fun connect(host: String, port: Int): ZtSocket? {
        return try {
            val socket = ZeroTierSocket(host, port)
            // 2026-08-19：必须设读超时——否则 read 永久阻塞，close 永远拿不到锁（见 LoopbackForwarder）
            socket.setSoTimeout(SO_RCVTIMEO_MS)
            LibztSocket(socket)
        } catch (e: IOException) {
            null
        } catch (t: Throwable) {
            // UnsatisfiedLinkError（缺 libzt.so）等一律视为不可用。
            null
        }
    }

    private class LibztSocket(
        private val socket: ZeroTierSocket
    ) : ZtSocket {
        override val inputStream: InputStream
            get() = socket.getInputStream()
        override val outputStream: OutputStream
            get() = socket.getOutputStream()
        override fun close() {
            runCatching { socket.close() }
        }
    }
}
