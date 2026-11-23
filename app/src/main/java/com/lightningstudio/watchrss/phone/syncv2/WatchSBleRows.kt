package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.cloud.CloudRssSourceIdentity
import org.json.JSONObject

internal object WatchSBleRows {
    const val MAX_OBJECT_BYTES = 192000
    private val objectId = Regex("(meta|state):sha256:[0-9a-f]{64}")

    fun metadataOid(oid: String): Boolean = objectId.matches(oid)

    fun matchesOid(bytes: ByteArray, oid: String): Boolean = metadataOid(oid) &&
        bytes.size in 24..MAX_OBJECT_BYTES && oid.substringAfterLast(':') == SyncV2Codec.hex(SyncV2Codec.sha(bytes))

    val kinds = setOf("source", "article", "rss-state")
    private val reading = listOf("isRead", "readingPositionBytes", "readingProgress", "readingPositionContentHash")
    private val metadata = listOf("url", "title", "siteName", "excerpt", "imageUrl", "contentHash", "importedAt",
        "rssSourceUrl", "rssSourceTitle", "guid", "rssIndexRetired", "bookIndexRetired")
    private val sourceMetadata = listOf("title", "description", "siteUrl", "imageUrl", "createdAt", "sortOrder",
        "isPinned", "useOriginalContent", "continuePlaybackInBackground", "sourceId")
    private data class Register(val name: String, val clock: String, val fields: List<String>)
    private val articleRegisters = listOf(Register("metadata", "updatedAt", metadata),
        Register("reading", "readingPositionChangedAt", reading),
        Register("deletion", "deletedAt", listOf("deleted"))) +
        listOf("independent", "favorite", "watchLater").map {
            Register(it, "${it}ChangedAt", listOf("${it}Saved", "${it}SortOrder"))
        }

    fun decode(identity: SyncV2Identity, row: JSONObject): JSONObject {
        SyncV2Codec.exact(row, setOf("kind", "entityKey", "value"))
        val kind = SyncV2Wire.string(row, "kind")
        if (kind !in kinds) SyncV2Codec.fail("unsupported_feature")
        val value = JSONObject(row.getJSONObject("value").toString())
        if (value.has("readingProgress")) SyncV2Codec.fail("invalid_schema")
        if (value.has("readingProgressMicros")) {
            val progress = SyncV2Wire.uint(value, "readingProgressMicros", 1_000_000)
            value.remove("readingProgressMicros")
            value.put("readingProgress", progress / 1_000_000.0)
        }
        SyncV2Business.validate(kind, value)
        val logical = SyncV2Business.logicalId(kind, value)
        val key = SyncV2Wire.string(row, "entityKey")
        if (identity.entity(kind, logical) != key) SyncV2Codec.fail("identity_conflict")
        when (kind) {
            "rss-state" -> {
                SyncV2Codec.unhex(value.getString("sourceId"), 32)
                SyncV2Codec.unhex(value.getString("articleIdHash"), 32)
            }
            "source" -> if (value.has("sourceId") && value.getString("sourceId") != rssSourceKey(value.getString("url"))) {
                SyncV2Codec.fail("identity_conflict")
            } else rssSourceKey(value.getString("url"))
            "article" -> {
                if (!portableArticle(value)) SyncV2Codec.fail("unsupported_feature")
                value.optJSONObject("registerSources")?.let {
                    if (value.optString("sourceDeviceId") != it.getString("metadata")) SyncV2Codec.fail("invalid_schema")
                }
            }
        }
        return record(kind, logical, key, value)
    }

    fun record(kind: String, logical: String, key: String, value: JSONObject): JSONObject {
        val split = SyncV2Business.split(kind, logical, key, value, null)
        return JSONObject().put("meta", split.meta).put("state", split.state).put("value", value)
    }

    fun encode(record: JSONObject): JSONObject {
        val meta = record.getJSONObject("meta")
        val payload = JSONObject(meta.getJSONObject("payload").toString())
        val state = record.getJSONObject("state").getJSONObject("payload")
        for (key in state.keys()) payload.put(key, state.get(key))
        return JSONObject().put("kind", meta.getString("kind")).put("entityKey", meta.getString("entityKey")).put("value", payload)
    }

