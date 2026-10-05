package com.shiyinplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.shiyinplayer.R
import com.shiyinplayer.data.sync.model.SyncContract
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「更多 → 局域网同步」设置页（契约 §7）。
 *
 * 本页是同步功能的唯一入口：总开关、配对码、已配对设备、接收文件夹。
 * 开关切换会直接启停 [com.shiyinplayer.data.sync.SyncAcceptService]（端口 23541）。
 */
@Composable
fun LanSyncSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    // lanSync* 是 DataStore 直出的普通 Flow（非 StateFlow），需显式给初始值
    val enabled by viewModel.lanSyncEnabled.collectAsState(initial = false)
    val deviceName by viewModel.lanSyncDeviceName.collectAsState(initial = "")
    val pin by viewModel.lanSyncPin.collectAsState(initial = "")
    val pinCreatedAt by viewModel.lanSyncPinCreatedAt.collectAsState(initial = 0L)
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val boundAddress by viewModel.syncBoundAddress.collectAsStateWithLifecycle()
    val startError by viewModel.syncStartError.collectAsStateWithLifecycle()

    // 进页面刷新一次运行态（服务是异步启动的，地址/错误不对应 Flow）
    LaunchedEffect(enabled) { viewModel.refreshLanSyncRuntimeState() }

    SettingsScaffold(stringResource(R.string.more_lan_sync), navController) {
        SectionLabel(stringResource(R.string.lan_sync_section_basic))
        SwitchRow(
            label = stringResource(R.string.lan_sync_enable_label),
            summary = stringResource(R.string.lan_sync_enable_summary),
            checked = enabled,
            onCheckedChange = { viewModel.setLanSyncEnabled(it) }
        )

        if (enabled) {
            val address = boundAddress
            Text(
                text = when {
                    address != null -> stringResource(R.string.lan_sync_listen_ok, address, SyncContract.PORT)
                    startError != null -> stringResource(R.string.lan_sync_listen_error, startError ?: "")
                    else -> stringResource(R.string.lan_sync_listen_starting)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (address != null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
        }

        SectionLabel(stringResource(R.string.lan_sync_section_device))
        // 该名称会在 /sync/hello 里返回，PC 用它展示设备；留空则回退为机型
        var nameDraft by remember(deviceName) { mutableStateOf(deviceName) }
        OutlinedTextField(
            value = nameDraft,
            onValueChange = { nameDraft = it },
            singleLine = true,
            label = { Text(stringResource(R.string.lan_sync_device_name_label)) },
            placeholder = { Text(stringResource(R.string.lan_sync_device_name_default)) },
            modifier = Modifier.fillMaxWidth()
        )
        if (nameDraft.trim() != deviceName) {
            OutlinedButton(
                onClick = { viewModel.setLanSyncDeviceName(nameDraft) },
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Text(stringResource(R.string.lan_sync_device_name_save))
            }
        }

        SectionLabel(stringResource(R.string.lan_sync_section_pair))
        PinSection(pin = pin, pinCreatedAt = pinCreatedAt, onRefresh = { viewModel.refreshLanSyncPin() })

        SectionLabel(stringResource(R.string.lan_sync_section_paired))
        if (pairedDevices.isEmpty()) {
            Text(
                stringResource(R.string.lan_sync_paired_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            pairedDevices.forEach { device ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(device.deviceName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(
                                R.string.lan_sync_paired_at,
                                formatTime(device.pairedAt),
                                device.deviceId
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { viewModel.removePairedDevice(device.deviceId) }) {
                        Text(stringResource(R.string.lan_sync_paired_remove))
                    }
                }
            }
        }

        SectionLabel(stringResource(R.string.lan_sync_section_folder))
        Text(
            text = File(context.filesDir, "synced").absolutePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.lan_sync_folder_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 6 位配对码展示 + 倒计时（过期提示刷新）。 */
@Composable
private fun PinSection(pin: String, pinCreatedAt: Long, onRefresh: () -> Unit) {
    // 倒计时：仅在有配对码时每秒重算，页面离开自动停止
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pin, pinCreatedAt) {
        while (pin.isNotEmpty() && pinCreatedAt > 0) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val remainingMs = (pinCreatedAt + SyncContract.PIN_TTL_MS) - now
    val valid = pin.isNotEmpty() && pinCreatedAt > 0 && remainingMs > 0

    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = if (valid) pin.chunked(3).joinToString(" ") else stringResource(R.string.lan_sync_pin_expired),
                style = MaterialTheme.typography.headlineSmall,
                color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (valid) {
                    stringResource(R.string.lan_sync_pin_remaining, formatDuration(remainingMs))
                } else {
                    stringResource(R.string.lan_sync_pin_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onRefresh) {
            Text(stringResource(R.string.lan_sync_pin_refresh))
        }
    }
}

private fun formatTime(ms: Long): String =
    if (ms <= 0) "-" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
