package com.shiyinplayer.ui.radio

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 电台数据字典「展示层」中英映射。
 *
 * 设计约束：这些字典值同时用于**存储与匹配逻辑**（genre 字段、筛选比对 `contains`、
 * 比较器对 "中央"/"省级" 的硬判断、`region == "大陆/港澳台/海外"` 的分支路由），
 * 因此存储/匹配必须保持中文原始值，绝不可翻译。
 * 本对象仅负责把中文字典值映射为当前语言的**展示文本**（供 UI 显示）。
 *
 * 未收录的值原样返回（例如 RadioBrowser 返回的海外国家名，属自由数据，保持原文）。
 */
object RadioDictTranslate {

    /** 分类（genre 第一级） */
    private val CATEGORY: Map<String, String> = mapOf(
        "音乐" to "Music",
        "新闻资讯" to "News",
        "综合" to "Variety",
        "交通" to "Traffic",
        "生活都市" to "Lifestyle",
        "财经" to "Finance",
        "文艺" to "Arts",
        "体育" to "Sports",
        "戏曲曲艺" to "Opera",
        "国际广播" to "International",
        "少儿" to "Children",
        "宗教" to "Religion",
        "三农" to "Agriculture",
        "老年" to "Elderly"
    )

    /** 地区大类 + "全部"（RadioHomeScreen 的 REGIONS / ChinaRegionData.majorRegions） */
    private val REGION: Map<String, String> = mapOf(
        "全部" to "All",
        "大陆" to "Mainland",
        "港澳台" to "HK · Macau · TW",
        "海外" to "Overseas"
    )

    /** 港澳台子区（ChinaRegionData.hmtSubRegions） */
    private val HMT: Map<String, String> = mapOf(
        "香港" to "Hong Kong",
        "澳门" to "Macau",
        "台湾" to "Taiwan"
    )

    /** 省份（ChinaRegionData.provinces） */
    private val PROVINCE: Map<String, String> = mapOf(
        "北京" to "Beijing",
        "天津" to "Tianjin",
        "上海" to "Shanghai",
        "重庆" to "Chongqing",
        "河北" to "Hebei",
        "山西" to "Shanxi",
        "辽宁" to "Liaoning",
        "吉林" to "Jilin",
        "黑龙江" to "Heilongjiang",
        "江苏" to "Jiangsu",
        "浙江" to "Zhejiang",
        "安徽" to "Anhui",
        "福建" to "Fujian",
        "江西" to "Jiangxi",
        "山东" to "Shandong",
        "河南" to "Henan",
        "湖北" to "Hubei",
        "湖南" to "Hunan",
        "广东" to "Guangdong",
        "广西" to "Guangxi",
        "海南" to "Hainan",
        "四川" to "Sichuan",
        "贵州" to "Guizhou",
        "云南" to "Yunnan",
        "西藏" to "Tibet",
        "陕西" to "Shaanxi",
        "甘肃" to "Gansu",
        "青海" to "Qinghai",
        "宁夏" to "Ningxia",
        "新疆" to "Xinjiang",
        "内蒙古" to "Inner Mongolia",
        "京津冀" to "Jing-Jin-Ji"
    )

