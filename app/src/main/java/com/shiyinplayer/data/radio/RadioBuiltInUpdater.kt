package com.shiyinplayer.data.radio

import android.net.Uri
import android.util.Log
import com.shiyinplayer.data.local.dao.RadioStationDao
import com.shiyinplayer.data.local.entity.RadioStationEntity
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import com.shiyinplayer.data.remote.webdav.WebDavCredential
import com.shiyinplayer.data.remote.webdav.WebDavCredentialStore
import com.shiyinplayer.di.WebDavClientProvider
import com.shiyinplayer.ui.settings.SettingsRepository
import com.shiyinplayer.util.Constants
import okhttp3.Credentials
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置电台清单远程更新器（2026-09-09 新增）。
 *
 * 每启动从播放器更新仓库拉取内置电台清单（与 App 安装包共用 [Constants.UPDATE_BASE_URL] 服务器、
 * 域名解析/ZeroTier 映射、WebDAV 凭据），按「单独的内置电台版本号」比对本机已应用版本：
 *  - 远程版本 > 本地版本 → 执行远程同步（新增 / 更新 / 下架），成功后回写本地版本；
 *  - 远程版本 <= 本地版本 → 跳过，避免重复更新。
 *
 * 覆盖保护：同步按 URL 匹配，且对已存在的、source 已被用户修改接管为非 builtin 的电台一律跳过，
 * 确保「用户长按修改的内置电台在远程更新时不再被覆盖」。
 *
 * 清单文件放置于更新仓库根：radio_builtin.json，结构
 *     { "version": 1, "stations": [ {name, url, genre, country, logoUrl}, ... ] }
 */
@Singleton
class RadioBuiltInUpdater @Inject constructor(
    private val stationDao: RadioStationDao,
    private val settingsRepository: SettingsRepository,
    private val clientProvider: WebDavClientProvider,
    private val credStore: WebDavCredentialStore,
    private val zeroTierManager: ZeroTierManager
) {
    private val TAG = "RadioBuiltInUpdater"
    private val originalHost: String = Uri.parse(Constants.UPDATE_BASE_URL).host.orEmpty()
    private val originalPort: Int =
        (Uri.parse(Constants.UPDATE_BASE_URL).port).takeIf { it > 0 } ?: 80

    /** 内置电台数据类，仅承载本机未知的远程记录，用于比对/插入。内部均按 builtin 处理。 */
    private data class RemoteStation(
        val name: String,
        val url: String,
        val genre: String?,
        val country: String?,
        val logoUrl: String?
    )

    /**
     * 尝试远程更新内置电台。拉起网络/写库均容错：任何异常只记日志，不影响启动与既有收音机功能。
     * 须已先完成 assets 播种（本方法假设库内已存在 builtin 记录）。
     */
    suspend fun updateIfNeeded() {
        withContext(Dispatchers.IO) {
            try {
                val local = settingsRepository.radioBuiltinVersion()
                val remote = fetchRemote()
                if (remote == null) {
                    Log.w(TAG, "拉取远程内置电台清单失败，跳过本次更新")
                    return@withContext
                }
                if (remote.versionCode <= local) {
                    Log.i(TAG, "内置电台清单已是最新（remote ${remote.versionCode} == local $local），跳过")
                    return@withContext
                }
                applyRemote(remote)
                settingsRepository.setRadioBuiltinVersion(remote.versionCode)
                Log.i(TAG, "内置电台清单已更新到远程 v${remote.versionCode}（共 ${remote.stations.size} 台）")
            } catch (e: Exception) {
                Log.w(TAG, "内置电台远程更新失败: ${e.message}")
            }
        }
    }

    private data class RemoteManifest(val versionCode: Int, val stations: List<RemoteStation>)

    private fun reachableBaseUrl(): String {
        val mapped = zeroTierManager.mapToLocal(originalHost, originalPort)
        return "http://${mapped.hostString}:${mapped.port}${Uri.parse(Constants.UPDATE_BASE_URL).path}"
    }

    private fun credential(): WebDavCredential =
        credStore.getForHost(originalHost)
            ?: WebDavCredential(
                Constants.ZT_WEBDAV_DEFAULT_USER, Constants.ZT_WEBDAV_DEFAULT_PASS
            )

    private suspend fun fetchRemote(): RemoteManifest? = withContext(Dispatchers.IO) {
        val url = reachableBaseUrl().trimEnd('/') + "/radio_builtin.json"
        val req = Request.Builder().url(url)
            .header("Authorization", Credentials.basic(credential().username, credential().password))
            .header("Accept-Encoding", "identity")
            .build()
        // 内置电台更新不是关键路径，读超时放宽到 15s，避免弱网下静默阻塞
        val client = clientProvider.of(url).newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "拉取远程清单 HTTP ${resp.code}")
                return@withContext null
            }
            parseManifest(resp.body?.string().orEmpty())
        }
    }

    private fun parseManifest(raw: String): RemoteManifest? {
        try {
            val root = JSONObject(raw)
            val version = root.optInt("version", 0)
            val arr = root.optJSONArray("stations") ?: return null
            val stations = mutableListOf<RemoteStation>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val url = o.optString("url").trim()
                if (name.isEmpty() || url.isEmpty()) continue
                stations += RemoteStation(
                    name = name,
                    url = url,
                    genre = o.optString("genre").ifBlank { null },
                    country = o.optString("country").ifBlank { null },
                    logoUrl = o.optString("logoUrl").ifBlank { null }
                )
            }
            return RemoteManifest(version, stations)
        } catch (e: Exception) {
            Log.w(TAG, "解析远程内置电台清单失败: ${e.message}")
            return null
        }
    }

    private suspend fun applyRemote(remote: RemoteManifest) {
        val entities = remote.stations.map {
            RadioStationEntity(
                name = it.name,
                url = it.url,
                logoUrl = it.logoUrl,
                genre = it.genre,
                country = it.country,
                source = "builtin"
            )
        }
        if (remote.versionCode > 0) {
            stationDao.syncBuiltInFromRemote(entities)
        }
    }
}