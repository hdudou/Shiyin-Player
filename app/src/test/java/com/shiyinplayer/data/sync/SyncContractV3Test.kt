package com.shiyinplayer.data.sync

import com.shiyinplayer.data.sync.model.SyncContract
import com.shiyinplayer.data.sync.model.SyncEntityName
import com.shiyinplayer.data.sync.model.SyncEntityNames
import com.shiyinplayer.data.sync.model.emittableLyricSongIds
import com.shiyinplayer.data.sync.model.syncRemoteUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.16 B4（安卓侧）：同步契约升 schemaVersion 3 的不变量与两条纯规则。
 *
 * 只覆盖**能在 JVM 上真断言**的部分：本项目 `testOptions.unitTests.isReturnDefaultValues = true`，
 * 单测里的 `org.json` 是 stub（`JSONObject.put` 返回 null、`optString` 返回 null），
 * 凡走 JSONObject 的映射（`*ToJson` / `*FromJson`）在此**测不出任何东西** ——
 * 因此把两处关键规则抽成纯函数后单测，其余留给 PC 侧 `SyncProbe` 与真机联调。
 */
class SyncContractV3Test {

    // ------------------------------------------------------------ 版本常量

    @Test
    fun `同步协议版本为 3`() {
        assertEquals(3, SyncContract.SCHEMA_VERSION)
    }

    @Test
    fun `视频端口与非 JSON 例外头保持既有取值`() {
        // 这些是 PC 侧逐字对齐的常量，改错会让请求直接 404 / 读不到元数据
        assertEquals(23541, SyncContract.PORT)
        assertEquals("X-Device-Token", SyncContract.HEADER_DEVICE_TOKEN)
        assertEquals("X-Sync-File-DedupKey", SyncContract.HEADER_FILE_DEDUP_KEY)
        assertEquals("X-Sync-File-RelPath", SyncContract.HEADER_FILE_REL_PATH)
    }

    // ------------------------------------------------------------ 实体名集合

    @Test
    fun `lyrics 只属于快照实体 不进 ops 白名单`() {
        assertTrue(SyncEntityNames.LIBRARY_TYPES.contains(SyncEntityName.LYRICS))
        assertFalse(
            "lyrics 不是 op 实体：两端都不发 lyrics op，收进来会掩盖契约漂移",
            SyncEntityNames.ALL_ENTITIES.contains(SyncEntityName.LYRICS)
        )
    }

    @Test
    fun ` lyrics 排在快照实体末尾 `() {
        // 末尾 = 大表优先，PC「只要曲库/只要源」的场景不会被歌词拖慢（游标按顺序推进）
        assertEquals(SyncEntityName.LYRICS, SyncEntityNames.LIBRARY_TYPES.last())
    }

    @Test
    fun `快照实体集合仍覆盖原有七类`() {
        val expected = setOf(
            SyncEntityName.SONG, SyncEntityName.ALBUM, SyncEntityName.ARTIST,
            SyncEntityName.PLAYLIST, SyncEntityName.PLAYLIST_ITEM,
            SyncEntityName.RADIO_STATION, SyncEntityName.MUSIC_SOURCE
        )
        assertTrue(SyncEntityNames.LIBRARY_TYPES.containsAll(expected))
        assertEquals(8, SyncEntityNames.LIBRARY_TYPES.size)
    }

    // ------------------------------------------------------------ 只传地址

    @Test
    fun `远程地址按原样透传`() {
        assertEquals("https://p1.music.126.net/a.jpg", syncRemoteUrl("https://p1.music.126.net/a.jpg"))
        assertEquals("http://y.gtimg.cn/b.jpg", syncRemoteUrl("http://y.gtimg.cn/b.jpg"))
        assertEquals("HTTPS://UP.EXAMPLE/c.jpg", syncRemoteUrl("HTTPS://UP.EXAMPLE/c.jpg"))
    }

    @Test
    fun `本机路径与空值一律归一成空串`() {
        // file:// 是本机专属路径（对端机器上不存在），传过去比"没有"更糟
        assertEquals("", syncRemoteUrl("file:///data/user/0/com.shiyinplayer/cacheDir/artwork/1.jpg"))
        assertEquals("", syncRemoteUrl("content://media/external/audio/albumart/7"))
        assertEquals("", syncRemoteUrl("/storage/emulated/0/Music/cover.jpg"))
        assertEquals("", syncRemoteUrl(null))
        assertEquals("", syncRemoteUrl(""))
        assertEquals("", syncRemoteUrl("   "))
        // 只认 http/https 两种 scheme，别的一律不算"可达地址"
        assertEquals("", syncRemoteUrl("ftp://example.com/a.jpg"))
        assertEquals("", syncRemoteUrl("httpx://example.com/a.jpg"))
    }

    @Test
    fun `前后空白被裁掉`() {
        assertEquals("https://a.example/b.jpg", syncRemoteUrl("  https://a.example/b.jpg  "))
    }

    // ------------------------------------------------------------ 歌词下发筛选

    @Test
    fun `未绑定曲目的歌词行不下发`() {
        val ids = listOf<Long?>(null, null)
        assertTrue(emittableLyricSongIds(ids, mapOf(1L to "k1")).isEmpty())
    }

    @Test
    fun `曲目已删或键为空的歌词行不下发`() {
        // 曲目被删（歌词清理可能还没跑到）/ dedupKey 尚未回填 ⇒ 对端落不了地
        val ids = listOf<Long?>(1L, 2L, 3L)
        val keys = mapOf(1L to "k1", 2L to "", 3L to "k3")
        assertEquals(listOf(1L, 3L), emittableLyricSongIds(ids, keys))
    }

    @Test
    fun `同曲目的多行只下发第一条`() {
        val ids = listOf<Long?>(7L, 7L, 7L)
        val keys = mapOf(7L to "k7")
        assertEquals(listOf(7L), emittableLyricSongIds(ids, keys))
    }

    @Test
    fun `保持 DAO 给出的稳定顺序`() {
        val ids = listOf<Long?>(30L, 10L, 20L)
        val keys = mapOf(10L to "a", 20L to "b", 30L to "c")
        assertEquals(listOf(30L, 10L, 20L), emittableLyricSongIds(ids, keys))
    }

    @Test
    fun `混合场景 一次覆盖三条剔除规则`() {
        val ids = listOf<Long?>(5L, null, 6L, 5L, 7L, 8L)
        val keys = mapOf(5L to "k5", 6L to "", 7L to "k7")   // 8 号曲目已不存在
        assertEquals(listOf(5L, 7L), emittableLyricSongIds(ids, keys))
    }
}
