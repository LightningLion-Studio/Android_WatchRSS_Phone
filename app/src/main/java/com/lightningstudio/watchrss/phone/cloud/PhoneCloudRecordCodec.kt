package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject
import org.json.JSONArray
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire domains are shared with the mini program; no plaintext body is accepted. */
internal object PhoneCloudRecordCodec {
    private fun key(accountKey: ByteArray, user: String, domain: String) =
        CloudSnapshotCodec.hkdfSha256(accountKey, user.toByteArray(Charsets.UTF_8), domain.toByteArray(Charsets.UTF_8), 32)
    fun id(accountKey: ByteArray, user: String, kind: String, value: JSONObject): String {
        require(kind in setOf("article", "source", "rss-state", "book", "book-reading"))
        val identity = if(kind=="book")value.getString("bookId") else if(kind=="book-reading")value.getString("bookId")+":"+value.getInt("chapterIndex") else if (kind == "rss-state") value.getString("sourceId") + ":" + value.getString("articleIdHash") else value.getString(if (kind == "article") "articleId" else "url")
        require(identity.isNotBlank())
        val derived = key(accountKey, user, "watchrss/cloud/record-id/v1")
        return try { Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(derived, "HmacSHA256")) }
            .doFinal("$kind:$identity".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } finally { derived.fill(0) }
    }
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
    fun hash(kind: String, value: JSONObject): String {
        if (kind == "rss-state") {
            val normalized = JSONObject(value.toString()).apply {
                remove("sourceDeviceId")
                put("readingProgress", kotlin.math.floor(optDouble("readingProgress",0.0).coerceIn(0.0,1.0) * 1_000_000.0 + 0.5) / 1_000_000.0)
            }
            return CloudSnapshotCodec.sha256("$kind:${canonical(normalized)}".toByteArray(Charsets.UTF_8))
        }
        // Cross-client serializers omit nullable/default fields differently. Device
        // provenance and source creation time are not independently merged state.
        val normalized = JSONObject()
        val privateSource = kind == "source" && com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.isImportedContentUrl(value.optString("url"))
        val strings = if (kind == "article") listOf("articleId", "url", "title", "siteName", "excerpt", "contentHash", "readingPositionContentHash")
            else if (privateSource) listOf("url", "title", "description") else listOf("url", "sourceId", "title")
        val nullable = if (kind == "article") listOf("imageUrl", "rssSourceUrl", "rssSourceTitle") else if (privateSource) listOf("siteUrl", "imageUrl") else emptyList()
        val numbers = if (kind == "article") listOf("importedAt", "updatedAt", "independentChangedAt", "independentSortOrder", "favoriteChangedAt", "favoriteSortOrder", "watchLaterChangedAt", "watchLaterSortOrder", "deletedAt", "readingPositionBytes", "readingPositionChangedAt")
            else listOf("updatedAt", "sortOrder", "deletedAt")
        val booleans = if (kind == "article") listOf("independentSaved", "favoriteSaved", "watchLaterSaved", "deleted", "isRead")
            else listOf("isPinned", "deleted", "useOriginalContent", "continuePlaybackInBackground")
        strings.forEach { normalized.put(it, value.optString(it, "")) }
        nullable.forEach { normalized.put(it, if (!value.has(it) || value.isNull(it)) JSONObject.NULL else value.getString(it)) }
        numbers.forEach { normalized.put(it, value.optLong(it, 0)) }
        booleans.forEach { normalized.put(it, value.optBoolean(it, false)) }
        if (kind == "article") {
            val progress = value.optDouble("readingProgress", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
            normalized.put("readingProgress", kotlin.math.floor(progress * 1_000_000.0 + 0.5) / 1_000_000.0)
        }
        return CloudSnapshotCodec.sha256("$kind:${canonical(if (kind in setOf("article", "source")) normalized else value)}".toByteArray(Charsets.UTF_8))
    }
    fun validate(kind: String, value: JSONObject) {
        require(kind.isNotBlank())
        if (kind !in setOf("article", "source", "rss-state", "book", "book-reading")) return
        require(listOf("contentText", "contentHtml", "contentTextGzip", "contentHtmlGzip", "body", "chapters").none(value::has)) { "记录不得包含正文" }
        if(kind=="book") {com.lightningstudio.watchrss.phone.data.book.BookSync.validate(value);return}
        if(kind=="book-reading") {com.lightningstudio.watchrss.phone.data.book.BookSync.validateReading(value);return}
        if (kind == "rss-state") {
            require(value.getString("sourceId").isNotBlank())
            require(value.getString("articleIdHash").matches(Regex("[0-9a-f]{64}")))
            require(value.keys().asSequence().all { it in CloudContentPolicy.interactionNumbers + CloudContentPolicy.interactionBooleans + listOf("sourceId", "articleIdHash", "readingProgress", "readingPositionContentHash", "sourceDeviceId") })
        } else require(value.getString(if (kind == "article") "articleId" else "url").isNotBlank())
        listOf("updatedAt", "deletedAt", "readingPositionChangedAt", "favoriteChangedAt", "watchLaterChangedAt", "independentChangedAt").forEach {
            if (value.has(it)) require(value.get(it) is Number && value.getDouble(it).isFinite() && value.getDouble(it) >= 0)
        }
        listOf("deleted", "isRead", "favoriteSaved", "watchLaterSaved", "independentSaved").forEach { if (value.has(it)) require(value.get(it) is Boolean) }
        if (value.has("readingProgress")) require(value.get("readingProgress") is Number && value.getDouble("readingProgress") in 0.0..1.0)
    }
    fun encrypt(accountKey: ByteArray, user: String, recordId: String, version: Int, kind: String, value: JSONObject,
                nonce: ByteArray = ByteArray(12).also(SecureRandom()::nextBytes), idKey: ByteArray = accountKey): JSONObject {
        validate(kind, value)
        require(id(idKey, user, kind, value) == recordId)
        val plaintext = canonical(JSONObject().put("format", "watchrss-rss-record").put("version", 1).put("kind", kind).put("value", value)).toByteArray(Charsets.UTF_8)
        require(plaintext.size + 16 <= 64 * 1024) { "单条云状态过大" }
        val derived = key(accountKey, user, "watchrss/cloud/record/v1")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD("watchrss-cloud-record-v1:$user:$recordId:$version".toByteArray(Charsets.UTF_8))
            JSONObject().put("algorithm", "A256GCM").put("keyVersion", version)
                .put("nonceBase64", Base64.getEncoder().encodeToString(nonce))
                .put("ciphertextBase64", Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)))
        } finally { derived.fill(0); plaintext.fill(0) }
    }
    fun decrypt(accountKey: ByteArray, user: String, recordId: String, envelope: JSONObject, idKeys: List<ByteArray> = listOf(accountKey)): Pair<String, JSONObject> {
        require(envelope.getString("algorithm") == "A256GCM")
        val version = envelope.getInt("keyVersion")
        val nonce = Base64.getDecoder().decode(envelope.getString("nonceBase64"))
        val encoded = envelope.getString("ciphertextBase64")
        require(encoded.length <= 87384)
        val bytes = Base64.getDecoder().decode(encoded)
        require(nonce.size == 12 && bytes.size in 16..65536)
        val derived = key(accountKey, user, "watchrss/cloud/record/v1")
        val plaintext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, nonce))
                updateAAD("watchrss-cloud-record-v1:$user:$recordId:$version".toByteArray(Charsets.UTF_8)); doFinal(bytes)
            }
        } finally { derived.fill(0) }
        try {
            val root = JSONObject(plaintext.toString(Charsets.UTF_8))
            require(root.getString("format") == "watchrss-rss-record" && root.getInt("version") == 1)
            val kind = root.getString("kind"); val value = root.getJSONObject("value")
            validate(kind, value)
            if (kind in setOf("article", "source", "rss-state", "book", "book-reading")) require(idKeys.any { id(it, user, kind, value) == recordId })
            return kind to value
        } catch (_: Exception) {
            // JSON errors may quote decrypted data. Never attach the original
            // message/cause to an exception observed by global telemetry.
            throw IllegalArgumentException("cloud_record_invalid")
        } finally { plaintext.fill(0) }
    }
}
