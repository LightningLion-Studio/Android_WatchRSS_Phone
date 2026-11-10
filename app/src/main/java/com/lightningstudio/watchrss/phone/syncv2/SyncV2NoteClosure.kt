package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/**
 * Spec 25.5 authenticated candidate closure for segmented notes. One shared core serves the
 * network pull and the fixed vectors so both entries report the same first error.
 * No business writes and no applied ACK happen here; any failure aborts the caller's round.
 */
internal object SyncV2NoteClosure {
    class Mapping(val info: JSONObject, private val read:()->ByteArray) {
        constructor(info:JSONObject,raw:ByteArray):this(info,{raw})
        val raw get()=read()
    }
    class Segment(val oid: String, val sealed: ByteArray, val plain: ByteArray, val value: JSONObject, val mapping: Mapping)
    class Closed(val ordinary: JSONObject, val streamBytes: Long, val segments: List<Segment>)
    class Context(val scope: JSONObject, val opaque: (String, ByteArray) -> String,
        val contentKey: (Long) -> ByteArray, val publicKey: (String, String) -> String?)

    private class Opened(val header: JSONObject, val value: JSONObject, val plain: ByteArray)

    private fun stateHeader(bytes: ByteArray): JSONObject? {
        if (bytes.size >= 8) {
            val n = java.nio.ByteBuffer.wrap(bytes, 4, 4).int
            if (n in 1..4096 && 8L + n <= bytes.size) return SyncV2Wire.parse(bytes.copyOfRange(8, 8 + n), 4096, true)
        }
        return null
    }
    private fun stateBounds(size: Int, header: JSONObject?) {
        if (header != null && header.has("plainBytes") && SyncV2Wire.uint(header, "plainBytes", Long.MAX_VALUE) > SyncV2NoteSegments.LIMIT ||
            size > SyncV2NoteSegments.LIMIT + SyncV2NoteSegments.SEALED_OVERHEAD) SyncV2Codec.fail("object_too_large")
    }
    private fun refsEncoding(raw: ByteArray) { SyncV2Wire.parse(raw, Int.MAX_VALUE, true) }
    private fun refsSize(raw: ByteArray) { refsEncoding(raw); if (raw.size > SyncV2NoteSegments.LIMIT) SyncV2Codec.fail("object_too_large") }

    /** All obtained raw encodings before any size failure: traversal order never changes the first error. */
    private fun sizes(bytes: ByteArray, objects: Map<String, ByteArray>, tables: Map<String, Mapping>) {
        for (t in tables.values) refsEncoding(t.raw)
        val pairs=mutableListOf(bytes.size to stateHeader(bytes))
        for(b in objects.values)pairs.add(b.size to stateHeader(b))
        for (t in tables.values) if (t.raw.size > SyncV2NoteSegments.LIMIT) SyncV2Codec.fail("object_too_large")
        for ((size, h) in pairs) stateBounds(size, h)
    }
    private fun hashes(oid: String, bytes: ByteArray, objects: Map<String, ByteArray>, tables: Map<String, Mapping>) {
        if ("state:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(bytes))}" != oid) SyncV2Codec.fail("hash_mismatch")
        for ((id, b) in objects) if ("state:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(b))}" != id) SyncV2Codec.fail("hash_mismatch")
        for ((_, t) in tables) if (Regex("state-refs:sha256:[0-9a-f]{64}").matches(SyncV2Wire.string(t.info, "refsOid")) &&
            ("state-refs:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(t.raw))}" != SyncV2Wire.string(t.info, "refsOid") ||
                (t.info.opt("totalBytes") is Int || t.info.opt("totalBytes") is Long) && (t.info.get("totalBytes") as Number).toLong()!=t.raw.size.toLong())) SyncV2Codec.fail("hash_mismatch")
    }

