package com.shiyinplayer.player.decoder

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * 文件头魔数校验器。按 [AudioFormatRegistry.magicOf] 声明校验文件头，
 * 防止「.mp3 改名 .ac3」等扩展名伪造导致解码失败。ByExtension 格式始终通过。
 * 详见 specs/audio_decoder_ext/tasks.md §1.3。
 */
object MagicNumberValidator {
    private const val MAX_HEADER = 64

    /** 用已读取的前缀字节校验（远程扫描复用已下载前缀，避免额外 IO）。 */
    fun validate(prefixBytes: ByteArray, ext: String): Boolean {
        val magic = AudioFormatRegistry.magicOf(ext) ?: return false
        return when (magic) {
            is AudioFormatRegistry.MagicSpec.ByExtension -> true
            is AudioFormatRegistry.MagicSpec.HexBytes -> matchBytes(prefixBytes, magic.bytes, magic.offset)
            is AudioFormatRegistry.MagicSpec.Ascii -> matchBytes(prefixBytes, magic.text.toByteArray(Charsets.US_ASCII), magic.offset)
        }
    }

    /** 本地 URI 校验：读文件头前缀字节再校验。IO 异常返回 false（无法读取视为校验失败）。 */
    fun validate(uri: Uri, ext: String, context: Context): Boolean {
        val magic = AudioFormatRegistry.magicOf(ext) ?: return false
        if (magic is AudioFormatRegistry.MagicSpec.ByExtension) return true
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(MAX_HEADER)
                val n = stream.read(buf)
                if (n <= 0) false else validate(buf.copyOf(n), ext)
            } ?: false
        } catch (e: Exception) {
            Log.w("MagicValidate", "validate($uri, $ext) failed: ${e.message}")
            false
        }
    }

    /**
     * 本地**文件**校验（Batch 4 / B4-2）：直接开 FileInputStream，不经 contentResolver。
     *
     * 扫描本机文件夹时文件本来就是 `java.io.File`，走 `Uri.fromFile` + contentResolver 只是多绕一层
     * —— 那个间接层是为 SAF 的 `content://` 准备的，对普通路径没有意义。
     *
     * ⚠️ 关于"二次读盘"的边界（别误以为这里能省掉一次完整读）：
     * 本方法读的是**定长 64 字节**前缀，且 [AudioFormatRegistry.MagicSpec.ByExtension] 声明的格式
     * **根本不读**（直接返回 true）。所以只有"确实声明了魔数"的格式才会有这一次额外的小读，
     * 而这次读正是校验本身的目的 —— 想彻底去掉它，只能改成"MMR 能解析就算已验证"，
     * 那会改变 `formatVerified` 这个已持久化字段的含义，不在本批范围内。
     */
    fun validate(file: File, ext: String): Boolean {
        val magic = AudioFormatRegistry.magicOf(ext) ?: return false
        if (magic is AudioFormatRegistry.MagicSpec.ByExtension) return true
        return try {
            FileInputStream(file).use { stream ->
                val buf = ByteArray(MAX_HEADER)
                val n = stream.read(buf)
                if (n <= 0) false else validate(buf.copyOf(n), ext)
            }
        } catch (e: Exception) {
            Log.w("MagicValidate", "validate(${file.name}, $ext) failed: ${e.message}")
            false
        }
    }

    private fun matchBytes(data: ByteArray, expected: ByteArray, offset: Int): Boolean {
        if (offset < 0 || data.size < offset + expected.size) return false
        for (i in expected.indices) {
            if (data[offset + i] != expected[i]) return false
        }
        return true
    }
}