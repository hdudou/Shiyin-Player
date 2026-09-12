package com.shiyinplayer.data.remote.webdav

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import javax.inject.Inject
import javax.inject.Singleton

/** WebDAV 凭据加密持久化（架构 §7：禁止明文 / 禁止拼入 URL）。 */
data class WebDavCredential(val username: String, val password: String)

@Singleton
class WebDavCredentialStore @Inject constructor(context: Context) {
    private val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "webdav_credentials",
        masterKey,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveForUrl(baseUrl: String, username: String, password: String) {
        val host = hostOf(baseUrl)
        prefs.edit().putString("u_$host", username).putString("p_$host", password).apply()
    }

    fun getForUrl(url: String): WebDavCredential? {
        val host = hostOf(url)
        return getForHost(host)
    }

    /** 按主机取凭据（host 不含端口；浏览/播放经 ZeroTier 回环映射后以原主机名查询）。 */
    fun getForHost(host: String): WebDavCredential? {
        val u = prefs.getString("u_$host", null) ?: return null
        val p = prefs.getString("p_$host", "") ?: ""
        return WebDavCredential(u, p)
    }

    fun clearForUrl(url: String) {
        val host = hostOf(url)
        prefs.edit().remove("u_$host").remove("p_$host").apply()
    }

    /** 枚举全部已保存的 WebDAV 凭据（key 为 host），供导出。 */
    fun getAll(): Map<String, WebDavCredential> {
        val result = LinkedHashMap<String, WebDavCredential>()
        prefs.all.forEach { (k, v) ->
            if (k.startsWith("u_") && v is String) {
                val host = k.removePrefix("u_")
                val p = prefs.getString("p_$host", "") ?: ""
                result[host] = WebDavCredential(v, p)
            }
        }
        return result
    }

    private fun hostOf(url: String): String {
        return url.substringAfter("://").substringBefore("/").substringBefore(":")
    }
}
