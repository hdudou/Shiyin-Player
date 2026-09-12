package com.shiyinplayer.ui.more

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch

/** 播放器数据导出界面（更多 → 播放器数据导出/导入 → 导出数据）。密码保护默认开启。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataExportScreen(
    navController: NavController,
    viewModel: DataTransferViewModel = hiltViewModel()
) {
    val snackbar = SnackbarHostState()
    val scope = rememberCoroutineScope()
    val busy by viewModel.busy.collectAsState()

    var includeSettings by rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var includeSongs by rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var includePlaylists by rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var protectExport by rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var exportPassword by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var exportPasswordConfirm by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }

    LaunchedEffect(viewModel.message.value) {
        viewModel.message.value?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            viewModel.exportTo(
                it, includeSettings, includeSongs, includePlaylists,
                if (protectExport) exportPassword else null
            )
        }
    }

    fun runExport() {
        if (!(includeSettings || includeSongs || includePlaylists)) return
        if (protectExport && exportPassword.isBlank()) {
            scope.launch { snackbar.showSnackbar("请输入导出密码") }
            return
        }
        if (protectExport && exportPassword != exportPasswordConfirm) {
            scope.launch { snackbar.showSnackbar("两次输入的密码不一致") }
            return
        }
        createDoc.launch(viewModel.defaultFileName())
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("导出数据") },
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
                "可将系统设置（含网络和源设置、开关状态与登录凭据）、曲库歌曲条目（含元数据）与保存的歌单导出为单个数据文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            ExportRow("系统设置", "含网络和源设置、开关状态、网络源登录凭据", includeSettings) { includeSettings = it }
            ExportRow("曲库歌曲条目", "含歌曲元数据（标题/歌手/专辑/年份等）", includeSongs) { includeSongs = it }
            ExportRow("保存的歌单", "含各歌单及其中曲目", includePlaylists) { includePlaylists = it }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = protectExport, onCheckedChange = { protectExport = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("密码保护", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "加密导出的数据文件，导入时需输入此密码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (protectExport) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = exportPassword,
                    onValueChange = { exportPassword = it },
                    label = { Text("导出密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = exportPasswordConfirm,
                    onValueChange = { exportPasswordConfirm = it },
                    label = { Text("确认密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { runExport() },
                enabled = !busy && (includeSettings || includeSongs || includePlaylists),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在导出…")
                } else {
                    Text("导出")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExportRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}