package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** rc6a-c conflict channel; missing channel is unknown, never an authoritative empty set. */
internal object SyncV2NoteConflicts {
    const val feature = "note-conflicts.v1"
    private val fields = setOf("conflictId", "noteId", "baseMarkdown", "localMarkdown", "remoteMarkdown", "remoteDeviceId", "createdAt", "resolvedAt")
    fun validate(value: JSONObject, noteId: String): JSONObject = SyncV2Wire.schema {
        SyncV2Codec.text(noteId, 1024); if (noteId.isEmpty()) SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.exact(value, setOf("version", "records"))
        if (value.get("version") != 1) SyncV2Codec.fail("invalid_schema")
        val a = value.getJSONArray("records"); var previous: String? = null
        if (a.length() > 1024) SyncV2Codec.fail("invalid_schema")
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i); SyncV2Codec.exact(r, fields)
            for (f in listOf("conflictId", "noteId", "remoteDeviceId")) SyncV2Codec.text(SyncV2Wire.string(r, f), 1024)
            val id = r.getString("conflictId")
            if (id.isEmpty() || r.getString("noteId") != noteId || previous?.let { id <= it } == true) SyncV2Codec.fail("invalid_schema")
            previous = id
            for (f in listOf("baseMarkdown", "localMarkdown", "remoteMarkdown")) SyncV2Codec.text(SyncV2Wire.string(r, f), 245760)
            SyncV2Wire.uint(r, "createdAt"); SyncV2Wire.uint(r, "resolvedAt")
        }
        JSONObject(SyncV2Codec.canonical(value))
    }
    fun phone(rows: JSONArray, noteId: String): JSONObject {
        val list = (0 until rows.length()).map { rows.getJSONObject(it) }.sortedBy { it.getString("conflictId") }
        return validate(JSONObject().put("version", 1).put("records", JSONArray(list)), noteId)
    }
    fun merge(left: JSONObject, right: JSONObject, noteId: String): JSONObject {
        validate(left, noteId); validate(right, noteId)
        val map = sortedMapOf<String, JSONObject>()
        for (set in listOf(left, right)) {
            val a = set.getJSONArray("records")
            for (i in 0 until a.length()) {
                val r = JSONObject(a.getJSONObject(i).toString()); val id = r.getString("conflictId"); val old = map[id]
                if (old != null) {
                    for (f in fields - setOf("createdAt", "resolvedAt")) if (r.get(f) != old.get(f)) SyncV2Codec.fail("conflict_identity_mismatch")
                    r.put("createdAt", minOf(r.getLong("createdAt"), old.getLong("createdAt")))
                    r.put("resolvedAt", maxOf(r.getLong("resolvedAt"), old.getLong("resolvedAt")))
                }
                map[id] = r
            }
        }
        return validate(JSONObject().put("version", 1).put("records", JSONArray(map.values.toList())), noteId)
    }
    fun openIds(value: JSONObject, noteId: String): List<String> = validate(value, noteId).getJSONArray("records").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getLong("resolvedAt") == 0L }.map { it.getString("conflictId") }
    }
    fun latest(value: JSONObject, noteId: String): String = validate(value, noteId).getJSONArray("records").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getLong("resolvedAt") == 0L }
            .sortedWith(compareByDescending<JSONObject> { it.getLong("createdAt") }.thenBy { it.getString("conflictId") })
            .firstOrNull()?.getString("conflictId") ?: SyncV2Codec.fail("notes_stale")
    }
    /** Shared attach/read structural guard, including malformed segmented parents even when unsupported. */
    private fun slot(state: JSONObject): JSONObject = SyncV2Wire.schema {
        SyncV2Codec.exact(state, setOf("version", "entityKey", "requiredFeatures", "payload", "noteSegments"))
        if (state.get("version") != 2) SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.unhex(SyncV2Wire.string(state, "entityKey"), 32)
        val features = SyncV2Wire.features(state.getJSONArray("requiredFeatures"))
        val p = state.getJSONObject("payload"); val segments = SyncV2Wire.strings(state.getJSONArray("noteSegments"), 2048, false)
        if (p.has("segmentedNote") || "note-segments.v1" in features) {
            SyncV2Codec.exact(p, setOf("segmentedNote", "noteRevision", "segmentCount", "plaintextSha256", "metadata"))
            if (p.get("segmentedNote") != true || "note-segments.v1" !in features || segments.size !in 1..2048 || segments.distinct() != segments || SyncV2Wire.uint(p, "segmentCount", 2048) != segments.size.toLong()) SyncV2Codec.fail("invalid_schema")
            if(!Regex("[0-9a-f]{32}").matches(SyncV2Wire.string(p,"noteRevision")) || !Regex("[0-9a-f]{64}").matches(SyncV2Wire.string(p,"plaintextSha256")))SyncV2Codec.fail("invalid_schema")
            segments.forEach { if(!Regex("state:sha256:[0-9a-f]{64}").matches(it))SyncV2Codec.fail("invalid_schema") }
            return p.get("metadata") as? JSONObject ?: SyncV2Codec.fail("invalid_schema")
        }
        if (segments.isNotEmpty()) SyncV2Codec.fail("invalid_schema")
        return p
    }
    fun attach(state: JSONObject, noteId: String, entity: String, conflicts: JSONObject): JSONObject {
        val copy = JSONObject(state.toString()); val p = slot(copy)
        if (copy.getString("entityKey") != entity) SyncV2Codec.fail("invalid_schema")
        if(p.has("noteConflicts") || feature in SyncV2Wire.features(copy.getJSONArray("requiredFeatures")))SyncV2Codec.fail("invalid_schema")
        if(p.has("segmentOf"))SyncV2Codec.fail("invalid_schema")
        p.put("noteConflicts", validate(conflicts, noteId))
        copy.put("requiredFeatures", JSONArray((SyncV2Wire.features(copy.getJSONArray("requiredFeatures")) + feature).distinct().sorted()))
        if (SyncV2Codec.utf8(SyncV2Codec.canonical(copy)).size > 262144) SyncV2Codec.fail("object_too_large")
        return copy
    }
    fun read(state: JSONObject, noteId: String, entity: String, supported: Set<String> = setOf(feature)): JSONObject? {
        val p = slot(state)
        if(p.has("segmentOf"))SyncV2Codec.fail("invalid_schema")
        if (state.getString("entityKey") != entity) SyncV2Codec.fail("invalid_schema")
        val features = SyncV2Wire.features(state.getJSONArray("requiredFeatures"))
        if ((feature in features) != p.has("noteConflicts")) SyncV2Codec.fail("invalid_schema")
        if (SyncV2Codec.utf8(SyncV2Codec.canonical(state)).size > 262144) SyncV2Codec.fail("object_too_large")
        if (features.any { it !in supported }) SyncV2Codec.fail("unsupported_feature")
        return if (feature in features) validate(p.getJSONObject("noteConflicts"), noteId) else null
    }
    /** Mandatory guard for the single planner: scan complete snapshots, including untouched notes. */
    fun plannerSets(base: JSONObject, local: JSONObject, cloud: JSONObject, generations: JSONObject?): Map<String, JSONObject> {
        val known = mutableMapOf<String, Pair<String, JSONObject>>()
        var nonempty = false
        for (snapshot in listOf(base, local, cloud)) for (id in snapshot.keys()) {
            val row = snapshot.getJSONObject(id)
            if (!row.has("noteConflicts")) continue
            val identity = SyncV2Wire.string(row, "identity")
            val set = validate(row.getJSONObject("noteConflicts"), identity)
            if (openIds(set, identity).isNotEmpty()) SyncV2Codec.fail("note_conflict")
            if (set.getJSONArray("records").length() != 0) nonempty = true
            val old = known[id]
            if (old != null && old.first != identity) SyncV2Codec.fail("identity_conflict")
            known[id] = identity to if (old == null) set else merge(old.second, set, identity)
        }
        if (nonempty) {
            if (generations == null) SyncV2Codec.fail("missing_base")
            val values=try {
                SyncV2Codec.exact(generations, setOf("base", "local", "cloud"))
                listOf("base", "local", "cloud").map { SyncV2Wire.string(generations, it).also(SyncV2Codec::d64) }
            }catch(_:Exception){SyncV2Codec.fail("missing_base")}
            if (values.distinct().size != 1) SyncV2Codec.fail("note_conflict")
        }
        for ((id, pair) in known) for (snapshot in listOf(base, local, cloud)) {
            if (snapshot.has(id) && snapshot.getJSONObject(id).optString("identity") != pair.first) SyncV2Codec.fail("identity_conflict")
        }
        return known.mapValues { it.value.second }
    }
}
