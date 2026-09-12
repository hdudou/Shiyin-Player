package com.shiyinplayer.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.player.decoder.AudioFormatRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaItem 单一构造入口（架构 §7：禁止在 UI 直接 new MediaItem）。
 * - SMB/WebDAV/HTTP 直接按 URI scheme 交由 [com.shiyinplayer.data.media.SmartDataSourceFactory] 路由，
 *   远程地址经 ZeroTierManager.mapToLocal 的映射已在数据源层完成（见 SmbDataSource / WebDavDataSource）。
 * - CUE 子曲目裁剪（T15）：song.clipStartMs/clipEndMs 非空时设置 ClippingConfiguration，
 *   由 DefaultMediaSourceFactory 自动包裹 ClippingMediaSource（无需在 UI 处理）。
 */
@Singleton
class MediaItemBuilder @Inject constructor() {
    fun build(song: Song): MediaItem {
        val uri = Uri.parse(song.uri)
        val meta = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artistName)
            .setAlbumTitle(song.albumName)
            .build()
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.id.toString())
            .setMediaMetadata(meta)
        // mimeType：Song 显式值优先；缺失时按 URI 扩展名从注册表兜底（外部 Intent 等场景），
        // 保证 DecoderAwareMediaSourceFactory 能按 mimeType 将 exotic 格式路由到 RawFileExtractor。
        // 中危-E：exotic（NDK/软解）格式的 mime 以注册表为权威——扫描期（ContentResolver/服务端）
        // 报的 mime 可能与注册表不一致，若照单全收会让 DecoderAwareMediaSourceFactory 的
        // exoticMimeTypes 命中失败而退回 defaultFactory，导致远端 ape/wv/dsf 等误判不可播。
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        val registryMime = ext?.let { AudioFormatRegistry.mimeTypeOf(it) }
        val isExoticNdk = ext != null &&
            AudioFormatRegistry.decodePathOf(ext) == AudioFormatRegistry.DecodePath.NDK &&
            AudioFormatRegistry.isFormatActive(ext)
        val mime = if (isExoticNdk) {
            registryMime
        } else {
            song.mimeType?.takeIf { it.isNotBlank() } ?: registryMime
        }
        if (mime != null) builder.setMimeType(mime)
        if (song.clipStartMs != null || song.clipEndMs != null) {
            val clip = MediaItem.ClippingConfiguration.Builder().apply {
                song.clipStartMs?.let { setStartPositionMs(it) }
                song.clipEndMs?.let { setEndPositionMs(it) }
            }.build()
            builder.setClippingConfiguration(clip)
        }
        return builder.build()
    }
}
