package com.shiyinplayer.data.remote.webdav

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import com.shiyinplayer.di.WebDavClientProvider
import okhttp3.Credentials
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

/**
 * WebDAV 播放数据源（架构 §1.2.11 / T14）：基于 OkHttp 的 Range 流式读取。
 * - 经 [ZeroTierManager.mapToLocal] 把 ZeroTier 私网地址映射到本地回环转发端口（libzt 私网路由），
 *   连接经 [WebDavClientProvider] 按原始主机选择客户端（内网/ZeroTier 信任自签，外链严格 TLS）。
 * - 凭据按【原始主机名】查询（映射后 URL 已变 127.0.0.1，不能按映射地址查），仅注入 Authorization 头。
 */
class WebDavDataSource @Inject constructor(
    private val credStore: WebDavCredentialStore,
    private val zt: ZeroTierManager,
    private val clientProvider: WebDavClientProvider
) : DataSource {

    private var stream: InputStream? = null
    private var response: Response? = null
    private var length: Long = -1
    private var headerLogged = false
    /** 2026-08-21：libzt 传输停滞续传——open 时的基准位置与当前绝对读取位置。 */
    private var basePosition = 0L
    private var absolutePos = 0L
    private var resumeCount = 0
    private var originalUri: Uri? = null

    @UnstableApi
    override fun open(dataSpec: DataSpec): Long {
        closeQuietly()
        val original = dataSpec.uri
        originalUri = original
        basePosition = dataSpec.position
        absolutePos = dataSpec.position
        val host = original.host ?: throw IOException("WebDAV URL 缺少主机：${original}")
        // 中危-D：明文流量收敛——http:// 仅内网来源允许（外链明文一律拒绝，防公网泄漏/篡改）。
        if (!clientProvider.cleartextAllowed(original.toString())) {
            throw IOException("明文 HTTP 来源已被安全策略拦截（仅内网允许）：${host}")
        }
        val port = if (original.port != -1) original.port else if (original.scheme == "https") 443 else 80
        val mapped = zt.mapToLocal(host, port)
        Log.d(TAG, "open host=$host:$port pos=${dataSpec.position} → mapped=${mapped.hostName}:${mapped.port} ztVip=${zt.virtualIp.value}")
        val url = if (mapped.hostName == host && mapped.port == port) {
            original.toString()
        } else {
            // encodedAuthority 保持 "host:port" 原样，避免 authority() 把端口冒号编码成 %3A
            original.buildUpon().encodedAuthority("${mapped.hostName}:${mapped.port}").build().toString()
        }
        val cred = credStore.getForUrl(original.toString())
        val range = if (dataSpec.position > 0) {
            val end = if (dataSpec.length > 0) dataSpec.position + dataSpec.length - 1 else ""
            "bytes=${dataSpec.position}-$end"
        } else if (dataSpec.length > 0) {
            "bytes=0-${dataSpec.length - 1}"
        } else {
            null
        }
        val req = okhttp3.Request.Builder()
            .url(url)
            // mime：经 ZT 转发后连接目标是 127.0.0.1:回环端口，但 WebDAV 服务器按「原始主机」
            // 路由虚拟主机/返回资源（Host 不匹配会 404）。转发映射改变时显式保留原始 Host 头，
            // 否则 OkHttp 会用 127.0.0.1 作 Host → 服务器 404（"冷启动后完全播不出"的直接根因）。
            .apply { if (mapped.hostName != host || mapped.port != port) header("Host", "$host:$port") }
            .apply { range?.let { header("Range", it) } }
            .apply { if (cred != null) header("Authorization", Credentials.basic(cred.username, cred.password)) }
            .build()
        val client = clientProvider.of(original.toString())
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            // 诊断（连续切歌 404 定位）：打印请求线与响应头，区分「vhost 路由错（Host 被改）」与真实 404；
            // 并用**全新连接池**重发一次，区分「池化连接串扰导致 404」VS「服务器确无该文件」。
            runCatching {
                val line = "${req.method} ${req.url} Host=${req.header("Host")} Range=${req.header("Range")}"
                val hdr = listOf("Server", "Content-Type", "Location", "WWW-Authenticate")
                    .mapNotNull { h -> resp.header(h)?.let { "$h=$it" } }.joinToString(" ")
                val bodyHead = resp.peekBody(120).string().replace('\n', ' ').take(80)
                val freshCode = runCatching {
                    client.newBuilder().connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS)).build()
                        .newCall(req.newBuilder().header("Connection", "close").build()).execute().use { it.code }
                }.getOrElse { -1 }
                Log.e(TAG, "open 失败 HTTP ${resp.code} host=$host:$port | $line | resp[$hdr] body=$bodyHead | 全新连接池直问=${freshCode}")
            }.onFailure { Log.e(TAG, "open 失败 HTTP ${resp.code} host=$host:$port（诊断失败）") }
            resp.close()
            throw IOException("WebDAV 播放请求失败 HTTP ${resp.code}")
        }
        response = resp
        stream = resp.body?.byteStream() ?: throw IOException("WebDAV 无响应体")
        val bodyLen = resp.body!!.contentLength()
        // 206 续传：校验服务器实际响应起点 == 请求位置，防止 libzt 转发错位导致字节丢失/重复。
        var skipExtra = 0L
        if (resp.code == 206 && dataSpec.position > 0) {
            try {
                val start = resp.header("Content-Range")
                    ?.substringAfter("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()
                when {
                    start == null -> Unit
                    start > dataSpec.position -> throw IOException(
                        "续传错位：请求 pos=${dataSpec.position}，服务器返回到 $start，拒绝继续"
                    )
                    start < dataSpec.position -> {
                        skipExtra = dataSpec.position - start
                        Log.w(TAG, "续传校正：请求=$dataSpec.position 服务器起点=$start，提前跳过 $skipExtra 字节")
                    }
                }
            } catch (e: IOException) {
                throw e
            } catch (_: Exception) {
                // Content-Range 非标准，不做校正
            }
        }
        val skipped = when {
            skipExtra > 0 -> skipFully(stream!!, skipExtra)
            dataSpec.position > 0 && resp.code == 200 -> skipFully(stream!!, dataSpec.position)
            else -> 0L
        }
        length = when {
            dataSpec.position == 0L -> bodyLen
            resp.code == 206 -> bodyLen - skipExtra
            bodyLen > 0 -> bodyLen - dataSpec.position
            else -> -1L
        }
        Log.d(TAG, "open OK HTTP ${resp.code} body=${bodyLen}B length=$length pos=${dataSpec.position} skip=$skipped skipExtra=$skipExtra")
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val n = try {
                stream?.read(buffer, offset, length) ?: -1
            } catch (e: java.net.SocketTimeoutException) {
                // libzt 传输停滞：内部 Range 续传（每 5s 无数据即重开连接续传，绕过单连接停滞）
                if (resumeAfterStall()) continue else return -1
            } catch (e: java.io.InterruptedIOException) {
                if (resumeAfterStall()) continue else return -1
            } catch (e: java.io.IOException) {
                // libzt 连接被重置/读失败：同样续传（新连接 Range 续传）
                if (resumeAfterStall()) continue else return -1
            }
            if (n > 0) {
                absolutePos += n
                this.length = if (this.length > 0) this.length - n else this.length
                return n
            }
            return n
        }
    }

    /** 停滞续传：关闭当前连接，以当前绝对位置发起 Range 请求继续读取。 */
    private fun resumeAfterStall(): Boolean {
        resumeCount++
        return try {
            val pos = absolutePos
            val uri = originalUri ?: return false
            closeQuietly()
            val spec = DataSpec.Builder().setUri(uri).setPosition(pos).build()
            open(spec)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun getUri(): Uri? = response?.request?.url?.toString()?.let { Uri.parse(it) }

    override fun close() = closeQuietly()

    override fun addTransferListener(transferListener: TransferListener) = Unit

    @UnstableApi
    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap() ?: emptyMap()

    private fun closeQuietly() {
        runCatching { stream?.close() }
        stream = null
        runCatching { response?.close() }
        response = null
        length = -1
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val buf = ByteArray(32 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    companion object {
        private const val TAG = "WebDavDataSource"
    }
}
