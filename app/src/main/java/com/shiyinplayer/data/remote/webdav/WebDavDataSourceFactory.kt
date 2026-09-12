package com.shiyinplayer.data.remote.webdav

import androidx.media3.datasource.DataSource
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import com.shiyinplayer.di.WebDavClientProvider
import javax.inject.Inject

class WebDavDataSourceFactory @Inject constructor(
    private val credStore: WebDavCredentialStore,
    private val zt: ZeroTierManager,
    private val clientProvider: WebDavClientProvider
) : DataSource.Factory {
    override fun createDataSource(): DataSource = WebDavDataSource(credStore, zt, clientProvider)
}