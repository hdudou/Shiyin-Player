package com.shiyinplayer.ui.more

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.shiyinplayer.data.transfer.ImportPreview

/** 播放器数据导入界面（更多 → 播放器数据导出/导入 → 导入数据）。导入过程显示转动图标。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataImportScreen(
    navController: NavController,
    viewModel: DataTransferViewModel = hiltViewModel()
) {
    val snackbar = SnackbarHostState()
    val busy by viewModel.busy.collectAsState()
    val importing by viewModel.importing.collectAsState()

    LaunchedEffect(viewModel.message.value) {
        viewModel.message.value?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.prepareImport(it) } }

    val preview by viewModel.preview.collectAsState()
    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("确认导入") },
            text = { ConfirmImportText(p) },
            confirmButton = {
                TextButton(onClick = { viewModel.performImport() }) {
                    Text("继续导入", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) { Text("取消") }
            }
        )
    }

    val needImportPassword by viewModel.needImportPassword.collectAsState()
    if (needImportPassword) {
        var importPassword by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.cancelImportPassword() },
            title = { Text("输入密码") },
            text = {
                Column {
                    Text(
                        "该数据文件已启用密码保护，请输入导出时设置的密码以解密导入。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        label = { Text("导入密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.submitImportPassword(importPassword) }) {
                    Text("解密导入", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImportPassword() }) { Text("取消") }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text("导入数据") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "可选择导出的数据文件恢复：系统设置（含网络和源设置、开关状态与登录凭据）、曲库歌曲条目（含元数据）与保存的歌单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "导入将覆盖本机已有的对应数据，导入前请确认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { openDoc.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "处理中…" else "选择数据文件导入")
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // 正在导入的覆盖层转动提示
        if (importing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "正在导入，请稍候…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.surface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmImportText(p: ImportPreview) {
    Column {
        Text("导入将会覆盖本机已有的相应数据，是否继续？", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        val lines = mutableListOf<String>()
        if (p.hasSettings) {
            val parts = mutableListOf<String>()
            if (p.settingCount > 0) parts += "设置 ${p.settingCount} 项"
            if (p.sourceCount > 0) parts += "音乐源 ${p.sourceCount} 个（含网络和源设置）"
            if (p.credentialCount > 0) parts += "登录凭据 ${p.credentialCount} 项"
            lines += "系统设置：${parts.joinToString("、")}"
        }
        if (p.songCount > 0) {
            val a = listOf("歌曲 ${p.songCount} 首") +
                (if (p.albumCount > 0) listOf("专辑 ${p.albumCount} 张") else emptyList()) +
                (if (p.artistCount > 0) listOf("艺术家 ${p.artistCount} 位") else emptyList())
            lines += "曲库歌曲：${a.joinToString("、")}"
        }
        if (p.playlistCount > 0) {
            lines += if (p.itemCount > 0) "保存的歌单：${p.playlistCount} 个（共 ${p.itemCount} 条曲目）"
            else "保存的歌单：${p.playlistCount} 个"
        }
        lines.forEach { l ->
            Text("• $l", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}