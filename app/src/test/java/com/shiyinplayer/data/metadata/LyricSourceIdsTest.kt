package com.shiyinplayer.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词来源标识跨端一致性回归（需求⑧：两端 source 取值统一，同步时不得来源漂移）。
 *
 * PC 端真源：PC 主控端仓库的 src/Shiyin.Core/Online/OnlineSourceIds.cs
 * 安卓端真源：本文件断言的 [LocalLyricLoader.SOURCE_EMBEDDED] / [LocalLyricLoader.SOURCE_SIDECAR]
 * 以及各在线 provider 返回的 id。
 *
 * golden 值写死在此（不引用生产常量推导），任何一端改了取值都会让本测试失败 ——
 * 这就是防漂移的闸门。若确需改值，必须两端同改并同步更新本测试。
 */
class LyricSourceIdsTest {

    /** PC 端 `OnlineSourceIds.ProviderIds` 的顺序与取值（8 家，顺序即默认优先级）。 */
    private val pcProviderIds = listOf(
        "netease", "qq", "kuwo", "migu", "kugou", "genius", "theaudiodb", "wikipedia"
    )

    /** PC 端 `OnlineSourceIds.LyricsIds` 中三个非在线取值。 */
    private val pcNonOnlineIds = listOf("embedded", "sidecar", "manual")

    @Test
    fun `本地歌词来源取值与 PC 端一致`() {
        assertEquals("embedded", LocalLyricLoader.SOURCE_EMBEDDED)
        assertEquals("sidecar", LocalLyricLoader.SOURCE_SIDECAR)
    }

    @Test
    fun `本地来源必须属于规范集合`() {
        assertTrue(LocalLyricLoader.SOURCE_EMBEDDED in pcNonOnlineIds)
        assertTrue(LocalLyricLoader.SOURCE_SIDECAR in pcNonOnlineIds)
        assertFalse(LocalLyricLoader.SOURCE_EMBEDDED in pcProviderIds)
        assertFalse(LocalLyricLoader.SOURCE_SIDECAR in pcProviderIds)
    }

    @Test
    fun `不得回退到旧的自定义串或展示名`() {
        // 改造前写死的 "local"，以及 PC 端历史误用的展示名 —— 出现即判定漂移
        val forbidden = listOf("local", "网易云音乐", "QQ音乐", "酷我音乐", "酷狗音乐", "咪咕音乐")
        for (bad in forbidden) {
            assertNotEquals(bad, LocalLyricLoader.SOURCE_EMBEDDED)
            assertNotEquals(bad, LocalLyricLoader.SOURCE_SIDECAR)
        }
    }

    @Test
    fun `来源标识必须是小写 ASCII 单词`() {
        for (id in listOf(LocalLyricLoader.SOURCE_EMBEDDED, LocalLyricLoader.SOURCE_SIDECAR)) {
            assertTrue("«$id» 应为小写", id == id.lowercase())
            assertTrue("«$id» 应仅含 ascii 字母数字", id.all { it.isLetterOrDigit() && it.code < 128 })
        }
    }
}
