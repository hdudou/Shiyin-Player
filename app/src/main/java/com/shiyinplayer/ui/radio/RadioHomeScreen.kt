package com.shiyinplayer.ui.radio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.shiyinplayer.R
import com.shiyinplayer.data.local.entity.RadioStationEntity
import com.shiyinplayer.data.radio.MergedRadioStation

/** 分类筛选（3行整齐排列）— 顶层常量避免每次重组创建 */
private val GENRE_ROW_1 = listOf("音乐", "新闻资讯", "综合", "交通", "生活都市")
private val GENRE_ROW_2 = listOf("财经", "文艺", "体育", "戏曲曲艺", "国际广播")
private val GENRE_ROW_3 = listOf("少儿", "宗教", "三农", "老年")
private val REGIONS = listOf("全部", "大陆", "港澳台", "海外")

/** 大陆省份/子分类排序：中央置顶，其余按中文拼音（词典序）名称排列 */
private val chinaCollator = java.text.Collator.getInstance(java.util.Locale.CHINA)
private val mainlandProvinceComparator = Comparator<String> { a, b ->
    if (a == "中央") -1
    else if (b == "中央") 1
    else chinaCollator.compare(a, b)
}
/** 省份内城市排序：省级（本省栏目）置顶，城市按中文拼音名称排列 */
private val cityComparator = Comparator<String> { a, b ->
    if (a == "省级") -1
    else if (b == "省级") 1
    else chinaCollator.compare(a, b)
}

/** SnapshotStateList<String> 的 Saver（用于 rememberSaveable 保存选中分类） */
private val StringListSaver: Saver<SnapshotStateList<String>, ArrayList<String>> = Saver(
    save = { ArrayList(it) },
    restore = { it.toMutableStateList() }
)

