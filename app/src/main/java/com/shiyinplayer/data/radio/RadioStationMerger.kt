package com.shiyinplayer.data.radio

import com.shiyinplayer.data.local.entity.RadioStationEntity
import com.shiyinplayer.ui.radio.normalizeRadioUrl

/**
 * 合并后的电台（支持多条线路/多个流地址）。
 *
 * 合并规则：
 * - 同名不同 URL → 合并为同一个电台的多条线路
 * - 同 URL 不同名 → 也合并（同一电台不同名称记录）
 * - 合并后优先选择中文名作为显示名称
 * - 所有不同 URL 保留为不同线路，播放失败时自动切下一条
 * - 用户可手动切换线路
 */
data class MergedRadioStation(
    val mergedId: String,          // 合并后的唯一ID（用于列表展示）
    val displayName: String,       // 显示名称（优选中文名）
    val primaryStationId: Long,    // 主记录ID（数据库主键，用于收藏/历史关联）
    val streams: List<RadioStream>, // 多条线路（流地址）
    val genre: String?,
    val country: String?,
    val logoUrl: String?,
    val isFavorite: Boolean,
    val source: String             // 只要有一个是 user 就用 user
) {
    /** 单条线路/流 */
    data class RadioStream(
        val stationId: Long,       // 原始数据库ID
        val url: String,           // 流地址
        val name: String,          // 原始名称（可能是英文名/别名）
        val source: String         // 原始来源
    )

    val streamCount: Int get() = streams.size
}

/**
 * 电台数据合并工具：将原始数据库中的多条记录按规则合并为含多条线路的电台。
 *
 * 合并规则（需求）：
 * 1. 同样名称有不同地址 → 认同为同一个电台合并
 * 2. 地址相同但是名称不一样 → 也认同为同一个电台合并
 * 3. 合并后优先使用中文名作为电台的合并后的名称
 * 4. 同一个电台的不同地址列为该电台的不同线路
 */
object RadioStationMerger {

    /**
     * 归一化名称用于分组：去空格、转小写、去掉常见后缀（如 "FM"、"电台" 不影响匹配）。
     * 保留汉字原序，仅做空白和大小写归一化。
     */
    private fun normalizeName(name: String): String {
        return name.trim()
            .lowercase()
            .replace(Regex("\\s+"), "")
            // 去掉常见前缀/后缀，避免因为 "北京交通广播" vs "北京交通广播FM" 不合并
            .removeSuffix("fm")
            .removeSuffix("am")
            .removeSuffix("电台")
            .removeSuffix("广播")
            .removePrefix("fm")
            .removePrefix("am")
            .ifBlank { name.trim().lowercase() }
    }

    /** 判断名称是否包含中文字符 */
    private fun hasChinese(name: String): Boolean {
        return name.any { it in '\u4e00'..'\u9fff' }
    }

    /**
     * 对原始电台列表进行合并分组。
     *
     * 分组逻辑：
     * - 先按归一化名称分组 → 同名合并
     * - 再按归一化 URL 扫描，如果已有 URL 归属到其他组，则合并两组
     * - 这样保证：同 URL 不同名的两条记录也会被合并到同一组
     */
    fun mergeStations(original: List<RadioStationEntity>): List<MergedRadioStation> {
        if (original.isEmpty()) return emptyList()

        // Step 1: 按归一化名称初步分组
        val nameGroups = original.groupBy { normalizeName(it.name) }.toMutableMap()

        // Step 2: 建立 URL → 包含该 URL 的组名 的映射，用于发现跨组合并（同 URL 不同名）
        val urlToGroupNames = mutableMapOf<String, MutableList<String>>()
        nameGroups.forEach { (groupName, stations) ->
            stations.forEach { station ->
                val normUrl = normalizeRadioUrl(station.url)
                urlToGroupNames.getOrPut(normUrl) { mutableListOf() }.add(groupName)
            }
        }

        // Step 3: 发现需要合并的组（同一个 URL 出现在多个名称分组 → 需要合并这些组）
        val groupsToMerge = urlToGroupNames.values
            .filter { it.size > 1 }
            .map { it.toSet() }
            .toList()

        // Step 4: 连通分量合并（多个组如果有交集就合并成一个大组）
        val mergedGroups = mergeConnectedGroups(nameGroups.keys, groupsToMerge)
            .mapNotNull { connectedGroupNames ->
                val allStations = connectedGroupNames.flatMap { nameGroups[it] ?: emptyList() }
                if (allStations.isEmpty()) null else mergeGroup(allStations)
            }

        // Step 5: 按显示名称（中文拼音）排序返回
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        return mergedGroups.sortedWith(Comparator { a, b -> collator.compare(a.displayName, b.displayName) })
    }

