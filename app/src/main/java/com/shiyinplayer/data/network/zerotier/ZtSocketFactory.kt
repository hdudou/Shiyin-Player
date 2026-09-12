package com.shiyinplayer.data.network.zerotier

import java.io.InputStream
import java.io.OutputStream

/**
 * libzt TCP socket 工厂（架构 §3.2）。真实实现经 libzt 私有 socket 连接虚拟网内地址。
 * 缺原生库时 [connect] 返回 null（降级）。
 */
interface ZtSocket {
    val inputStream: InputStream
    val outputStream: OutputStream
    fun close()
}

interface ZtSocketFactory {
    fun connect(host: String, port: Int): ZtSocket?
}

class StubZtSocketFactory : ZtSocketFactory {
    override fun connect(host: String, port: Int): ZtSocket? = null
}
