package com.shiyinplayer.data.network.zerotier

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ZeroTier 配置（架构 §7：Network ID / 自动重连开关走 DataStore，非敏感；无 Token/密码）。
 */
@Singleton
class ZeroTierConfig @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val NETWORK_ID = stringPreferencesKey("zt_network_id")
    private val AUTO_RECONNECT = booleanPreferencesKey("zt_auto_reconnect")
    private val INIT_DONE = booleanPreferencesKey("zt_init_done")

    val networkId: Flow<String?> = dataStore.data.map { it[NETWORK_ID]?.takeIf { it.isNotBlank() } }
    val autoReconnect: Flow<Boolean> = dataStore.data.map { it[AUTO_RECONNECT] ?: true }

    /**
     * 是否已做过首次初始化（[INIT_DONE] 标记）。用于区分「全新安装」与「覆盖升级」：
     * 覆盖升级时该标记已由旧版在首次启动写入，返回 true → 内置默认网络/源/凭据不再生效，保留旧数据。
     */
    suspend fun isInitDone(): Boolean = dataStore.data.first()[INIT_DONE] == true

    suspend fun setNetworkId(id: String?) = dataStore.edit { it[NETWORK_ID] = id ?: "" }
    suspend fun setAutoReconnect(value: Boolean) = dataStore.edit { it[AUTO_RECONNECT] = value }

    /**
     * 首次启动预置默认网络 ID（R2-03）：仅执行一次（由 [INIT_DONE] 标记）。
     * 之后用户手动清空 networkId 不会被再次覆盖，尊重用户「不连接」的意图。
     */
    suspend fun ensureDefaultNetwork(defaultId: String) {
        dataStore.edit { prefs ->
            if (prefs[INIT_DONE] != true) {
                if (prefs[NETWORK_ID].isNullOrBlank()) prefs[NETWORK_ID] = defaultId
                prefs[INIT_DONE] = true
            }
        }
    }
}
