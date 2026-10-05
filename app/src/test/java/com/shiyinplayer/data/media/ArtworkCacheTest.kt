package com.shiyinplayer.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面缓存的**归属判定 + 引用计数**（需求⑤：图片永不过期，只在删曲目时清）。
 *
 * 为什么值得单测：这两条规则写错的表现都很隐蔽 ——
 *  · 归属判宽了 → 删曲目的动作会去删**用户自己的文件**（不可逆，最坏的一类 bug）；
 *  · 归属判窄了 / 忘了引用计数 → 同专辑其它曲目共用的一张封面被删掉，别人的封面空白。
 * 而它们又是纯字符串逻辑，正好能离线锁死。
 */
class ArtworkCacheTest {

    private val roots = listOf(
        "/data/user/0/com.shiyinplayer/cache/artwork",
        "/data/user/0/com.shiyinplayer/files/artwork",
    )

    private val owned = "file:///data/user/0/com.shiyinplayer/cache/artwork/123456.jpg"
    private val ownedInFiles = "file:///data/user/0/com.shiyinplayer/files/artwork/123456.jpg"

    @Test
    fun `落在我们自己封面目录下的 file 地址算自己的`() {
        assertTrue(isOwnedArtwork(owned, roots))
        assertTrue(isOwnedArtwork(ownedInFiles, roots))
    }

    @Test
    fun `远程 https 地址一律不是我们的文件`() {
        assertFalse(isOwnedArtwork("https://p1.music.126.net/abc/109951169164936450.jpg", roots))
        assertFalse(isOwnedArtwork("http://192.168.1.10/cover.jpg", roots))
    }

    @Test
    fun `不属于 artwork 目录的本地文件绝不能删`() {
        assertFalse(isOwnedArtwork("file:///storage/emulated/0/Music/cover.jpg", roots))
        assertFalse(isOwnedArtwork("file:///data/user/0/com.shiyinplayer/cache/other/1.jpg", roots))
        // 前缀相似但不是子目录：不能把 artworkX 当成 artwork
        assertFalse(isOwnedArtwork("file:///data/user/0/com.shiyinplayer/cache/artworkX/1.jpg", roots))
    }

    @Test
    fun `空值与非 file 协议一律 false`() {
        assertFalse(isOwnedArtwork(null, roots))
        assertFalse(isOwnedArtwork("", roots))
        assertFalse(isOwnedArtwork("   ", roots))
        assertFalse(isOwnedArtwork("content://media/external/audio/1", roots))
        assertFalse(isOwnedArtwork("smb://192.168.1.10/Music/a.mp3", roots))
    }

    @Test
    fun `路径分隔符是反斜杠也认得出来（不同工具链产出的路径）`() {
        // 只把**路径部分**换成反斜杠：协议那两个斜杠本来就是正斜杠，替掉它反而不是合法 URI 了
        val path = "/data/user/0/com.shiyinplayer/cache/artwork/123456.jpg".replace('/', '\\')
        assertTrue(isOwnedArtwork("file://$path", roots))
    }

    @Test
    fun `没人引用的封面才删、还有人引用的要留`() {
        val kept = owned
        val orphan = "file:///data/user/0/com.shiyinplayer/cache/artwork/999.jpg"
        val remote = "https://p1.music.126.net/abc/x.jpg"
        val referenced = setOf(kept)

        val deletable = collectDeletableArtwork(
            listOf(kept, orphan, remote, null),
            roots,
        ) { it in referenced }

        assertEquals("只剩没人引用的那张（远程地址与 null 都不该出现）", listOf(orphan), deletable)
    }

    @Test
    fun `同一地址在候选里重复出现时只删一次`() {
        val orphan = "file:///data/user/0/com.shiyinplayer/cache/artwork/999.jpg"
        val deletable = collectDeletableArtwork(listOf(orphan, orphan, orphan), roots) { false }
        assertEquals(1, deletable.size)
    }

    @Test
    fun `候选为空时什么也不删`() {
        assertTrue(collectDeletableArtwork(emptyList(), roots) { false }.isEmpty())
    }
}
