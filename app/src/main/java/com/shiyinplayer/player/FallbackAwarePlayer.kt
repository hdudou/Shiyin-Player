package com.shiyinplayer.player

import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.shiyinplayer.data.model.Song

/**
 * MediaSession 兜底格式状态桥接（2026-08-24，功能缺口 #1）：
 *
 * 背景：MediaSession 直接绑定 ExoPlayer。APE/WMA 走系统 MediaPlayer 兜底时 ExoPlayer 处于
 * 暂停/空载态，导致系统媒体面板 / 锁屏 / 通知读到「暂停、进度冻结、无曲目」的错误状态，
 * 且其控制命令路由到闲置的 ExoPlayer 而非真正发声的兜底引擎。
 *
 * 方案：[ForwardingPlayer] 包装当前 ExoPlayer，平时完全透传（零回归）；兜底播放活跃时
 * 用合并后的 [PlayerManager.playbackState] 覆盖状态读数，并把传输命令转投回到
 * [PlayerManager] 的兜底感知门面（play/pause/next/previous/seekTo 内部已判兜底）。
 */
@UnstableApi
internal class FallbackAwarePlayer(
    private val exo: ExoPlayer,
    private val pm: PlayerManager
) : ForwardingPlayer(exo) {

    private val fbActive: Boolean get() = pm.fallbackActive
    private val merged get() = pm.playbackState.value

    // ===== 状态读数（兜底时覆盖） =====
    override fun getPlayWhenReady(): Boolean = if (fbActive) merged.isPlaying else super.getPlayWhenReady()
    override fun isPlaying(): Boolean = if (fbActive) merged.isPlaying else super.isPlaying()
    override fun getCurrentPosition(): Long = if (fbActive) merged.positionMs else super.getCurrentPosition()
    override fun getDuration(): Long = if (fbActive) merged.durationMs.coerceAtLeast(0) else super.getDuration()

    override fun getMediaMetadata(): MediaMetadata =
        if (fbActive) merged.currentSong?.let { it.toMediaMetadata() } ?: super.getMediaMetadata()
        else super.getMediaMetadata()

    // ===== 命令路由（兜底时转投兜底感知门面） =====
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (fbActive) {
            if (playWhenReady) pm.play() else pm.pause()
        } else super.setPlayWhenReady(playWhenReady)
    }

    override fun play() {
        if (fbActive) pm.play() else super.play()
    }

    override fun pause() {
        if (fbActive) pm.pause() else super.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (fbActive) pm.seekTo(positionMs) else super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (fbActive) pm.seekTo(positionMs) else super.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekBack() {
        if (fbActive) seekTo((merged.positionMs - 10_000).coerceAtLeast(0)) else super.seekBack()
    }

    override fun seekForward() {
        if (fbActive) seekTo(merged.positionMs + 10_000) else super.seekForward()
    }

    override fun seekToPrevious() {
        if (fbActive) pm.previous() else super.seekToPrevious()
    }

    override fun seekToNext() {
        if (fbActive) pm.next() else super.seekToNext()
    }

    override fun seekToPreviousMediaItem() {
        if (fbActive) pm.previous() else super.seekToPreviousMediaItem()
    }

    override fun seekToNextMediaItem() {
        if (fbActive) pm.next() else super.seekToNextMediaItem()
    }

    override fun seekToPreviousWindow() {
        if (fbActive) pm.previous() else super.seekToPreviousWindow()
    }

    override fun seekToNextWindow() {
        if (fbActive) pm.next() else super.seekToNextWindow()
    }
}

/** 曲目 → MediaMetadata（兜底时系统媒体面板/通知展示曲名/歌手/专辑/封面）。 */
@UnstableApi
private fun Song.toMediaMetadata(): MediaMetadata = MediaMetadata.Builder()
    .setTitle(title)
    .setArtist(artistName)
    .setAlbumTitle(albumName ?: title)
    .setArtworkUri(albumArtUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
    .build()