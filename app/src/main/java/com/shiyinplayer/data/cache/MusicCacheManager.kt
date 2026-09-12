package com.shiyinplayer.data.cache

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.media3.datasource.DataSpec
import com.shiyinplayer.data.media.SmbDataSourceFactory
import com.shiyinplayer.data.model.MediaSourceType
import com.shiyinplayer.data.model.Song
import com.shiyinplayer.data.remote.webdav.WebDavDataSourceFactory
import com.shiyinplayer.player.decoder.AudioFormatRegistry
import com.shiyinplayer.player.decoder.MagicNumberValidator
import com.shiyinplayer.ui.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络源本地缓存（离网播放）。
 *
 * 功能：
 * - **自动缓存**：播放 SMB/WebDAV 来源曲目时自动把文件下载到 app 私有缓存目录（原格式保真，不做转码）。
 * - **动态存储水位清理**：MonitorStatFs 可用空间，低于 [CACHE_WATER_LEVEL_GB 默认 50GB] 便按 LRU（least-recently-accessed）
 *   删除缓存文件释放空间；同时用 [CACHE_CAP_GB 默认 20GB] 作为缓存总量硬上限，防止在空间充裕时无限膨胀。
 * - **原格式保真**：下载原文件字节流到 .ext 文件，不做重编码；播放路由命中时就近从本地读，零网络依赖。
 *
 * 缓存索引持久化在 `cacheDir/music_cache/index.json`，进程重启后重建内存映射；失效文件自动剔除。
 * 并发：最多 2 个后台下载（[downloadSem]），下载中止/停滞有看门狗退出，避免长期占用并发槽位。
 */
