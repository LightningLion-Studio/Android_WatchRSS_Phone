package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** Only fully materialized authenticated snapshots can prove a disjoint transfer.
 * Article, source and alias relationships require a separate complete witness. */
internal object SyncV2BluetoothImpact {
    fun stableEntities(
        identity: SyncV2Identity, before: List<JSONObject>, after: List<JSONObject>
    ): Set<String>? {
        fun graph(rows: List<JSONObject>): JSONObject? {
            val values = JSONObject()
            val dependencies = linkedMapOf<String, MutableSet<String>>()
            for (row in rows) {
                if (row.has(SyncV2NoteWiring.ISOLATED_KEY)) return null
                val meta = row.getJSONObject("meta")
                val kind = meta.getString("kind")
                val logical = meta.getString("logicalId")
                val entity = meta.getString("entityKey")
                if (identity.entity(kind, logical) != entity ||
                    kind !in setOf("book", "book-reading", "note", "asset", "folder")) return null
                val value = SyncV2Business.join(kind, meta, row.getJSONObject("state"))
                val related = when (kind) {
                    "book-reading" -> setOf(identity.entity("book", value.getString("bookId")))
                    "note" -> buildSet {
                        value.optString("folderId").takeIf { it.isNotEmpty() && it != "null" }?.let {
                            add(identity.entity("folder", it))
                        }
                        for (key in SyncV2BodyRepository.noteReferences(value)) add(identity.entity("asset", key))
                    }
                    else -> emptySet()
                }
                dependencies.getOrPut(entity) { linkedSetOf() }.addAll(related)
                for (id in related) dependencies.getOrPut(id) { linkedSetOf() }.add(entity)
                values.put(entity, JSONObject().put("row", row.getJSONObject("row")))
            }
            for ((entity, ids) in dependencies) {
                values.optJSONObject(entity)?.put("dependencies", JSONArray(ids.sorted()))
            }
            return values
        }
        val original = graph(before) ?: return null
        val current = graph(after) ?: return null
        val affected = SyncV2Business.affected(original, current)?.toSet() ?: return null
        return original.keys().asSequence().filter { current.has(it) && it !in affected }.toSet()
    }
}
