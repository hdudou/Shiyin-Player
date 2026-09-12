package com.shiyinplayer.data.network.zerotier

import android.util.Log
import javax.inject.Inject

/**
 * 端口映射注册表（架构 §1.2.10 / T13）：维护「远程地址 → 本地端口」缓存，
 * 懒创建、失效重建（ZtStatus 变化时全量失效）。
 */
class PortMappingRegistry @Inject constructor(
    private val forwarder: LoopbackForwarder
) {
    companion object {
        private const val TAG = "ZtPortRegistry"
    }
    private val map = LinkedHashMap<String, Int>()

    /** P2-2：@Synchronized 防止多线程并发 open 同一 host:port 造成 ServerSocket/线程泄漏。 */
    @Synchronized
    fun getOrOpen(remoteHost: String, remotePort: Int): Int {
        val key = "$remoteHost:$remotePort"
        val existing = map[key]
        // 2026-08-29 修复（连续切歌 ECONNREFUSED 定位）：转发端口可能已因 accept 线程异常退出而失效
        // （ServerSocket 关闭但 map 缓存仍在）。返回前校验端口是否仍存活，已死则丢弃缓存并重建，
        // 避免 mapToLocal 一直返回一个不监听的端口，导致 OkHttp 连接回环端口 Connection refused、
        // 播放器误判断网连续跳歌。
        if (existing != null) {
            if (forwarder.isAlive(existing)) return existing
            Log.i(TAG, "转发端口 $existing 已失效（僵尸端口），重建映射 $key")
            map.remove(key)
        }
        return forwarder.open(remoteHost, remotePort).also { map[key] = it }
    }

    @Synchronized
    fun invalidate(host: String, port: Int) {
        map.remove("$host:$port")?.let { forwarder.close(it) }
    }

    @Synchronized
    fun clear() {
        map.values.forEach { runCatching { forwarder.close(it) } }
        map.clear()
    }
}
