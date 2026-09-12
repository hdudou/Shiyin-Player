package com.shiyinplayer.di

import com.shiyinplayer.data.network.zerotier.LibztZtSocketFactory
import com.shiyinplayer.data.network.zerotier.ZtSocketFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 网络层依赖（T14）：提供 WebDavClient 所需的 [OkHttpClient]；绑定 [ZtSocketFactory] 为 libzt 实现。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * WebDAV 客户端改由 [WebDavClientProvider] 按主机分流（内网/ZeroTier 信任自签，外链严格 TLS），
     * 不再提供「信任所有证书」的全局 @Named("webdav") 单客户端。
     */
    @Provides
    @Singleton
    fun provideZtSocketFactory(): ZtSocketFactory = LibztZtSocketFactory()
}
