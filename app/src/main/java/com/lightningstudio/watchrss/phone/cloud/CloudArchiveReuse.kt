package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Reuse only a previously validated archive with the exact private versions/configuration. */
internal object CloudArchiveReuse {
    fun matches(bytes: ByteArray, versions: Map<String, String>, reader: JSONObject, appMeta: Map<String,String>, reason: (String) -> Unit = {}): Boolean {
        fun reject(code: String): Boolean { reason(code); return false }
        var articles: JSONArray? = null
        var archivedReader: JSONObject? = null
        var archivedMeta = emptyMap<String,String>()
        val bodies = mutableMapOf<String,String>()
        val blankHtml = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name.startsWith("bodies/") -> {
                        val hash = java.security.MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(8192)
                        var blank = true
                        while (true) {
                            val read = zip.read(buffer); if (read < 0) break
                            hash.update(buffer,0,read)
                            if (blank && (0 until read).any { buffer[it].toInt() !in listOf(9,10,13,32) }) blank = false
                        }
                        bodies[entry.name] = hex(hash.digest())
                        if (blank && entry.name.endsWith(".html")) blankHtml.add(entry.name)
                    }
                    else -> when (entry.name) {
                    "articles.json" -> articles = JSONArray(zip.readBytes().toString(Charsets.UTF_8))
                    "reader/manifest.json" -> archivedReader = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    "app_meta.json" -> {
                        val rows = JSONArray(zip.readBytes().toString(Charsets.UTF_8))
                        archivedMeta = (0 until rows.length()).associate { rows.getJSONObject(it).let { r -> r.getString("key") to r.getString("value") } }
                    }
                }
                }
                zip.closeEntry() // verifies each entry CRC without retaining body text
            }
        }
        val rows = articles ?: return reject("missing-metadata")
        val archived = mutableMapOf<String,String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val source = if (row.isNull("rssSourceUrl")) null else row.optString("rssSourceUrl").takeIf { it.isNotBlank() }
            val ids = com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
            val explicitPrivate = row.optBoolean("independentSaved") || row.optBoolean("favoriteSaved") || row.optBoolean("watchLaterSaved") || row.optBoolean("deleted")
            if (!ids.isImportedContentUrl(row.optString("url")) && !ids.isImportedContentUrl(source) && !(source.isNullOrBlank() && explicitPrivate)) continue
            val id = row.getString("articleId")
            if (row.optString("contentHash").isBlank()) return reject("unknown-version")
            val textHash = bodies[row.optString("contentTextEntry")] ?: return reject("missing-text")
            val htmlHash = if (row.isNull("contentHtmlEntry") || row.optString("contentHtmlEntry") in blankHtml) null else bodies[row.optString("contentHtmlEntry")] ?: return reject("missing-html")
            val signature = signature(row.getString("contentHash"),row.optBoolean("deleted"),textHash,htmlHash)
            if (archived.put(id,signature) != null) return reject("duplicate")
        }
        if (archived != versions) {
            val common = versions.keys.intersect(archived.keys)
            val meta = common.count { versions.getValue(it).split(':').take(2) != archived.getValue(it).split(':').take(2) }
            val text = common.count { versions.getValue(it).split(':').getOrNull(2) != archived.getValue(it).split(':').getOrNull(2) }
            val html = common.count { versions.getValue(it).split(':').getOrNull(3) != archived.getValue(it).split(':').getOrNull(3) }
            return reject("versions:local=${versions.size}:archive=${archived.size}:meta=$meta:text=$text:html=$html")
        }
        val empty = JSONObject().put("presets",JSONArray()).put("fonts",JSONArray()).put("backgrounds",JSONArray()).put("deletions",JSONArray())
        if (readerSignature(archivedReader ?: empty) != readerSignature(reader)) return reject("reader-settings")
        // first_use_at is deliberately per-installation, not shared library state.
        if (archivedMeta.filterKeys { it != "first_use_at" } != appMeta.filterKeys { it != "first_use_at" }) return reject("app-settings")
        return true
    }
    fun version(hash: String, deleted: Boolean, text: String, html: String?): String =
        signature(hash,deleted,digest(text.toByteArray(Charsets.UTF_8)),html?.takeIf { it.isNotBlank() }?.let { digest(it.toByteArray(Charsets.UTF_8)) })
    private fun signature(hash: String, deleted: Boolean, textHash: String, htmlHash: String?): String =
        "$hash:$deleted:$textHash:${htmlHash ?: "null"}"
    private fun digest(bytes: ByteArray): String = hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String {
        val chars = "0123456789abcdef"; val output = CharArray(bytes.size*2)
        bytes.forEachIndexed { i,b -> val n=b.toInt() and 255;output[i*2]=chars[n ushr 4];output[i*2+1]=chars[n and 15] }
        return String(output)
    }
    private fun readerSignature(value: JSONObject): String {
        val normalized = JSONObject()
        for (name in listOf("presets","fonts","backgrounds","deletions")) {
            val rows = value.optJSONArray(name) ?: JSONArray()
            normalized.put(name, JSONArray((0 until rows.length()).map { PhoneCloudRecordCodec.canonical(rows.get(it)) }.sorted()))
        }
        return PhoneCloudRecordCodec.canonical(normalized)
    }
}