    /** Verifies one signed refs mapping against its parent and returns the published segment list. */
    fun verifyRefs(ctx: Context, t: Mapping, oid: String, type: String): JSONArray {
        SyncV2Codec.exact(t.info, setOf("scope", "parentOid", "refsOid", "authorDeviceId", "keyId", "totalBytes"))
        val value = SyncV2Wire.parse(t.raw, SyncV2NoteSegments.LIMIT, true); SyncV2Codec.exact(value, setOf("body", "signature"))
        val body = value.optJSONObject("body") ?: SyncV2Codec.fail("invalid_schema")
        val field = if (type == "state") "segmentOids" else "blobOids"
        SyncV2Codec.exact(body, setOf("scope", "${type}Oid", field))
        val ids = body.optJSONArray(field) ?: SyncV2Codec.fail("invalid_schema")
        val refsOid = SyncV2Wire.oid(SyncV2Wire.string(t.info, "refsOid"), "$type-refs")
        if (refsOid != "$type-refs:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(t.raw))}" ||
            SyncV2Wire.uint(t.info, "totalBytes", 262144) != t.raw.size.toLong()) SyncV2Codec.fail("hash_mismatch")
        SyncV2Codec.scope(body.getJSONObject("scope")); SyncV2Codec.scope(t.info.getJSONObject("scope"))
        if (SyncV2Codec.canonical(t.info.getJSONObject("scope")) != SyncV2Codec.canonical(ctx.scope) ||
            SyncV2Codec.canonical(body.getJSONObject("scope")) != SyncV2Codec.canonical(ctx.scope)) SyncV2Codec.fail("scope_mismatch")
        if (SyncV2Wire.string(body, "${type}Oid") != oid || t.info.get("parentOid") != oid) SyncV2Codec.fail("invalid_schema")
        val list = SyncV2Wire.strings(ids, SyncV2NoteSegments.MAX, false)
        if (list.distinct().size != list.size) SyncV2Codec.fail("invalid_schema")
        list.forEach { SyncV2Wire.oid(it, "state") }
        val pub = ctx.publicKey(SyncV2Wire.string(t.info, "authorDeviceId"), SyncV2Wire.string(t.info, "keyId")) ?: SyncV2Codec.fail("identity_conflict")
        if (!SyncV2Identity.verifySignature(pub, SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/$type-refs-sign"),
                SyncV2Codec.utf8(SyncV2Codec.canonical(body))), SyncV2Wire.string(value, "signature"))) SyncV2Codec.fail("signature_invalid")
        return JSONArray(list)
    }

    private fun open(ctx: Context, oid: String, bytes: ByteArray): Opened {
        if (bytes.size < 9) SyncV2Codec.fail("invalid_encoding")
        val len = java.nio.ByteBuffer.wrap(bytes, 4, 4).int
        if (len !in 1..4096 || 24L + len > bytes.size) SyncV2Codec.fail("invalid_encoding")
        val h = SyncV2Wire.parse(bytes.copyOfRange(8, 8 + len), 4096, true)
        val version = SyncV2Wire.uint(h, "keyVersion", 4294967295)
        val plain = SyncV2Envelope.open(ctx.scope, ctx.opaque, ctx.contentKey(version), oid, bytes, version,lengthError="invalid_schema")
        return Opened(h, SyncV2Wire.parse(plain, 262144, true), plain)
    }

    /**
     * Full spec-25.5 authentication of one candidate parent plus its segments.
     * [object]/[table] return null for a missing item; missing is only decided after the
     * size pass so an oversize sibling cannot be hidden by a missing one (same first error
     * for the network entry and the vector entry, review R3/CLI N1 class).
     */
    fun authenticate(ctx: Context, state: JSONObject, oid: String, bytes: ByteArray,
        objectFor: (String) -> ByteArray?, table: (String) -> Mapping?, supported: Set<String>,
        maxBytes: Long = SyncV2NoteSegments.maxNoteBytes, objectsStore:(()->MutableMap<String,ByteArray>)?=null): Closed = SyncV2Wire.schema {
        authenticateChecked(ctx,state,oid,bytes,objectFor,table,supported,maxBytes,objectsStore)
    }
    private fun authenticateChecked(ctx: Context, state: JSONObject, oid: String, bytes: ByteArray,
        objectFor: (String) -> ByteArray?, table: (String) -> Mapping?, supported: Set<String>,
        maxBytes:Long,objectsStore:(()->MutableMap<String,ByteArray>)?):Closed {
        if (!SyncV2NoteSegments.structure(state, supported)) SyncV2Codec.fail("invalid_schema")
        val ids = SyncV2Wire.strings(state.getJSONArray("noteSegments"), SyncV2NoteSegments.MAX, false)
        val all = listOf(oid) + ids
        val objects = objectsStore?.invoke()?:linkedMapOf<String, ByteArray>(); val tables = linkedMapOf<String, Mapping>()
        try {
        for (id in all) {
            val t = try { table(id) } catch (e: IllegalStateException) {
                if (e.message in setOf("object_missing", "not_found", "closure_incomplete")) null else throw e
            }
            if (t != null) tables[id] = t
            if (id != oid) {
                val b = try { objectFor(id) } catch (e: IllegalStateException) {
                    if (e.message in setOf("object_missing", "not_found", "closure_incomplete")) null else throw e
                }
                if (b != null) objects[id] = b
            }
        }
        sizes(bytes, objects, tables)
        if (tables.size != all.size || objects.size != ids.size) SyncV2Codec.fail("closure_incomplete")
        var resident = bytes.size.toLong()
        for (t in tables.values) resident += t.raw.size
        for (b in objects.values) resident += b.size
        hashes(oid, bytes, objects, tables)
        // This is a physical allocation cap, independent of the publish/read meter.
        // Authenticate step 7 one object at a time before allowing this cap to isolate
        // the entity. No list of decoded bodies is allocated for an over-cap closure.
        val overhead=SyncV2NoteSegments.SEALED_OVERHEAD.toLong()*ids.size
        val hardCap=if(maxBytes>(Long.MAX_VALUE-overhead)/3)Long.MAX_VALUE else 3*maxBytes+overhead
        var plainBytes=0L
        for(b in listOf(bytes).asSequence()+objects.values.asSequence())plainBytes+=(stateHeader(b)?.opt("plainBytes") as? Number)?.toLong()?:0
        if(resident>hardCap||plainBytes>hardCap) {
            val parent=open(ctx,oid,bytes)
            if(SyncV2Codec.canonical(parent.value)!=SyncV2Codec.canonical(state) || SyncV2Wire.string(parent.header,"type")!="state")SyncV2Codec.fail("invalid_schema")
            for(id in ids)open(ctx,id,objects.getValue(id))
            for(id in all)verifyRefs(ctx,tables.getValue(id),id,"state")
            SyncV2Codec.fail("storage_pressure")
        }
        val parent = open(ctx, oid, bytes)
        if (SyncV2Codec.canonical(parent.value) != SyncV2Codec.canonical(state)) SyncV2Codec.fail("invalid_schema")
        if (SyncV2Wire.string(parent.header, "type") != "state") SyncV2Codec.fail("invalid_schema")
        val values = ids.map { open(ctx, it, objects.getValue(it)) }
        val published = verifyRefs(ctx, tables.getValue(oid), oid, "state")
        if (SyncV2Codec.canonical(published) != SyncV2Codec.canonical(state.getJSONArray("noteSegments"))) SyncV2Codec.fail("invalid_schema")
        for (id in ids) if (verifyRefs(ctx, tables.getValue(id), id, "state").length() != 0) SyncV2Codec.fail("invalid_schema")
        val ordinary = SyncV2NoteSegments.reassemble(state, values.map { it.value }, Long.MAX_VALUE / 4)
        // M1b: publish and read use the same escaped text meter. Sealed bytes are only
        // a separate physical hard cap, after the complete 25.5 authentication order.
        if (SyncV2NoteSegments.measure(ordinary.getString("entityKey"), ordinary.getJSONObject("payload"),
                ordinary.getJSONArray("requiredFeatures")).streamBytes > maxBytes ||
            resident > hardCap)
            SyncV2Codec.fail("storage_pressure")
        val streamBytes = values.sumOf { it.value.getJSONObject("payload").getString("text").let { text -> SyncV2Codec.utf8(text).size.toLong() } }
        val segments = ids.mapIndexed { i, id -> Segment(id, objects.getValue(id), values[i].plain, values[i].value, tables.getValue(id)) }
        return Closed(ordinary, streamBytes, segments)
        }finally{(objects as? AutoCloseable)?.close()}
    }

    /** Authenticated reuse cache for the previous segments of one binding (spec 25.7). */
    class Reusable internal constructor(internal val cache: Map<String, Triple<String, ByteArray, ByteArray>>) {
        fun size() = cache.size
        /** A hit requires the exact canonical segment plaintext, never a hash-trust shortcut. */
        fun lookup(plain: ByteArray): Pair<String, ByteArray>? {
            val hit = cache[SyncV2Codec.hex(SyncV2Codec.sha(plain))] ?: return null
            return if (hit.third.contentEquals(plain)) hit.first to hit.second else null
        }
    }
    fun reusable(ctx: Context, entries: List<Segment>): Reusable {
        val cache = linkedMapOf<String, Triple<String, ByteArray, ByteArray>>()
        for (item in entries) {
            val opened = open(ctx, item.oid, item.sealed)
            if (SyncV2Wire.string(opened.header, "type") != "state") SyncV2Codec.fail("invalid_schema")
            if (verifyRefs(ctx, item.mapping, item.oid, "state").length() != 0) SyncV2Codec.fail("invalid_schema")
            cache[SyncV2Codec.hex(SyncV2Codec.sha(opened.plain))] = Triple(item.oid, item.sealed, opened.plain)
        }
        return Reusable(cache)
    }
}
