package com.lightningstudio.watchrss.phone.data.backup

import android.util.JsonReader
import android.util.JsonToken
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibrarySyncPayload
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/** Keep only one article's JSON/body in RAM, including old plain-text mini-program relays. */
internal object CloudRelayStream {
    suspend fun read(input: InputStream, article: suspend (PhoneArticleEntity) -> Unit, source: suspend (PhoneRssSourceEntity) -> Unit) {
        JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "articles" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val row = row(reader)
                            LibrarySyncPayload.parseArticles(JSONArray().put(row)).forEach { parsed ->
                                article(parsed.copy(
                                    contentText = if (!string(row, "contentTextGzip").isNullOrBlank()) parsed.contentText else string(row, "contentText").orEmpty(),
                                    contentHtml = if (!string(row, "contentHtmlGzip").isNullOrBlank()) parsed.contentHtml else string(row, "contentHtml")
                                ))
                            }
                        }
                        reader.endArray()
                    }
                    "rssSources", "sources" -> {
                        reader.beginArray()
                        while (reader.hasNext()) LibrarySyncPayload.parseRssSources(JSONArray().put(row(reader))).forEach { source(it) }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "云端资料包含多余内容" }
        }
    }

    private fun string(row: JSONObject, name: String): String? =
        if (!row.has(name) || row.isNull(name)) null else row.getString(name)

    private fun row(reader: JsonReader): JSONObject {
        val result = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.NULL -> { reader.nextNull(); result.put(name, JSONObject.NULL) }
                JsonToken.BOOLEAN -> result.put(name, reader.nextBoolean())
                JsonToken.NUMBER -> { val number = reader.nextString(); result.put(name, number.toLongOrNull() ?: number.toDouble()) }
                JsonToken.STRING -> result.put(name, reader.nextString())
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return result
    }
}
