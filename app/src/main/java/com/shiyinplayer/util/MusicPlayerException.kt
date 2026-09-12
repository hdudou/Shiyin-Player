package com.shiyinplayer.util

/**
 * 统一异常层级（架构 §7：异常处理 MusicPlayerException）。
 */
sealed class MusicPlayerException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class ScanException(message: String, cause: Throwable? = null) :
        MusicPlayerException(message, cause)

    class PlaybackException(message: String, cause: Throwable? = null) :
        MusicPlayerException(message, cause)

    class SourceUnreachableException(message: String, cause: Throwable? = null) :
        MusicPlayerException(message, cause)

    class FormatUnsupportedException(format: String) :
        MusicPlayerException("不支持的格式：$format")

    class CueParseException(message: String, cause: Throwable? = null) :
        MusicPlayerException(message, cause)
}