    fun portableArticle(value: JSONObject): Boolean {
        val url = value.optString("url")
        val feed = value.opt("rssSourceUrl") as? String
        return portableUrl(url) && (feed == null || portableUrl(feed))
    }

    private fun portableUrl(url: String) = (url.startsWith("https://", true) || url.startsWith("http://", true)) &&
        runCatching { rssSourceKey(url) }.isSuccess

    fun rssSourceKey(url: String): String = runCatching { CloudRssSourceIdentity.sourceKey(url) }
        .getOrElse { SyncV2Codec.fail("unsupported_feature") }

    fun merge(kind: String, local: JSONObject?, remote: JSONObject): JSONObject {
        if (local == null) return remote
        val legacy = kind != "article" || !(local.has("registerSources") || remote.has("registerSources"))
        val sources = JSONObject()
        val result = JSONObject(local.toString())
        val registers = when (kind) {
            "source" -> listOf(Register("metadata", "updatedAt", sourceMetadata),
                Register("deletion", "deletedAt", listOf("deleted")))
            "rss-state" -> articleRegisters.filter { it.name in setOf("reading", "favorite", "watchLater", "deletion") }
            else -> articleRegisters
        }
        for (register in registers) {
            val clock = register.clock
            val fields = register.fields
            val source = register.name
            val origin = when {
                kind == "rss-state" -> source == "reading"
                else -> source == "metadata"
            }
            val remoteHasRegister = remote.has(clock) || fields.any(remote::has)
            val winner = if (remoteHasRegister && compare(register, remote, local, origin || !legacy) > 0) remote else local
            if (winner === remote) {
                for (field in fields + clock) if (remote.has(field)) result.put(field, remote.get(field))
                if (origin && remote.has("sourceDeviceId")) result.put("sourceDeviceId", remote.get("sourceDeviceId"))
            }
            if (!legacy) sources.put(source, author(winner, source))
        }
        if (!legacy) result.put("registerSources", sources).put("sourceDeviceId", sources.getString("metadata"))
        return result
    }

    private fun author(value: JSONObject, register: String) =
        value.optJSONObject("registerSources")?.getString(register) ?: value.optString("sourceDeviceId")

    private fun compare(register: Register, a: JSONObject, b: JSONObject, compareAuthors: Boolean): Int {
        val clock = a.optLong(register.clock).compareTo(b.optLong(register.clock))
        if (clock != 0) return clock
        if (compareAuthors) {
            val source = author(a, register.name).compareTo(author(b, register.name))
            if (source != 0) return source
        }
        for (field in register.fields) {
            val av = a.opt(field)
            val bv = b.opt(field)
            val order = when {
                av == null || av === JSONObject.NULL -> if (bv == null || bv === JSONObject.NULL) 0 else -1
                bv == null || bv === JSONObject.NULL -> 1
                av is Boolean && bv is Boolean -> if (field == "deleted" || field == "isPinned" ||
                    field == "useOriginalContent" || field == "continuePlaybackInBackground") av.compareTo(bv)
                    else bv.compareTo(av)
                av is Number && bv is Number -> av.toDouble().compareTo(bv.toDouble())
                av is String && bv is String -> av.compareTo(bv)
                else -> SyncV2Codec.fail("invalid_schema")
            }
            if (order != 0) return order
        }
        return 0
    }

    fun carryArticleState(physical: JSONObject, candidate: JSONObject): JSONObject {
        val sources = candidate.optJSONObject("registerSources")
        for (field in listOf("guid", "rssIndexRetired", "bookIndexRetired")) {
            if (!physical.has(field) && candidate.has(field)) physical.put(field, candidate.get(field))
        }
        if (sources == null) return physical
        val current = JSONObject()
        for (register in articleRegisters) {
            val unchanged = (register.fields + register.clock).all { field ->
                !physical.has(field) || SyncV2LocalJson.canonical(physical.opt(field)) ==
                    SyncV2LocalJson.canonical(candidate.opt(field))
            }
            current.put(register.name, if (unchanged) sources.getString(register.name) else physical.optString("sourceDeviceId"))
        }
        return physical.put("registerSources", current).put("sourceDeviceId", current.getString("metadata"))
    }
}
