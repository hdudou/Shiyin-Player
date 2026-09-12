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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiyinplayer.R
import com.shiyinplayer.player.EqualizerManager
import com.shiyinplayer.ui.settings.SettingsViewModel

/** 均衡器预设内部键（保持英文以便 applyGenrePreset 匹配，参考 AIMP/MusicBee/Discord Player 主流方案）。 */
private val PRESET_KEYS = listOf(
    "Flat", "Pop", "Rock", "Classical", "Jazz", "Blues", "Hip-Hop", "Electronic",
    "Dance", "Metal", "Reggae", "Latin", "Acoustic", "Vocal", "Live", "Ska", "Soft Rock"
)

/** 预设键 → 当前语言显示名；未知键（含自定义）给出可读名称。 */
@Composable
private fun presetLabel(key: String): String = when (key) {
    "Flat" -> stringResource(R.string.eq_preset_flat)
    "Pop" -> stringResource(R.string.eq_preset_pop)
    "Rock" -> stringResource(R.string.eq_preset_rock)
    "Classical" -> stringResource(R.string.eq_preset_classical)
    "Jazz" -> stringResource(R.string.eq_preset_jazz)
    "Blues" -> stringResource(R.string.eq_preset_blues)
    "Hip-Hop" -> stringResource(R.string.eq_preset_hiphop)
    "Electronic" -> stringResource(R.string.eq_preset_electronic)
    "Dance" -> stringResource(R.string.eq_preset_dance)
    "Metal" -> stringResource(R.string.eq_preset_metal)
    "Reggae" -> stringResource(R.string.eq_preset_reggae)
    "Latin" -> stringResource(R.string.eq_preset_latin)
    "Acoustic" -> stringResource(R.string.eq_preset_acoustic)
    "Vocal" -> stringResource(R.string.eq_preset_vocal)
    "Live" -> stringResource(R.string.eq_preset_live)
    "Ska" -> stringResource(R.string.eq_preset_ska)
    "Soft Rock" -> stringResource(R.string.eq_preset_softrock)
    "Custom" -> stringResource(R.string.eq_preset_custom)
    else -> key
}

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
        Text(stringResource(R.string.more_equalizer), style = MaterialTheme.typography.titleLarge)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(stringResource(R.string.eq_enable), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = eqEnabled, onCheckedChange = settingsViewModel::setEqEnabled)
        }
        Text(stringResource(R.string.eq_preset_hint), style = MaterialTheme.typography.bodySmall)
        var presetMenuOpen by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { presetMenuOpen = true },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Text(
                stringResource(R.string.eq_preset_label, presetLabel(preset)),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.eq_expand_list))
        }
        DropdownMenu(expanded = presetMenuOpen, onDismissRequest = { presetMenuOpen = false }) {
            PRESET_KEYS.forEach { key ->
                DropdownMenuItem(
                    text = { Text(presetLabel(key)) },
                    trailingIcon = { if (preset == key) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.eq_selected)) },
                    onClick = {
                        presetMenuOpen = false
                        viewModel.loadPreset(key)
                    }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.eq_auto_by_genre), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = autoEqByGenre, onCheckedChange = settingsViewModel::setAutoEqByGenre)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // 声道平衡（滑动即时生效，无需重建播放器）
        Text(stringResource(R.string.eq_channel_balance), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        val balanceLabel = when {
            channelBalance <= -0.5f -> stringResource(R.string.eq_balance_left)
            channelBalance >= 0.5f -> stringResource(R.string.eq_balance_right)
            kotlin.math.abs(channelBalance) < 0.05f -> stringResource(R.string.eq_balance_center)
            channelBalance < 0f -> stringResource(R.string.eq_balance_left_lean)
            else -> stringResource(R.string.eq_balance_right_lean)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.eq_balance_left), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = channelBalance,
                onValueChange = settingsViewModel::setChannelBalance,
                valueRange = -1f..1f,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
            Text(stringResource(R.string.eq_balance_right), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(balanceLabel, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(stringResource(R.string.eq_band_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        EqualizerManager.BAND_FREQS.forEachIndexed { i, freq ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("${freq.toInt()}Hz", modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Slider(
                    value = gains[i],
                    onValueChange = { viewModel.setBand(i, it) },
                    valueRange = -15f..15f,
                    modifier = Modifier.weight(1f)
                )
                Text("%.1f".format(gains[i]), modifier = Modifier.width(48.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
            Button(onClick = viewModel::reset) { Text(stringResource(R.string.eq_reset_default)) }
        }
    }
}