package com.shiyinplayer.ui.radio

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shiyinplayer.data.radio.MergedRadioStation

/**
 * 电台信息对话框用的单行键值对显示。
 */
@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            "$label：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 修改电台对话框：编辑名称、多条 URL 线路、分类、地区（大陆省份→城市 / 港澳台子类 / 海外国家）。
 * 传入合并电台 [MergedRadioStation]，可同时维护所有线路。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditStationDialog(
    station: MergedRadioStation,
    onDismiss: () -> Unit,
    onSave: (name: String, urls: List<String>, genre: String) -> Unit
) {
    val parts = station.genre?.split("/") ?: emptyList()
    val legacyRegion = parts.getOrNull(1)
    var name by remember(station.mergedId) { mutableStateOf(station.displayName) }
    val urls = remember(station.mergedId) {
        mutableStateListOf<String>().also { it.addAll(station.streams.map { s -> s.url }) }
    }
    var category by remember(station.mergedId) { mutableStateOf(parts.getOrNull(0) ?: "综合") }
    // 兼容旧数据：旧格式地区可能是"香港/澳门"（应归入港澳台大类），"海外"缺国家
    var region by remember(station.mergedId) {
        mutableStateOf(
            if (legacyRegion == "香港" || legacyRegion == "澳门") "港澳台" else (legacyRegion ?: "大陆")
        )
    }
    var sub by remember(station.mergedId) {
        mutableStateOf(if (legacyRegion == "香港" || legacyRegion == "澳门") legacyRegion else (parts.getOrNull(2) ?: ""))
    }
    var city by remember(station.mergedId) { mutableStateOf(parts.getOrNull(3) ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("修改电台") },
        text = {
            Column(
                modifier = Modifier
                    .height(520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                StationEditorFields(
                    name = name,
                    onNameChange = { name = it },
                    urls = urls,
                    category = category,
                    onCategoryChange = { category = it },
                    region = region,
                    onRegionChange = { reg ->
                        region = reg
                        sub = ""
                        city = ""
                    },
                    sub = sub,
                    onSubChange = { sub = it },
                    city = city,
                    onCityChange = { city = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newGenre = ChinaRegionData.buildGenre(category, region, sub, city)
                onSave(name.trim().ifBlank { station.displayName }, urls.toList(), newGenre)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 电台表单公共组件（修改电台 / 手动输入 URL 统一使用）。
 * 布局：名称 → 多条 URL 线路 → 分类（chip 单选）→ 地区（chip 单选，大陆/港澳台/海外）→
 * 按地区显示子类：大陆省份/城市下拉、港澳台香港/澳门/台湾、海外国家文本。可整体滚动。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun StationEditorFields(
    name: String,
    onNameChange: (String) -> Unit,
    urls: SnapshotStateList<String>,
    category: String,
    onCategoryChange: (String) -> Unit,
    region: String,
    onRegionChange: (String) -> Unit,
    sub: String,
    onSubChange: (String) -> Unit,
    city: String,
    onCityChange: (String) -> Unit
) {
    val allCategories = listOf("音乐", "新闻资讯", "综合", "交通", "生活都市", "财经", "文艺", "体育", "戏曲曲艺", "国际广播", "少儿", "宗教", "三农", "老年")

    val availableCities = remember(region, sub) {
        if (region == "大陆" && sub.isNotBlank()) {
            ChinaRegionData.citiesByProvince[sub] ?: emptyList()
        } else emptyList()
    }

    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("名称") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))

    Text("线路（可添加多条）", style = MaterialTheme.typography.labelSmall)
    urls.forEachIndexed { index, u ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = u,
                onValueChange = { urls[index] = it },
                label = { Text("线路 ${index + 1}") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (urls.size > 1) {
                IconButton(onClick = { urls.removeAt(index) }) {
                    Icon(Icons.Filled.Close, contentDescription = "移除线路")
                }
            }
        }
    }
    TextButton(
        onClick = { urls.add("") },
        modifier = Modifier.padding(top = 0.dp)
    ) { Text("+ 添加线路") }
    Spacer(Modifier.height(8.dp))

    Text("分类", style = MaterialTheme.typography.labelSmall)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        allCategories.forEach { cat ->
            FilterChip(
                selected = category == cat,
                onClick = { onCategoryChange(cat) },
                label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(24.dp)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("地区", style = MaterialTheme.typography.labelSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ChinaRegionData.majorRegions.forEach { reg ->
            FilterChip(
                selected = region == reg,
                onClick = { onRegionChange(reg) },
                label = { Text(reg, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(24.dp)
            )
        }
    }

    if (region == "大陆") {
        Spacer(Modifier.height(8.dp))
        Text("省份", style = MaterialTheme.typography.labelSmall)
        var provinceExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = provinceExpanded,
            onExpandedChange = { provinceExpanded = it }
        ) {
            OutlinedTextField(
                value = sub.ifBlank { "请选择省份" },
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = provinceExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = provinceExpanded,
                onDismissRequest = { provinceExpanded = false }
            ) {
                ChinaRegionData.provinces.forEach { prov ->
                    DropdownMenuItem(
                        text = { Text(prov) },
                        onClick = {
                            onSubChange(prov)
                            provinceExpanded = false
                        }
                    )
                }
            }
        }
        if (sub.isNotBlank() && availableCities.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("城市", style = MaterialTheme.typography.labelSmall)
            var cityExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = cityExpanded,
                onExpandedChange = { cityExpanded = it }
            ) {
                OutlinedTextField(
                    value = city.ifBlank { "请选择城市" },
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cityExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = cityExpanded,
                    onDismissRequest = { cityExpanded = false }
                ) {
                    availableCities.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c) },
                            onClick = {
                                onCityChange(c)
                                cityExpanded = false
                            }
                        )
                    }
                }
            }
        }
    } else if (region == "港澳台") {
        Spacer(Modifier.height(8.dp))
        Text("子类地区", style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ChinaRegionData.hmtSubRegions.forEach { sr ->
                FilterChip(
                    selected = sub == sr,
                    onClick = { onSubChange(sr) },
                    label = { Text(sr, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(24.dp)
                )
            }
        }
    } else if (region == "海外") {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = sub,
            onValueChange = onSubChange,
            label = { Text("所属国家") },
            placeholder = { Text("例如：葡萄牙、日本、美国") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
