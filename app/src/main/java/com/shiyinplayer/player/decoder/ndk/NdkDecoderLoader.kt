package com.shiyinplayer.player.decoder.ndk

import android.content.Context
import android.os.Build
import android.util.Log
import com.shiyinplayer.player.decoder.OpenSourceLibraryRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NDK 库加载器（P1A 基础设施 §1.3）。
 * 统一 System.loadLibrary + SHA-256 哈希校验 + 审计日志，加载失败降级不崩溃。
 * 详见 specs/audio_decoder_opensource/design.md §2.1.3.2。
 */
@Singleton
class NdkDecoderLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "NdkDecoderLoader"
        private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a")
    }

    private val loadedLibraries: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val auditEntries: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** NDK 加载结果。 */
    enum class NdkLoadResult {
        SUCCESS, MISSING, HASH_MISMATCH, ABI_UNSUPPORTED, ALREADY_LOADED;

        val isSuccess: Boolean get() = this == SUCCESS || this == ALREADY_LOADED

        fun errorMessage(libName: String): String? = when (this) {
            SUCCESS, ALREADY_LOADED -> null
            MISSING -> "库文件 lib$libName.so 不存在"
            HASH_MISMATCH -> "库 $libName 哈希不匹配，可能被篡改"
            ABI_UNSUPPORTED -> "本设备架构不支持 $libName"
        }
    }

    /** 加载 NDK 库：ABI 检查 → .so 存在检查 → SHA-256 哈希校验 → System.loadLibrary。 */
    @Suppress("UNUSED_PARAMETER")
    suspend fun load(libName: String, expectedHashArm64: String, expectedHashArmv7: String): NdkLoadResult {
        if (libName in loadedLibraries) {
            Log.i(TAG, "库 $libName 已加载（缓存）")
            return NdkLoadResult.ALREADY_LOADED
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }
        if (abi == null) {
            Log.e(TAG, "设备 ABI 不支持: ${Build.SUPPORTED_ABIS.toList()}（需 $SUPPORTED_ABIS）")
            auditEntries.add("$libName|ABI_UNSUPPORTED|abi=${Build.SUPPORTED_ABIS.toList()}")
            return NdkLoadResult.ABI_UNSUPPORTED
        }

        val expectedHash = if (abi == "arm64-v8a") expectedHashArm64 else expectedHashArmv7
        val soFile = findSoFile(libName, abi)
        if (soFile == null || !soFile.exists()) {
            Log.e(TAG, "库文件 lib$libName.so 不存在（abi=$abi）")
            auditEntries.add("$libName|MISSING|abi=$abi")
            return NdkLoadResult.MISSING
        }

        val actualHash = withContext(Dispatchers.IO) { calculateSha256(soFile) }
        if (expectedHash != "PLACEHOLDER_ARM64" && expectedHash != "PLACEHOLDER_ARMV7" && actualHash != expectedHash) {
            Log.e(TAG, "库 $libName 哈希不匹配: expected=$expectedHash actual=$actualHash")
            auditEntries.add("$libName|HASH_MISMATCH|expected=$expectedHash actual=$actualHash")
            return NdkLoadResult.HASH_MISMATCH
        }

        return try {
            System.loadLibrary(libName)
            loadedLibraries.add(libName)
            val lib = OpenSourceLibraryRegistry.libraryByName(libName)
            val logMsg = "lib$libName 加载成功，版本=${lib?.version ?: "?"}，许可证=${lib?.license ?: "?"}，来源=${lib?.sourceUrl ?: "?"}"
            Log.i(TAG, logMsg)
            auditEntries.add("$libName|SUCCESS|hash=$actualHash|$logMsg")
            NdkLoadResult.SUCCESS
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "库 $libName 加载失败 (UnsatisfiedLinkError): ${e.message}")
            auditEntries.add("$libName|HASH_MISMATCH|UnsatisfiedLinkError=${e.message}")
            NdkLoadResult.HASH_MISMATCH
        }
    }

    /** 库是否已加载。 */
    fun isLoaded(libName: String): Boolean = libName in loadedLibraries

    /** 全部已加载库审计日志汇总。 */
    fun auditLog(): String = auditEntries.joinToString("\n")

    /** 检查 jniLibs 与注册表一致性：返回缺失清单。 */
    fun verifyJniLibsConsistency(): List<String> {
        val missing = mutableListOf<String>()
        for (lib in OpenSourceLibraryRegistry.allLibraries) {
            if (lib.integrationMode == OpenSourceLibraryRegistry.IntegrationMode.NDK_STATIC ||
                lib.integrationMode == OpenSourceLibraryRegistry.IntegrationMode.NDK_DYNAMIC) {
                for (abi in SUPPORTED_ABIS) {
                    val soFile = findSoFile(lib.name, abi)
                    if (soFile == null || !soFile.exists()) {
                        missing.add("${lib.name} ($abi)")
                    }
                }
            }
        }
        return missing
    }

    @Suppress("UNUSED_PARAMETER")
    private fun findSoFile(libName: String, abi: String): File? {
        return try {
            // P1-2：Android 上 java.library.path 是 /vendor/lib 等系统路径，找不到打包在 jniLibs 的 .so；
            // 改用 applicationInfo.nativeLibraryDir（/data/app/.../lib/<abi>，含本应用全部 jniLibs 产物）。
            val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
            val soFile = File(nativeLibraryDir, "lib$libName.so")
            if (soFile.exists()) soFile else null
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}