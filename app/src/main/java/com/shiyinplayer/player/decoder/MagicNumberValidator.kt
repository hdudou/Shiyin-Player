package com.shiyinplayer.player.decoder

import android.content.Context
import android.net.Uri
import android.util.Log

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

    private fun matchBytes(data: ByteArray, expected: ByteArray, offset: Int): Boolean {
        if (offset < 0 || data.size < offset + expected.size) return false
        for (i in expected.indices) {
            if (data[offset + i] != expected[i]) return false
        }
        return true
    }
}