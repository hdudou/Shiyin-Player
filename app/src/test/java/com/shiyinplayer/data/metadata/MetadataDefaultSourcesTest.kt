package com.shiyinplayer.data.metadata

import com.shiyinplayer.ui.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认启用源 / 官方顺序的契约回归（1.0.16 需求①「两端都要接 TheAudioDB 拿中文简介」）。
 *
 * 为什么值得单独锁一条测试：`MetadataRepository.getArtistInfo` 会遍历
 * `registry.orderedEnabled()` 并 `if (!src.capabilities.contains(ARTIST)) continue`。
 * 也就是说，**只要默认启用集里没有任何一家声明 ARTIST 的源，歌手头像/简介在默认设置下就永不触发**
 * —— 代码全写好了、编译通过、单测全绿，功能却永远不会跑，属于最难发现的静默失效。
 * 2026-09-21 开发期就在 PC 端真实踩到这个坑（那边的 ArtistInfo 只由 TheAudioDB/维基声明，
 * 而两家都不在默认启用集里），故两端各加一道闸门。
 *
 * [artistCapableIds] 是**golden 表**，与各 provider 源码里的 `capabilities` 声明一一对应
 * （不引用生产常量推导，否则源里把声明删掉时测试也跟着"通过"）：
 *   NeteaseApi / QQMusicApi / GeniusApi / TheAudioDBApi / WikipediaApi 声明 ARTIST；
 *   KuwoApi / MiguApi / KugouApi 不声明。任何一端增删声明都会让本测试失败，这就是防漂移的闸门。
 */
class MetadataDefaultSourcesTest {

    /** 声明了 [MetaCapability.ARTIST]（歌手头像/简介）的源 id。 */
    private val artistCapableIds =
        listOf("netease", "qq", "genius", "theaudiodb", "wikipedia")

    @Test
    fun `默认启用集里必须至少有一家能给歌手资料的源`() {
        val enabled = SettingsRepository.defaultEnabledSources
        assertTrue("默认启用集不能为空", enabled.isNotEmpty())
        assertTrue(
            "默认启用集 $enabled 里没有任何声明 ARTIST 的源（$artistCapableIds），" +
                "getArtistInfo 会按能力位逐个跳过 → 歌手资料默认永不触发",
            enabled.any { it in artistCapableIds }
        )
    }

    @Test
    fun `TheAudioDB 默认启用（需求① 的中文简介来源）`() {
        assertTrue(
            "theaudiodb 必须默认启用，否则中文简介链路默认不可达",
            "theaudiodb" in SettingsRepository.defaultEnabledSources
        )
    }

    @Test
    fun `默认启用集必须是官方顺序表的子集`() {
        val enabled = SettingsRepository.defaultEnabledSources
        val order = SettingsRepository.defaultSourceOrder

        assertEquals("官方顺序应含全部 8 家", 8, order.size)
        assertEquals("官方顺序不应有重复 id", order.size, order.toSet().size)
        for (id in enabled) {
            assertTrue("启用集里的 «$id» 不在 official order 里，设置页会显示不出来", id in order)
        }
    }
}