    /** 城市（ChinaRegionData.citiesByProvince 全部值） + 逻辑标记 */
    private val CITY: Map<String, String> = mapOf(
        // 逻辑标记（同一模式再用于城市下拉，故并入）
        "省级" to "Provincial",
        "中央" to "Central",
        "其他" to "Other",
        // 直辖市
        "北京" to "Beijing",
        "天津" to "Tianjin",
        "上海" to "Shanghai",
        "重庆" to "Chongqing",
        // 河北
        "石家庄" to "Shijiazhuang",
        "保定" to "Baoding",
        "沧州" to "Cangzhou",
        "承德" to "Chengde",
        "邯郸" to "Handan",
        "衡水" to "Hengshui",
        "廊坊" to "Langfang",
        "唐山" to "Tangshan",
        "邢台" to "Xingtai",
        "张家口" to "Zhangjiakou",
        // 山西
        "太原" to "Taiyuan",
        "大同" to "Datong",
        "晋城" to "Jincheng",
        "长治" to "Changzhi",
        // 辽宁
        "沈阳" to "Shenyang",
        "大连" to "Dalian",
        "朝阳" to "Chaoyang",
        "辽阳" to "Liaoyang",
        // 吉林
        "长春" to "Changchun",
        "吉林市" to "Jilin City",
        "通化" to "Tonghua",
        // 黑龙江
        "哈尔滨" to "Harbin",
        "齐齐哈尔" to "Qiqihar",
        // 江苏
        "南京" to "Nanjing",
        "常州" to "Changzhou",
        "南通" to "Nantong",
        "苏州" to "Suzhou",
        "徐州" to "Xuzhou",
        "盐城" to "Yancheng",
        "扬州" to "Yangzhou",
        "镇江" to "Zhenjiang",
        "无锡" to "Wuxi",
        "宿迁" to "Suqian",
        // 浙江
        "杭州" to "Hangzhou",
        "嘉兴" to "Jiaxing",
        "金华" to "Jinhua",
        "湖州" to "Huzhou",
        "宁波" to "Ningbo",
        "绍兴" to "Shaoxing",
        "台州" to "Taizhou",
        "温州" to "Wenzhou",
        // 安徽
        "合肥" to "Hefei",
        "安庆" to "Anqing",
        "亳州" to "Bozhou",
        "芜湖" to "Wuhu",
        // 福建
        "福州" to "Fuzhou",
        "泉州" to "Quanzhou",
        "厦门" to "Xiamen",
        "漳州" to "Zhangzhou",
        // 江西
        "南昌" to "Nanchang",
        "九江" to "Jiujiang",
        // 山东
        "济南" to "Jinan",
        "济宁" to "Jining",
        "临沂" to "Linyi",
        "青岛" to "Qingdao",
        "潍坊" to "Weifang",
        "烟台" to "Yantai",
        "菏泽" to "Heze",
        // 河南
        "郑州" to "Zhengzhou",
        "开封" to "Kaifeng",
        "漯河" to "Luohe",
        "洛阳" to "Luoyang",
        "信阳" to "Xinyang",
        "许昌" to "Xuchang",
        // 湖北
        "武汉" to "Wuhan",
        "宜昌" to "Yichang",
        "十堰" to "Shiyan",
        "襄阳" to "Xiangyang",
        "随州" to "Suizhou",
        "黄冈" to "Huanggang",
        "黄石" to "Huangshi",
        // 湖南
        "长沙" to "Changsha",
        "常德" to "Changde",
        "益阳" to "Yiyang",
        "邵阳" to "Shaoyang",
        "郴州" to "Chenzhou",
        // 广东
        "广州" to "Guangzhou",
        "深圳" to "Shenzhen",
        "珠海" to "Zhuhai",
        "惠州" to "Huizhou",
        "揭阳" to "Jieyang",
        "清远" to "Qingyuan",
        "云浮" to "Yunfu",
        // 广西
        "南宁" to "Nanning",
        "桂林" to "Guilin",
        "玉林" to "Yulin",
        // 海南
        "海口" to "Haikou",
        "三亚" to "Sanya",
        // 四川
        "成都" to "Chengdu",
        "德阳" to "Deyang",
        "达州" to "Dazhou",
        "乐山" to "Leshan",
        "凉山" to "Liangshan",
        // 贵州
        "贵阳" to "Guiyang",
        "黔东南" to "Qiandongnan",
        // 云南
        "昆明" to "Kunming",
        "玉溪" to "Yuxi",
        "昭通" to "Zhaotong",
        // 陕西
        "西安" to "Xi'an",
        "咸阳" to "Xianyang",
        "安康" to "Ankang",
        // 甘肃
        "张掖" to "Zhangye",
        // 新疆
        "乌鲁木齐" to "Urumqi",
        "伊犁" to "Ili",
        // 内蒙古
        "呼和浩特" to "Hohhot",
        "包头" to "Baotou",
        "乌海" to "Wuhai",
        "鄂尔多斯" to "Ordos"
    )

    private val ALL: Map<String, String> =
        CATEGORY + REGION + HMT + PROVINCE + CITY

    /** 是否为英文展示环境（中文或其他语言一律回退中文原文） */
    @Composable
    fun isEnglish(): Boolean =
        LocalConfiguration.current.locales[0].language == "en"

    /**
     * 把中文字典值映射为当前语言展示文本。
     * 英文环境且有收录译名 → 返回译文；否则返回原文。
     */
    @Composable
    fun text(value: String): String =
        if (isEnglish()) ALL[value] ?: value else value

    /** 供非 Composable 上下文（如 ViewModel/纯函数）使用的映射，需显式传入是否英文 */
    fun text(value: String, english: Boolean): String =
        if (english) ALL[value] ?: value else value
}