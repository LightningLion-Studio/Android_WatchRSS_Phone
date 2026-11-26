package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.json.JSONObject

/** Complete, decoded body chunks are exact; partial JSON frames are reported separately as wire bytes. */
internal class LibraryBodyTransferStats {
    private val seen = mutableSetOf<String>()
    private val sent = mutableSetOf<String>()
    private var bodyBytes = 0L
    private var duplicateBytes = 0L
    private var partialWireBytes = 0L
    private var attemptBody = 0L
    private var attemptRepeated = 0L
    private var attemptPartial = 0L
    fun beginAttempt() { attemptBody = bodyBytes; attemptRepeated = duplicateBytes; attemptPartial = partialWireBytes }
    fun partialWire(bytes: Long) { partialWireBytes += bytes }
    val sentArticles: Int get() = sent.size
    fun frame(direction: String, frame: JSONObject) {
        val articles = frame.optJSONArray("articles") ?: return
        for (i in 0 until articles.length()) {
            val article = articles.optJSONObject(i) ?: continue
            val id = article.optString("articleId").takeIf { it.isNotEmpty() } ?: continue
            if (direction == "send") sent += id
            val body = article.optJSONObject("body") ?: continue
            val chunks = body.optJSONArray("chunks") ?: continue
            for (n in 0 until chunks.length()) {
                val chunk = chunks.optJSONObject(n) ?: continue
                val data = chunk.opt("data") as? String ?: continue
                val bytes = data.length.toLong() * 3L / 4L - data.takeLast(2).count { it == '=' }
                val key = "$direction|$id|${body.optString("bodyHash")}|${chunk.optInt("index", -1)}|${chunk.optString("hash")}"
                bodyBytes += bytes
                if (!seen.add(key)) duplicateBytes += bytes
            }
        }
    }
    fun snapshot(): Map<String, Any> = mapOf("bodyBytes" to bodyBytes, "repeatedBodyBytes" to duplicateBytes,
        "partialFrameWireBytes" to partialWireBytes, "uniqueBodyChunks" to seen.size,
        "attemptBodyBytes" to bodyBytes - attemptBody, "attemptRepeatedBodyBytes" to duplicateBytes - attemptRepeated,
        "attemptPartialFrameWireBytes" to partialWireBytes - attemptPartial)
}
