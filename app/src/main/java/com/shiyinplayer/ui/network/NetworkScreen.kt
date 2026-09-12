package com.shiyinplayer.ui.network

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.StringRes
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.R
import com.shiyinplayer.data.media.ScanMode
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.MusicSource
import com.shiyinplayer.data.repository.ScanProgress
import kotlinx.coroutines.launch

@Composable
fun NetworkScreen(viewModel: NetworkViewModel = hiltViewModel()) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()
    val scanResult by viewModel.scanResult.collectAsStateWithLifecycle()
    val scanningIds by viewModel.scanningIds.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // MANAGE_EXTERNAL_STORAGE 收敛：绝对路径文件夹源需「所有文件访问」权限（见 requireAllFilesAccessFor）
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<MusicSource?>(null) }
    var deletingSource by remember { mutableStateOf<MusicSource?>(null) }
    // 2026-08-24：源行扫描图标点击后先弹窗，让用户选择「全量更新」或「仅新增」
    var scanTarget by remember { mutableStateOf<MusicSource?>(null) }
    // 本机文件夹（绝对路径）授权提醒：检测到存在 folderPath 源但未授予「所有文件访问」时弹窗引导授权
    var storagePermReminderHidden by remember { mutableStateOf(false) }
    val needsAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        !com.shiyinplayer.util.PermissionsHelper.hasAllFilesAccess(context) &&
        sources.any { s ->
            s.type == MediaSourceType.LOCAL &&
                runCatching { org.json.JSONObject(s.configJson).optString("folderPath") }
                    .getOrNull()?.isNotBlank() == true
        }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.network_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.network_add))
            }
        }
        Spacer(Modifier.height(8.dp))
        scanResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        if (sources.isEmpty()) {
            Text(
                stringResource(R.string.network_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            // 2026-08-24 修复：浏览中把源列表高度封顶，让浏览面板紧贴在源列表下方，
            // 而不是被 weight(1f) 挤到屏幕底部、中间空出一大截。
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .then(if (currentPath == null) Modifier.weight(1f) else Modifier.heightIn(max = 320.dp))
            ) {
                items(sources, key = { it.id }) { src ->
                    NetworkSourceRow(
                        src = src,
                        isScanning = src.id in scanningIds,
                        progress = scanProgress?.takeIf { it.sourceId == src.id },
                        onBrowse = viewModel::startBrowse,
                        onSync = { scanTarget = it },
                        onEdit = { editingSource = src },
                        onDelete = { deletingSource = src }
                    )
                }
            }
        }

        currentPath?.let { path ->
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                IconButton(onClick = viewModel::up) { Icon(Icons.Default.NavigateBefore, contentDescription = stringResource(R.string.action_up)) }
                // 2026-08-24：local 源可见路径为 content:// 树 URI，解码后再展示，避免百分号编码乱码
                Text(Uri.decode(path) ?: path, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            LazyColumn(modifier = Modifier.height(220.dp).fillMaxWidth()) {
                items(entries, key = { it.name }) { e ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = e.isDir) { viewModel.openEntry(e) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (e.isDir) Icons.Default.Folder else Icons.Default.Audiotrack,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(e.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddSourceDialog(
            onDismiss = { showAdd = false },
            onSave = { type, name, url, user, pass ->
                scope.launch {
                    when (type) {
                        MediaSourceType.SMB -> viewModel.addSmbSource(name, url, user, pass)
                        MediaSourceType.WEBDAV -> viewModel.addWebDavSource(name, url, user, pass)
                        MediaSourceType.HTTP -> viewModel.addHttpSource(name, url)
                        MediaSourceType.LOCAL -> if (requireAllFilesAccessFor(context, url)) viewModel.addLocalFolderSource(name, url) else return@launch
                    }
                }
                if (type != MediaSourceType.LOCAL || requireAllFilesAccessFor(context, url)) showAdd = false
            }
        )
    }

    editingSource?.let { src ->
        AddSourceDialog(
            initial = src,
            onDismiss = { editingSource = null },
            onSave = { type, name, url, user, pass ->
                scope.launch {
                    when (type) {
                        MediaSourceType.SMB -> viewModel.updateSmbSource(src.id, name, url, user, pass)
                        MediaSourceType.WEBDAV -> viewModel.updateWebDavSource(src.id, name, url, user, pass)
                        MediaSourceType.HTTP -> viewModel.updateHttpSource(src.id, name, url)
                        MediaSourceType.LOCAL -> if (requireAllFilesAccessFor(context, url)) viewModel.updateLocalFolderSource(src.id, name, url) else return@launch
                    }
                }
                if (type != MediaSourceType.LOCAL || requireAllFilesAccessFor(context, url)) editingSource = null
            }
        )
    }

    // 2026-08-19 需求5：删除源确认弹窗——告知会同时删除本机保存的该源音乐文件信息和对应元数据
    deletingSource?.let { src ->
        AlertDialog(
            onDismissRequest = { deletingSource = null },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { viewModel.removeSource(src) }
                    deletingSource = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingSource = null }) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.network_delete_title)) },
            text = {
                Text(stringResource(R.string.network_delete_body, src.name))
            }
        )
    }

    // 2026-08-24：扫描模式选择弹窗——用户决定本次扫描是「全量更新已有内容」还是「仅扫描新增」
    scanTarget?.let { src ->
        AlertDialog(
            onDismissRequest = { scanTarget = null },
            title = { Text(stringResource(R.string.network_scan_title, src.name)) },
            text = {
                Text(stringResource(R.string.network_scan_choose), style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.scanSource(src, ScanMode.NEW_ONLY)
                        scanTarget = null
                    }) { Text(stringResource(R.string.network_scan_newonly)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        viewModel.scanSource(src, ScanMode.FULL_UPDATE)
                        scanTarget = null
                    }) { Text(stringResource(R.string.network_scan_full)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { scanTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // 本机文件夹授权提醒：存在绝对路径文件夹源但未授予「所有文件访问」权限时，弹窗引导用户去授权
    if (needsAllFilesAccess && !storagePermReminderHidden) {
        AlertDialog(
            onDismissRequest = { storagePermReminderHidden = true },
            title = { Text(stringResource(R.string.network_perm_title)) },
            text = { Text(stringResource(R.string.network_perm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    storagePermReminderHidden = true
                    runCatching { context.startActivity(com.shiyinplayer.util.PermissionsHelper.allFilesAccessSettingsIntent(context)) }
                }) { Text(stringResource(R.string.network_go_grant)) }
            },
            dismissButton = {
                TextButton(onClick = { storagePermReminderHidden = true }) { Text(stringResource(R.string.network_later)) }
            }
        )
    }
}

@Composable
private fun NetworkSourceRow(
    src: MusicSource,
    isScanning: Boolean,
    progress: ScanProgress?,
    onBrowse: (MusicSource) -> Unit,
    onSync: (MusicSource) -> Unit,
    onEdit: (MusicSource) -> Unit,
    onDelete: () -> Unit
) {
    // 扫描中：同步图标持续旋转，直观提示"正在扫描"
    val rotation = rememberInfiniteTransition(label = "syncScan")
    val angle by rotation.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000), RepeatMode.Restart),
        label = "syncAngle"
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when (src.type) {
                MediaSourceType.SMB -> Icons.Default.Storage
                MediaSourceType.WEBDAV -> Icons.Default.Link
                MediaSourceType.HTTP -> Icons.Default.Public
                MediaSourceType.LOCAL -> Icons.Default.Folder
            },
            contentDescription = null
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                src.name + if (isScanning) stringResource(R.string.network_scanning_suffix) else "",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(stringResource(typeLabelRes(src.type)) + if (src.enabled) "" else stringResource(R.string.network_disabled_suffix), style = MaterialTheme.typography.bodySmall)
            // 2026-08-26：扫描实况计数——仅新增答新增数量；全量更新答已更新+已新增数量
            if (isScanning && progress != null) {
                Text(
                    if (progress.mode == ScanMode.FULL_UPDATE) {
                        stringResource(R.string.network_progress_update, progress.updated, progress.added)
                    } else {
                        stringResource(R.string.network_progress_added, progress.added)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        // HTTP 直链无目录结构，不提供「浏览」；保留同步/修改/删除
        if (src.type != MediaSourceType.HTTP) {
            IconButton(onClick = { onBrowse(src) }) { Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.action_browse)) }
        }
        IconButton(onClick = { onSync(src) }) {
            Icon(
                Icons.Default.Sync,
                contentDescription = if (isScanning) stringResource(R.string.action_scanning) else stringResource(R.string.network_sync),
                modifier = if (isScanning) Modifier.rotate(angle) else Modifier
            )
        }
        IconButton(onClick = { onEdit(src) }) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit)) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete)) }
    }
    HorizontalDivider()
}

