package com.shiyinplayer.ui.more

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiyinplayer.BuildConfig
import com.shiyinplayer.update.AppUpdateInfo
import com.shiyinplayer.update.AppUpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** 版本更新检查/下载/安装的状态机。 */
sealed interface UpdateUiState {
    object Idle : UpdateUiState
    object Checking : UpdateUiState
    object UpToDate : UpdateUiState
    data class NewVersion(val info: AppUpdateInfo) : UpdateUiState       // 确认弹窗
    data class NeedInstallPermission(val info: AppUpdateInfo) : UpdateUiState // 提示去授权
    data class Downloading(val downloaded: Long, val total: Long) : UpdateUiState
    data class ReadyToInstall(val file: File, val info: AppUpdateInfo) : UpdateUiState
    data class Installed(val success: Boolean, val versionName: String) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val updateManager: AppUpdateManager
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val localCode = BuildConfig.VERSION_CODE

    /** 当前下载协程（dismiss 时取消，避免"关闭弹窗后仍在后台下载、完成后自动拉起安装器"）。 */
    private var downloadJob: Job? = null

    /** 检查更新（更多界面点击「检查版本更新」）。 */
    fun check() {
        val s = _state.value
        if (s is UpdateUiState.Checking || s is UpdateUiState.Downloading) return
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            val result = updateManager.checkForUpdate(localCode)
            if (result.error != null) {
                _state.value = UpdateUiState.Error(result.error)
            } else if (result.update != null) {
                _state.value = UpdateUiState.NewVersion(result.update)
            } else {
                _state.value = UpdateUiState.UpToDate
            }
        }
    }

    /** 用户同意更新。先校验安装权限，具备则直接下载安装，否则提示授权。 */
    fun confirmUpdate() {
        val s = _state.value
        if (s !is UpdateUiState.NewVersion) return
        if (updateManager.canRequestInstallPackages()) {
            downloadAndInstall(s.info)
        } else {
            _state.value = UpdateUiState.NeedInstallPermission(s.info)
        }
    }

    /** 用户从「允许安装未知应用」设置页返回后调用：授权成功则继续，否则保持提示。 */
    fun onInstallPermissionReturn() {
        val s = _state.value
        if (s !is UpdateUiState.NeedInstallPermission) return
        if (updateManager.canRequestInstallPackages()) {
            downloadAndInstall(s.info)
        } else {
            // 未授权：留在当前状态，用户可再次点击「去授权」或取消
        }
    }

    private fun downloadAndInstall(info: AppUpdateInfo) {
        _state.value = UpdateUiState.Downloading(0, info.size)
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = updateManager.downloadApk(info) { d, t ->
                    if (downloadJob?.isActive == true) _state.value = UpdateUiState.Downloading(d, t)
                }
                _state.value = UpdateUiState.ReadyToInstall(file, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateUiState.Error("下载安装包失败：${e.message}")
            }
        }
    }

    /** 系统安装器返回后上报结果。 */
    fun installResult(success: Boolean) {
        val s = _state.value
        if (s is UpdateUiState.ReadyToInstall) {
            _state.value = UpdateUiState.Installed(success, s.info.versionName)
        }
    }

    fun dismiss() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateUiState.Idle
    }

    fun installPermissionSettingsIntent(): Intent {
        return updateManager.installPermissionSettingsIntent()
    }

    fun installApkIntent(file: File): Intent {
        return updateManager.installApkIntent(file)
    }
}