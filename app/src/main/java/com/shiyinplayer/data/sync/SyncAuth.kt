package com.shiyinplayer.data.sync

import android.os.Build
import android.util.Base64
import android.util.Log
import com.shiyinplayer.data.sync.model.SyncContract
import com.shiyinplayer.ui.settings.SettingsRepository
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * 配对 / 鉴权 / TLS 服务端证书（契约 §3、§9）。
 *
 * - 配对码：6 位数字，5 分钟时效（[SyncContract.PIN_TTL_MS]），存 DataStore 供设置页展示
 * - `deviceToken`：32 字节随机，Base64（NO_WRAP）；每个 PC 独立一份
 * - 服务端证书：RSA-2048 自签，**首次生成后持久化**（PC 按 SHA-1 指纹 pinning，证书必须稳定）
 *
 * 证书不写 subjectAltName：PC 侧校验回调显式容忍 `RemoteCertificateNameMismatch`
 * （`SyncPeerClient.ValidateCertificate`），且已配对后走指纹比对，主机名校验不参与。
 */
@Singleton
class SyncAuth @Inject constructor(
    private val pairingStore: SyncPairingStore,
    private val settings: SettingsRepository
) {

    private val random = SecureRandom()

    /** 内存即时生效的配对码（DataStore 落盘有异步延迟，避免刚刷新就配对失败）。 */
    @Volatile
    private var pinCache: String = ""

    @Volatile
    private var pinCreatedAtCache: Long = 0L

    @Volatile
    private var sslFactoryCache: SSLServerSocketFactory? = null

    val deviceId: String get() = pairingStore.deviceId

    /** 设备展示名：设置页自定义优先，否则用机型。 */
    fun deviceName(): String {
        val custom = settings.lanSyncDeviceNameSync().trim()
        return custom.ifEmpty { Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android" }
    }

    fun isSyncEnabled(): Boolean = settings.lanSyncEnabledSync()

    // ------------------------------------------------------------ 配对码

    /** 生成并即时生效新配对码；返回明文供设置页展示（调用方负责 `setLanSyncPin` 持久化）。 */
    fun issueNewPin(): String {
        val pin = buildString { repeat(6) { append(random.nextInt(10)) } }
        pinCache = pin
        pinCreatedAtCache = System.currentTimeMillis()
        return pin
    }

    /** 当前配对码（未生成或已过期返回空串 → 设置页提示「点击刷新」）。 */
    fun currentPin(): String {
        val pin = if (pinCache.isNotEmpty()) pinCache else settings.lanSyncPinSync()
        val created = if (pinCache.isNotEmpty()) pinCreatedAtCache else settings.lanSyncPinCreatedAtSync()
        if (pin.isEmpty() || created <= 0L) return ""
        return if (System.currentTimeMillis() - created > SyncContract.PIN_TTL_MS) "" else pin
    }

    fun pinCreatedAt(): Long =
        if (pinCache.isNotEmpty()) pinCreatedAtCache else settings.lanSyncPinCreatedAtSync()

    /** 校验 PC 提交的配对码（6 位 + 时效 + 定长比较）。 */
    fun validatePin(input: String?): Boolean {
        val given = input?.trim().orEmpty()
        if (given.length != 6) return false
        val expected = if (pinCache.isNotEmpty()) pinCache else settings.lanSyncPinSync()
        val created = if (pinCache.isNotEmpty()) pinCreatedAtCache else settings.lanSyncPinCreatedAtSync()
        if (expected.length != 6 || created <= 0L) return false
        if (System.currentTimeMillis() - created > SyncContract.PIN_TTL_MS) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            given.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * 配对成功后作废配对码（一次性使用，防重放）。
     *
     * ⚠️ 必须**内存 + DataStore 双清**：只清内存 `pinCache` 时，[validatePin] 会回落到落盘值，
     * 同一配对码在其 5 分钟时效内可被反复使用（真机实测 2026-09-15：同一 PIN 连续两次都换到了 token）。
     */
    suspend fun consumePin() {
        pinCache = ""
        pinCreatedAtCache = 0L
        settings.setLanSyncPin("", 0L)
    }

    // ------------------------------------------------------------ token

    /** 签发新的 deviceToken（32 字节随机 → Base64 NO_WRAP）。 */
    fun issueToken(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 校验 `X-Device-Token`，命中返回对应配对记录。 */
    fun verifyToken(header: String?): PairedDevice? = pairingStore.findByToken(header)

    // ------------------------------------------------------------ TLS 证书

    /** 取（或首次生成并持久化）服务端证书与私钥。 */
    fun ensureServerKeyMaterial(): ServerKeyMaterial {
        pairingStore.getServerKeyMaterial()?.let { return it }
        return synchronized(this) {
            pairingStore.getServerKeyMaterial() ?: generateAndSave()
        }
    }

    /** 对外展示/返回给 PC 的证书 PEM（契约 §3 `cert` 字段）。 */
    fun serverCertPem(): String = ensureServerKeyMaterial().certPem

    /** TLS 服务端 socket 工厂（优先 TLS 1.3，minSdk 24 设备降级 1.2 由 NanoHTTPD 侧协议数组处理）。 */
    fun sslServerSocketFactory(): SSLServerSocketFactory {
        sslFactoryCache?.let { return it }
        return synchronized(this) {
            sslFactoryCache ?: buildSslFactory().also { sslFactoryCache = it }
        }
    }

    private fun buildSslFactory(): SSLServerSocketFactory {
        val material = ensureServerKeyMaterial()
        val cert = parseCertificate(material.certPem)
        val key = parsePrivateKey(material.keyPem)

        // 口令仅存于内存：keystore 每次进程启动重建，不需要口令持久化。
        val password = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keyStore.setKeyEntry(KEY_ALIAS, key, password.toCharArray(), arrayOf(cert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password.toCharArray())

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, random)
        }.serverSocketFactory
    }

    private fun generateAndSave(): ServerKeyMaterial {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val now = System.currentTimeMillis()
        // notBefore 回拨 1 天：规避双方时钟偏差导致的「证书尚未生效」
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000)
        val subject = X500Name("CN=Shiyin Sync, O=Shiyin, OU=$deviceId")

        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, random).abs().add(BigInteger.ONE),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )

        val holder = builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        val certificate = JcaX509CertificateConverter().getCertificate(holder)
        certificate.verify(keyPair.public)

        val material = ServerKeyMaterial(
            certPem = toPem(certificate),
            keyPem = toPem(keyPair.private)
        )
        pairingStore.saveServerKeyMaterial(material)
        return material
    }

    private fun toPem(obj: Any): String = StringWriter().also { sw ->
        JcaPEMWriter(sw).use { it.writeObject(obj) }
    }.toString()

    /** PEM → X509（标准 JCA，未引入 BC 解析路径）。 */
    private fun parseCertificate(pem: String): X509Certificate {
        val der = Base64.decode(pemBody(pem, "CERTIFICATE"), Base64.DEFAULT)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate
    }

    /**
     * PEM → RSA 私钥。**两种写法都要吃**：
     * - `-----BEGIN PRIVATE KEY-----`（PKCS#8，JCA 直读）
     * - `-----BEGIN RSA PRIVATE KEY-----`（PKCS#1，BC 的 `JcaPEMWriter` 在某些 provider 下会这么写）
     *
     * 真机教训（2026-09-15 ACE7V）：只认 PKCS#8 时，`JcaPEMWriter` 实际写出的是 PKCS#1 头，
     * 于是 `ensureServerKeyMaterial()` 抛异常 → 服务启动失败 → 连带触发
     * `ForegroundServiceDidNotStartInTimeException` 把整个 App 打崩。这里做兼容 + 失败时打真实头。
     */
    private fun parsePrivateKey(pem: String): PrivateKey {
        val header = pem.lineSequence().firstOrNull { it.contains("-----BEGIN") } ?: "(无 PEM 头)"
        return when {
            pem.contains("-----BEGIN PRIVATE KEY-----") -> {
                val der = Base64.decode(pemBody(pem, "PRIVATE KEY"), Base64.DEFAULT)
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
            }

            pem.contains("-----BEGIN RSA PRIVATE KEY-----") -> {
                // PKCS#1 的 DER 是裸 RSAPrivateKey（SEQUENCE of INTEGER），不能直接当 PrivateKeyInfo 解析；
                // 需包一层 AlgorithmIdentifier(rsaEncryption, NULL) 组装成 PKCS#8，再走标准 JCA
                // （不依赖 BC provider 注册，避免 provider 名冲突）。
                val pkcs1 = org.bouncycastle.asn1.ASN1Sequence.getInstance(
                    Base64.decode(pemBody(pem, "RSA PRIVATE KEY"), Base64.DEFAULT)
                )
                val algId = org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                    org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.rsaEncryption,
                    org.bouncycastle.asn1.DERNull.INSTANCE
                )
                val pkcs8 = org.bouncycastle.asn1.pkcs.PrivateKeyInfo(algId, pkcs1).encoded
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            }

            else -> {
                Log.e(TAG, "私钥 PEM 头无法识别：$header（长度 ${pem.length}）")
                error("不支持的私钥 PEM 头：$header")
            }
        }
    }

    private fun pemBody(pem: String, type: String): String {
        val begin = "-----BEGIN $type-----"
        val end = "-----END $type-----"
        val start = pem.indexOf(begin)
        require(start >= 0) { "PEM 缺少 $begin" }
        val bodyStart = start + begin.length
        val bodyEnd = pem.indexOf(end, bodyStart)
        require(bodyEnd > bodyStart) { "PEM 缺少 $end" }
        return pem.substring(bodyStart, bodyEnd).filterNot { it.isWhitespace() }
    }

    private companion object {
        const val KEY_ALIAS = "shiyin-sync"
        const val TAG = "SyncAuth"
    }
}
