package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/**
 * Spec 25 (v2-rc7) long-note segmentation codec. Pure functions only: no network, no storage,
 * no business apply. The deterministic choice between the ordinary root S0 and the segmented
 * parent is made here for every end so identical business records always choose the same form.
 */
internal object SyncV2NoteSegments {
    const val LIMIT = 262144
    const val TEXT = 245760
    const val MAX = 2048
    const val feature = "note-segments.v1"
    /** Sealed-state upper bound: header + tag + framing allowance from spec 25.6. */
    const val SEALED_OVERHEAD = 4120
    /** One bounded remote-note budget for this end; the product never loads more than one body. */
    const val maxNoteBytes = 2L * 1024 * 1024
    val names = listOf("baseMarkdown", "markdown", "plainText")
    private val metadataKeys = setOf("contentHash", "baseContentHash", "pinned", "updatedAt", "modifiedBy", "deleted", "deletedAt", "noteConflicts")
    /** Worst-case authenticated scope used only to size a refs table before the real one exists. */
    internal val budgetScope = JSONObject().put("repoId", "0".repeat(32)).put("libraryEpoch", "18446744073709551615").put("contentGeneration", "18446744073709551615")

    /** Replayable bounded text: the body may be longer than any single host copy allowed. */
    internal class NoteTextSource(val codeUnits: Long, private val provider: () -> Sequence<String>) {
        fun blocks(): Sequence<String> = provider()
        companion object {
            fun of(text: String): NoteTextSource = NoteTextSource(text.length.toLong()) { blocksOf(text) }
            fun replayed(codeUnits: Long, provider: () -> Sequence<String>): NoteTextSource = NoteTextSource(codeUnits, provider)
        }
    }

    private fun add(a: Long, b: Long): Long = (a + b).also { if (it < 0) SyncV2Codec.fail("object_too_large") }
    internal fun blocksOf(text: String): Sequence<String> = sequence {
        var i = 0
        while (i < text.length) {
            var end = minOf(i + 8192, text.length)
            if (end < text.length && text[end - 1].code in 0xd800..0xdbff) end--
            if (end == i) end = i + 1 // lone surrogate tail: keep one unit to fail as invalid_encoding
            yield(text.substring(i, end)); i = end
        }
    }
    private fun quoteInner(text: String): String {
        val quoted = SyncV2Codec.quotePublic(text)
        return quoted.substring(1, quoted.length - 1)
    }
    /** Bytes of the J string encoding minus its two outer quotes, per spec 1 escaping.
     * A caller that only needs a threshold check passes maxBytes: the walk stops as soon as the
     * running total exceeds it, keeping over-budget classification proportional to the budget. */
    fun escapedBytes(source: NoteTextSource, maxBytes: Long = Long.MAX_VALUE): Long {
        var total = 0L
        for (b in source.blocks()) {
            SyncV2Codec.utf8(b) // a malformed block fails as invalid_encoding before quoting
            total = add(total, SyncV2Codec.utf8(quoteInner(b)).size.toLong())
            if (total > maxBytes) return total
        }
        return total
    }

    fun root(entityKey: String, payload: JSONObject, requiredFeatures: List<String> = emptyList(), noteSegments: JSONArray = JSONArray()): JSONObject =
        JSONObject().put("version", 2).put("entityKey", entityKey).put("requiredFeatures", JSONArray(requiredFeatures)).put("payload", payload).put("noteSegments", noteSegments)
    fun segment(entityKey: String, revision: String, index: Int, text: String): JSONObject =
        root(entityKey, JSONObject().put("segmentOf", revision).put("index", index).put("text", text))

