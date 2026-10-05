package com.shiyinplayer.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * QQ 歌手头像的挑选与拼接规则（1.0.16 需求①/③）。
 *
 * 为什么值得单测：`QQMusicApi.artist()` 是本次补上的**能力实装** —— 改造前它声明了
 * `MetaCapability.ARTIST` 却没有 `override artist()`（能力虚标，`getArtistInfo` 按优先级白跑一轮，
 * 与 B1 修掉的 TheAudioDB 同类）。provider 本体依赖 OkHttp 与 `org.json`（在 JVM 单测里是 stub），
 * 所以把「挑哪一个 singer + 怎么拼地址」抽成纯函数 [qqArtistAvatarUrl] 单独验证 ——
 * 真正会出错的也正是这条规则。
 *
 * 用例取值对照真实响应：`w=周杰伦` → `singer[0] = {mid: 0025NhlN2yWrP4, name: 周杰伦}`。
 */
class QqArtistAvatarTest {

    private fun url(mid: String) = "https://y.gtimg.cn/music/photo_new/T001R300x300M000$mid.jpg"

    @Test
    fun `名字对得上时拼出歌手图地址`() {
        assertEquals(
            url("0025NhlN2yWrP4"),
            qqArtistAvatarUrl(listOf("周杰伦" to "0025NhlN2yWrP4"), "周杰伦")
        )
    }

    @Test
    fun `合唱曲目里挑名字对得上的那个而不是第一个`() {
        val singers = listOf("费玉清" to "AAA", "周杰伦" to "BBB")
        assertEquals(url("BBB"), qqArtistAvatarUrl(singers, "周杰伦"))
    }

    @Test
    fun `名字大小写与首尾空白不影响匹配`() {
        assertEquals(url("MID1"), qqArtistAvatarUrl(listOf("  beyond " to "MID1"), "Beyond"))
    }

    @Test
    fun `名字对不上返回 null（不能拿别人的头像充数）`() {
        assertNull(qqArtistAvatarUrl(listOf("陈奕迅" to "003Nz2So3XXYek"), "周杰伦"))
    }

    @Test
    fun `mid 为空或全空白时跳过`() {
        assertNull(qqArtistAvatarUrl(listOf("周杰伦" to ""), "周杰伦"))
        assertNull(qqArtistAvatarUrl(listOf("周杰伦" to "   "), "周杰伦"))
    }

    @Test
    fun `空名字或空候选返回 null`() {
        assertNull(qqArtistAvatarUrl(listOf("周杰伦" to "0025NhlN2yWrP4"), ""))
        assertNull(qqArtistAvatarUrl(emptyList(), "周杰伦"))
    }

    @Test
    fun `拼法与 PC 端逐字一致且必须是歌手图槽位`() {
        val u = qqArtistAvatarUrlByMid("0025NhlN2yWrP4")
        assertEquals("https://y.gtimg.cn/music/photo_new/T001R300x300M0000025NhlN2yWrP4.jpg", u)
        // T001 = 歌手图、T002 = 专辑图 —— 写成 T002 会拿专辑封面当歌手头像，且两端不一致
        assertTrue("歌手图必须是 T001 槽位", u!!.contains("T001"))
    }
}
