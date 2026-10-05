package com.shiyinplayer.data.sync

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 同步凭据信封（契约 §9，与 PC 端 `Shiyin.Core/Data/Transfer/TransferEnvelope` **字节兼容**）。
 *
 * 外层结构：
 * ```json
 * { "app":"shiyin", "schemaVersion":2, "encrypted":true,
 *   "crypto": { "kdf":"pbkdf2-sha256", "iter":120000, "salt":"<b64 16B>", "iv":"<b64 12B>" },
 *   "cipher": "<b64 密文‖GCM tag(16B)>" }
 * ```
 * ⚠️ **tag 附在密文末尾**（不是独立字段），故解密时密文长度 − 16 = 明文长度。
 * ⚠️ `iter` **读信封里的值**（不写死 120000），以便对端将来提升迭代次数时仍能解开。
 *
 * **口令 = 该配对设备的 deviceToken**：配对时经一次性 6 位码 + TLS 分配，两端各自安全存储
 * （安卓 = EncryptedSharedPreferences，PC = DPAPI），因此无需额外信令通道或用户再次输入口令。
 *
 * 算法与 `data/transfer/DataTransferManager` 内的同名实现保持一致（同 iter / 同 tag 布局），
 * 不直接复用后者是因为那个类面向「整包文件导入导出」的字节流，与本处「JSON 字段内嵌信封」形态不同。
 */
object SyncCredentialCipher {

    private const val TAG = "SyncCredentialCipher"
    private const val KDF = "pbkdf2-sha256"
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val TAG_BYTES = 16

    /** 明文 JSON → 信封 JSON 字符串。 */
    fun seal(plainJson: String, password: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt, ITERATIONS)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        // Android 的 doFinal 输出即「密文‖tag」，与 PC 的 AesGcm.Encrypt 布局一致
        val sealed = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))

        return JSONObject()
            .put("app", "shiyin")
            .put("schemaVersion", 2)
            .put("encrypted", true)
            .put(
                "crypto",
                JSONObject()
                    .put("kdf", KDF)
                    .put("iter", ITERATIONS)
                    .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            )
            .put("cipher", Base64.encodeToString(sealed, Base64.NO_WRAP))
            .toString()
    }

    /**
     * 信封 JSON → 明文 JSON；不是信封、格式非法或口令错误一律返回 null
     * （凭据是增值信息，解密失败绝不能让整个 op 失败）。
     */
    fun open(envelopeJson: String?, password: String?): String? {
        if (envelopeJson.isNullOrBlank() || password.isNullOrBlank()) return null

        return runCatching {
            val root = JSONObject(envelopeJson)
            if (!root.optBoolean("encrypted", false)) return@runCatching null

            val crypto = root.optJSONObject("crypto") ?: return@runCatching null
            if (crypto.optString("kdf") != KDF) {
                Log.w(TAG, "不支持的 KDF：${crypto.optString("kdf")}")
                return@runCatching null
            }

            // 读信封里的 iter（不写死），保证对端提升迭代次数后仍可解开
            val iter = crypto.optInt("iter", ITERATIONS)
            val salt = Base64.decode(crypto.getString("salt"), Base64.NO_WRAP)
            val iv = Base64.decode(crypto.getString("iv"), Base64.NO_WRAP)
            val sealed = Base64.decode(root.getString("cipher"), Base64.NO_WRAP)
            if (sealed.size <= TAG_BYTES) return@runCatching null

            val key = deriveKey(password, salt, iter)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        }.onFailure {
            // GCM tag 校验失败（口令不符）也会走到这里：属预期情况，不刷 ERROR 级日志
            Log.i(TAG, "凭据信封解密失败：${it.message}")
        }.getOrNull()
    }

    /** PBKDF2-HMAC-SHA256 派生 AES-256 密钥。 */
    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
