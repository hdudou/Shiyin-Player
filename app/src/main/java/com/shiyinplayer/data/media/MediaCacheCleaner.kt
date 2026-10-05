package com.shiyinplayer.data.media

import android.content.Context
import com.shiyinplayer.data.local.cache.LyricCacheDao
import com.shiyinplayer.data.local.dao.AlbumDao
import com.shiyinplayer.data.local.dao.SongDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 删曲目后的**缓存清理**（需求⑤的另一半）。
 *
 * 口径：图片与歌词**永不过期**，唯一的下架时机就是「曲目被删」——
 * 所以每一条删曲目的路径都必须调用它，漏一处就会出现「删了歌，封面文件与歌词还留在盘上」。
 *
 * 清理两类东西：
 * 1. **歌词缓存**：按 `songId` 删（只删确实属于这几首的行；未绑定 songId 的旧行可能被别的歌复用）；
 * 2. **封面文件**：只删「我们自己的（`cacheDir/artwork` 下）+ 已无人引用」的，
 *    删完再把 Coil 的内存/磁盘缓存条目 evict 掉。远程 `https` 地址一律不动（它只是地址、常被共用）。
 *
 * ⚠️ **调用顺序**：封面地址要在**删行之前**取（[collectArtworkBeforeDelete]），
 * 清理要在**删行之后**做（[cleanAfterDelete]）—— 引用计数必须看到"删完之后还剩谁"。
 */
@Singleton
class MediaCacheCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lyricCacheDao: LyricCacheDao,
    private val songDao: SongDao,
    private val albumDao: AlbumDao,
) {

    /**
     * 删除曲目**之前**：把这些曲目的封面地址取出来留用（删完就查不到了，见类注释）。
     * 查询失败返回空表 —— 少删一个封面文件而已，不能让删曲目失败。
     */
    suspend fun collectArtworkBeforeDelete(songIds: Collection<Long>): List<String?> {
        val ids = songIds.distinct()
        if (ids.isEmpty()) return emptyList()
        return runCatching { songDao.getAlbumArtUrisByIds(ids) }.getOrDefault(emptyList())
    }

    /**
     * 删除曲目**之后**：清歌词缓存 + 回收无引用的封面文件并 evict。
     *
     * @param songIds 已删除的曲目主键（用于删歌词）
     * @param artworkUris 删除前用 [collectArtworkBeforeDelete] 捕获的封面地址
     */
    suspend fun cleanAfterDelete(
        songIds: Collection<Long>,
        artworkUris: Collection<String?> = emptyList(),
    ) {
        val ids = songIds.distinct()
        if (ids.isNotEmpty()) {
            runCatching { lyricCacheDao.deleteBySongIds(ids) }
        }
        deleteUnreferencedArtwork(artworkUris)
    }

    /**
     * 只清封面、不动歌词（给「移除音乐源」这类会连带删掉大量曲目的路径用也可以直接走
     * [cleanAfterDelete]；这里留一个更窄的入口，便于调用方表达意图）。
     */
    suspend fun deleteUnreferencedArtwork(artworkUris: Collection<String?>) {
        if (artworkUris.isEmpty()) return

        val ownedRoots = ArtworkStore.ownedRoots(context)

        // 引用计数在**这里**（suspend 上下文）一次性算好，再以纯集合传给 collectDeletableArtwork ——
        // 这样那边的筛选逻辑保持无协程，JVM 单测能直接跑。
        // 查询异常时保守当作"仍被引用"：宁可不删，也不要误删别人的封面。
        val referenced = artworkUris
            .mapNotNull { it?.trim()?.takeIf { u -> u.isNotEmpty() } }
            .distinct()
            .filter { uri ->
                val inSongs = runCatching { songDao.countByAlbumArtUri(uri) > 0 }.getOrDefault(true)
                if (inSongs) true else runCatching { albumDao.countByAlbumArtUri(uri) > 0 }.getOrDefault(true)
            }
            .toSet()

        val deletable = collectDeletableArtwork(artworkUris, ownedRoots) { it in referenced }

        for (uri in deletable) {
            val path = runCatching { uri.substringAfter("file://") }.getOrNull()
            if (!path.isNullOrBlank()) {
                runCatching { File(path).delete() }
            }
            ShiyinImageLoader.evict(context, uri)
        }
    }
}
