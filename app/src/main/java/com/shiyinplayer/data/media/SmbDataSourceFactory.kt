package com.shiyinplayer.data.media

import android.content.Context
import androidx.media3.datasource.DataSource
import com.shiyinplayer.data.network.zerotier.ZeroTierManager
import javax.inject.Inject

/** 为 ExoPlayer 提供 SmbDataSource（含非 smb 委托）。 */
class SmbDataSourceFactory @Inject constructor(
    private val context: Context,
    private val credStore: SmbCredentialStore,
    private val zt: ZeroTierManager
) : DataSource.Factory {
    override fun createDataSource(): DataSource = SmbDataSource(context, credStore, zt)
}
