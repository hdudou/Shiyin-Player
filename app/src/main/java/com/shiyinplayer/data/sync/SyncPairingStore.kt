package com.shiyinplayer.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** 已配对 PC（契约 §3：设备名 + token + pinnedCert）。 */
data class PairedDevice(
    val deviceId: String,
    val deviceName: String,
    val token: String,
    /** PC 客户端证书 PEM（本机为 TLS 服务端，通常为 null；保留双向 pinning 能力）。 */
    val pinnedCert: String? = null,
    val pairedAt: Long = 0L
)

/** 本机 TLS 服务端证书 + 私钥（PEM）。
 *
 * ⚠️ **必须持久化**：PC 侧按证书 **SHA-1 指纹** 做 cert pinning（`SyncPeerClient.ValidateCertificate`），
 * 每次重启重新生成证书会导致已配对设备指纹失配 → 全部 403，只能重新配对。
 */
data class ServerKeyMaterial(val certPem: String, val keyPem: String)

/**
 * 局域网同步凭据存储（EncryptedSharedPreferences）。
 *
 * 存三类东西：
 * 1. 本机 `deviceId`（首次生成后固定；PC 用它判重，已配对则只更新地址不重新发 token）
 * 2. 本机 TLS 服务端证书/私钥
 * 3. 已配对设备列表（`pairedDevices`）
 *
 * 非敏感的开关 / 配对码 / 设备名走 DataStore（`SettingsRepository`）。
 */
@Singleton
class SyncPairingStore @Inject constructor(context: Context) {

    private val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "sync_pairing",
        masterKey,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ------------------------------------------------------------ 本机身份

    /** 本机 deviceId：`and-<32位hex>`，首次访问时生成并持久化。 */
    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val id = "and-" + bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            id
        }
    }

    // ------------------------------------------------------------ 服务端证书

    fun getServerKeyMaterial(): ServerKeyMaterial? {
        val cert = prefs.getString(KEY_SERVER_CERT, null) ?: return null
        val key = prefs.getString(KEY_SERVER_KEY, null) ?: return null
        return ServerKeyMaterial(cert, key)
    }

    fun saveServerKeyMaterial(material: ServerKeyMaterial) {
        prefs.edit()
            .putString(KEY_SERVER_CERT, material.certPem)
            .putString(KEY_SERVER_KEY, material.keyPem)
            .apply()
    }

    // ------------------------------------------------------------ 已配对设备

    fun getAll(): List<PairedDevice> {
        val raw = prefs.getString(KEY_PAIRED, null) ?: return emptyList()
        return runCatching { parsePaired(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun savePaired(device: PairedDevice) {
        val list = getAll().toMutableList()
        list.removeAll { it.deviceId == device.deviceId }
        list.add(device)
        prefs.edit().putString(KEY_PAIRED, toJson(list).toString()).apply()
    }

    fun remove(deviceId: String) {
        val list = getAll().filterNot { it.deviceId == deviceId }
        prefs.edit().putString(KEY_PAIRED, toJson(list).toString()).apply()
    }

    /**
     * 按 token 校验请求方身份。
     * 用 [MessageDigest.isEqual] 做定长比较，避免逐字符短路带来的时序侧信道。
     */
    fun findByToken(token: String?): PairedDevice? {
        if (token.isNullOrEmpty()) return null
        val probe = token.toByteArray(Charsets.UTF_8)
        return getAll().firstOrNull { MessageDigest.isEqual(it.token.toByteArray(Charsets.UTF_8), probe) }
    }

    /** 更新既有配对的展示名（PC 重连时可能改了名）。 */
    fun renamePaired(deviceId: String, newName: String) {
        val list = getAll().map { if (it.deviceId == deviceId) it.copy(deviceName = newName) else it }
        prefs.edit().putString(KEY_PAIRED, toJson(list).toString()).apply()
    }

    fun clearAll() {
        prefs.edit()
            .remove(KEY_PAIRED)
            .remove(KEY_SERVER_CERT)
            .remove(KEY_SERVER_KEY)
            .apply()
    }

    // ------------------------------------------------------------ 序列化

    private fun toJson(list: List<PairedDevice>): JSONArray = JSONArray().also { arr ->
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("deviceId", d.deviceId)
                    .put("deviceName", d.deviceName)
                    .put("token", d.token)
                    .put("pinnedCert", d.pinnedCert)
                    .put("pairedAt", d.pairedAt)
            )
        }
    }

    private fun parsePaired(arr: JSONArray): List<PairedDevice> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("deviceId", "")
            val token = o.optString("token", "")
            if (id.isEmpty() || token.isEmpty()) continue
            add(
                PairedDevice(
                    deviceId = id,
                    deviceName = o.optString("deviceName", ""),
                    token = token,
                    pinnedCert = o.optString("pinnedCert", "").ifEmpty { null },
                    pairedAt = o.optLong("pairedAt", 0L)
                )
            )
        }
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SERVER_CERT = "server_cert_pem"
        const val KEY_SERVER_KEY = "server_key_pem"
        const val KEY_PAIRED = "pairedDevices"
    }
}
