package com.shiyinplayer.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.shiyinplayer.BuildConfig
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import com.shiyinplayer.data.remote.webdav.WebDavCredentialStore
import com.shiyinplayer.di.WebDavClientProvider
import com.shiyinplayer.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Credentials
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 检查更新结果。 [update] 非空表示存在可更新版本；为空表示已是最新（此时 [error] 应为 null）。 */
data class UpdateCheckResult(
    val update: AppUpdateInfo?,
    val error: String? = null
)

/**
 * 版本更新管理器（2026-08-24 新增）。
 *
 * 更新仓库地址 [Constants.UPDATE_BASE_URL]（ZT WebDAV 目录），经已联通的 ZT 源访问：
 *  - [ZeroTierManager.mapToLocal] 解析虚拟地址到可达地址（系统 ZT 网卡直连原地址 /
 *    内嵌 libzt 回环映射），与播放/浏览 WebDAV 源同套解析；
 *  - 凭据按【原始主机名】从 [WebDavCredentialStore] 查询（映射后 URL 已变 127.0.0.1，
 *    不能按映射地址查），查不到时回退默认 ZT 源凭据，与 DefaultSourceSeeder 一致。
 *
 * 流程：checkForUpdate 读 latest.json → 与本地 BuildConfig.VERSION_CODE 比较 → 有新版本时
 * downloadApk 经 WebDAV 分块下载并做长度/MD5 校验 → 申请 REQUEST_INSTALL_PACKAGES 安装权限 →
 * installApk 交由系统 PackageInstaller 安装。请求走 @Named("webdav") OkHttp，信任自签 HTTPS。
 *
 * 双通道：latest.json 顶层分 {"debug":..,"release":..} 两节，App 按自身 buildType 取对应节下载，
 * 从而 release 与 debug 两条线各自更新互不串包。
 */
