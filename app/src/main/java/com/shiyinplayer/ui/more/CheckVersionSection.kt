package com.shiyinplayer.ui.more

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.shiyinplayer.util.AppVersion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 更多界面「检查版本更新」入口 + 更新全流程弹窗。
 * 点击后经 ZT WebDAV 读 latest.json 检查新版本，发现则提示更新，
 * 同意后下载安装包（含进度）并经系统安装器安装。
 */
@Composable
fun CheckVersionSection(
    viewModel: AppUpdateViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onInstallPermissionReturn() }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.installResult(result.resultCode == Activity.RESULT_OK)
    }

    // 下载完成 → 自动拉起系统安装器
    LaunchedEffect(state) {
        val s = state
        if (s is UpdateUiState.ReadyToInstall) {
            installLauncher.launch(viewModel.installApkIntent(s.file))
        }
    }

    // ===== 入口行（竖屏列表项 / 横屏卡片通用） =====
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.check() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text("检查版本更新",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
            Text(
                when (state) {
                    is UpdateUiState.Checking -> "正在检查…"
                    is UpdateUiState.Downloading -> "正在下载新版本…"
                    is UpdateUiState.NewVersion -> "发现新版本，点击查看"
                    is UpdateUiState.UpToDate -> "已是最新版本"
                    is UpdateUiState.NeedInstallPermission -> "需授权后安装更新"
                    is UpdateUiState.Installed -> "安装处理完成"
                    else -> "检查并安装最新版本"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state is UpdateUiState.Checking || state is UpdateUiState.Downloading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // ===== 发现新版本：确认弹窗 =====
    val newVersion = state as? UpdateUiState.NewVersion
    if (newVersion != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("发现新版本 v${newVersion.info.versionName}") },
            text = {
                Column {
                    Text("检查到可用更新（构建 ${newVersion.info.versionCode}）。")
                    val note = newVersion.info.releaseNote
                    if (!note.isNullOrBlank()) {
                        note.split(Regex("[\r\n/；;]+"))
                            .map { it.trim() }.filter { it.isNotEmpty() }
                            .forEachIndexed { index, line ->
                                Text("${index + 1}. $line")
                            }
                    }
                    Text("下载需要占用网络与存储，确定要更新吗？")
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmUpdate) { Text("现在更新") } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("取消") } }
        )
    }

    // ===== 需要安装权限 =====
    val needPerm = state as? UpdateUiState.NeedInstallPermission
    if (needPerm != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("需要安装权限") },
            text = { Text("安装更新前，需允许本应用安装未知来源的应用。是否前往授权？") },
            confirmButton = {
                TextButton(onClick = {
                    permLauncher.launch(viewModel.installPermissionSettingsIntent())
                }) { Text("去授权") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("取消") } }
        )
    }

    // ===== 正在下载：进度弹窗 =====
    val downloading = state as? UpdateUiState.Downloading
    if (downloading != null) {
        val pct = if (downloading.total > 0)
            (downloading.downloaded.toFloat() / downloading.total * 100).toInt().coerceIn(0, 100)
        else 0
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载更新") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = (downloading.downloaded.toFloat() / (downloading.total.takeIf { it > 0 } ?: 1)).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("已下载 ${downloading.downloaded} / ${downloading.total} 字节（$pct%）")
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // ===== 无新版本 =====
    if (state is UpdateUiState.UpToDate) {
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("已是最新版本") },
            text = { Text("当前版本 ${AppVersion.displayName} 已是最新。") },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("知道了") } }
        )
    }

    // ===== 安装结果 =====
    val installed = state as? UpdateUiState.Installed
    if (installed != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(if (installed.success) "去安装新版本" else "安装已取消") },
            text = { Text(if (installed.success) "新版本v${installed.versionName}安装包已就绪，请在系统安装界面确认安装。" else "已取消安装，可稍后重新检查更新。") },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("知道了") } }
        )
    }

    // ===== 错误 =====
    val err = state as? UpdateUiState.Error
    if (err != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("检查更新") },
            text = { Text(err.message) },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("知道了") } }
        )
    }
}