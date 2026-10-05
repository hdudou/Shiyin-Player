package com.shiyinplayer.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dedupKey 规范回归（与 PC 端 `Shiyin.Core/Library/DedupKey.cs` 逐字节一致）。
 *
 * golden 值由独立实现（Python hashlib）算出，不随本工程代码变化——任何一端改动算法都会让本测试失败。
 * 用例只覆盖**路径分支**：URI 分支依赖 `android.net.Uri`，JVM 单测下为 not-mocked，留待真机验证。
 */
class DedupKeyTest {

    @Test
    fun `本地路径键与跨端规范一致`() {
        assertEquals(
            "LOCAL:5fd952b8e9fff70abb30c5a78c2d0176d732eed1f58699a10cba2bfd64f5e94b",
            DedupKey.forLocalFile("E:\\Music\\周杰伦\\晴天.flac")
        )
        assertEquals(
            "LOCAL:21331004fb3b5107d18cefcbcec115356935938fb10e1ab288109861a6af0572",
            DedupKey.forLocalFile("/storage/emulated/0/Music/a.flac")
        )
    }

    @Test
    fun `CUE 子轨追加 idx 后缀`() {
        assertEquals(
            "LOCAL:a30e47071e5088a7a8993348ce2f24b66fabafb4517bcc41c39de0b723ad03be#idx3",
            DedupKey.forLocalFile("D:\\CD\\album.flac", 3)
        )
        // cueIndex 为空或 0 时不追加后缀（与 PC 端 `cueIndex is > 0` 判定一致）
        assertEquals(
            DedupKey.forLocalFile("D:\\CD\\album.flac"),
            DedupKey.forLocalFile("D:\\CD\\album.flac", 0)
        )
    }

    @Test
    fun `大小写与冗余分隔符归一`() {
        assertEquals(
            DedupKey.forLocalFile("/storage/emulated/0/Music/a.flac"),
            DedupKey.forLocalFile("/storage/emulated/0/music//a.flac")
        )
        assertEquals(
            DedupKey.forLocalFile("/storage/emulated/0/Music/a.flac"),
            DedupKey.forLocalFile("/storage/emulated/0/./Music/../Music/a.flac")
        )
    }

    @Test
    fun `来源类型进入键前缀`() {
        val local = DedupKey.forTrack(com.shiyinplayer.data.model.MediaSourceType.LOCAL, "x.flac")
        assertNotEquals(local, DedupKey.forTrack(com.shiyinplayer.data.model.MediaSourceType.SMB, "x.flac"))
        assertTrue(local.startsWith("LOCAL:"))
    }
}
