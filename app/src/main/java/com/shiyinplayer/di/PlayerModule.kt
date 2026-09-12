package com.shiyinplayer.di

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.shiyinplayer.lockscreen.LockScreenVisualizer
import com.shiyinplayer.player.AudioRoutingController
import com.shiyinplayer.player.EqualizerManager
import com.shiyinplayer.player.MediaItemBuilder
import com.shiyinplayer.player.MediaSessionManager
import com.shiyinplayer.player.PlayerFactory
import com.shiyinplayer.player.PlayerManager
import com.shiyinplayer.player.QueueController
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 播放层依赖：ExoPlayer（构建委托 [PlayerFactory]，支持热重建）、队列、MediaItem 构造、
 * 管理器、MediaSession。
 * 2026-08-21：播放器构建逻辑（含均衡器等 AudioProcessor 注入、LoadControl、NDK 渲染器）
 * 已抽到 [PlayerFactory]——音频链开关 #9-12 切换时由 PlayerManager.rebuildPlayer 热重建，
 * 运行时即时生效，无需重启 App。
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideEqualizerManager(settings: SettingsRepository): EqualizerManager =
        EqualizerManager(settings)

    @Provides
    @Singleton
    fun provideAudioRoutingController(): AudioRoutingController = AudioRoutingController()

    @Provides
    @Singleton
    fun provideExoPlayer(playerFactory: PlayerFactory): ExoPlayer = playerFactory.create()

    @Provides
    @Singleton
    fun provideQueueController(): QueueController = QueueController()

    @Provides
    @Singleton
    fun provideMediaItemBuilder(): MediaItemBuilder = MediaItemBuilder()

    @Provides
    @Singleton
    fun provideMediaSessionManager(
        @ApplicationContext context: Context,
        playerManager: PlayerManager,
        settings: SettingsRepository
    ): MediaSessionManager = MediaSessionManager(context, playerManager, settings)

    @Provides
    @Singleton
    fun provideLockScreenVisualizer(): LockScreenVisualizer = LockScreenVisualizer()
}