    /** Keys of the text object T: only the three fields that actually exist, values are strings. */
    fun fieldsOf(payload: JSONObject): LinkedHashMap<String, NoteTextSource> {
        val fields = LinkedHashMap<String, NoteTextSource>()
        for (key in names.sorted()) if (payload.has(key)) fields[key] = NoteTextSource.of(SyncV2Wire.string(payload, key))
        return fields
    }
    /** Streaming fragments of U(J(T)); the same boundaries as the canonical encoder itself. */
    fun tokens(fields: Map<String, NoteTextSource>): Sequence<String> = sequence {
        yield("{")
        var first = true
        for (key in fields.keys.sorted()) {
            if (key !in names) SyncV2Codec.fail("invalid_schema")
            if (!first) yield(","); first = false
            yield(SyncV2Codec.quotePublic(key) + ":\"")
            for (b in fields.getValue(key).blocks()) yield(quoteInner(b))
            yield("\"")
        }
        yield("}")
    }
    /** Canonical byte count without concatenation; fails at limit so oversized M never materializes. */
    fun canonicalSize(value: Any?, limit: Int = LIMIT): Long {
        var bytes = 0L
        fun charge(n: Long) { bytes = add(bytes, n); if (bytes > limit) SyncV2Codec.fail("object_too_large") }
        fun visit(v: Any?) {
            when (v) {
                is String -> { charge(2); charge(escapedBytes(NoteTextSource.of(v))) }
                null, JSONObject.NULL -> charge(4)
                is Boolean -> charge(if (v) 4 else 5)
                is Byte, is Short, is Int, is Long -> charge((v as Number).toLong().also {
                    if (it !in -9_007_199_254_740_991L..9_007_199_254_740_991L) SyncV2Codec.fail("invalid_encoding")
                }.toString().length.toLong())
                is JSONArray -> {
                    charge(2)
                    for (i in 0 until v.length()) { if (i > 0) charge(1); visit(v.get(i)) }
                }
                is JSONObject -> {
                    charge(2)
                    val keys = v.keys().asSequence().toList().sorted()
                    for ((i, k) in keys.withIndex()) {
                        if (i > 0) charge(1)
                        charge(2); charge(escapedBytes(NoteTextSource.of(k))); charge(1); visit(v.get(k))
                    }
                }
                else -> SyncV2Codec.fail("invalid_encoding")
            }
        }
        visit(value); return bytes
    }

    data class Measure(val fields: LinkedHashMap<String, NoteTextSource>, val metadata: JSONObject, val ordinaryFeatures: List<String>,
        val shortPlainBytes: Long, val streamBytes: Long)

    /** Measure S0 without ever building its giant JSON; the three texts stay block-bounded. */
    fun measure(entityKey: String, payload: JSONObject, requiredFeatures: JSONArray, maxStreamBytes: Long = Long.MAX_VALUE): Measure {
        SyncV2Codec.unhex(entityKey, 32)
        val metadata = JSONObject()
        for (key in payload.keys()) if (key !in names) {
            if (key !in metadataKeys) SyncV2Codec.fail("invalid_schema")
            metadata.put(key, payload.get(key))
        }
        return measure(entityKey, metadata, fieldsOf(payload), requiredFeatures, maxStreamBytes)
    }
    private fun measure(entityKey: String, metadata: JSONObject, fields: LinkedHashMap<String, NoteTextSource>, requiredFeatures: JSONArray, maxStreamBytes: Long): Measure {
        SyncV2Codec.unhex(entityKey, 32)
        for (key in metadata.keys()) if (key !in metadataKeys) SyncV2Codec.fail("invalid_schema")
        var extra = 0L
        val empty = JSONObject()
        for ((key, value) in fields) {
            empty.put(key, "")
            extra = add(extra, escapedBytes(value,if (maxStreamBytes==Long.MAX_VALUE) Long.MAX_VALUE else maxStreamBytes-extra.coerceAtLeast(0)))
            if (extra > maxStreamBytes) SyncV2Codec.fail("storage_pressure")
        }
        val ordinaryFeatures = SyncV2Wire.features(requiredFeatures).filter { it != feature }
        val shortRoot = JSONObject(metadata.toString())
        empty.keys().forEach { shortRoot.put(it, "") }
        val shortPlainBytes = add(canonicalSize(root(entityKey, shortRoot, ordinaryFeatures)), extra)
        val streamBytes = add(SyncV2Codec.utf8(SyncV2Codec.canonical(empty)).size.toLong(), extra)
        if(streamBytes>maxStreamBytes)SyncV2Codec.fail("storage_pressure")
        return Measure(fields, metadata, ordinaryFeatures, shortPlainBytes, streamBytes)
    }