@StringRes
private fun typeLabelRes(t: MediaSourceType): Int = when (t) {
    MediaSourceType.SMB -> R.string.network_type_smb
    MediaSourceType.WEBDAV -> R.string.network_type_webdav
    MediaSourceType.HTTP -> R.string.network_type_http
    MediaSourceType.LOCAL -> R.string.network_type_folder
}

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onSave: (type: MediaSourceType, name: String, url: String, user: String, pass: String) -> Unit,
    initial: MusicSource? = null
) {
    val cfg = initial?.let { runCatching { org.json.JSONObject(it.configJson) }.getOrNull() }
    var type by remember { mutableStateOf(initial?.type ?: MediaSourceType.SMB) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember {
        mutableStateOf(
            if (initial?.type == MediaSourceType.LOCAL) (cfg?.optString("folderPath")?.takeIf { it.isNotBlank() } ?: cfg?.optString("treeUri") ?: "")
            else cfg?.optString("url") ?: ""
        )
    }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    // 2026-08-24 需求4：SMB 添加时可快速浏览局域网主机/共享（不依赖已保存源）
    val vm: NetworkViewModel = hiltViewModel()
    val context = LocalContext.current
    var smbBrowseOpen by remember { mutableStateOf(false) }
    var browsePath by remember { mutableStateOf("smb://") }
    var browseEntries by remember { mutableStateOf<List<NetworkEntry>>(emptyList()) }
    var manualHost by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun loadSMBList(path: String) {
        browseEntries = try { vm.smbQuickBrowse(path) } catch (t: Throwable) { emptyList() }
        browsePath = path
    }
    fun openSmbDir(entry: NetworkEntry) {
        if (entry.isDir) scope.launch { loadSMBList(entry.path) }
    }

    // 2026-08-24 需求1：本机文件夹用系统目录选择器（SAF），选中后持久化读取权限并回填 treeUri
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            url = uri.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                enabled = url.isNotBlank(),
                onClick = { onSave(type, name.trim(), url.trim(), user.trim(), pass) }
            ) { Text(if (initial == null) stringResource(R.string.action_save) else stringResource(R.string.network_save_edit)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(if (initial == null) stringResource(R.string.network_add_title) else stringResource(R.string.network_edit_src)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { type = MediaSourceType.SMB },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.network_type_smb), color = if (type == MediaSourceType.SMB) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                    OutlinedButton(
                        onClick = { type = MediaSourceType.WEBDAV },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.network_type_webdav), color = if (type == MediaSourceType.WEBDAV) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { type = MediaSourceType.HTTP },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.network_type_http), color = if (type == MediaSourceType.HTTP) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                    OutlinedButton(
                        onClick = { type = MediaSourceType.LOCAL },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.network_type_folder), color = if (type == MediaSourceType.LOCAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.network_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it },
                    label = {
                        Text(
                            when (type) {
                                MediaSourceType.SMB -> stringResource(R.string.network_url_hint_smb)
                                MediaSourceType.WEBDAV -> stringResource(R.string.network_url_hint_webdav)
                                MediaSourceType.HTTP -> stringResource(R.string.network_url_hint_http)
                                MediaSourceType.LOCAL -> stringResource(R.string.network_url_hint_local)
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (type == MediaSourceType.SMB) {
                        OutlinedButton(
                            onClick = { smbBrowseOpen = true; scope.launch { loadSMBList("smb://") } }
                        ) { Text(stringResource(R.string.network_browse_lan)) }
                    }
                    if (type == MediaSourceType.LOCAL) {
                        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.network_browse_folder)) }
                    }
                }
                if (type != MediaSourceType.HTTP && type != MediaSourceType.LOCAL) {
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.network_user)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        pass, { pass = it },
                        label = { Text(if (initial == null) stringResource(R.string.network_pass) else stringResource(R.string.network_pass_edit)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(autoCorrect = false),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        when (type) {
                            MediaSourceType.LOCAL -> stringResource(R.string.network_note_local)
                            else -> stringResource(R.string.network_note_http)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    )

    // 2026-08-24 需求4：SMB 添加时的局域网主机/共享浏览面板（复用 VM 快速浏览）
    if (smbBrowseOpen) {
        AlertDialog(
            onDismissRequest = { smbBrowseOpen = false },
            title = { Text(stringResource(R.string.network_smb_title)) },
            text = {
                Column {
                    // 2026-08-24：自动发现（NetBIOS 广播）在许多家用/办公网络下取不到工作组，
                    // 故额外提供手动输入主机地址兜底，确保能凭已知 IP 进入共享选择。
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            manualHost,
                            { manualHost = it },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.network_smb_manual_host)) },
                            singleLine = true
                        )
                        Button(onClick = {
                            val h = manualHost.trim()
                            if (h.isNotEmpty()) scope.launch { loadSMBList("smb://$h") }
                        }) { Text(stringResource(R.string.action_open)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(browsePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.height(300.dp).fillMaxWidth()) {
                        items(browseEntries, key = { it.path }) { e ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = e.isDir) { openSmbDir(e) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (e.isDir) Icons.Default.Folder else Icons.Default.Audiotrack, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(e.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { url = browsePath; smbBrowseOpen = false }) { Text(stringResource(R.string.network_use_dir)) }
            },
            dismissButton = { TextButton(onClick = { smbBrowseOpen = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

/**
 * MANAGE_EXTERNAL_STORAGE 收敛：绝对路径文件夹源需「所有文件访问」权限，未授予则引导授权并返回 false（阻止保存）。
 * content://（SAF tree）路径走受控权限，无需此检查。
 */
private fun requireAllFilesAccessFor(context: android.content.Context, input: String): Boolean {
    if (input.trim().startsWith("content://")) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        !com.shiyinplayer.util.PermissionsHelper.hasAllFilesAccess(context)
    ) {
        val intent = com.shiyinplayer.util.PermissionsHelper.allFilesAccessSettingsIntent(context)
        runCatching { context.startActivity(intent) }
        return false
    }
    return true
}
