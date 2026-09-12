package com.shiyinplayer.ui.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.player.EqualizerManager
import com.shiyinplayer.ui.settings.SettingsViewModel

/** 均衡器预设中文名 → 管理器键名（内部键保持英文以便 applyGenrePreset 匹配，参考 AIMP/MusicBee/Discord Player 主流方案）。 */
private val PRESET_OPTIONS = listOf(
    "标准（平直）" to "Flat",
    "流行" to "Pop",
    "摇滚" to "Rock",
    "古典" to "Classical",
    "爵士" to "Jazz",
    "蓝调" to "Blues",
    "嘻哈" to "Hip-Hop",
    "电子" to "Electronic",
    "舞曲" to "Dance",
    "金属" to "Metal",
    "雷鬼" to "Reggae",
    "拉丁" to "Latin",
    "原声" to "Acoustic",
    "人声" to "Vocal",
    "现场" to "Live",
    "斯卡" to "Ska",
    "轻摇滚" to "Soft Rock"
)

/** 预设键 → 当前显示名；未知键（含自定义）给出可读名称。 */
private fun presetLabel(key: String): String =
    PRESET_OPTIONS.firstOrNull { it.second == key }?.first
        ?: if (key == "Custom") "自定义" else key

@Composable
fun EqualizerScreen(
    viewModel: EqualizerViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val gains by viewModel.gains.collectAsStateWithLifecycle()
    val preset by viewModel.preset.collectAsStateWithLifecycle()
    val eqEnabled by settingsViewModel.eqEnabled.collectAsStateWithLifecycle()
    val autoEqByGenre by settingsViewModel.autoEqByGenre.collectAsStateWithLifecycle()
    val channelBalance by settingsViewModel.channelBalance.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text("均衡器", style = MaterialTheme.typography.titleLarge)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("启用均衡器", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = eqEnabled, onCheckedChange = settingsViewModel::setEqEnabled)
        }
        Text("选择预设后各频段增益立即生效；拖动滑杆后进入自定义。", style = MaterialTheme.typography.bodySmall)
        var presetMenuOpen by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { presetMenuOpen = true },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Text(
                "预设风格：${presetLabel(preset)}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = "展开列表")
        }
        DropdownMenu(expanded = presetMenuOpen, onDismissRequest = { presetMenuOpen = false }) {
            PRESET_OPTIONS.forEach { (label, key) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = { if (preset == key) Icon(Icons.Default.Check, contentDescription = "已选中") },
                    onClick = {
                        presetMenuOpen = false
                        viewModel.loadPreset(key)
                    }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("按流派套用预设", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = autoEqByGenre, onCheckedChange = settingsViewModel::setAutoEqByGenre)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // F3-1：声道平衡（滑动即时生效，无需重建播放器）
        Text("声道平衡", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        val balanceLabel = when {
            channelBalance <= -0.5f -> "左"
            channelBalance >= 0.5f -> "右"
            kotlin.math.abs(channelBalance) < 0.05f -> "中"
            channelBalance < 0f -> "偏左"
            else -> "偏右"
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("左", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = channelBalance,
                onValueChange = settingsViewModel::setChannelBalance,
                valueRange = -1f..1f,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
            Text("右", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(balanceLabel, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("图形均衡器 10 段增益", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        EqualizerManager.BAND_FREQS.forEachIndexed { i, freq ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("${freq.toInt()}Hz", modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = gains[i],
                    onValueChange = { viewModel.setBand(i, it) },
                    valueRange = -15f..15f,
                    modifier = Modifier.weight(1f)
                )
                Text("%.1f".format(gains[i]), modifier = Modifier.width(48.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
            Button(onClick = viewModel::reset) { Text("恢复默认值") }
        }
    }
}