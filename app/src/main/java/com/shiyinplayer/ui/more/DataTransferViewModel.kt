package com.shiyinplayer.ui.more

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.data.transfer.DataTransferManager
import com.shiyinplayer.data.transfer.ImportPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 播放器数据导出/导入界面状态与动作。 */
@HiltViewModel
class DataTransferViewModel @Inject constructor(
    private val manager: DataTransferManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 待确认的导入数据包内容（非空 = 弹出覆盖确认）。 */
    private val _preview = MutableStateFlow<ImportPreview?>(null)
    val preview: StateFlow<ImportPreview?> = _preview.asStateFlow()

    /** 所选导入文件需要密码解密（非空 = 弹出密码输入框）。 */
    private val _needImportPassword = MutableStateFlow(false)
    val needImportPassword: StateFlow<Boolean> = _needImportPassword.asStateFlow()

    /** 正在执行覆盖导入（用于显示转动图标）。 */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private var pendingBytes: ByteArray? = null

    fun consumeMessage() { _message.value = null }

    /** 导出到指定 SAF Uri。password 非空则启用密码保护加密。 */
    fun exportTo(
        uri: Uri,
        includeSettings: Boolean,
        includeSongs: Boolean,
        includePlaylists: Boolean,
        password: String?
    ) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val bytes = withContext(Dispatchers.Default) {
                    manager.buildExport(includeSettings, includeSongs, includePlaylists, password)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("无法写入导出文件")
                }
            }.onSuccess {
                _message.value = "导出完成"
            }.onFailure {
                _message.value = "导出失败：${it.message}"
            }
            _busy.value = false
        }
    }

    /** 读取导入文件：若带密码保护则请求输入密码，否则直接解析。 */
    fun prepareImport(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取所选文件")
                }
            }.onSuccess { bytes ->
                pendingBytes = bytes
                if (manager.isEncrypted(bytes)) {
                    _needImportPassword.value = true
                } else {
                    resolveImport(bytes)
                }
            }.onFailure {
                pendingBytes = null
                _message.value = "导入文件无效：${it.message}"
            }
            _busy.value = false
        }
    }

    /** 解析并准备待导入内容（供覆盖确认展示）。 */
    private fun resolveImport(bytes: ByteArray) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { manager.parseImport(bytes) }
                .onSuccess { p ->
                    if (p.isEmpty) {
                        pendingBytes = null
                        _message.value = "所选文件不含可导入的数据"
                    } else {
                        _preview.value = p
                    }
                }
                .onFailure {
                    pendingBytes = null
                    _message.value = "导入文件无效：${it.message}"
                }
            _busy.value = false
        }
    }

    /** 提交导入密码，正确则解密并解析，错误则提示。 */
    fun submitImportPassword(password: String) {
        val encrypted = pendingBytes ?: return
        if (password.isBlank()) {
            _message.value = "请输入密码"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                withContext(Dispatchers.Default) { manager.decrypt(encrypted, password) }
            }.onSuccess { decrypted ->
                pendingBytes = decrypted
                _needImportPassword.value = false
                resolveImport(decrypted)
            }.onFailure {
                _message.value = "密码错误或文件损坏：${it.message}"
            }
            _busy.value = false
        }
    }

    /** 取消密码输入（放弃导入或返回）。 */
    fun cancelImportPassword() {
        pendingBytes = null
        _needImportPassword.value = false
    }

    /** 确认覆盖后执行导入。失败时保留预览与已读数据，允许直接重试，无需重新选文件。 */
    fun performImport() {
        val bytes = pendingBytes
        if (bytes == null) {
            _preview.value = null
            _message.value = "未读取到导入文件，请重新选择"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _importing.value = true
            runCatching { withContext(Dispatchers.Default) { manager.import(bytes) } }
                .onSuccess {
                    _message.value = "导入完成"
                    pendingBytes = null
                    _preview.value = null
                }
                .onFailure {
                    // 不清空 pendingBytes/preview，用户可直接再次点击「继续导入」重试
                    _message.value = "导入失败：${it.message}"
                }
            _importing.value = false
            _busy.value = false
        }
    }

    fun cancelImport() {
        pendingBytes = null
        _preview.value = null
    }

    /** 建议文件名：包名 + 导出时间戳。 */
    fun defaultFileName(): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        return "${context.packageName}_$ts.json"
    }
}