/**
 * 电台列表页：筛选模式。
 * 分类筛选（3行整齐排列）+ 地区筛选 + 按类型分组的电台列表（支持展开/收起）。
 * 大陆地区下支持按省市二次分类（展开/收起）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RadioHomeScreen(
    onNavigateToPlaying: () -> Unit,
    onNavigateToSearch: () -> Unit = {},
    onNavigateToImport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: RadioViewModel
) {
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val recentStations by viewModel.recentStations.collectAsStateWithLifecycle()
    val radioState by viewModel.radioState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    // 分类筛选（多选）— 用 rememberSaveable 跨配置变更保留
    val selectedGenres: SnapshotStateList<String> = rememberSaveable(saver = StringListSaver) {
        mutableStateListOf()
    }

    // 地区筛选
    var selectedRegion by rememberSaveable { mutableStateOf("全部") }

    // 展开/收起状态（用 SnapshotStateList，跨配置变更保留）
    val expandedGroups: SnapshotStateList<String> = rememberSaveable(saver = StringListSaver) {
        mutableStateListOf()
    }

    // 长按菜单状态（合并后的电台）
    var longPressStation by remember { mutableStateOf<MergedRadioStation?>(null) }
    var showInfoDialog by remember { mutableStateOf<MergedRadioStation?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<MergedRadioStation?>(null) }
    var showEditDialog by remember { mutableStateOf<MergedRadioStation?>(null) }

    // 筛选+分组缓存：只有依赖变化时才重新计算（O(n) filter + O(n) groupBy 避免每次重组跑）
    val (filteredStations, groupedByProvince, groupedByType) = remember(stations, selectedGenres, selectedRegion) {
        val filtered = stations.filter { station ->
            val genreType = station.genre?.split("/")?.firstOrNull() ?: ""
            val region = station.genre?.split("/")?.getOrNull(1) ?: station.country ?: ""
            val matchGenre = selectedGenres.isEmpty() || selectedGenres.any { g ->
                genreType.contains(g, ignoreCase = true)
            }
            val matchRegion = selectedRegion == "全部" ||
                    region.contains(selectedRegion, ignoreCase = true)
            matchGenre && matchRegion
        }

        if (selectedRegion == "大陆") {
            // 大陆：中央置顶，其余省份/省级按名称；内层城市省级置顶、其余按名称；每类下电台按名称
            val groupedP = filtered.groupBy { station ->
                station.genre?.split("/")?.getOrNull(2) ?: "其他"
            }.mapValues { (_, provStations) ->
                provStations.groupBy { station ->
                    station.genre?.split("/")?.getOrNull(3) ?: "其他"
                }.toSortedMap(cityComparator)
                    .mapValues { (_, cityStations) -> cityStations.sortedBy { it.displayName } }
            }.toSortedMap(mainlandProvinceComparator)
            Triple(filtered, groupedP, emptyMap<String, List<MergedRadioStation>>())
        } else if (selectedRegion == "港澳台") {
            // 港澳台：一级子分类 香港/澳门/台湾，每类下电台按名称
            val groupedT = filtered.groupBy { station ->
                station.genre?.split("/")?.getOrNull(2) ?: "其他"
            }.toSortedMap(mainlandProvinceComparator)
                .mapValues { (_, list) -> list.sortedBy { it.displayName } }
            Triple(filtered, emptyMap<String, Map<String, List<MergedRadioStation>>>(), groupedT)
        } else if (selectedRegion == "海外") {
            // 海外：一级子分类按国家，每类下电台按名称（国家为空则归到"其他"分组）
            val groupedT = filtered.groupBy { station ->
                station.genre?.split("/")?.getOrNull(2) ?: "其他"
            }.toSortedMap(mainlandProvinceComparator)
                .mapValues { (_, list) -> list.sortedBy { it.displayName } }
            Triple(filtered, emptyMap<String, Map<String, List<MergedRadioStation>>>(), groupedT)
        } else {
            // 全部：分类项按上方分类标题顺序（音乐/新闻资讯/综合/交通…），每类下电台按名称
            val genreOrder = (GENRE_ROW_1 + GENRE_ROW_2 + GENRE_ROW_3)
            val groupedT = filtered.groupBy { station ->
                station.genre?.split("/")?.firstOrNull() ?: "其他"
            }.toSortedMap(compareBy<String> { genre ->
                val i = genreOrder.indexOf(genre)
                if (i >= 0) i else genreOrder.size
            }).mapValues { (_, list) -> list.sortedBy { it.displayName } }
            Triple(filtered, emptyMap<String, Map<String, List<MergedRadioStation>>>(), groupedT)
        }
    }
    val isMainland = selectedRegion == "大陆"

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                val total = filteredStations.size
                Text(stringResource(R.string.radio_list_title, total))
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            actions = {
                IconButton(onClick = onNavigateToSearch) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.radio_search_title))
                }
                IconButton(onClick = onNavigateToImport) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.radio_import))
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.radio_settings))
                }
            }
        )

        // 筛选区
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            // 分类筛选标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.radio_genre_label), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                if (selectedGenres.isNotEmpty()) {
                    Text(
                        stringResource(R.string.radio_selected_count, selectedGenres.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.weight(1f))
                if (selectedGenres.isNotEmpty()) {
                    Text(
                        stringResource(R.string.radio_clear_selection),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { selectedGenres.clear() }
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            // 第1行
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                GENRE_ROW_1.forEach { genre ->
                    GenreChip(genre, selectedGenres)
                }
            }
            // 第2行
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                GENRE_ROW_2.forEach { genre ->
                    GenreChip(genre, selectedGenres)
                }
            }
            // 第3行
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                GENRE_ROW_3.forEach { genre ->
                    GenreChip(genre, selectedGenres)
                }
            }

            Spacer(Modifier.height(4.dp))

            // 地区筛选
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.radio_region), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    REGIONS.forEach { region ->
                        FilterChip(
                            selected = selectedRegion == region,
                            onClick = { selectedRegion = region },
                            label = { Text(RadioDictTranslate.text(region), style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 正在播放横条
            if (radioState.isPlaying || radioState.isBuffering) {
                item {
                    NowPlayingBar(
                        stationName = radioState.stationName,
                        programName = radioState.subtitle,
                        stationLogoUrl = radioState.coverUrl,
                        isPlaying = radioState.isPlaying,
                        isBuffering = radioState.isBuffering,
                        onClick = onNavigateToPlaying,
                        onTogglePlay = { viewModel.togglePlayPause() }
                    )
                }
            }

            // 最近收听
            if (recentStations.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.radio_recent),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        recentStations.take(5).forEach { station ->
                            RecentStationChip(
                                station = station,
                                isPlaying = isMergedRadioPlaying(radioState.streamUrl, station) && radioState.isPlaying,
                                onClick = { viewModel.playStation(station) }
                            )
                        }
                    }
                }
            }

            // 分组电台列表（支持展开/收起）
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (isMainland) {
                // 大陆：省 → 市两级分组
                groupedByProvince.forEach { (province, cityMap) ->
                    val totalInProvince = cityMap.values.sumOf { it.size }
                    val isProvExpanded = province in expandedGroups
                    // 省级标题
                    item(key = "prov_$province") {
                        GroupHeader(
                            title = RadioDictTranslate.text(province),
                            count = totalInProvince,
                            isExpanded = isProvExpanded,
                            onClick = {
                                if (isProvExpanded) expandedGroups.remove(province)
                                else expandedGroups.add(province)
                            }
                        )
                    }
                    if (isProvExpanded) {
                        if (province == "中央") {
                            // 中央：一级分类下不再细分城市，直接平铺该级所有电台（按名称）
                            val centralStations = cityMap.values.flatten().sortedBy { it.displayName }
                            items(items = centralStations, key = { "st_${it.primaryStationId}" }) { station ->
                                StationListItem(
                                    station = station,
                                    isPlaying = isMergedRadioPlaying(radioState.streamUrl, station) && radioState.isPlaying,
                                    onClick = { viewModel.playStation(station) },
                                    onFavorite = { viewModel.toggleFavorite(station) },
                                    onLongPress = { longPressStation = station }
                                )
                            }
                        } else {
                            // 省下每个市级分组：标题 + 电台列表 平铺到 LazyColumn
                            cityMap.forEach { (city, cityStations) ->
                                val cityKey = "$province/$city"
                                val isCityExpanded = cityKey in expandedGroups
                                item(key = "city_$cityKey") {
                                    CityGroupHeader(
                                        title = RadioDictTranslate.text(city),
                                        count = cityStations.size,
                                        isExpanded = isCityExpanded,
                                        onClick = {
                                            if (isCityExpanded) expandedGroups.remove(cityKey)
                                            else expandedGroups.add(cityKey)
                                        }
                                    )
                                }
                                if (isCityExpanded) {
                                    items(items = cityStations, key = { "st_${it.primaryStationId}" }) { station ->
                                        StationListItem(
                                            station = station,
                                            isPlaying = isMergedRadioPlaying(radioState.streamUrl, station) && radioState.isPlaying,
                                            onClick = { viewModel.playStation(station) },
                                            onFavorite = { viewModel.toggleFavorite(station) },
                                            onLongPress = { longPressStation = station }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // 非大陆：按类型分组
                groupedByType.forEach { (groupName, groupStations) ->
                    val isExpanded = groupName in expandedGroups
                    item(key = "grp_$groupName") {
                        GroupHeader(
                            title = RadioDictTranslate.text(groupName),
                            count = groupStations.size,
                            isExpanded = isExpanded,
                            onClick = {
                                if (isExpanded) expandedGroups.remove(groupName)
                                else expandedGroups.add(groupName)
                            }
                        )
                    }
                    if (isExpanded) {
                        items(items = groupStations, key = { "st_${it.primaryStationId}" }) { station ->
                            StationListItem(
                                station = station,
                                isPlaying = isMergedRadioPlaying(radioState.streamUrl, station) && radioState.isPlaying,
                                onClick = { viewModel.playStation(station) },
                                onFavorite = { viewModel.toggleFavorite(station) },
                                onLongPress = { longPressStation = station }
                            )
                        }
                    }
                }
            }

            if (((isMainland && groupedByProvince.isEmpty()) || (!isMainland && groupedByType.isEmpty())) && !isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.radio_no_match),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    // 长按菜单 - 使用 ModalBottomSheet 避免阻挡底部导航栏
    longPressStation?.let { station ->
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { longPressStation = null }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    station.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                if (station.streamCount > 1) {
                    Text(
                        stringResource(R.string.radio_lines_count, station.streamCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
                    )
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.radio_view_info)) },
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    selected = false,
                    onClick = {
                        showInfoDialog = station
                        longPressStation = null
                    }
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(if (station.isFavorite) R.string.radio_unfavorite else R.string.radio_add_favorite)) },
                    icon = {
                        Icon(
                            if (station.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = null
                        )
                    },
                    selected = false,
                    onClick = {
                        viewModel.toggleFavorite(station)
                        longPressStation = null
                    }
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.radio_edit)) },
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    selected = false,
                    onClick = {
                        showEditDialog = station
                        longPressStation = null
                    }
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                    icon = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    selected = false,
                    onClick = {
                        showDeleteConfirm = station
                        longPressStation = null
                    }
                )
            }
        }
    }

    // 查看电台信息对话框
    showInfoDialog?.let { station ->
        AlertDialog(
            onDismissRequest = { showInfoDialog = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.radio_station_info)) },
            text = {
                Column {
                    InfoRow(stringResource(R.string.radio_name_label), station.displayName)
                    InfoRow(stringResource(R.string.radio_category), RadioDictTranslate.text(station.genre?.split("/")?.firstOrNull() ?: "").ifEmpty { stringResource(R.string.radio_uncategorized) })
                    InfoRow(stringResource(R.string.radio_region), RadioDictTranslate.text(station.country ?: station.genre?.split("/")?.getOrNull(1) ?: "-"))
                    if (station.genre?.split("/")?.getOrNull(2) != null) {
                        InfoRow(stringResource(R.string.radio_province), RadioDictTranslate.text(station.genre.split("/").getOrNull(2) ?: "-"))
                    }
                    if (station.genre?.split("/")?.getOrNull(3) != null) {
                        InfoRow(stringResource(R.string.radio_city), RadioDictTranslate.text(station.genre.split("/").getOrNull(3) ?: "-"))
                    }
                    InfoRow(stringResource(R.string.radio_line_total_label), "${station.streamCount}")
                    station.streams.forEachIndexed { index, stream ->
                        InfoRow(stringResource(R.string.radio_line_label, index + 1), stream.name)
                    }
                    InfoRow(stringResource(R.string.radio_source), station.source)
                    InfoRow(stringResource(R.string.radio_favorite), if (station.isFavorite) stringResource(R.string.radio_favorited) else stringResource(R.string.radio_no))
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }

    // 删除确认对话框
    showDeleteConfirm?.let { station ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.radio_delete_station_title)) },
            text = {
                Text(
                    if (station.streamCount > 1)
                        stringResource(R.string.radio_delete_station_confirm_multi, station.displayName, station.streamCount)
                    else
                        stringResource(R.string.radio_delete_station_confirm, station.displayName)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteStation(station)
                    showDeleteConfirm = null
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // 修改电台对话框
    showEditDialog?.let { station ->
        EditStationDialog(
            station = station,
            onDismiss = { showEditDialog = null },
            onSave = { name, urls, genre ->
                viewModel.saveEditedStation(name, urls, genre, station)
                showEditDialog = null
            }
        )
    }
}

@Composable
private fun GenreChip(genre: String, selectedGenres: SnapshotStateList<String>) {
    val display = RadioDictTranslate.text(genre)
    val isSelected = genre in selectedGenres
    FilterChip(
        selected = isSelected,
        onClick = {
            if (isSelected) selectedGenres.remove(genre)
            else selectedGenres.add(genre)
        },
        label = { Text(display, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
        modifier = Modifier.height(24.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
}

@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.radio_count_station, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.weight(1f))
        Icon(
            if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (isExpanded) stringResource(R.string.radio_collapse) else stringResource(R.string.radio_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun CityGroupHeader(
    title: String,
    count: Int,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.radio_count_station, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.weight(1f))
        Icon(
            if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (isExpanded) stringResource(R.string.radio_collapse) else stringResource(R.string.radio_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun NowPlayingBar(
    stationName: String?,
    programName: String?,
    stationLogoUrl: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (!stationLogoUrl.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = stationLogoUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        error = androidx.compose.ui.graphics.painter.ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
                    )
                } else {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stationName ?: stringResource(R.string.radio_unknown_station),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (programName != null) {
                    Text(
                        programName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isBuffering) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onTogglePlay, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.action_play_pause) else stringResource(R.string.radio_play),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentStationChip(
    station: MergedRadioStation,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                } else {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                station.displayName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationListItem(
    station: MergedRadioStation,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isPlaying) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.5.dp)
            } else {
                val logoUrl = station.logoUrl
                if (!logoUrl.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = logoUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        error = androidx.compose.ui.graphics.painter.ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
                    )
                } else {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                station.displayName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val region = station.country ?: station.genre?.split("/")?.getOrNull(1) ?: ""
            val subtitle = listOfNotNull(
                station.genre?.split("/")?.firstOrNull(),
                region.ifEmpty { null },
                if (station.streamCount > 1) stringResource(R.string.radio_line_count_short, station.streamCount) else null
            ).map { RadioDictTranslate.text(it) }.joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onFavorite, modifier = Modifier.size(28.dp)) {
            Icon(
                if (station.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (station.isFavorite) stringResource(R.string.radio_unfavorite) else stringResource(R.string.radio_favorite),
                tint = if (station.isFavorite) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