@Singleton
class AppUpdateManager @Inject constructor(
    private val clientProvider: WebDavClientProvider,
    private val credStore: WebDavCredentialStore,
    private val zeroTierManager: ZeroTierManager,
    @ApplicationContext private val context: Context
) {
    private val TAG = "AppUpdateManager"
    /** 当前构建变体对应的更新通道（debug/release），决定 latest.json 取哪一节。 */
    private val channel: String = if (BuildConfig.DEBUG) "debug" else "release"
    private val originalHost: String = Uri.parse(Constants.UPDATE_BASE_URL).host.orEmpty()
    private val originalPort: Int =
        (Uri.parse(Constants.UPDATE_BASE_URL).port).takeIf { it > 0 } ?: 80

    /** 解析出可达的仓库基址（含 /shiyinupdate 目录）与凭据主机。 */
    private fun reachableBaseUrl(): String {
        val mapped = zeroTierManager.mapToLocal(originalHost, originalPort)
        return "http://${mapped.hostString}:${mapped.port}${Uri.parse(Constants.UPDATE_BASE_URL).path}"
    }

    private fun credential(): com.shiyinplayer.data.remote.webdav.WebDavCredential {
        return credStore.getForHost(originalHost)
            ?: com.shiyinplayer.data.remote.webdav.WebDavCredential(
                Constants.ZT_WEBDAV_DEFAULT_USER, Constants.ZT_WEBDAV_DEFAULT_PASS
            )
    }

    private fun Request.Builder.webdavHeaders() = apply {
        val cred = credential()
        header("Authorization", Credentials.basic(cred.username, cred.password))
        header("Accept-Encoding", "identity")
    }

    /**
     * 检查更新：读取 latest.json 并与本地 BuildConfig 比较。
     *  @return [UpdateCheckResult.update] 非空表示存在新版本；[error] 非空表示检查失败。
     */
    suspend fun checkForUpdate(localCode: Int): UpdateCheckResult {
        return withContext(Dispatchers.IO) {
            try {
                val url = reachableBaseUrl().trimEnd('/') + "/latest.json"
                val req = Request.Builder().url(url).webdavHeaders().build()
                clientProvider.of(url).newCall(req).execute().use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        return@withContext UpdateCheckResult(null, "更新仓库认证失败（HTTP ${resp.code}）")
                    }
                    if (!resp.isSuccessful) {
                        return@withContext UpdateCheckResult(null, "检查更新失败（HTTP ${resp.code}），请确认 ZT 已联通且仓库已放 latest.json")
                    }
                    val raw = resp.body?.string().orEmpty()
                    val info = try {
                        AppUpdateInfo.fromJson(raw, channel)
                    } catch (e: org.json.JSONException) {
                        // 2026-08-28 区分「清单损坏」与「网络不可达」，便于排障
                        Log.w(TAG, "更新清单格式损坏: ${e.message}")
                        return@withContext UpdateCheckResult(null, "更新清单格式损坏：${e.message}")
                    }
                    if (info.apkName.isBlank()) return@withContext UpdateCheckResult(null, "更新清单缺少 apk 字段")
                    if (info.isNewerThan(localCode)) {
                        Log.i(TAG, "发现新版本 ${info.versionName} (code ${info.versionCode}) > 本地 $localCode")
                        UpdateCheckResult(info)
                    } else {
                        UpdateCheckResult(null)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查更新失败: ${e.message}")
                UpdateCheckResult(null, "无法连接更新仓库：${e.message}")
            }
        }
    }

    /**
     * 经 WebDAV 分块下载安装包到缓存目录，下载后做长度与（可选的）MD5 校验。
     *  @param onProgress 进度回调（已下载字节, 总字节）
     */
    suspend fun downloadApk(
        info: AppUpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val url = reachableBaseUrl().trimEnd('/') + "/" + info.apkName.trimStart('/')
        val req = Request.Builder().url(url).webdavHeaders().build()
        clientProvider.of(url).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载安装包失败（HTTP ${resp.code}）")
            // contentLength 返回 0/未知时回退 info.size，避免进度错显示 0/0
            val cl = resp.body?.contentLength()
            val total = if (cl != null && cl > 0) cl else info.size
            // 2026-08-28 原子写入：先写 .tmp 校验通过后重命名为最终包，失败删除临时文件，避免残留半写 APK
            val finalFile = File(context.cacheDir, "shiyinupdate_${info.versionName}.apk")
            val tmp = File(context.cacheDir, "shiyinupdate_${info.versionName}.apk.tmp")
            tmp.delete()
            var len = 0L
            try {
                resp.body!!.byteStream().use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int; var done = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
                len = tmp.length()
                if (info.size > 0 && len != info.size) {
                    throw IOException("下载不完整：期望 ${info.size} 字节，实际 $len")
                }
                info.md5?.let { expect ->
                    if (!md5(tmp).equals(expect.trim(), ignoreCase = true)) {
                        throw IOException("下载校验失败（MD5 不符）")
                    }
                }
                finalFile.delete()
                if (!tmp.renameTo(finalFile)) {
                    throw IOException("安装包落盘失败（无法写入 ${finalFile.name}）")
                }
                Log.i(TAG, "安装包下载完成: $finalFile (${len}B)")
                finalFile
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        }
    }

    /** 是否已具备「允许安装未知来源应用」权限。 */
    fun canRequestInstallPackages(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.packageManager.canRequestPackageInstalls()
        }
        return true
    }

    /** 跳转系统「允许安装未知应用」设置页（Android 8+）。由 UI 经 Activity 启动。 */
    fun installPermissionSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"))
    }

    /** 构建交给系统安装器的 Intent（经 FileProvider 暴露文件 URI）。 */
    fun installApkIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun md5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        DigestInputStream(FileInputStream(file), md).use { input ->
            val buf = ByteArray(64 * 1024)
            while (input.read(buf) != -1) { /* 流式读完整包以更新摘要，避免整载入内存 */ }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}