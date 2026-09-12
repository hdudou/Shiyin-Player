package com.shiyinplayer.ui.network

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import com.shiyinplayer.data.media.SmbBrowser
import com.shiyinplayer.data.media.SmbCredentialStore
import com.shiyinplayer.data.media.ScanMode
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.MusicSource
import com.shiyinplayer.data.remote.webdav.WebDavBrowser
import com.shiyinplayer.data.remote.webdav.WebDavCredentialStore
import com.shiyinplayer.data.repository.LibraryRepository
import com.shiyinplayer.data.repository.ScanProgress
import com.shiyinplayer.data.repository.ScanResult
import com.shiyinplayer.util.DispatcherProvider
import com.shiyinplayer.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import kotlinx.coroutines.flow.asStateFlow

/** 网络源（SMB / WebDAV）管理 + 浏览（T14）。 */
@HiltViewModel
class NetworkViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: LibraryRepository,
    private val smbCred: SmbCredentialStore,
    private val webDavCred: WebDavCredentialStore,
    private val smbBrowser: SmbBrowser,
    private val webDavBrowser: WebDavBrowser,
    private val dispatcher: DispatcherProvider
) : ViewModel() {

    val sources = repo.getMusicSources()
        .map { list ->
            list.filter {
                it.type == MediaSourceType.SMB ||
                    it.type == MediaSourceType.WEBDAV ||
                    it.type == MediaSourceType.HTTP ||
                    it.type == MediaSourceType.LOCAL
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 正在扫描的来源 id 集合（透传自仓库），UI 据此让对应源的同步图标动态显示"扫描中"。 */
    val scanningIds: StateFlow<Set<Long>> = repo.scanningIds

    /** 当前单源扫描实时计数（新增/更新），随落库批次刷新；扫描结束置 null。UI 在对应源行显示计数提示。 */
    val scanProgress: StateFlow<ScanProgress?> = repo.scanProgress

    private val _entries = MutableStateFlow<List<NetworkEntry>>(emptyList())
    val entries: StateFlow<List<NetworkEntry>> = _entries.asStateFlow()

    private val _currentPath = MutableStateFlow<String?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()

    private val _scanResult = MutableStateFlow<String?>(null)
    val scanResult: StateFlow<String?> = _scanResult.asStateFlow()

    private var browsingSourceId: Long? = null

    // ===== 来源管理 =====

    suspend fun addSmbSource(name: String, url: String, user: String, pass: String) {
        val host = Uri.parse(url).host
            ?: url.substringAfter("//").substringBefore("/").substringBefore(":")
        if (user.isNotBlank()) smbCred.save(host, user, pass)
        val cfg = JSONObject().put("url", url).toString()
        repo.addMusicSource(
            MusicSource(name = name.ifBlank { url }, type = MediaSourceType.SMB, configJson = cfg, enabled = true)
        )
    }

    suspend fun addWebDavSource(name: String, url: String, user: String, pass: String) {
        if (user.isNotBlank()) webDavCred.saveForUrl(url, user, pass)
        val cfg = JSONObject().put("url", url).toString()
        repo.addMusicSource(
            MusicSource(name = name.ifBlank { url }, type = MediaSourceType.WEBDAV, configJson = cfg, enabled = true)
        )
    }

    /** 2026-08-24 需求：HTTP 直链来源——url 为 m3u/换行列表或 HTML 自动索引页地址；无凭据。 */
    suspend fun addHttpSource(name: String, url: String) {
        val cfg = JSONObject().put("url", url.trim()).toString()
        repo.addMusicSource(
            MusicSource(name = name.ifBlank { url }, type = MediaSourceType.HTTP, configJson = cfg, enabled = true)
        )
    }

    suspend fun updateHttpSource(id: Long, name: String, url: String) {
        val cfg = JSONObject().put("url", url.trim()).toString()
        val cur = sources.value.firstOrNull { it.id == id } ?: return
        repo.updateMusicSource(cur.copy(name = name.ifBlank { url }, configJson = cfg))
    }

    /**
     * 2026-08-24：添加本机文件夹源。输入可能是两类：
     * - content:// 树 URI（系统目录选择器返回）→ 存 configJson.treeUri，由 SAF 递归扫描；
     * - 绝对路径（如 /storage/emulated/0/Music）→ 存 configJson.folderPath，File 递归扫描。
     * 名称为空时用解码后的文件夹名兜底，避免直接显示 content:///百分号编码 URI 造成乱码。
     */
    suspend fun addLocalFolderSource(name: String, input: String) {
        val t = input.trim()
        val cfg = if (t.startsWith("content://")) {
            JSONObject().put("treeUri", t).toString()
        } else {
            JSONObject().put("folderPath", t).toString()
        }
        repo.addMusicSource(
            MusicSource(name = if (name.isBlank()) deriveFolderName(t) else name, type = MediaSourceType.LOCAL, configJson = cfg, enabled = true)
        )
    }

    suspend fun updateLocalFolderSource(id: Long, name: String, input: String) {
        val t = input.trim()
        val cfg = if (t.startsWith("content://")) {
            JSONObject().put("treeUri", t).toString()
        } else {
            JSONObject().put("folderPath", t).toString()
        }
        val cur = sources.value.firstOrNull { it.id == id } ?: return
        repo.updateMusicSource(cur.copy(name = if (name.isBlank()) deriveFolderName(t) else name, configJson = cfg))
    }

    /** 从文件夹输入（content:// 树 URI 或绝对路径）提取一个可读的文件夹名，用于名称兜底。 */
    private fun deriveFolderName(input: String): String {
        val decoded = runCatching { Uri.decode(input) }.getOrNull() ?: input
        val last = decoded.substringAfterLast('/').ifBlank { decoded }
        // content://.../tree/primary:Music → "Music"; 绝对路径尾部同理
        return last.substringAfter(':').ifBlank { last }
    }

    /** 2026-08-19 需求5：删除网络源——清本机凭据 + 删源行 + 删该源曲目条目（多源只删本源条目）。 */
    suspend fun removeSource(source: MusicSource) {
        // 清本机保存的该源连接凭据
        val cfg = runCatching { JSONObject(source.configJson).optString("url") }.getOrNull().orEmpty()
        when (source.type) {
            MediaSourceType.SMB -> {
                val host = runCatching {
                    Uri.parse(cfg).host ?: cfg.substringAfter("//").substringBefore("/").substringBefore(":")
                }.getOrDefault(cfg)
                if (host.isNotBlank()) smbCred.clear(host)
            }
            MediaSourceType.WEBDAV -> if (cfg.isNotBlank()) webDavCred.clearForUrl(cfg)
            else -> {}
        }
        repo.removeNetworkSourceWithSongs(source)
    }

    /** 2026-08-19 需求2：修改网络源（类型/名称/地址/凭据）。 */
    suspend fun updateSmbSource(id: Long, name: String, url: String, user: String, pass: String) {
        val host = Uri.parse(url).host
            ?: url.substringAfter("//").substringBefore("/").substringBefore(":")
        if (user.isNotBlank()) smbCred.save(host, user, pass)
        val cfg = JSONObject().put("url", url).toString()
        val cur = sources.value.firstOrNull { it.id == id } ?: return
        repo.updateMusicSource(cur.copy(name = name.ifBlank { url }, configJson = cfg))
    }

    suspend fun updateWebDavSource(id: Long, name: String, url: String, user: String, pass: String) {
        if (user.isNotBlank()) webDavCred.saveForUrl(url, user, pass)
        val cfg = JSONObject().put("url", url).toString()
        val cur = sources.value.firstOrNull { it.id == id } ?: return
        repo.updateMusicSource(cur.copy(name = name.ifBlank { url }, configJson = cfg))
    }

    // ===== 浏览 =====

    fun startBrowse(source: MusicSource) {
        browsingSourceId = source.id
        val root = when (source.type) {
            MediaSourceType.LOCAL -> localRoot(source)
            else -> configUrl(source)
        }
        _currentPath.value = root
        browseAt(root, source)
    }

    fun openEntry(entry: NetworkEntry) {
        val src = sources.value.firstOrNull { it.id == browsingSourceId } ?: return
        _currentPath.value = entry.path
        browseAt(entry.path, src)
    }

    fun up() {
        val base = _currentPath.value ?: return
        val src = sources.value.firstOrNull { it.id == browsingSourceId } ?: return
        if (src.type == MediaSourceType.LOCAL && base.startsWith("content://")) {
            // SAF 目录用 DocumentFile.parentFile 上溯，避免对编码路径做字符串切分
            val doc = DocumentFile.fromSingleUri(context, Uri.parse(base))
                ?: DocumentFile.fromTreeUri(context, Uri.parse(base))
            val parent = doc?.parentFile?.uri?.toString()
            if (parent == null) {
                _currentPath.value = null
                _entries.value = emptyList()
            } else {
                _currentPath.value = parent
                browseAt(parent, src)
            }
            return
        }
        val u = Uri.parse(base)
        val path = (u.path ?: "").trimEnd('/')
        val parentPath = path.substringBeforeLast('/')
        if (parentPath.isEmpty()) {
            _currentPath.value = null
            _entries.value = emptyList()
            return
        }
        val parent = "${u.scheme}://${u.authority}$parentPath"
        _currentPath.value = parent
        browseAt(parent, src)
    }

    /** 2026-08-24：添加 SMB 来源时的快速浏览（不依赖已保存的源记录），列出 path 下目录与文件。 */
    suspend fun smbQuickBrowse(path: String): List<NetworkEntry> =
        withContext(dispatcher.io) {
            smbBrowser.listFiles(path)
                .map { NetworkEntry(it.name, it.isDir, it.size, if (path.endsWith("/")) "$path${it.name}" else "$path/${it.name}") }
        }

    private fun browseAt(path: String, source: MusicSource) {
        viewModelScope.launch(dispatcher.io) {
            val list = try {
                when (source.type) {
                    MediaSourceType.SMB -> smbBrowser.listFiles(path)
                        .map { NetworkEntry(it.name, it.isDir, it.size, if (path.endsWith("/")) "$path${it.name}" else "$path/${it.name}") }
                    MediaSourceType.WEBDAV -> webDavBrowser.listFiles(path)
                        .map { NetworkEntry(it.name, it.isDir, it.size, it.path) }
                    MediaSourceType.LOCAL -> if (path.startsWith("content://")) browseLocalSaf(path) else browseLocalFolder(path)
                    else -> emptyList()
                }
            } catch (t: Throwable) {
                // 认证失败 / 连接失败不再静默显示空目录，而是明确提示原因
                _entries.value = emptyList()
                _scanResult.value = "浏览失败：${t.message}"
                return@launch
            }
            _entries.value = list.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        }
    }

    // ===== 扫描 =====

    /**
     * 2026-08-19 需求：只同步指定网络源（源行「同步」按钮）。
     * 2026-08-24：源行扫描图标弹窗可选扫描模式——[ScanMode.NEW_ONLY] 仅新增，[ScanMode.FULL_UPDATE] 全量并更新已有内容。
     * 独立协程执行单源扫描；与其它源互不影响（扫描器单源模式跳过失效清理，避免误删同类型其它源歌曲）。
     */
    fun scanSource(src: MusicSource, mode: ScanMode = ScanMode.NEW_ONLY) {
        val verb = if (mode == ScanMode.FULL_UPDATE) "全量更新" else "同步"
        _scanResult.value = "正在$verb「${src.name}」…"
        repo.scanSources(listOf(src), singleSourceId = src.id, mode = mode) { res ->
            _scanResult.value = res.fold(
                {
                    val prefix = if (mode == ScanMode.FULL_UPDATE) "全量更新「${src.name}」完成" else "同步「${src.name}」完成"
                    scanMessage(prefix, it, mode)
                },
                { "扫描「${src.name}」失败：${it.message}" }
            )
        }
    }

    /** 统一扫描结果文案：有来源错误时不只显示计数，透出具体错误原因，避免"新增 0 首"掩盖认证/连接问题。 */
    private fun scanMessage(prefix: String, res: ScanResult, mode: ScanMode = ScanMode.NEW_ONLY): String {
        // 计数区分模式：仅新增扫描只答新增；全量更新答「已更新 X 首，已新增 Y 首」
        val head = if (mode == ScanMode.FULL_UPDATE) {
            "$prefix：已更新 ${res.updated} 首，已新增 ${res.added} 首"
        } else {
            "$prefix：新增 ${res.added} 首"
        }
        return if (res.errors.isEmpty()) head else "$head，但有 ${res.errors.size} 个来源错误。\n${res.errors.first()}"
    }

    /** 本机文件夹目录浏览：列出子目录与音频文件（目录可继续进入）。 */
    private fun browseLocalFolder(path: String): List<NetworkEntry> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: return emptyList())
            .filter { it.isDirectory || isBrowseableAudio(it.name) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { NetworkEntry(it.name, it.isDirectory, if (it.isFile) it.length() else 0, it.absolutePath) }
    }

    /** SAF 目录浏览：基于 DocumentFile，传入的是 content:// 树 URI 或子文档 URI。 */
    private fun browseLocalSaf(uriStr: String): List<NetworkEntry> {
        val uri = Uri.parse(uriStr)
        val doc = DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
        return (doc.listFiles() ?: emptyArray())
            .filter { it.isDirectory || isBrowseableAudio(it.name ?: "") }
            .sortedWith(compareBy({ !it.isDirectory }, { (it.name ?: "").lowercase() }))
            .map { NetworkEntry(it.name ?: "", it.isDirectory, it.length().coerceAtLeast(0), it.uri.toString()) }
    }

    /** 本机文件夹源根：优先 treeUri（SAF），否则 folderPath（绝对路径）。 */
    private fun localRoot(source: MusicSource): String {
        val cfg = runCatching { JSONObject(source.configJson) }.getOrNull() ?: return ""
        return cfg.optString("treeUri").takeIf { it.isNotBlank() } ?: cfg.optString("folderPath")
    }

    private fun isBrowseableAudio(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return Constants.AUDIO_EXTENSIONS.contains(ext)
    }

    private fun configUrl(source: MusicSource): String =
        runCatching { JSONObject(source.configJson).optString("url") }.getOrNull().orEmpty()
}

/** 浏览项 UI 模型（对 SmbEntry / WebDavEntry 的归一）。path 为完整可访问/可播放 URL。 */
data class NetworkEntry(val name: String, val isDir: Boolean, val size: Long, val path: String)