    /**
     * Spec 25.4 dual-limit greedy split. Each yielded state is one complete segment at most
     * LIMIT bytes; the iterator retains a single segment, never the whole body.
     */
    fun chunks(fields: Map<String, NoteTextSource>, entityKey: String, revision: String): Sequence<JSONObject> = sequence {
        SyncV2Codec.unhex(revision, 16)
        var parts = mutableListOf<String>(); var raw = 0L; var quoted = 0L; var index = 0
        fun flush(): JSONObject {
            if (index >= MAX) SyncV2Codec.fail("object_too_large")
            val value = segment(entityKey, revision, index++, parts.joinToString(""))
            parts = mutableListOf(); raw = 0; quoted = 0; return value
        }
        fun size(text: String) = SyncV2Codec.utf8(text).size.toLong() to escapedBytes(NoteTextSource.of(text))
        for (start in tokens(fields)) {
            var token = start
            while (token.isNotEmpty()) {
                val overhead = SyncV2Codec.utf8(SyncV2Codec.canonical(segment(entityKey, revision, index, ""))).size
                val (nRaw, nQuoted) = size(token)
                if (raw + nRaw <= TEXT && overhead + quoted + nQuoted <= LIMIT) {
                    parts.add(token); raw += nRaw; quoted += nQuoted; break
                }
                var low = 0; var high = token.length
                fun scalarEnd(n: Int): Int =
                    if (n != 0 && n < token.length && token[n - 1].code in 0xd800..0xdbff) n - 1 else n
                while (low < high) {
                    val mid = (low + high + 1) / 2
                    val (pRaw, pQuoted) = size(token.substring(0, scalarEnd(mid)))
                    if (raw + pRaw <= TEXT && overhead + quoted + pQuoted <= LIMIT) low = mid else high = mid - 1
                }
                val cut = scalarEnd(low)
                if (cut > 0) {
                    val (pRaw, pQuoted) = size(token.substring(0, cut))
                    parts.add(token.substring(0, cut)); raw += pRaw; quoted += pQuoted; token = token.substring(cut)
                }
                if (parts.isEmpty()) SyncV2Codec.fail("object_too_large")
                yield(flush())
            }
        }
        if (parts.isNotEmpty()) yield(flush())
    }

    fun refsBytes(scope: JSONObject, oid: String, ids: List<String>): Long =
        SyncV2Codec.utf8(SyncV2Codec.canonical(JSONObject().put("body", JSONObject().put("scope", scope).put("stateOid", oid)
            .put("segmentOids", JSONArray(ids))).put("signature", "A".repeat(86)))).size.toLong()

    data class Encoded(val state: JSONObject, val form: String, val shortPlainBytes: Long, val streamBytes: Long,
        val segmentCount: Int, val noteRevision: String?, val plaintextSha256: String?, val closureStoredBytesUpperBound: Long)

    /**
     * Encode an ordinary note state either as the short root or as a segmented parent.
     * [seal] must seal the given segment state and return its authenticated OID; it is called
     * exactly once per segment and may reuse an authenticated original (spec 25.7).
     */
    fun encode(entityKey: String, payload: JSONObject, requiredFeatures: JSONArray, noteRevision: String?,
        scope: JSONObject = budgetScope, maxStreamBytes: Long = Long.MAX_VALUE, seal: ((JSONObject, Int) -> String)? = null): Encoded =
        encode(entityKey, JSONObject(payload.toString()).apply { for (k in names) remove(k) }, LinkedHashMap<String, NoteTextSource>().apply { for (k in names.sorted()) if (payload.has(k)) put(k, NoteTextSource.of(SyncV2Wire.string(payload, k))) }, requiredFeatures, noteRevision, scope, maxStreamBytes, seal)

