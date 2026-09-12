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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.shiyinplayer.R
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
    val context = LocalContext.current
    val busy by viewModel.busy.collectAsState()

    var includeSettings by rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var includeSongs by rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var includePlaylists by rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var protectExport by rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var exportPassword by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var exportPasswordConfirm by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }

    LaunchedEffect(viewModel.message.value) {
        viewModel.message.value?.let {
            snackbar.showSnackbar(context.getString(it.resId, *it.argArray))
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
            scope.launch { snackbar.showSnackbar(context.getString(R.string.transfer_export_password_hint)) }
            return
        }
        if (protectExport && exportPassword != exportPasswordConfirm) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.transfer_password_mismatch)) }
            return
        }
        createDoc.launch(viewModel.defaultFileName())
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_export_title)) },
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
                stringResource(R.string.transfer_export_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            ExportRow(stringResource(R.string.transfer_export_settings), stringResource(R.string.transfer_export_settings_sub), includeSettings) { includeSettings = it }
            ExportRow(stringResource(R.string.transfer_export_songs), stringResource(R.string.transfer_export_songs_sub), includeSongs) { includeSongs = it }
            ExportRow(stringResource(R.string.transfer_export_playlists), stringResource(R.string.transfer_export_playlists_sub), includePlaylists) { includePlaylists = it }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = protectExport, onCheckedChange = { protectExport = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stringResource(R.string.transfer_password_protect), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.transfer_password_protect_sub),
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
                    label = { Text(stringResource(R.string.transfer_export_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = exportPasswordConfirm,
                    onValueChange = { exportPasswordConfirm = it },
                    label = { Text(stringResource(R.string.transfer_export_password_confirm)) },
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
                    Text(stringResource(R.string.transfer_exporting))
                } else {
                    Text(stringResource(R.string.transfer_export))
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