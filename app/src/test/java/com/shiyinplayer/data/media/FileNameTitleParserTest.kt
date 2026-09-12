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
}