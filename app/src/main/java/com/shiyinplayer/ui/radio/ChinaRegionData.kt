package com.shiyinplayer.ui.radio

/**
 * 电台地区逐级选择数据（从内置电台 genre 字段提取去重）。
 * 用于 EditStationDialog / RadioImportScreen 的逐级地区选择。
 *
 * 地区分三大类（genre 第 2 级 = region）：
 * - 大陆  = `类型/大陆/省份(/城市)`
 * - 港澳台 = `类型/港澳台/香港|澳门|台湾`
 * - 海外  = `类型/海外/国家`
 */
object ChinaRegionData {

    /** 三个大类（与 RadioHomeScreen 的地区筛选 REGIONS 保持一致）。 */
    val majorRegions: List<String> = listOf("大陆", "港澳台", "海外")

    /** 港澳台下可选的子类地区。 */
    val hmtSubRegions: List<String> = listOf("香港", "澳门", "台湾")

    /** 所有省级行政区列表 */
    val provinces: List<String> = listOf(
        "北京", "天津", "上海", "重庆",
        "河北", "山西", "辽宁", "吉林", "黑龙江",
        "江苏", "浙江", "安徽", "福建", "江西", "山东",
        "河南", "湖北", "湖南", "广东", "广西",
        "海南", "四川", "贵州", "云南", "西藏",
        "陕西", "甘肃", "青海", "宁夏", "新疆",
        "内蒙古", "京津冀"
    )

    /** 省份 → 城市列表（含"省级"表示该省直属/省级台） */
    val citiesByProvince: Map<String, List<String>> = mapOf(
        "北京" to listOf("北京"),
        "天津" to listOf("天津"),
        "上海" to listOf("上海"),
        "重庆" to listOf("省级"),
        "河北" to listOf("省级", "石家庄", "保定", "沧州", "承德", "邯郸", "衡水", "廊坊", "唐山", "邢台", "张家口"),
        "山西" to listOf("省级", "太原", "大同", "晋城", "长治"),
        "辽宁" to listOf("省级", "沈阳", "大连", "朝阳", "辽阳"),
        "吉林" to listOf("省级", "长春", "吉林市", "通化"),
        "黑龙江" to listOf("省级", "哈尔滨", "齐齐哈尔"),
        "江苏" to listOf("省级", "南京", "常州", "南通", "苏州", "徐州", "盐城", "扬州", "镇江", "无锡", "宿迁"),
        "浙江" to listOf("省级", "杭州", "嘉兴", "金华", "湖州", "宁波", "绍兴", "台州", "温州"),
        "安徽" to listOf("省级", "合肥", "安庆", "亳州", "芜湖"),
        "福建" to listOf("省级", "福州", "泉州", "厦门", "漳州"),
        "江西" to listOf("省级", "南昌", "九江"),
        "山东" to listOf("省级", "济南", "济宁", "临沂", "青岛", "潍坊", "烟台", "菏泽"),
        "河南" to listOf("省级", "郑州", "开封", "漯河", "洛阳", "信阳", "许昌"),
        "湖北" to listOf("省级", "武汉", "宜昌", "十堰", "襄阳", "随州", "黄冈", "黄石"),
        "湖南" to listOf("省级", "长沙", "常德", "益阳", "邵阳", "郴州"),
        "广东" to listOf("省级", "广州", "深圳", "珠海", "惠州", "揭阳", "清远", "云浮"),
        "广西" to listOf("省级", "南宁", "桂林", "玉林"),
        "海南" to listOf("省级", "海口", "三亚"),
        "四川" to listOf("省级", "成都", "德阳", "达州", "乐山", "凉山"),
        "贵州" to listOf("省级", "贵阳", "黔东南"),
        "云南" to listOf("省级", "昆明", "玉溪", "昭通"),
        "西藏" to listOf("省级"),
        "陕西" to listOf("省级", "西安", "咸阳", "安康"),
        "甘肃" to listOf("省级", "张掖"),
        "青海" to listOf("省级"),
        "宁夏" to listOf("省级"),
        "新疆" to listOf("省级", "乌鲁木齐", "伊犁"),
        "内蒙古" to listOf("省级", "呼和浩特", "包头", "乌海", "鄂尔多斯"),
        "京津冀" to listOf("省级")
    )

    /**
     * 根据 genre 字段解析省份和城市。
     * genre 格式: "分类/地区/省份/城市" 或 "分类/地区/省份"
     */
    fun parseFromGenre(genre: String?): Pair<String, String> {
        val parts = genre?.split("/") ?: emptyList()
        val province = parts.getOrNull(2) ?: ""
        val city = parts.getOrNull(3) ?: ""
        return province to city
    }

    /**
     * 构建 genre 字符串。第 3 参 sub 为「子类」语义：
     * - 大陆  → 省份(可选，sub) + 城市(可选，city)
     * - 港澳台 → 香港/澳门/台湾(sub)
     * - 海外  → 国家(sub)
     */
    fun buildGenre(category: String, region: String, sub: String, city: String): String {
        return buildString {
            append(category)
            append("/").append(region)
            when (region) {
                "大陆" -> if (sub.isNotBlank()) {
                    append("/").append(sub)
                    if (city.isNotBlank()) append("/").append(city)
                }
                "港澳台", "海外" -> if (sub.isNotBlank()) append("/").append(sub)
            }
        }
    }
}
