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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            Text("音乐库来源管理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("添加来源")
            }
        }
        Spacer(Modifier.height(8.dp))
        scanResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        if (sources.isEmpty()) {
            Text(
                "暂无网络来源。点击「添加来源」添加 SMB、WebDAV 或 HTTP 直链，扫描后即可在曲库中播放。",
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
                IconButton(onClick = viewModel::up) { Icon(Icons.Default.NavigateBefore, contentDescription = "上一级") }
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
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingSource = null }) { Text("取消") } },
            title = { Text("删除网络来源") },
            text = {
                Text(
                    "确定删除「${src.name}」吗？\n\n" +
                        "将同时：\n" +
                        "① 删除本机保存的该源的连接信息；\n" +
                        "② 删除本机曲库中该源的音乐文件条目及其元数据。\n\n" +
                        "若同一音乐在多个源存在，仅删除该源的条目，其它源保留；" +
                        "仅当某音乐只有这一个来源时，才会连同其元数据和整个条目一并删除。"
                )
            }
        )
    }

    // 2026-08-24：扫描模式选择弹窗——用户决定本次扫描是「全量更新已有内容」还是「仅扫描新增」
    scanTarget?.let { src ->
        AlertDialog(
            onDismissRequest = { scanTarget = null },
            title = { Text("扫描「${src.name}」") },
            text = {
                Text("请选择本次扫描方式：", style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.scanSource(src, ScanMode.NEW_ONLY)
                        scanTarget = null
                    }) { Text("只扫描新增内容") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        viewModel.scanSource(src, ScanMode.FULL_UPDATE)
                        scanTarget = null
                    }) { Text("全部扫描并更新已有内容") }
                }
            },
            dismissButton = {
                TextButton(onClick = { scanTarget = null }) { Text("取消") }
            }
        )
    }

    // 本机文件夹授权提醒：存在绝对路径文件夹源但未授予「所有文件访问」权限时，弹窗引导用户去授权
    if (needsAllFilesAccess && !storagePermReminderHidden) {
        AlertDialog(
            onDismissRequest = { storagePermReminderHidden = true },
            title = { Text("需要「所有文件访问」权限") },
            text = { Text("本机文件夹来源通过绝对路径扫描本机音乐，需要「所有文件访问」权限。请前往系统设置授予，否则该文件夹将无法扫描。") },
            confirmButton = {
                TextButton(onClick = {
                    storagePermReminderHidden = true
                    runCatching { context.startActivity(com.shiyinplayer.util.PermissionsHelper.allFilesAccessSettingsIntent(context)) }
                }) { Text("去授权") }
            },
            dismissButton = {
                TextButton(onClick = { storagePermReminderHidden = true }) { Text("暂不") }
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
                src.name + if (isScanning) "（扫描中…）" else "",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(typeLabel(src.type) + if (src.enabled) "" else "（已禁用）", style = MaterialTheme.typography.bodySmall)
            // 2026-08-26：扫描实况计数——仅新增答新增数量；全量更新答已更新+已新增数量
            if (isScanning && progress != null) {
                Text(
                    if (progress.mode == ScanMode.FULL_UPDATE) {
                        "已更新 ${progress.updated} 首，已新增 ${progress.added} 首"
                    } else {
                        "已新增 ${progress.added} 首"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        // HTTP 直链无目录结构，不提供「浏览」；保留同步/修改/删除
        if (src.type != MediaSourceType.HTTP) {
            IconButton(onClick = { onBrowse(src) }) { Icon(Icons.Default.FolderOpen, contentDescription = "浏览") }
        }
        IconButton(onClick = { onSync(src) }) {
            Icon(
                Icons.Default.Sync,
                contentDescription = if (isScanning) "扫描中" else "同步",
                modifier = if (isScanning) Modifier.rotate(angle) else Modifier
            )
        }
        IconButton(onClick = { onEdit(src) }) { Icon(Icons.Default.Edit, contentDescription = "修改") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除") }
    }
    HorizontalDivider()
}

private fun typeLabel(t: MediaSourceType): String = when (t) {
    MediaSourceType.SMB -> "SMB"
    MediaSourceType.WEBDAV -> "WebDAV"
    MediaSourceType.HTTP -> "HTTP 直链"
    MediaSourceType.LOCAL -> "文件夹"
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
            ) { Text(if (initial == null) "保存" else "保存修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(if (initial == null) "添加来源" else "修改来源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { type = MediaSourceType.SMB },
                        modifier = Modifier.weight(1f)
                    ) { Text("SMB", color = if (type == MediaSourceType.SMB) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                    OutlinedButton(
                        onClick = { type = MediaSourceType.WEBDAV },
                        modifier = Modifier.weight(1f)
                    ) { Text("WebDAV", color = if (type == MediaSourceType.WEBDAV) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { type = MediaSourceType.HTTP },
                        modifier = Modifier.weight(1f)
                    ) { Text("HTTP直链", color = if (type == MediaSourceType.HTTP) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                    OutlinedButton(
                        onClick = { type = MediaSourceType.LOCAL },
                        modifier = Modifier.weight(1f)
                    ) { Text("文件夹", color = if (type == MediaSourceType.LOCAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                }
                OutlinedTextField(name, { name = it }, label = { Text("名称（可选）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it },
                    label = {
                        Text(
                            when (type) {
                                MediaSourceType.SMB -> "地址，如 smb://192.168.1.10/Music"
                                MediaSourceType.WEBDAV -> "地址，如 https://dav.example.com/dav"
                                MediaSourceType.HTTP -> "播放列表/索引地址，如 https://example.com/playlist.m3u"
                                MediaSourceType.LOCAL -> "文件夹路径，如 /storage/emulated/0/Music"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (type == MediaSourceType.SMB) {
                        OutlinedButton(
                            onClick = { smbBrowseOpen = true; scope.launch { loadSMBList("smb://") } }
                        ) { Text("浏览局域网") }
                    }
                    if (type == MediaSourceType.LOCAL) {
                        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("浏览文件夹") }
                    }
                }
                if (type != MediaSourceType.HTTP && type != MediaSourceType.LOCAL) {
                    OutlinedTextField(user, { user = it }, label = { Text("用户名（可选）") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        pass, { pass = it },
                        label = { Text(if (initial == null) "密码" else "密码（留空则不修改）") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(autoCorrect = false),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        when (type) {
                            MediaSourceType.LOCAL -> "本机文件夹直接扫描文件系统，无需账号密码；需已授予「所有文件访问」权限。"
                            else -> "HTTP 直链无需账号密码；扫描将抓取该地址并导入其中的音频链接。"
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
            title = { Text("浏览局域网主机 / 共享") },
            text = {
                Column {
                    // 2026-08-24：自动发现（NetBIOS 广播）在许多家用/办公网络下取不到工作组，
                    // 故额外提供手动输入主机地址兜底，确保能凭已知 IP 进入共享选择。
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            manualHost,
                            { manualHost = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("自动发现无结果时，输入主机 IP") },
                            singleLine = true
                        )
                        Button(onClick = {
                            val h = manualHost.trim()
                            if (h.isNotEmpty()) scope.launch { loadSMBList("smb://$h") }
                        }) { Text("打开") }
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
                TextButton(onClick = { url = browsePath; smbBrowseOpen = false }) { Text("使用此目录") }
            },
            dismissButton = { TextButton(onClick = { smbBrowseOpen = false }) { Text("取消") } }
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
