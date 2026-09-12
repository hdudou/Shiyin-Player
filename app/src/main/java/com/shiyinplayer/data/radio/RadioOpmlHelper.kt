package com.shiyinplayer.data.radio

import android.content.Context
import android.net.Uri
import com.shiyinplayer.data.local.entity.RadioStationEntity
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.InputStream
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OPML 导入导出工具类。
 *
 * OPML 格式示例：
 * ```xml
 * <?xml version="1.0" encoding="UTF-8"?>
 * <opml version="2.0">
 *   <head>
 *     <title>ShiyinPlayer 电台收藏</title>
 *     <dateCreated>Sat, 05 Sep 2026 12:00:00 GMT</dateCreated>
 *   </head>
 *   <body>
 *     <outline text="中国之声" type="audio"
 *              url="http://stream.example.com/zgzs"
 *              description="分类/地区" logo="http://..." />
 *   </body>
 * </opml>
 * ```
 */
object RadioOpmlHelper {

    /**
     * 将收藏电台列表导出为 OPML XML 字符串。
     */
    fun exportToOpml(stations: List<RadioStationEntity>): String {
        val serializer: XmlSerializer = XmlPullParserFactory.newInstance().newSerializer()
        val writer = StringWriter()
        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)

        // <opml version="2.0">
        serializer.startTag(null, "opml")
        serializer.attribute(null, "version", "2.0")

        // <head>
        serializer.startTag(null, "head")
        serializer.startTag(null, "title")
        serializer.text("ShiyinPlayer 电台收藏")
        serializer.endTag(null, "title")
        serializer.startTag(null, "dateCreated")
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        serializer.text(dateFormat.format(Date()))
        serializer.endTag(null, "dateCreated")
        serializer.endTag(null, "head")

        // <body>
        serializer.startTag(null, "body")

        for (station in stations) {
            serializer.startTag(null, "outline")
            serializer.attribute(null, "text", station.name)
            serializer.attribute(null, "type", "audio")
            serializer.attribute(null, "url", station.url)
            if (!station.genre.isNullOrBlank()) {
                serializer.attribute(null, "description", station.genre)
            }
            if (!station.logoUrl.isNullOrBlank()) {
                serializer.attribute(null, "logo", station.logoUrl)
            }
            serializer.endTag(null, "outline")
        }

        serializer.endTag(null, "body")
        serializer.endTag(null, "opml")
        serializer.endDocument()

        return writer.toString()
    }

    /**
     * 从输入流解析 OPML 文件，返回电台实体列表。
     * 不设置 id/isFavorite（由调用方决定）。
     */
    fun importFromOpml(inputStream: InputStream): List<RadioStationEntity> {
        val stations = mutableListOf<RadioStationEntity>()
        val parser: XmlPullParser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "outline") {
                val type = parser.getAttributeValue(null, "type")
                val url = parser.getAttributeValue(null, "url")
                // 只导入 type=audio 或有 url 的 outline
                if (!url.isNullOrBlank() && (type == "audio" || type == null)) {
                    val name = parser.getAttributeValue(null, "text")
                        ?: parser.getAttributeValue(null, "title")
                        ?: "未知电台"
                    val genre = parser.getAttributeValue(null, "description")
                    val logoUrl = parser.getAttributeValue(null, "logo")
                        ?: parser.getAttributeValue(null, "image")

                    stations.add(
                        RadioStationEntity(
                            name = name,
                            url = url,
                            genre = genre,
                            logoUrl = logoUrl,
                            source = "user"
                        )
                    )
                }
            }
            eventType = parser.next()
        }
        return stations
    }

    /**
     * 导出 OPML 到 Downloads 目录，返回文件 Uri（MediaStore）。
     * 使用 MediaStore API 写入，无需 WRITE_EXTERNAL_STORAGE 权限。
     */
    fun exportToDownloads(context: Context, stations: List<RadioStationEntity>): Uri? {
        val content = exportToOpml(stations)
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "ShiyinPlayer_电台收藏.opml")
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/x-opml")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null
        try {
            resolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }
}
