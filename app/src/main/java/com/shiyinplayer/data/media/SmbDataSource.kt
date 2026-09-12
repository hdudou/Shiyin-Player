package com.shiyinplayer.data.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import jcifs.CIFSContext
import jcifs.smb.NtlmPasswordAuthentication
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile

import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import javax.inject.Inject

/**
 * SMB 数据源（架构 §1.2.8 / T8）。
 * scheme == "smb" 时用 jcifs-ng 读取；否则委托 DefaultDataSource 处理 file/content/http(s)。
 * 这样单个 DataSource.Factory 即可覆盖所有来源（PlayerModule 注入此工厂）。
 *
 * 注（2026-08-17）：SMB 现统一经 ZeroTierManager.mapToLocal() 做虚拟地址→本地端口替换，
 * 与 WebDAV 行为一致（架构 §1.2.12）。凭据按【原始 host】查询。详见 PROJECT_STATUS §五 P1 #5。
 */
class SmbDataSource @Inject constructor(
    private val context: Context,
    private val credStore: SmbCredentialStore,
    private val zt: ZeroTierManager
) : DataSource {
    private var delegate: DataSource? = null
    private var smbRaf: SmbRandomAccessFile? = null
    private var smbLength: Long = -1
    private var smbPosition: Long = 0
    private var smbUri: Uri? = null

    @UnstableApi
    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme == "smb") {
            smbUri = dataSpec.uri
            val originalHost = dataSpec.uri.host ?: ""
            val originalPort = dataSpec.uri.port.takeIf { it > 0 } ?: 445
            // 经 ZeroTier 映射（虚拟网段 → 127.0.0.1:本地端口；非虚拟网原样返回）
            val mapped = zt.mapToLocal(originalHost, originalPort)
            val cred = credStore.get(originalHost) // 凭据按原始 host 查
            val baseCtx = SmbContextFactory.baseContext()
            val auth = if (cred != null) {
                NtlmPasswordAuthentication(baseCtx, null, cred.username, cred.password)
            } else {
                NtlmPasswordAuthentication(baseCtx)
            }
            val cifsContext: CIFSContext = baseCtx.withCredentials(auth)
            // 用映射后的 host:port 重建 SMB URL，path 保持原始
            val path = dataSpec.uri.path ?: "/"
            val url = "smb://${mapped.hostString}:${mapped.port}$path"
            val file = SmbFile(url, cifsContext)
            smbLength = file.length()
            // 用 SmbRandomAccessFile 支持随机 seek（替代 InputStream.skip 顺序跳字节，大文件拖动秒级响应）
            smbRaf = file.openRandomAccess("r")
            smbPosition = 0
            if (dataSpec.position > 0) {
                smbRaf!!.seek(dataSpec.position)
                smbPosition = dataSpec.position
            }
            return if (dataSpec.length == -1L) smbLength - smbPosition else dataSpec.length
        }
        delegate = DefaultDataSource.Factory(context).createDataSource()
        return delegate!!.open(dataSpec)
    }

    @UnstableApi
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (delegate != null) return delegate!!.read(buffer, offset, length)
        val read = smbRaf!!.read(buffer, offset, length)
        if (read > 0) smbPosition += read
        return read
    }

    override fun getUri(): Uri? = smbUri ?: delegate?.uri

    override fun close() {
        runCatching { smbRaf?.close() }
        smbRaf = null
        smbUri = null
        runCatching { delegate?.close() }
        delegate = null
    }

    override fun addTransferListener(transferListener: TransferListener) {
        delegate?.addTransferListener(transferListener)
    }

    @UnstableApi
    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()
}
