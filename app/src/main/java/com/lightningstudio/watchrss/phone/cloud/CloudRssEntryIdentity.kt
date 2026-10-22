package com.lightningstudio.watchrss.phone.cloud

/** rss.rs store_entry dedup_key; a display URL or pool UUID is never a replacement GUID. */
internal object CloudRssEntryIdentity {
    fun id(guid: String?, link: String?, contentHash: String?, title: String, dedupKey: String? = null): String {
        if (dedupKey != null) {
            require(dedupKey.matches(Regex("[0-9a-f]{64}"))) { "invalid_rss_dedup_key" }
            return dedupKey
        }
        val identity=guid?.trim()?.takeIf { it.isNotEmpty() }
            ?: CloudRssSourceIdentity.canonicalEntryLink(link)
            ?: contentHash
            ?: title.trim()
        return CloudSnapshotCodec.sha256(identity.toByteArray(Charsets.UTF_8))
    }
}
