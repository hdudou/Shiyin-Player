package com.shiyinplayer.ui.radio

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiyinplayer.data.radio.RadioBrowserClient
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 电台导入页：
 * - 第一层：选择"文件导入"或"手动输入URL"
 * - 第二层：手动输入弹窗 / 文件解析后弹列表（每条电台独立卡片含流派/地区/省/市）
 * - 整个页面背景实心，拦截点击防止穿透到后面电台列表
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RadioImportScreen(
    onBack: () -> Unit,
    onAddFavorite: (urls: List<String>, name: String, genre: String) -> Unit
) {
    val context = LocalContext.current
    val client = remember { RadioBrowserClient() }
    DisposableEffect(Unit) {
        onDispose { client.shutdown() }
    }

    // 子页模式：null = 入口选择；"url" = 手动输入弹窗；"list" = 文件解析列表
    var mode by remember { mutableStateOf<String?>(null) }
    // 文件解析后的待导入电台（每条含独立 genre）
    val pendingList = remember { mutableStateListOf<PendingStation>() }
    // 当前已添加/失败的反馈
    var feedback by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val content = context.contentResolver.openInputStream(it)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        reader.readText()
                    }
                } ?: return@let
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "playlist.txt"
                val parsed = client.parsePlaylist(content, fileName)
                // 每条电台预填默认 genre（"综合/大陆"），用户可在卡片中改
                pendingList.clear()
                parsed.forEach { (name, url) ->
                    pendingList.add(
                        PendingStation(
                            name = name,
                            url = url,
                            category = "综合",
                            region = "大陆",
                            province = "",
                            city = ""
                        )
                    )
                }
                if (pendingList.isNotEmpty()) {
                    mode = "list"
                } else {
                    feedback = "文件解析成功但未识别到任何电台条目"
                }
            } catch (e: Exception) {
                feedback = "文件解析失败：${e.message}"
            }
        }
    }

    // 系统返回键：list → 入口；url → 入口；入口 → onBack
    BackHandler(enabled = mode != null) {
        when (mode) {
            "list" -> {
                pendingList.clear()
                mode = null
            }
            "url" -> mode = null
            else -> onBack()
        }
    }

    // 拦截所有点击：整页用 clickable Box 包裹，吞掉点击事件防止穿透
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = false) {}
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(
                    when (mode) {
                        "url" -> "手动输入 URL"
                        "list" -> "文件导入 · ${pendingList.size} 个电台"
                        else -> "导入电台"
                    }
                ) },
                navigationIcon = {
                    IconButton(onClick = {
                        when (mode) {
                            "list" -> { pendingList.clear(); mode = null }
                            "url" -> mode = null
                            else -> onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            when (mode) {
                null -> ImportEntryPicker(
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onPickUrl = { mode = "url" }
                )
                "url" -> UrlInputMode(
                    onConfirm = { name, urls, genre ->
                        onAddFavorite(urls, name, genre)
                        mode = null
                        feedback = "已添加：$name"
                    },
                    onCancel = { mode = null }
                )
                "list" -> ParsedListMode(
                    pendingList = pendingList,
                    onAddOne = { station ->
                        val genre = ChinaRegionData.buildGenre(
                            station.category, station.region, station.province, station.city
                        )
                        onAddFavorite(listOf(station.url), station.name, genre)
                        pendingList.remove(station)
                    },
                    onAddAll = {
                        pendingList.forEach { station ->
                            val genre = ChinaRegionData.buildGenre(
                                station.category, station.region, station.province, station.city
                            )
                            onAddFavorite(listOf(station.url), station.name, genre)
                        }
                        pendingList.clear()
                    }
                )
            }

            feedback?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/** 待导入的电台（含用户选择的分类/地区/省/市） */
data class PendingStation(
    val name: String,
    val url: String,
    var category: String,
    var region: String,
    var province: String,
    var city: String
)

// ===== 第一层：入口选择 =====
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportEntryPicker(
    onPickFile: () -> Unit,
    onPickUrl: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "选择导入方式",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(32.dp))

        // 文件导入选项
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPickFile() },
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "从文件导入",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "支持 M3U / PLS / TXT 格式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 手动输入URL选项
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPickUrl() },
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "手动输入 URL",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "输入电台名称和流地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ===== 第二层 A：手动输入 URL =====
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun UrlInputMode(
    onConfirm: (name: String, urls: List<String>, genre: String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val urls = remember { mutableStateListOf("") }
    var category by remember { mutableStateOf("综合") }
    var region by remember { mutableStateOf("大陆") }
    var sub by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            StationEditorFields(
                name = name,
                onNameChange = { name = it },
                urls = urls,
                category = category,
                onCategoryChange = { category = it },
                region = region,
                onRegionChange = {
                    region = it
                    sub = ""
                    city = ""
                },
                sub = sub,
                onSubChange = { sub = it },
                city = city,
                onCityChange = { city = it }
            )
        }
        item {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
                Button(
                    onClick = {
                        if (urls.any { it.isNotBlank() }) {
                            val stationName = name.ifBlank { "未知电台" }
                            val genre = ChinaRegionData.buildGenre(category, region, sub, city)
                            onConfirm(stationName, urls.toList(), genre)
                        }
                    },
                    enabled = urls.any { it.isNotBlank() },
                    modifier = Modifier.weight(1f)
                ) { Text("添加") }
            }
        }
    }
}