    /** Same deterministic selection with replayable text sources; the payload path never materializes large bodies. */
    fun encode(entityKey: String, metadata: JSONObject, fields: LinkedHashMap<String, NoteTextSource>, requiredFeatures: JSONArray, noteRevision: String?,
        scope: JSONObject = budgetScope, maxStreamBytes: Long = Long.MAX_VALUE, seal: ((JSONObject, Int) -> String)? = null): Encoded {
        val m = measure(entityKey, metadata, fields, requiredFeatures, maxStreamBytes)
        if (m.shortPlainBytes <= LIMIT) {
            val payloadCopy = JSONObject(m.metadata.toString())
            for ((k, v) in m.fields) {
                val text = StringBuilder(); for (b in v.blocks()) text.append(b); payloadCopy.put(k, text.toString())
            }
            return Encoded(root(entityKey, payloadCopy, m.ordinaryFeatures), "short", m.shortPlainBytes, m.streamBytes, 0, null, null, 0)
        }
        val revision = noteRevision ?: SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.unhex(revision, 16)
        val ids = mutableListOf<String>(); val digest = java.security.MessageDigest.getInstance("SHA-256")
        var closure = 0L; var count = 0
        for (value in chunks(m.fields, entityKey, revision)) {
            val bytes = SyncV2Codec.utf8(value.getJSONObject("payload").getString("text"))
            digest.update(bytes)
            val oid = seal?.invoke(value, count) ?: SyncV2Codec.oid("state", value)
            SyncV2Wire.oid(oid, "state"); ids.add(oid)
            closure = add(closure, (SyncV2Codec.utf8(SyncV2Codec.canonical(value)).size + SEALED_OVERHEAD).toLong() + refsBytes(scope, oid, emptyList()))
            count++
        }
        val next = root(entityKey, JSONObject().put("segmentedNote", true).put("noteRevision", revision)
            .put("segmentCount", count).put("plaintextSha256", SyncV2Codec.hex(digest.digest())).put("metadata", m.metadata),
            (m.ordinaryFeatures + feature).distinct().sorted(), JSONArray(ids))
        val rootPlainBytes = SyncV2Codec.utf8(SyncV2Codec.canonical(next)).size.toLong()
        val tableBytes = refsBytes(scope, "state:sha256:" + "0".repeat(64), ids)
        if (rootPlainBytes > LIMIT.toLong() || tableBytes > LIMIT.toLong()) SyncV2Codec.fail("object_too_large")
        return Encoded(next, "segmented", m.shortPlainBytes, m.streamBytes, count, revision,
            SyncV2Wire.string(next.getJSONObject("payload"), "plaintextSha256"),
            add(closure, rootPlainBytes + SEALED_OVERHEAD + tableBytes))
    }

    /** Structural guard for a candidate parent (spec 23.3/25.5 steps 1-4), before any content read. */
    fun structure(state: JSONObject, supported: Set<String> = setOf(feature, SyncV2NoteConflicts.feature)): Boolean {
        SyncV2Codec.canonical(state) // raw encoding failures first: invalid_encoding
        SyncV2Codec.exact(state, setOf("version", "entityKey", "requiredFeatures", "payload", "noteSegments"))
        if (state.get("version") != 2) SyncV2Codec.fail("invalid_schema")
        val features = SyncV2Wire.features(state.getJSONArray("requiredFeatures"))
        SyncV2Codec.unhex(SyncV2Wire.string(state, "entityKey"), 32)
        val segments = SyncV2Wire.strings(state.getJSONArray("noteSegments"), MAX, false)
        val p = state.optJSONObject("payload") ?: SyncV2Codec.fail("invalid_schema")
        val segmented = p.opt("segmentedNote") == true
        if (p.has("segmentedNote") && !segmented) SyncV2Codec.fail("invalid_schema")
        if (segmented != (feature in features)) SyncV2Codec.fail("invalid_schema")
        if (segmented) {
            SyncV2Codec.exact(p, setOf("segmentedNote", "noteRevision", "segmentCount", "plaintextSha256", "metadata"))
            if (!Regex("[0-9a-f]{32}").matches(SyncV2Wire.string(p, "noteRevision")) || !Regex("[0-9a-f]{64}").matches(SyncV2Wire.string(p, "plaintextSha256"))) SyncV2Codec.fail("invalid_schema")
            val metadata = p.optJSONObject("metadata") ?: SyncV2Codec.fail("invalid_schema")
            if (metadata.keys().asSequence().any { it !in metadataKeys }) SyncV2Codec.fail("invalid_schema")
            val count = SyncV2Wire.uint(p, "segmentCount", MAX.toLong())
            if (count < 1 || count != segments.size.toLong() || segments.distinct() != segments) SyncV2Codec.fail("invalid_schema")
            segments.forEach { if (!Regex("state:sha256:[0-9a-f]{64}").matches(it)) SyncV2Codec.fail("invalid_schema") }
        } else if (segments.isNotEmpty()) SyncV2Codec.fail("invalid_schema")
        if (features.any { it !in supported }) SyncV2Codec.fail("unsupported_feature")
        if (SyncV2Codec.utf8(SyncV2Codec.canonical(state)).size > LIMIT) SyncV2Codec.fail("object_too_large")
        return segmented
    }

