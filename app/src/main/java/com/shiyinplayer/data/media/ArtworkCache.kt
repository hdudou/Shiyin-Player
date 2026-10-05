package com.shiyinplayer.data.media

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.ImageRequest
import java.io.File

/**
 * 封面/图片缓存的**单一真源**（需求⑤ + D7 = A 字面严格：图片永不过期，只在删曲目时清）。
 *
 * 三件事集中在这里，避免散落各处各写一套：
 * 1. **目录**：我们自己从音频里提取/抓取的内嵌封面写在哪（[dir] / [pathFor]）；
 * 2. **归属判定 + 引用计数**（[isOwnedArtwork] / [collectDeletableArtwork]）：删曲目时
 *    只有「我们管的、且再没有任何行引用」的封面文件才能删 —— 同专辑多首共用一个封面是常态，
 *    按曲目直接删文件必然误删别人的封面（PC 端同样踩过，见 `CacheCleanupService`）；
 * 3. **Coil 的 ImageLoader**（[ShiyinImageLoader]）：独立目录 + 关容量淘汰 + 显式 evict。
 *
 * 归属判定与候选筛选刻意写成**纯函数**（只吃字符串与 lambda，不碰 `android.net.Uri`）——
 * JVM 单测里 android 类都是 stub，碰了就测不了，而这里恰恰是最容易写错的地方。
 */

/** 我们自己提取/抓取的内嵌封面目录名（`LibraryScanner` 写、删除时读，必须同一处定义）。 */
internal object ArtworkStore {
    const val DIR_NAME = "artwork"

    /** 内嵌封面目录（`cacheDir/artwork`）。 */
    fun dir(context: Context): File = File(context.cacheDir, DIR_NAME)

    fun pathFor(context: Context, name: String): File = File(dir(context), name)

    /**
     * 「属于我们」的封面文件根目录（删除时据此判断能不能动这个文件）。
     *
     * 同时接受 `cacheDir/artwork` 与 `filesDir/artwork`：前者是历史与当前写入位置，
     * 后者留作将来迁移（早期版本可能写过，判定宽容一点不会误删别的文件）。
     */
    fun ownedRoots(context: Context): List<String> = listOf(
        File(context.cacheDir, DIR_NAME).absolutePath,
        File(context.filesDir, DIR_NAME).absolutePath,
    )
}

/**
 * 该 uri 是否指向我们自己的封面缓存文件（`file://` 且落在 [ownedRoots] 之下）。
 *
 * ⚠️ 刻意用**纯字符串**处理而不走 `android.net.Uri`：JVM 单测里 `Uri` 是 stub，一碰就抛。
 * 同时容忍路径分隔符与大小写差异（Windows 侧工具链产出的路径可能是反斜杠）。
 */
internal fun isOwnedArtwork(uri: String?, ownedRoots: List<String>): Boolean {
    val u = uri?.trim().orEmpty()
    if (u.isEmpty() || !u.startsWith("file://", ignoreCase = true)) return false

    // 先归一化分隔符再判首字符：有的工具链产出的路径用反斜杠，若不先归一，
    // 会把它误判成 `file://host/path` 里的 host 段从而整个判错。
    var path = u.substring(7).replace('\\', '/')     // 去掉 "file://"
    if (!path.startsWith("/")) {                     // file://host/path 形式：丢掉 authority
        val slash = path.indexOf('/')
        if (slash < 0) return false
        path = path.substring(slash)
    }
    val normalizedPath = path.lowercase()

    return ownedRoots.any { root ->
        val r = root.replace('\\', '/').lowercase().trimEnd('/')
        r.isNotEmpty() && normalizedPath.startsWith("$r/")
    }
}

/**
 * 从候选中挑出**真正可以删掉**的封面 uri：
 * 我们自己管的（[isOwnedArtwork]）**且** `stillReferenced(uri)` 为 false（没人再引用）。
 *
 * 远程 `https://` 地址一律不动 —— 它们只是地址，且常常被同专辑的其它曲目共用。
 */
internal fun collectDeletableArtwork(
    candidates: Collection<String?>,
    ownedRoots: List<String>,
    stillReferenced: (String) -> Boolean,
): List<String> {
    val out = LinkedHashSet<String>()
    for (uri in candidates) {
        val u = uri?.trim().orEmpty()
        if (u.isEmpty() || !isOwnedArtwork(u, ownedRoots)) continue
        if (stillReferenced(u)) continue
        out.add(u)
    }
    return out.toList()
}

/**
 * Coil 的**自定义 ImageLoader**（D7 = A 字面严格）。
 *
 * 默认的 `Coil.imageLoader()` 有两个不符合「永不过期」的行为：
 * 1. 磁盘缓存默认落在 `cacheDir/image_cache` —— **系统在存储紧张时会清掉它**；
 * 2. 默认 `respectCacheHeaders = true`，服务端返回的 `Cache-Control: max-age=…`
 *    会让本地缓存条目「过期」，即使我们并没有重新请求。
 *
 * 因此这里：
 * - 把磁盘缓存放到 `filesDir/image_cache`（应用私有、系统不会清）；
 * - `respectCacheHeaders(false)`：是否复用本地副本由**我们**决定（删曲目时 evict），不由服务端头决定；
 * - 容量上限给到 [MAX_DISK_BYTES]。
 *
 * ⚠️ **诚实记一笔**：Coil 的 `DiskCache` 本质就是 LRU，**没有"关闭淘汰"这个开关**；
 * 给一个远超实际用量的上限（2 GB）是能做到的极限 —— 真正生效的淘汰是我们自己在删曲目时
 * 调 [evict]。顺带说明：**我们自己提取的内嵌封面不在 Coil 的磁盘缓存里**（那是 `file://`，
 * Coil 只对网络响应做磁盘缓存），它们由 [ArtworkStore] 管、删除时直接删文件。
 */
object ShiyinImageLoader {

    /** 磁盘缓存上限：给一个远超实际用量的值，等效"不淘汰"（Coil 无真正关闭开关，见类注释）。 */
    private const val MAX_DISK_BYTES = 2L * 1024 * 1024 * 1024

    /** 磁盘缓存目录名（放 filesDir 而非 cacheDir，避免被系统清掉）。 */
    private const val DISK_DIR_NAME = "image_cache"

    fun build(context: Context): ImageLoader = ImageLoader.Builder(context)
        .respectCacheHeaders(false)
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.filesDir, DISK_DIR_NAME))
                .maxSizeBytes(MAX_DISK_BYTES)
                .build()
        }
        .build()

    /**
     * 显式把某个地址的缓存条目踢掉（删曲目清封面时调用）。
     *
     * 内存缓存按 `ImageRequest.memoryCacheKey` 删、磁盘按 `diskCacheKey` 删；
     * 两者都由 Coil 自己算，这里不猜规则。任一步失败都吞掉 —— 清缓存是"尽力而为"，
     * 绝不能因为它失败而影响删曲目本身。
     */
    fun evict(context: Context, url: String) {
        if (url.isBlank()) return
        runCatching {
            val loader = coil.Coil.imageLoader(context)
            // 构造器只吃 Context，数据要另用 .data() 设；两个 key 都是可空，按可空处理
            val request = ImageRequest.Builder(context).data(url).build()
            request.memoryCacheKey?.let { loader.memoryCache?.remove(it) }
            request.diskCacheKey?.let { loader.diskCache?.remove(it) }
        }
    }
}
