package com.shiyinplayer.data.media

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import javax.inject.Inject
import javax.inject.Singleton

/** SMB 凭据加密持久化（架构 §7：禁止明文落盘，走 EncryptedSharedPreferences）。 */
data class SmbCredential(val username: String, val password: String)

@Singleton
class SmbCredentialStore @Inject constructor(context: Context) {
    private val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "smb_credentials",
        masterKey,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(host: String, username: String, password: String) {
        prefs.edit()
            .putString("u_$host", username)
            .putString("p_$host", password)
            .apply()
    }

    fun get(host: String): SmbCredential? {
        val u = prefs.getString("u_$host", null) ?: return null
        val p = prefs.getString("p_$host", "") ?: ""
        return SmbCredential(u, p)
    }

    fun clear(host: String) {
        prefs.edit().remove("u_$host").remove("p_$host").apply()
    }

    /** 枚举全部已保存的 SMB 凭据（key 为 host），供导出。 */
    fun getAll(): Map<String, SmbCredential> {
        val result = LinkedHashMap<String, SmbCredential>()
        prefs.all.forEach { (k, v) ->
            if (k.startsWith("u_") && v is String) {
                val host = k.removePrefix("u_")
                val p = prefs.getString("p_$host", "") ?: ""
                result[host] = SmbCredential(v, p)
            }
        }
        return result
    }
}
