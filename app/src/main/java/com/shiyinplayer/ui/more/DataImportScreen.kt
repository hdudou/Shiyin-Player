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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.shiyinplayer.R
import com.shiyinplayer.data.transfer.ImportPreview

/** 播放器数据导入界面（更多 → 播放器数据导出/导入 → 导入数据）。导入过程显示转动图标。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataImportScreen(
    navController: NavController,
    viewModel: DataTransferViewModel = hiltViewModel()
) {
    val snackbar = SnackbarHostState()
    val context = LocalContext.current
    val busy by viewModel.busy.collectAsState()
    val importing by viewModel.importing.collectAsState()

    LaunchedEffect(viewModel.message.value) {
        viewModel.message.value?.let {
            snackbar.showSnackbar(context.getString(it.resId, *it.argArray))
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
            title = { Text(stringResource(R.string.transfer_confirm_title)) },
            text = { ConfirmImportText(p) },
            confirmButton = {
                TextButton(onClick = { viewModel.performImport() }) {
                    Text(stringResource(R.string.transfer_confirm_continue), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    val needImportPassword by viewModel.needImportPassword.collectAsState()
    if (needImportPassword) {
        var importPassword by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.cancelImportPassword() },
            title = { Text(stringResource(R.string.transfer_import_password_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.transfer_import_password_dialog_text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        label = { Text(stringResource(R.string.transfer_import_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.submitImportPassword(importPassword) }) {
                    Text(stringResource(R.string.transfer_import_password_decrypt), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImportPassword() }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.transfer_import_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                    stringResource(R.string.transfer_import_desc),
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
                        stringResource(R.string.transfer_import_overwrite_hint),
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
                    Text(if (busy) stringResource(R.string.transfer_import_processing) else stringResource(R.string.transfer_import_select))
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
                        stringResource(R.string.transfer_importing),
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
    val sep = stringResource(R.string.transfer_list_sep)
    Column {
        Text(stringResource(R.string.transfer_confirm_text), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        val lines = mutableListOf<String>()
        if (p.hasSettings) {
            val parts = mutableListOf<String>()
            if (p.settingCount > 0) parts += stringResource(R.string.transfer_part_setting, p.settingCount)
            if (p.sourceCount > 0) parts += stringResource(R.string.transfer_part_source, p.sourceCount)
            if (p.credentialCount > 0) parts += stringResource(R.string.transfer_part_credential, p.credentialCount)
            lines += stringResource(R.string.transfer_part_settings, parts.joinToString(sep))
        }
        if (p.songCount > 0) {
            val a = listOf(stringResource(R.string.transfer_part_song, p.songCount)) +
                (if (p.albumCount > 0) listOf(stringResource(R.string.transfer_part_album, p.albumCount)) else emptyList()) +
                (if (p.artistCount > 0) listOf(stringResource(R.string.transfer_part_artist, p.artistCount)) else emptyList())
            lines += stringResource(R.string.transfer_part_songs, a.joinToString(sep))
        }
        if (p.playlistCount > 0) {
            lines += if (p.itemCount > 0) stringResource(R.string.transfer_part_playlist_many, p.playlistCount, p.itemCount)
            else stringResource(R.string.transfer_part_playlist, p.playlistCount)
        }
        lines.forEach { l ->
            Text("• $l", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}