package com.shiyinplayer.data.media

import android.net.Uri
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthentication
import jcifs.smb.SmbFile
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P2-13：jcifs-ng 统一上下文工厂——配置连接/响应/SO 超时，
 * 避免 SMB 服务器不可达（尤其 ZeroTier 未连接）时默认超时长达数分钟、阻塞扫描/播放。
 */
internal object SmbContextFactory {
    // P2-13：BaseContext(PropertyConfiguration) 直接构造——本版本 jcifs-ng 的
    // SingletonContext 无公开 withConfig，直接用配置构造 BaseContext 即可应用超时。
    private val base: CIFSContext = jcifs.context.BaseContext(
        PropertyConfiguration(java.util.Properties().apply {
            setProperty("jcifs.smb.client.connTimeout", "10000")
            setProperty("jcifs.smb.client.responseTimeout", "30000")
            setProperty("jcifs.smb.client.soTimeout", "30000")
        })
    )

    /** 返回带超时配置的基础上下文（不含凭据；凭据由调用方 withCredentials 注入）。 */
    fun baseContext(): CIFSContext = base
}

/** SMB 目录浏览（架构 §1.2.8 / T8）。 */
data class SmbEntry(val name: String, val isDir: Boolean, val size: Long)

@Singleton
class SmbBrowser @Inject constructor(
    private val credStore: SmbCredentialStore,
    private val zt: ZeroTierManager
) {
    private companion object {
        /** 附件预览单文件读取上限（封面/说明 txt 通常远小于此）。 */
        const val MAX_PREVIEW_BYTES = 8 * 1024 * 1024
    }
    private val ipRegex = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    /** 浏览会话临时凭据（host → 凭据）：仅用于「浏览局域网」实时连接，不写入已保存源凭据库。 */
    private val browseCreds = java.util.concurrent.ConcurrentHashMap<String, SmbCredential>()

    /** 设置某 SMB 主机的临时浏览凭据（匿名访问时传空用户名即清除）。 */
    fun setBrowseCredential(host: String, username: String, password: String) {
        if (host.isBlank()) return
        if (username.isBlank()) browseCreds.remove(host) else browseCreds[host] = SmbCredential(username, password)
    }

    /** 清除某主机的临时浏览凭据（匿名访问）。 */
    fun clearBrowseCredential(host: String) {
        if (host.isBlank()) return
        browseCreds.remove(host)
    }

    fun listFiles(path: String): List<SmbEntry> {
        try {
            val (url, ctx) = locate(path)
            val dir = SmbFile(url, ctx)
            val uri = Uri.parse(path)
            // 根 smb:// 表示枚举工作组/域：直接 listFiles，不做 isDirectory 门控（根可能不报告为目录）
            if (uri.host.isNullOrEmpty()) {
                val enumerated = runCatching { dir.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
                if (enumerated.isNotEmpty()) return toEntries(enumerated)
                // F6-4 浏览增强：NetBIOS 广播在现代网络（Windows 10+ / 屏蔽 UDP 138 的路由器）常无响应，
                // 枚举根取不到主浏览器 → 列表空白。回退扫描本地 /24 网段的 SMB 445 端口直接发现主机。
                return rootEntriesViaLanScan()
            }
            if (!dir.isDirectory) return emptyList()
            return toEntries((dir.listFiles() ?: emptyArray()).toList())
        } catch (t: Throwable) {
            android.util.Log.e("SmbBrowse", "listFiles($path) failed", t)
            throw t
        }
    }

    /** 把 jcifs 枚举结果转为条目：文件夹优先 + 名称不区分大小写排序。 */
    private fun toEntries(files: List<SmbFile>): List<SmbEntry> =
        files.asSequence()
            .mapNotNull { f ->
                runCatching {
                    SmbEntry(f.path.trimEnd('/').substringAfterLast('/'), f.isDirectory, if (f.isDirectory) 0L else f.length())
                }.getOrNull()
            }
            .sortedWith(
                compareBy<SmbEntry> { !it.isDir } then compareBy({ it.name.lowercase() })
            )
            .toList()

    /**
     * 局域网主机发现回退：逐物理网卡 /24 网段并行探测 TCP 445，把可达主机作为目录条目
     * （isDir=true，path 由调用方拼为 smb://<ip>/），用户点击后进入该主机枚举共享。
     */
    private fun rootEntriesViaLanScan(): List<SmbEntry> {
        val found = LinkedHashSet<String>()
        for ((selfIp, subnet) in lanSubnets()) {
            probeSmbPorts(subnet, selfIp, found)
        }
        return found.map { SmbEntry(it, true, 0L) }
            .sortedBy { it.name }
    }

    /** 本机局域网 IPv4 及所在 /24 网段前缀；排除回环/蜂窝/隧道/ZeroTier 虚拟接口。 */
    private fun lanSubnets(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface == null || !iface.isUp) continue
                val name = iface.name.lowercase()
                if (name.startsWith("lo") || name.startsWith("rmnet") || name.startsWith("p2p") ||
                    name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("zt")) continue
                val ip = iface.inetAddresses.asSequence().filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isLoopbackAddress.not() && !it.isLinkLocalAddress }?.hostAddress ?: continue
                map[ip] = ip.substringBeforeLast('.')
            }
        } catch (_: Throwable) {
        }
        return map
    }

    /** 并行探测给定 /24 网段内主机的 445 端口，可达者写入 [out]。 */
    private fun probeSmbPorts(subnet: String, selfIp: String, out: MutableSet<String>) {
        val executor = Executors.newFixedThreadPool(16)
        try {
            val futures = (1..254).mapNotNull { n ->
                val ip = "$subnet.$n"
                if (ip == selfIp) null
                else executor.submit(Callable {
                    try {
                        Socket().use { it.connect(InetSocketAddress(ip, 445), 800); ip }
                    } catch (_: Throwable) {
                        null
                    }
                })
            }
            out.addAll(futures.mapNotNull { f -> runCatching { f.get() }.getOrNull() })
        } finally {
            executor.shutdown()
        }
    }

    /** 下载 SMB 文件前缀字节（用于扫描时探测时长）。经 ZeroTier 映射 + 凭据按原始 host 查。 */
    fun readPrefix(path: String, maxBytes: Int): ByteArray? {
        return try {
            val (url, ctx) = locate(path)
            val file = SmbFile(url, ctx)
            val len = file.length().coerceAtMost(maxBytes.toLong()).toInt()
            if (len <= 0) return null
            val buf = ByteArray(len)
            file.inputStream.use { it.read(buf) }
            buf
        } catch (_: Throwable) {
            null
        }
    }

    /** 全量读取 SMB 文件（文件夹附件封面/说明 txt 预览）。经 ZeroTier 映射 + 凭据按原始 host 查。 */
    fun readAll(path: String): ByteArray? {
        return try {
            val (url, ctx) = locate(path)
            val file = SmbFile(url, ctx)
            val len = file.length().coerceAtMost(MAX_PREVIEW_BYTES.toLong()).toInt()
            if (len <= 0) return null
            val buf = ByteArray(len)
            file.inputStream.use { it.read(buf) }
            buf
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 2026-08-24：解析目标地址并返回 (可访问 URL, CIFSContext)。
     * - 根 smb://：枚举工作组/域，用 SingletonContext（带默认发现机制），原样返回；
     * - 工作组/主机名（非 IP）：枚举主机/共享或连接命名主机。无已存凭据时用 SingletonContext
     *   以便发现网络主机；有已存凭据则走自定义超时上下文的凭据连接；
     * - IP：ZeroTier mapToLocal 映射（虚拟网段→127.0.0.1:本地端口），凭据按原始 host 查。
     * 修复：旧实现把 smb:// 根拼成 smb://:445/（host 空）导致局域网主机枚举失败。
     */
    private fun locate(path: String): Pair<String, CIFSContext> {
        val uri = Uri.parse(path)
        val originalHost = uri.host ?: ""
        val urlPath = uri.path ?: "/"
        val baseCtx = SmbContextFactory.baseContext()
        if (originalHost.isEmpty()) return path to SingletonContext.getInstance()
        if (!ipRegex.matches(originalHost)) {
            // 工作组/命名主机：不套 ZeroTier 映射与默认端口，保持用户输入的端口
            val cred = credStore.get(originalHost)
            val port = uri.port.takeIf { it > 0 }
            val url = if (port != null) "smb://$originalHost:$port$urlPath" else "smb://$originalHost$urlPath"
            return if (cred != null) {
                val auth = NtlmPasswordAuthentication(baseCtx, null, cred.username, cred.password)
                url to baseCtx.withCredentials(auth)
            } else {
                url to SingletonContext.getInstance()
            }
        }
        val originalPort = uri.port.takeIf { it > 0 } ?: 445
        // 经 ZeroTier 映射（虚拟网段 → 127.0.0.1:本地端口；非虚拟网原样返回）
        val mapped = zt.mapToLocal(originalHost, originalPort)
        // 凭据按原始 host 查：浏览会话临时凭据优先（「浏览局域网」实时输入），否则用已保存源凭据
        val cred = browseCreds[originalHost] ?: credStore.get(originalHost)
        val auth = if (cred != null) {
            NtlmPasswordAuthentication(baseCtx, null, cred.username, cred.password)
        } else {
            NtlmPasswordAuthentication(baseCtx)
        }
        val cifsContext: CIFSContext = baseCtx.withCredentials(auth)
        val mappedUrl = "smb://${mapped.hostString}:${mapped.port}$urlPath"
        return mappedUrl to cifsContext
    }
}
