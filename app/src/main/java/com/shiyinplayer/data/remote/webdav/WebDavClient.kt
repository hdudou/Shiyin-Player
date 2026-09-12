package com.shiyinplayer.data.remote.webdav

import android.util.Log
import android.util.Xml
import com.shiyinplayer.di.WebDavClientProvider
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.RequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 浏览客户端（架构 §1.2.11 / T14）：OkHttp 手写 PROPFIND，解析 <D:response>。
 * 不引入专用 WebDAV 库。鉴权经 Authorization 头注入（凭据来自 WebDavCredentialStore）。
 * 客户端经 [WebDavClientProvider] 按 URL 主机选择（内网/ZeroTier 信任自签，外链严格 TLS）；
 * authHost 为原始主机名（经 ZeroTier 回环映射后 baseUrl 已是 127.0.0.1，须按原主机查凭据）。
 */
@Singleton
class WebDavClient @Inject constructor(
    private val clientProvider: WebDavClientProvider,
    private val credStore: WebDavCredentialStore
) {
    private val TAG = "WebDavClient"
    private val PROPFIND_BODY = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:"><d:prop>
        <d:resourcetype/><d:getcontentlength/><d:getcontenttype/><d:displayname/>
        </d:prop></d:propfind>
    """.trimIndent()

    fun propfind(baseUrl: String, path: String, authHost: String? = null): List<WebDavEntry> {
        val url = (baseUrl.trimEnd('/') + "/" + path.trimStart('/')).trimEnd('/').ifEmpty { "/" }
        // 中危-D：明文流量收敛——http:// 仅内网来源允许（外链明文一律拒绝，防公网泄漏/篡改）。
        if (!clientProvider.cleartextAllowed(url)) {
            throw IOException("明文 HTTP 来源已被安全策略拦截（仅内网允许）：$url")
        }
        val cred = authHost?.takeIf { it.isNotBlank() }?.let { credStore.getForHost(it) }
            ?: credStore.getForUrl(baseUrl)
        Log.i(TAG, "PROPFIND: $url (auth=${cred != null})")
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", RequestBody.create(null, PROPFIND_BODY))
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .apply { if (cred != null) header("Authorization", Credentials.basic(cred.username, cred.password)) }
            .build()
        return try {
            clientProvider.of(url).newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.i(TAG, "PROPFIND: $url -> HTTP ${resp.code} (${body.length}B)")
                // 认证失败必须上抛而非返回空列表，否则会被上层误显示成"空目录/新增 0 首"，掩盖账号密码问题
                if (resp.code == 401 || resp.code == 403) throw WebDavAuthException(url, resp.code)
                if (!resp.isSuccessful) throw IOException("WebDAV 目录读取失败（HTTP ${resp.code}）：$url")
                parse(body, url)
            }
        } catch (t: IOException) {
            throw t   // 认证失败 / HTTP 错误 / IO 异常：向上冒泡，由浏览/扫描层提示用户
        } catch (t: Throwable) {
            Log.w(TAG, "PROPFIND: $url 异常: ${t.message}")
            throw IOException("WebDAV 浏览异常：${t.message}", t)
        }
    }

    /** Range GET 下载前缀字节（用于扫描时探测时长）。url 须为已映射地址，authHost 为原始主机名。 */
    fun readRange(url: String, maxBytes: Int, authHost: String? = null): ByteArray? {
        val cred = authHost?.takeIf { it.isNotBlank() }?.let { credStore.getForHost(it) }
            ?: credStore.getForUrl(url)
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${maxBytes - 1}")
            .apply { if (cred != null) header("Authorization", Credentials.basic(cred.username, cred.password)) }
            .build()
        return try {
            clientProvider.of(url).newCall(request).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return null
                resp.body?.bytes()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 全量 GET 下载远程文件（用于文件夹附件封面/说明 txt 预览）。url 须为已映射地址，authHost 为原始主机名。 */
    fun download(url: String, authHost: String? = null): ByteArray? {
        val cred = authHost?.takeIf { it.isNotBlank() }?.let { credStore.getForHost(it) }
            ?: credStore.getForUrl(url)
        val request = Request.Builder()
            .url(url)
            .apply { if (cred != null) header("Authorization", Credentials.basic(cred.username, cred.password)) }
            .build()
        return try {
            clientProvider.of(url).newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parse(xml: String, selfUrl: String): List<WebDavEntry> {
        if (xml.isBlank()) return emptyList()
        val entries = mutableListOf<WebDavEntry>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        var href = ""
        var displayName: String? = null
        var isCollection = false
        var size = 0L
        var contentType: String? = null
        var inResponse = false
        val selfPath = android.net.Uri.parse(selfUrl).path?.trimEnd('/').orEmpty()
        while (event != XmlPullParser.END_DOCUMENT) {
            val local = parser.name?.substringAfter(':') ?: ""
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (local) {
                        "response" -> { inResponse = true; href = ""; displayName = null; isCollection = false; size = 0; contentType = null }
                        "href" -> href = parser.nextText().trim()
                        "displayname" -> displayName = parser.nextText().trim()
                        "collection" -> isCollection = true
                        "getcontentlength" -> size = parser.nextText().toLongOrNull() ?: 0
                        "getcontenttype" -> contentType = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (local == "response" && inResponse) {
                        val norm = href.trimEnd('/')
                        val normDecoded = runCatching { android.net.Uri.decode(norm) }.getOrDefault(norm)
                        if (norm.isNotEmpty() && normDecoded != selfPath) {
                            val name = displayName?.takeIf { it.isNotEmpty() }
                                ?: norm.substringAfterLast('/')
                                    .let { runCatching { android.net.Uri.decode(it) }.getOrDefault(it) }
                            entries += WebDavEntry(name, isCollection, size, contentType, href)
                        }
                        inResponse = false
                    }
                }
            }
            event = parser.next()
        }
        return entries
    }
}

/**
 * WebDAV 认证失败（HTTP 401/403）。捕获方据此向用户提示账号/密码问题，
 * 而非把空列表误当成"无文件/空目录"。
 */
class WebDavAuthException(val url: String, val httpCode: Int) :
    java.io.IOException("WebDAV 认证失败（HTTP $httpCode）：请检查该来源的账号密码")
