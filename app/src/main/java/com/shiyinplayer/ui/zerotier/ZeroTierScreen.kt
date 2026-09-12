package com.shiyinplayer.ui.zerotier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.data.network.zerotier.ZtStatus


@Composable
fun ZeroTierScreen(viewModel: ZeroTierViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val ip by viewModel.virtualIp.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val networkId by viewModel.networkId.collectAsStateWithLifecycle()
    val autoReconnect by viewModel.autoReconnect.collectAsStateWithLifecycle()
    // 2026-08-19 修复：remember 依赖 networkId——networkId 从 DataStore 异步加载（首次为 null）
    // 完成后自动预填输入框，否则输入框永远为空（与 SongsScreen 的 remember 缓存同类问题）。
    var text by remember(networkId) { mutableStateOf(networkId ?: "") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("ZeroTier 虚拟网络", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Network ID") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.join(text) }) { Text("加入网络") }
            Button(onClick = viewModel::leave) { Text("离开") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("启动自动连接 / 掉线自动重连", modifier = Modifier.weight(1f))
            Switch(checked = autoReconnect, onCheckedChange = viewModel::setAutoReconnect)
        }
        Text("状态：${status.name}", modifier = Modifier.padding(top = 8.dp))
        ip?.let { Text("虚拟 IP：$it") }
        error?.let {
            Text(
                "错误：$it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (status == ZtStatus.CONNECTING) {
            Text(
                "连接中…若长时间未变为「已连接」，请确认已在 ZeroTier 控制台批准本节点，并确认 libzt 原生库已正确打包。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else if (status != ZtStatus.CONNECTED && error == null) {
            Text(
                "提示：需在 ZeroTier 控制台批准本节点；libzt 原生库缺失时功能不可用（降级模式）。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
