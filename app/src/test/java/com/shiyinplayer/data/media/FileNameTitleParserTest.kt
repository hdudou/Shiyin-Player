package com.shiyinplayer.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNameTitleParserTest {

    private fun pair(a: String?, b: String?) = Pair(a, b)

    @Test
    fun stripTrackNumberAndEmptyBracket() {
        assertEquals(pair(null, "Bar style"), parseFileNameTitle("(01) [] Bar style"))
        assertEquals(pair(null, "藍蓮花"), parseFileNameTitle("(01) [] 藍蓮花"))
        assertEquals(pair(null, "蓝莲花"), parseFileNameTitle("（02） [] 蓝莲花"))
        assertEquals(pair(null, "Rain"), parseFileNameTitle("[01] Rain"))
    }

    @Test
    fun stripTrackNumberThenArtistBracket() {
        assertEquals(pair("Enya 恩雅", "And"), parseFileNameTitle("(01) [Enya 恩雅] And"))
        assertEquals(pair("wally210", "邓紫棋成名曲《泡沫》纯享版"),
            parseFileNameTitle("(01) [wally210] 邓紫棋成名曲《泡沫》纯享版"))
    }

    @Test
    fun keepValidFormats() {
        assertEquals(pair("吴紫涵", "一定要爱你"), parseFileNameTitle("[吴紫涵] 一定要爱你"))
        assertEquals(pair("张靓颖", "画心"), parseFileNameTitle("(张靓颖)画心"))
        assertEquals(pair(null, "Superman"), parseFileNameTitle("《Superman》(双雄)"))
        assertEquals(pair("4inlove", "一千零一个愿望"), parseFileNameTitle("4inlove.-.一千零一个愿望"))
        assertEquals(pair("BEYOND", "谁伴我闯荡"), parseFileNameTitle("BEYOND - 谁伴我闯荡"))
        assertEquals(pair(null, "PEGASUS FANTASY"), parseFileNameTitle("PEGASUS FANTASY-"))
        assertEquals(pair("Sittin' On", "The Dock of the Bay"), parseFileNameTitle("(Sittin' On) The Dock of the Bay"))
    }

    /**
     * 艺术家标签里带「-003.」序号前缀的回归（与 PC 端 MetadataNameNormalizer 同一批事故）。
     *
     * 真实文件名形如 `cd08-007.Alizee - A Contre Courant.flac`，其 ARTIST 标签被写成 `-007.Alizee`，
     * 于是艺术家页出现「-003.Dido」「-004.Madonna」这类条目，把同一歌手裂成多行。
     * cleanArtist 的既有链路（先剥两端符号 → 再循环剥序号）本就能还原，这里用测试把该行为钉住，
     * 避免以后有人收窄规则时无声退化。
     */
    @Test
    fun cleanArtistStripsLeadingTrackIndexWithDot() {
        assertEquals("Dido", cleanArtist("-003.Dido"))
        assertEquals("Dido", cleanArtist("-014.Dido"))
        assertEquals("Madonna", cleanArtist("-004.Madonna"))
        assertEquals("Willie Nelson", cleanArtist("-021.Willie Nelson"))
        assertEquals("Delta Goodrem", cleanArtist("-010.Delta Goodrem"))
        assertEquals("Dido", cleanArtist("003. Dido"))
    }

    /** 反向锚点：合法艺术家名不许被这套规则改写。 */
    @Test
    fun cleanArtistKeepsLegitimateNames() {
        assertEquals("Dido", cleanArtist("Dido"))
        assertEquals("恩雅(Enya)", cleanArtist("恩雅(Enya)"))
        assertEquals("A-Lin（黄丽玲）", cleanArtist("A-Lin（黄丽玲）"))
        assertEquals("2 Unlimited", cleanArtist("2 Unlimited"))
        assertEquals("Maroon 5", cleanArtist("Maroon 5"))
    }

    /** 整串只是序号（剥完没有名字）⇒ 判无效，不入库。 */
    @Test
    fun cleanArtistRejectsBareIndex() {
        assertEquals(null, cleanArtist("003."))
        assertEquals(null, cleanArtist("-007."))
        assertEquals(null, cleanArtist("000"))
    }
}