    /**
     * Spec 25.5 steps 9-11 after sealed children were authenticated. Replays the greedy
     * boundaries, requires the exact reverse transform of J(T) and returns the ordinary
     * candidate (payload = M ∪ T, feature removed, empty noteSegments).
     */
    fun reassemble(state: JSONObject, values: List<JSONObject>, maxBytes: Long = maxNoteBytes): JSONObject = SyncV2Wire.schema {
        val p = state.getJSONObject("payload")
        val ids = SyncV2Wire.strings(state.getJSONArray("noteSegments"), MAX, false)
        if (values.size != ids.size) SyncV2Codec.fail("closure_incomplete")
        val entityKey = state.getString("entityKey"); val revision = SyncV2Wire.string(p, "noteRevision")
        var total = 0L
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val texts = mutableListOf<String>()
        for ((i, v) in values.withIndex()) {
            SyncV2Codec.exact(v, setOf("version", "entityKey", "requiredFeatures", "payload", "noteSegments"))
            if (v.get("version") != 2 || v.get("entityKey") != entityKey) SyncV2Codec.fail("invalid_schema")
            if (SyncV2Wire.features(v.getJSONArray("requiredFeatures")).isNotEmpty() || v.getJSONArray("noteSegments").length() != 0) SyncV2Codec.fail("invalid_schema")
            val q = v.optJSONObject("payload") ?: SyncV2Codec.fail("invalid_schema")
            SyncV2Codec.exact(q, setOf("segmentOf", "index", "text"))
            if (SyncV2Wire.string(q, "segmentOf") != revision || SyncV2Wire.uint(q, "index", MAX.toLong()) != i.toLong()) SyncV2Codec.fail("invalid_schema")
            val text = SyncV2Wire.string(q, "text")
            val bytes = SyncV2Codec.utf8(text)
            if (text.isEmpty()) SyncV2Codec.fail("invalid_schema")
            if (bytes.size > TEXT) SyncV2Codec.fail("invalid_schema")
            if (SyncV2Codec.utf8(SyncV2Codec.canonical(v)).size > LIMIT) SyncV2Codec.fail("object_too_large")
            total = add(total, bytes.size.toLong())
            if (total > maxBytes) SyncV2Codec.fail("storage_pressure")
            digest.update(bytes); texts.add(text)
        }
        if (SyncV2Codec.hex(digest.digest()) != SyncV2Wire.string(p, "plaintextSha256")) SyncV2Codec.fail("hash_mismatch")
        val joined = texts.joinToString("")
        val decoded = try { SyncV2Wire.parse(SyncV2Codec.utf8(joined), minOf(Int.MAX_VALUE.toLong(), maxBytes + 4096).toInt(), true) }
        catch (e: IllegalStateException) { if (e.message == "object_too_large") SyncV2Codec.fail("invalid_encoding") else throw e }
        if (decoded.keys().asSequence().any { it !in names || decoded.get(it) !is String }) SyncV2Codec.fail("invalid_schema")
        val metadata = p.getJSONObject("metadata")
        if (decoded.keys().asSequence().any { metadata.has(it) }) SyncV2Codec.fail("invalid_schema")
        if (SyncV2Codec.utf8(SyncV2Codec.canonical(decoded)).size.toLong() != total) SyncV2Codec.fail("invalid_encoding")
        var index = 0
        val fields = LinkedHashMap<String, NoteTextSource>()
        for (key in names) if (decoded.has(key)) fields[key] = NoteTextSource.of(decoded.getString(key))
        for (v in chunks(fields, entityKey, revision)) {
            if (index >= values.size || values[index].getJSONObject("payload").getString("text") != v.getJSONObject("payload").getString("text")) SyncV2Codec.fail("invalid_schema")
            index++
        }
        if (index != values.size) SyncV2Codec.fail("invalid_schema")
        val ordinary = JSONObject(metadata.toString())
        for (key in decoded.keys()) ordinary.put(key, decoded.get(key))
        val features = SyncV2Wire.features(state.getJSONArray("requiredFeatures")).filter { it != feature }
        if (measure(entityKey, ordinary, JSONArray(features)).shortPlainBytes <= LIMIT) SyncV2Codec.fail("invalid_schema")
        root(entityKey, ordinary, features)
    }
}