    /**
     * 连通分量合并：给定需要合并的组集合，找出所有连通的组并合并。
     * 使用并查集算法。
     */
    private fun mergeConnectedGroups(
        allGroupNames: Collection<String>,
        connections: List<Set<String>>
    ): List<Set<String>> {
        val parent = allGroupNames.associateWithTo(mutableMapOf()) { it }

        fun find(name: String): String {
            if (parent[name] != name) {
                parent[name] = find(parent[name]!!)
            }
            return parent[name]!!
        }

        fun union(a: String, b: String) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) {
                parent[rootB] = rootA
            }
        }

        connections.forEach { connectedNames ->
            val list = connectedNames.toList()
            for (i in 0 until list.lastIndex) {
                union(list[i], list[i + 1])
            }
        }

        return allGroupNames.groupBy { find(it) }.values.map { it.toSet() }
    }

    /**
     * 将一组原始电台合并为一个 [MergedRadioStation]。
     *
     * 选择规则：
     * - 显示名称：优先选择含中文的名称，多个中文选最长/最完整的
     * - 主ID：任意选一个（优选用户修改过的，其次优选内置）
     * - 收藏状态：任意一条已收藏则整体标记为已收藏
     * - 来源：任意一条是 user → 整体标记为 user（用户已修改）
     */
    private fun mergeGroup(stations: List<RadioStationEntity>): MergedRadioStation {
        // 去重 URL（同一个 URL 只保留一次）
        val distinctByUrl = stations.distinctBy { normalizeRadioUrl(it.url) }

        // 选择显示名称：优先含中文，多个中文选第一个（一般最完整）
        val chineseStations = distinctByUrl.filter { hasChinese(it.name) }
        val displayName = when {
            chineseStations.isNotEmpty() -> chineseStations.first().name
            else -> distinctByUrl.first().name
        }

        // 选择主ID：优先来源为 user（用户修改过），其次任意
        val primaryStation = when {
            distinctByUrl.any { it.source == "user" } -> distinctByUrl.first { it.source == "user" }
            else -> distinctByUrl.first()
        }

        // 构建线路列表（保持原始顺序，去重 URL 后）
        val streams = distinctByUrl.map { station ->
            MergedRadioStation.RadioStream(
                stationId = station.id,
                url = station.url,
                name = station.name,
                source = station.source
            )
        }

        // 合并属性：任意一条收藏即整体收藏；任意一条 user 即整体来源为 user
        val isFavorite = distinctByUrl.any { it.isFavorite }
        val source = if (distinctByUrl.any { it.source == "user" }) "user" else "builtin"

        // 分类/地区/logo：优先取主站，为空从其他站找
        val genre = primaryStation.genre ?: distinctByUrl.firstNotNullOfOrNull { it.genre }
        val country = primaryStation.country ?: distinctByUrl.firstNotNullOfOrNull { it.country }
        val logoUrl = primaryStation.logoUrl ?: distinctByUrl.firstNotNullOfOrNull { it.logoUrl }

        // 生成 mergedId：用主ID转字符串即可，保证唯一性
        val mergedId = "merged_${primaryStation.id}"

        return MergedRadioStation(
            mergedId = mergedId,
            displayName = displayName,
            primaryStationId = primaryStation.id,
            streams = streams,
            genre = genre,
            country = country,
            logoUrl = logoUrl,
            isFavorite = isFavorite,
            source = source
        )
    }

    /**
     * 从合并电台中提取所有原始ID（用于删除等操作）。
     */
    fun extractAllIds(merged: MergedRadioStation): List<Long> {
        return merged.streams.map { it.stationId }
    }

    /**
     * 判断给定的原始ID是否属于这个合并电台。
     */
    fun containsId(merged: MergedRadioStation, id: Long): Boolean {
        return merged.streams.any { it.stationId == id }
    }

    /**
     * 判断给定的URL是否属于这个合并电台。
     */
    fun containsUrl(merged: MergedRadioStation, url: String): Boolean {
        val normUrl = normalizeRadioUrl(url)
        return merged.streams.any { normalizeRadioUrl(it.url) == normUrl }
    }
}