// ===== 第二层 B：文件解析后的列表项 =====
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParsedListMode(
    pendingList: List<PendingStation>,
    onAddOne: (PendingStation) -> Unit,
    onAddAll: () -> Unit
) {
    if (pendingList.isEmpty()) {
        // 全部添加完成后的空态
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text("所有电台已添加完成", style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部批量操作栏
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "共 ${pendingList.size} 个电台，可逐个添加或批量添加",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onAddAll) {
                    Text("全部添加", fontWeight = FontWeight.Bold)
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(pendingList, key = { it.url }) { station ->
                PendingStationCard(
                    station = station,
                    onAdd = { onAddOne(station) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PendingStationCard(
    station: PendingStation,
    onAdd: () -> Unit
) {
    var categoryExpanded by remember { mutableStateOf(false) }
    var regionExpanded by remember { mutableStateOf(false) }
    var provinceExpanded by remember { mutableStateOf(false) }
    var cityExpanded by remember { mutableStateOf(false) }

    val categories = listOf("音乐", "新闻资讯", "综合", "交通", "生活都市", "财经", "文艺", "体育", "戏曲曲艺", "国际广播", "少儿", "宗教", "三农", "老年")
    val regions = ChinaRegionData.majorRegions

    val availableCities = remember(station.province) {
        if (station.province.isNotBlank()) ChinaRegionData.citiesByProvince[station.province] ?: emptyList()
        else emptyList()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 电台名 + 单独添加按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        station.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        station.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onAdd) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "添加此电台",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 分类 + 地区级联选择
            RegionCascadePicker(
                category = station.category,
                onCategoryChange = { station.category = it },
                region = station.region,
                onRegionChange = {
                    station.region = it
                    if (it != "大陆") {
                        station.province = ""
                        station.city = ""
                    }
                },
                province = station.province,
                onProvinceChange = {
                    station.province = it
                    station.city = ""
                },
                city = station.city,
                onCityChange = { station.city = it },
                categoryExpanded = categoryExpanded,
                onCategoryExpandedChange = { categoryExpanded = it },
                regionExpanded = regionExpanded,
                onRegionExpandedChange = { regionExpanded = it },
                provinceExpanded = provinceExpanded,
                onProvinceExpandedChange = { provinceExpanded = it },
                cityExpanded = cityExpanded,
                onCityExpandedChange = { cityExpanded = it },
                categories = categories,
                regions = regions,
                availableCities = availableCities
            )
        }
    }
}

// ===== 通用：分类 + 地区 + 省份 + 城市级联选择器 =====
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RegionCascadePicker(
    category: String,
    onCategoryChange: (String) -> Unit,
    region: String,
    onRegionChange: (String) -> Unit,
    province: String,
    onProvinceChange: (String) -> Unit,
    city: String,
    onCityChange: (String) -> Unit,
    categoryExpanded: Boolean,
    onCategoryExpandedChange: (Boolean) -> Unit,
    regionExpanded: Boolean,
    onRegionExpandedChange: (Boolean) -> Unit,
    provinceExpanded: Boolean,
    onProvinceExpandedChange: (Boolean) -> Unit,
    cityExpanded: Boolean,
    onCityExpandedChange: (Boolean) -> Unit,
    categories: List<String>,
    regions: List<String>,
    availableCities: List<String>
) {
    // 第 1 行：分类 + 地区
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = categoryExpanded,
            onExpandedChange = onCategoryExpandedChange,
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = category,
                onValueChange = {},
                readOnly = true,
                label = { Text("分类", style = MaterialTheme.typography.labelSmall) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) },
                modifier = Modifier.menuAnchor(),
                textStyle = MaterialTheme.typography.bodySmall
            )
            ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { onCategoryExpandedChange(false) }) {
                categories.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c, style = MaterialTheme.typography.bodySmall) },
                        onClick = { onCategoryChange(c); onCategoryExpandedChange(false) }
                    )
                }
            }
        }
        ExposedDropdownMenuBox(
            expanded = regionExpanded,
            onExpandedChange = onRegionExpandedChange,
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = region,
                onValueChange = {},
                readOnly = true,
                label = { Text("地区", style = MaterialTheme.typography.labelSmall) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(regionExpanded) },
                modifier = Modifier.menuAnchor(),
                textStyle = MaterialTheme.typography.bodySmall
            )
            ExposedDropdownMenu(expanded = regionExpanded, onDismissRequest = { onRegionExpandedChange(false) }) {
                regions.forEach { r ->
                    DropdownMenuItem(
                        text = { Text(r, style = MaterialTheme.typography.bodySmall) },
                        onClick = { onRegionChange(r); onRegionExpandedChange(false) }
                    )
                }
            }
        }
    }
    // 第 2 行：按地区显示子类选择
    if (region == "大陆") {
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExposedDropdownMenuBox(
                expanded = provinceExpanded,
                onExpandedChange = onProvinceExpandedChange,
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = province.ifBlank { "省份（可选）" },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("省份", style = MaterialTheme.typography.labelSmall) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(provinceExpanded) },
                    modifier = Modifier.menuAnchor(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                ExposedDropdownMenu(expanded = provinceExpanded, onDismissRequest = { onProvinceExpandedChange(false) }) {
                    ChinaRegionData.provinces.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p, style = MaterialTheme.typography.bodySmall) },
                            onClick = { onProvinceChange(p); onProvinceExpandedChange(false) }
                        )
                    }
                }
            }
            if (province.isNotBlank() && availableCities.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = cityExpanded,
                    onExpandedChange = onCityExpandedChange,
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = city.ifBlank { "城市（可选）" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("城市", style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cityExpanded) },
                        modifier = Modifier.menuAnchor(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    ExposedDropdownMenu(expanded = cityExpanded, onDismissRequest = { onCityExpandedChange(false) }) {
                        availableCities.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c, style = MaterialTheme.typography.bodySmall) },
                                onClick = { onCityChange(c); onCityExpandedChange(false) }
                            )
                        }
                    }
                }
            }
        }
    } else if (region == "港澳台") {
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = provinceExpanded,
            onExpandedChange = onProvinceExpandedChange,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = province.ifBlank { "请选择子类地区" },
                onValueChange = {},
                readOnly = true,
                label = { Text("子类地区", style = MaterialTheme.typography.labelSmall) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(provinceExpanded) },
                modifier = Modifier.menuAnchor(),
                textStyle = MaterialTheme.typography.bodySmall
            )
            ExposedDropdownMenu(expanded = provinceExpanded, onDismissRequest = { onProvinceExpandedChange(false) }) {
                ChinaRegionData.hmtSubRegions.forEach { sr ->
                    DropdownMenuItem(
                        text = { Text(sr, style = MaterialTheme.typography.bodySmall) },
                        onClick = { onProvinceChange(sr); onProvinceExpandedChange(false) }
                    )
                }
            }
        }
    } else if (region == "海外") {
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = province,
            onValueChange = onProvinceChange,
            label = { Text("所属国家", style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall
        )
    }
}
