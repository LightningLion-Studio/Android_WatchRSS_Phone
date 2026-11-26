package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject

/** Two independent records in a new library have no common edit base. */
internal object SyncV2ResetMerge {
    fun merge(kind: String, local: JSONObject, remote: JSONObject, localBody: Boolean): JSONObject {
        val live = listOf(local, remote).filterNot { it.optBoolean("deleted") }
        val candidates = live.ifEmpty { listOf(local, remote) }
        if (candidates.size == 1) return JSONObject(candidates.single().toString())

        val metadata = when {
            localBody && kind in setOf("article", "book", "asset") -> local
            kind == "article" && local.optString("contentHash").isNotBlank() != remote.optString("contentHash").isNotBlank() ->
                if (local.optString("contentHash").isNotBlank()) local else remote
            else -> winner(candidates, "updatedAt", if (kind == "folder") "modifiedBy" else "sourceDeviceId")
        }
        val other = if (metadata === local) remote else local
        if (kind == "book") {
            val sameEdition = local.getString("fingerprint") == remote.getString("fingerprint") &&
                local.getInt("chapterCount") == remote.getInt("chapterCount") &&
                local.optString("instanceId") == remote.optString("instanceId")
            val merged = if (sameEdition) SyncV2Business.mergeBook(local, remote) else JSONObject(metadata.toString())
            val sourceUrl = winner(candidates.filter { it.optString("sourceUrl").isNotBlank() }.ifEmpty { candidates },
                "updatedAt", "sourceDeviceId").opt("sourceUrl")
            if (merged.optString("sourceUrl").isBlank() && sourceUrl != null) merged.put("sourceUrl", sourceUrl)
            return merged
        }
        val merged = JSONObject(metadata.toString())
        for (field in other.keys()) {
            val current = merged.opt(field)
            val incoming = other.opt(field)
            if (field != "contentHash" && (merged.isNull(field) || current == "") &&
                incoming != null && incoming != JSONObject.NULL && incoming != "") merged.put(field, incoming)
        }
        for (field in listOf("createdAt", "importedAt")) {
            candidates.map { it.optLong(field) }.filter { it > 0 }.minOrNull()?.let { merged.put(field, it) }
        }

        if (kind in setOf("article", "book-reading", "rss-state")) {
            val contentHash = merged.optString("contentHash")
            val positions = candidates.filter { it.optLong("readingPositionBytes") > 0 }
            val compatible = positions.filter { contentHash.isNotEmpty() && it.optString("readingPositionContentHash") == contentHash }
            val reading = winner(compatible.ifEmpty { positions.ifEmpty { candidates } }, "readingPositionChangedAt", "reading")
            copyFields(merged, reading, "readingPositionBytes", "readingPositionContentHash", "readingPositionChangedAt")
            if (candidates.any { it.has("isRead") }) merged.put("isRead", candidates.any { it.optBoolean("isRead") })
            if (candidates.any { it.has("readingProgress") }) {
                merged.put("readingProgress", candidates.maxOf { it.optDouble("readingProgress", 0.0) })
            }

            val provenance = JSONObject()
            provenance.put("metadata", author(metadata, "metadata"))
            provenance.put("reading", author(reading, "reading"))
            provenance.put("deletion", author(metadata, "deletion"))
            if (kind != "article" && reading.has("sourceDeviceId")) merged.put("sourceDeviceId", author(reading, "reading"))
            val saves = if (kind == "article") listOf("independent", "favorite", "watchLater") else listOf("favorite", "watchLater")
            for (save in saves) {
                val clock = save + "ChangedAt"
                val saved = save + "Saved"
                val saving = candidates.filter { it.has(saved) }
                if (saving.isEmpty()) {
                    provenance.put(save, author(metadata, save))
                    continue
                }
                // An uninitialized default does not erase a saved item.
                val winner = winner(saving.filter { it.optLong(clock) > 0 || it.optBoolean(saved) }.ifEmpty { saving }, clock, save)
                copyFields(merged, winner, saved, clock, save + "SortOrder", save + "SourceDeviceId")
                val author = author(winner, save)
                if (kind == "book-reading") merged.put(save + "SourceDeviceId", author)
                provenance.put(save, author)
            }
            if (kind == "article" && candidates.any { it.has("registerSources") }) {
                merged.put("registerSources", provenance)
                merged.put("sourceDeviceId", provenance.getString("metadata"))
                if (!merged.has("contentHash")) merged.put("contentHash", "")
                if (!merged.has("updatedAt")) merged.put("updatedAt", 0)
            }
        }
        return merged
    }

    private fun winner(rows: List<JSONObject>, clock: String, register: String) =
        rows.maxWith(compareBy({ it.optLong(clock) }, { author(it, register) }, SyncV2LocalJson::canonical))

    private fun author(row: JSONObject, register: String): String =
        when (register) {
            "sourceDeviceId", "modifiedBy" -> row.optString(register)
            else -> row.optJSONObject("registerSources")?.optString(register)
                ?: row.optString(register + "SourceDeviceId", row.optString("sourceDeviceId"))
        }

    private fun copyFields(target: JSONObject, row: JSONObject, vararg fields: String) {
        for (field in fields) target.put(field, row.opt(field))
    }
}