@Singleton
class MusicCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webDavFactory: WebDavDataSourceFactory,
    private val smbFactory: SmbDataSourceFactory,
    private val settings: SettingsRepository
) {
    companion object {
        private const val TAG = "MusicCache"
        private const val GB = 1024L * 1024 * 1024
        private const val DOWNLOAD_STALL_MS = 20_000L
        private const val DEFAULT_WATER_LEVEL_GB = 5
        private const val DEFAULT_CAP_GB = 20
        private const val MIN_SAFE_FREE_BYTES = 1L * 1024 * 1024 * 1024 // 低于 1GB 可用直接放弃新增缓存
    }

    private val cacheDir = File(context.cacheDir, "music_cache")
    private val indexFile = File(cacheDir, "index.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val downloadSem = Semaphore(2)
    private val lock = Any()
    private val entries = LinkedHashMap<String, CacheEntry>() // url -> entry
    private val downloading = ConcurrentHashMap.newKeySet<String>()
    // single-flight：同一 URL 并发请求共享同一次下载，避免重复拉取
    private val flight = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    init {
        cacheDir.mkdirs()
        scope.launch { withContext(Dispatchers.IO) { loadIndex() } }
        startMonitoring()
    }

    /** 命中本地缓存则返回本地文件绝对路径，并刷新访问时间（LRU 依据）；未命中返回 null。 */
    fun filePathFor(url: String): String? {
        synchronized(lock) {
            val e = entries[url] ?: return null
            val f = File(cacheDir, e.fileName)
            if (!f.exists() || !f.isFile) {
                entries.remove(url)
                return null
            }
            // 完整性（2026-08-22）：尺寸不符或头部明显异常 → 判定缓存损坏，剔除并强制重新下载，
            // 避免把早期切段续传产生的坏文件一直命中 READY 重播。
            // 中危-E：按目标扩展名做魔数校验，缺位时仍做通用过小/全空拦截。
            if (f.length() != e.sizeBytes || !validAudioHeader(f, extOf(url))) {
                Log.w(TAG, "缓存损坏剔除 ${e.fileName}（记录=${e.sizeBytes}B 实际=${f.length()}B）")
                entries.remove(url)
                runCatching { f.delete() }
                return null
            }
            e.lastAccessMs = System.currentTimeMillis()
            return f.absolutePath
        }
    }

    /** 已缓存（含大小），供 UI 展示/统计。 */
    fun cachedBytes(): Long = synchronized(lock) { entries.values.sumOf { it.sizeBytes } }

    /**
     * AB-缓存随删源失效：按来源 URL 前缀清理孤儿缓存文件与索引（删源/删曲后不可达的占盘文件）。
     * 有界前缀匹配（URI 前缀），ZT 映射改写 host 的缓存可能漏网，由 LRU 水位监控兜底。返回清理条目数。
     * 需在后台线程调用（含文件删除与索引写盘）。
     */
    fun purgeByUrlPrefix(prefix: String): Int {
        if (prefix.isBlank()) return 0
        val removed = synchronized(lock) {
            val stale = entries.filterKeys { it.startsWith(prefix) }
            stale.keys.forEach { url ->
                val e = entries.remove(url) ?: return@forEach
                runCatching { File(cacheDir, e.fileName).delete() }
            }
            stale.size
        }
        if (removed > 0) saveIndex()
        if (removed > 0) Log.i(TAG, "按前缀清理缓存 $removed 项：$prefix")
        return removed
    }

    /** 播放触发自动缓存（离网播放）；不阻塞，后台下载完成后自动入索引。 */
    fun requestCache(song: Song) {
        scope.launch { ensureCached(song) }
    }

    /**
     * 确保远端曲目已缓存到本地并返回本地绝对路径；SMB/WebDAV 以外来源直接返回 null；
     * 缓存被禁用或下载失败也返回 null。阻塞至完成。
     *
     * 供"远端 FFmpeg 解不动 → 下载后系统解码"兜底路径使用：须在 IO 协程调用。
     * single-flight：同一 URL 并发调用共享同一次下载，重复调用不重复拉取。
     */
    suspend fun ensureCached(song: Song): String? {
        val type = song.source
        if (type != MediaSourceType.WEBDAV && type != MediaSourceType.SMB) return null
        val url = song.uri
        if (url.isBlank()) return null
        filePathFor(url)?.let { return it }
        val enabled = runCatching { settings.cacheEnabled.first() }.getOrDefault(true)
        if (!enabled) return null
        val def = flight.computeIfAbsent(url) { CompletableDeferred<String?>().also { d ->
            scope.launch {
                synchronized(lock) { downloading.add(url) }
                try {
                    downloadSem.withPermit { transferFile(url) }
                    d.complete(filePathFor(url))
                } catch (e: CancellationException) {
                    d.complete(null)
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "强制缓存异常 $url: ${e.message}")
                    d.complete(null)
                } finally {
                    synchronized(lock) { downloading.remove(url) }
                    flight.remove(url)
                    enforceBudget()
                }
            }
        } }
        return def.await()
    }

    /** 后台持续水位监控：进程存活期内周期触发清理。 */
    fun startMonitoring() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                if (runningCount() == 0) withContext(Dispatchers.IO) { enforceBudget() }
            }
        }
    }

    private fun runningCount(): Int = synchronized(lock) { downloading.size }

    /** 把远程源整文件下载到缓存目录（原格式保真）。阻塞函数，仅在 IO 调度器调用。 */
    private fun transferFile(url: String) {
        if (freeBytes() < MIN_SAFE_FREE_BYTES) {
            Log.w(TAG, "可用空间不足 1GB，跳过缓存 $url")
            return
        }
        val fileName = fileNameFor(url)
        val tmp = File(cacheDir, "$fileName.tmp")
        val destFile = File(cacheDir, fileName)
        val isHttp = url.startsWith("http")
        try {
            destFile.delete() // 幂等：同名残留清掉
            val src = if (isHttp) webDavFactory.createDataSource() else smbFactory.createDataSource()
            // F6-3 WebDAV 断点续传：HTTP 源复用上次中断残留的 .tmp 进度，从断点尾部发 Range 续传；
            // 非 HTTP（SMB DataSource 无 Range 支持）一律全量下载，不尝试续传。
            if (!isHttp) tmp.delete()
            val resumePos = if (isHttp) tmp.length() else 0L
            val expected = try {
                src.open(DataSpec.Builder().setUri(Uri.parse(url)).setPosition(resumePos).build())
            } catch (e: Exception) {
                Log.w(TAG, "缓存打开失败 $url (pos=$resumePos): ${e.message}")
                return
            }
            try {
                var total = resumePos
                FileOutputStream(tmp, resumePos > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastProgress = System.currentTimeMillis()
                    while (true) {
                        val n = src.read(buf, 0, buf.size)
                        if (n < 0) break
                        if (n > 0) {
                            out.write(buf, 0, n)
                            total += n
                            lastProgress = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - lastProgress > DOWNLOAD_STALL_MS) {
                            throw java.io.IOException("下载停滞（>${DOWNLOAD_STALL_MS}ms 无数据）：$url")
                        }
                    }
                    out.fd.sync()
                }
                val written = total - resumePos
                // 长度校验：open 返回的「预期剩余字节数」>0 时字节数不符 → 续传丢/多字节，判损坏。
                // （position=0 全量下载时 expected=Content-Length，行为与原一致。）
                if (expected >= 0 && written != expected) {
                    throw java.io.IOException(
                        "缓存长度不符 预期剩余=${expected}B 实际=${written}B（传输字节错位/截断），判定损坏"
                    )
                }
                if (!tmp.renameTo(destFile)) throw java.io.IOException("缓存临时文件无法重命名")
                // 头部完整性校验：明显异常（过小/全空）判损坏，避免把坏文件一直缓存重播。
                // 中危-E：下载完成按目标扩展名重校验魔数，格式不符即判损坏拉倒重下。
                if (!validAudioHeader(destFile, extOf(url))) {
                    destFile.delete()
                    throw java.io.IOException("缓存文件头校验失败：$fileName 不是有效音频，判定损坏")
                }
            } finally {
                runCatching { src.close() }
            }
            synchronized(lock) {
                entries[url] = CacheEntry(
                    url = url,
                    fileName = fileName,
                    sizeBytes = destFile.length(),
                    cachedAtMs = System.currentTimeMillis(),
                    lastAccessMs = System.currentTimeMillis()
                )
            }
            saveIndex()
            Log.i(TAG, "${if (resumePos > 0) "断点续传完成" else "已缓存"} ${destFile.length()}B  $url -> $destFile")
        } catch (e: java.io.IOException) {
            Log.w(TAG, "缓存失败 $url: ${e.message}")
            // 失败保留 .tmp，供下次触发缓存时从断点续传（不删除进度）。
        }
    }

    /** 动态水位清理：可用空间 < 水位 或 缓存总量 > 上限 时，按 LRU 删除直到达标。 */
    private suspend fun enforceBudget() {
        val waterLevel = (runCatching { settings.cacheWaterLevelGb.first() }.getOrDefault(DEFAULT_WATER_LEVEL_GB))
            .coerceAtLeast(5) * GB
        val cap = (runCatching { settings.cacheCapGb.first() }.getOrDefault(DEFAULT_CAP_GB))
            .coerceAtLeast(1) * GB
        var free = freeBytes()
        var total = synchronized(lock) { entries.values.sumOf { it.sizeBytes } }
        if (free >= waterLevel && total <= cap) return
        var evicted = 0
        while ((free < waterLevel || total > cap) && total > 0) {
            val victim = synchronized(lock) { entries.values.minByOrNull { it.lastAccessMs } } ?: break
            val file = File(cacheDir, victim.fileName)
            runCatching { file.delete() }
            synchronized(lock) { entries.remove(victim.url) }
            total = synchronized(lock) { entries.values.sumOf { it.sizeBytes } }
            free = freeBytes()
            evicted++
        }
        if (evicted > 0) {
            saveIndex()
            Log.i(TAG, "水位清理：删除 $evicted 个缓存，可用=${free / GB}GB，缓存=${total / GB}GB")
        }
    }

    private fun freeBytes(): Long = runCatching {
        val st = StatFs(cacheDir.path)
        st.availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    /** 从 URL 取目标扩展名（掐 query/fragment，取最后一个 .ext）。 */
    private fun extOf(url: String): String {
        val name = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        return name.substringAfterLast('.').trim().takeIf { it.isNotBlank() }?.lowercase() ?: ""
    }

    /** 头部完整性校验：小文件/空头直接拒；已登记魔数且非 ByExtension 的格式严格要求魔数匹配，避免把坏文件一直缓存重播。 */
    private fun validAudioHeader(file: File, ext: String): Boolean {
        if (!file.isFile || file.length() < 8) return false
        try {
            FileInputStream(file).use { ins ->
                val head = ByteArray(64)
                val n = ins.read(head)
                if (n < 4) return false
                // 注册表声明了非 ByExtension 魔数 → 严格落实格式魔数匹配；
                // ByExtension/未登记格式 → 仅做通用非法头拦截。
                when (val magic = AudioFormatRegistry.magicOf(ext)) {
                    is AudioFormatRegistry.MagicSpec.ByExtension, null ->
                        if (head.copyOf(n).all { it == 0x00.toByte() }) return false
                    else -> if (!MagicNumberValidator.validate(head.copyOf(n), ext)) return false
                }
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun fileNameFor(url: String): String {
        val hash = android.util.Base64.encodeToString(
            url.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        ).replace('+', 'a').replace('/', 'b').replace('=', 'x')
        val shortHash = hash.take(12)
        val base = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val ext = url.substringAfterLast('.', "").substringBefore('?').substringBefore('#')
            .takeIf { it.length in 1..5 && it.matches(Regex("[A-Za-z0-9]+")) } ?: "bin"
        val safeBase = base.ifBlank { "track" }
        return "${shortHash}_${safeBase}.$ext".trimEnd('.')
    }

    private suspend fun loadIndex() {
        val loaded = synchronized(lock) {
            try {
                if (!indexFile.exists()) return@synchronized emptyList<CacheEntry>()
                val arr = JSONArray(indexFile.readText())
                arr.let { ja ->
                    buildList {
                        for (i in 0 until ja.length()) {
                            val o = ja.getJSONObject(i)
                            val e = CacheEntry(
                                url = o.getString("url"),
                                fileName = o.getString("fileName"),
                                sizeBytes = o.getLong("sizeBytes"),
                                cachedAtMs = o.getLong("cachedAtMs"),
                                lastAccessMs = o.optLong("lastAccessMs", o.getLong("cachedAtMs"))
                            )
                            val f = File(cacheDir, e.fileName)
                            if (f.exists() && f.isFile) add(e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "缓存索引读取失败：${e.message}")
                emptyList()
            }
        }
        synchronized(lock) {
            entries.clear()
            loaded.forEach { entries[it.url] = it }
        }
        Log.i(TAG, "缓存索引加载 ${entries.size} 项，共 ${(cachedBytes() / MB)}MB")
        enforceBudget()
    }

    private fun saveIndex() {
        synchronized(lock) {
            try {
                val arr = JSONArray()
                entries.values.forEach { e ->
                    arr.put(JSONObject().apply {
                        put("url", e.url)
                        put("fileName", e.fileName)
                        put("sizeBytes", e.sizeBytes)
                        put("cachedAtMs", e.cachedAtMs)
                        put("lastAccessMs", e.lastAccessMs)
                    })
                }
                val tmp = File(cacheDir, "index.json.tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(indexFile)) {
                    tmp.delete()
                    indexFile.writeText(arr.toString())
                } else {
                    runCatching { tmp.delete() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "缓存索引写入失败：${e.message}")
            }
        }
    }

    private val MB = 1024L * 1024

    /** 缓存索引项（内存可变，lastAccessMs 为 LRU 依据）。 */
    private class CacheEntry(
        val url: String,
        val fileName: String,
        val sizeBytes: Long,
        val cachedAtMs: Long,
        var lastAccessMs: Long
    )
}