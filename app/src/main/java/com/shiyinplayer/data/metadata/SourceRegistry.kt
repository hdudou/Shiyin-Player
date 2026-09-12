package com.shiyinplayer.data.metadata

import com.shiyinplayer.ui.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 元数据源注册表（§3.2）：管理全部 8 个元数据源（网易/QQ/酷我/咪咕/酷狗/Genius/TheAudioDB/维基）。
 * 「排列顺序」与「启用集合」在 [SettingsRepository] 中分离存储（METADATA_SOURCES_ORDER /
 * METADATA_SOURCES_ENABLED）；此处经 orderedMetadataSourcesEnabledSync() 返回「按当前顺序过滤启用」的源列表，
 * 供 [MetadataRepository] 做多源兜底。「元数据来源」设置页经 [all] 展示顺序 + 开关。
 */
@Singleton
class SourceRegistry @Inject constructor(
    private val settings: SettingsRepository,
    netease: NeteaseApi,
    qq: QQMusicApi,
    kuwo: KuwoApi,
    migu: MiguApi,
    kugou: KugouApi,
    genius: GeniusApi,
    theAudioDB: TheAudioDBApi,
    wikipedia: WikipediaApi
) {
    /** 全部 8 个源，按「默认统一顺序」排列（网易→QQ→酷我→咪咕→酷狗→Genius→TheAudioDB→维基）。 */
    val all: List<MetadataSource> = listOf(
        netease, qq, kuwo, migu, kugou, genius, theAudioDB, wikipedia
    )

    private val byIdMap: Map<String, MetadataSource> = all.associateBy { it.id }

    /** 默认启用源（仅前 3 个中文核心源；其余源默认不启用，用户在「元数据来源」设置中手动开启）。 */
    val defaultEnabled: List<String> = SettingsRepository.defaultEnabledSources

    /** 全部源 id（供设置界面确认可用 id 全集）。 */
    val allIds: List<String> = all.map { it.id }

    fun byId(id: String): MetadataSource? = byIdMap[id]

    /** 当前生效源：按用户排列顺序，跳过未启用的源（同步快照，主线程安全）。 */
    fun orderedEnabled(): List<MetadataSource> =
        settings.orderedMetadataSourcesEnabledSync().mapNotNull { byIdMap[it] }
}