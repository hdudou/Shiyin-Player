package com.shiyinplayer.data

import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.MusicSource
import com.shiyinplayer.data.network.zerotier.ZeroTierConfig
import com.shiyinplayer.data.remote.webdav.WebDavCredentialStore
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.util.Constants
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * 首启默认配置播种（便利项）：
 * 1. 预填 ZeroTier 默认网络 ID（仅首次启动执行一次，见 [ZeroTierConfig.ensureDefaultNetwork]）。
 * 2. 幂等预置默认 ZT WebDAV 源与登录凭据（[Constants.ZT_WEBDAV_DEFAULT_URL/USER/PASS]）。
 *
 * 【全新安装 vs 覆盖升级】内置默认网络/源/凭据只在【全新安装】（[ZeroTierConfig.isInitDone] 为 false，
 * 尚无任何历史初始化数据）时生效；覆盖升级（旧版已写入 zt_init_done 标记）时一律【不覆盖、不写】旧版
 * 的 ZT 网络 ID、网络源与登录凭据记录，完整保留用户既有数据。
 *
 * ⚠️ **开源版的四个默认值全是空串**（不内嵌作者的私有网络 ID、服务器地址与口令）。
 * 因此下面每个播种步骤都**必须先判空**：否则会写进一条地址为空的网络源 / 一条空凭据，
 * 在用户曲库里留一个永远连不上的坏源。判空后行为 = 「什么都不播种，用户自行配置」。
 */
@Singleton
class DefaultSourceSeeder @Inject constructor(
    private val repo: LibraryRepository,
    private val credentialStore: WebDavCredentialStore,
    private val ztConfig: ZeroTierConfig
) {
    suspend fun seed() {
        // 全新安装：预置内置源+凭据；覆盖升级：isInitDone()==true → 跳过，保留旧版源与凭据（不覆盖不写）。
        if (!ztConfig.isInitDone()) {
            seedDefaultWebDavSource()
        }
        // 网络 ID 预置由 ensureDefaultNetwork 自带 INIT_DONE 门控：仅首次（且 networkId 为空）写入，升级不生效。
        seedZeroTierNetworkId()
    }

    private suspend fun seedZeroTierNetworkId() {
        val nid = Constants.ZEROTIER_DEFAULT_NETWORK_ID
        if (nid.isBlank()) return          // 开源版留空：用户自行输入网络 ID
        runCatching { ztConfig.ensureDefaultNetwork(nid) }
    }

    private suspend fun seedDefaultWebDavSource() {
        val url = Constants.ZT_WEBDAV_DEFAULT_URL
        // 开源版留空：不播种。否则会写进一条地址为空的 WebDAV 源 + 一条空凭据（用户曲库里的坏源）。
        if (url.isBlank()) return
        val host = hostOf(url)
        runCatching { credentialStore.saveForUrl(url, Constants.ZT_WEBDAV_DEFAULT_USER, Constants.ZT_WEBDAV_DEFAULT_PASS) }
        val exists = runCatching {
            repo.getMusicSources().first().any { src ->
                src.type == MediaSourceType.WEBDAV && hostOf(src.configUrl().orEmpty()) == host
            }
        }.getOrDefault(false)
        if (!exists) {
            runCatching {
                repo.addMusicSource(
                    MusicSource(
                        name = Constants.ZT_WEBDAV_DEFAULT_NAME,
                        type = MediaSourceType.WEBDAV,
                        configJson = JSONObject().put("url", url).toString(),
                        enabled = true
                    )
                )
            }
        }
    }

    private fun MusicSource.configUrl(): String? {
        if (configJson.isBlank()) return null
        return runCatching { JSONObject(configJson).optString("url") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore("/").substringBefore(":")
}