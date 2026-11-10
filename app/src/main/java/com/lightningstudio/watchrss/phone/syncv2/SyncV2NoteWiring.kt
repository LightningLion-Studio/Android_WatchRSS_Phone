package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spec-25 product wiring shared by the P2 wire index and the P5 account snapshot.
 *
 * Bindings live in their own table so a sealed noteRevision survives edits, short
 * round-trips and publish retries (25.3). Over-budget or unsupported remote notes are
 * isolated per entity (F1/W3): the authenticated raw bytes stay stored, the note is
 * marked unopenable and every publication path carries the stored row back verbatim
 * instead of re-sealing or tombstoning it (R3).
 */
internal object SyncV2NoteWiring {
    const val ISOLATED_KEY = "isolatedReason"
    const val SEGMENTS_KEY = "wireSegmentOids"
    const val REFS_MAPS_KEY = "wireRefsMaps"
    const val REVISION_KEY = "wireNoteRevision"
    /** Pull-side per-entity staging marker (F2/R4): the cloud-row body and raw bytes were
     * handed off while this entity was processed and reach the cache through the staging tables. */
    const val PULL_STAGED_KEY = "pullStaged"

    /** One closure context for both entries so read errors never depend on the caller. */
    fun context(identity: SyncV2Identity, authors: SyncV2Authors?, contentKey: (Long) -> ByteArray, own: SyncV2Signing? = null) =
        SyncV2NoteClosure.Context(identity.scope, { type, plain -> identity.opaque(type, plain) },
            contentKey, { device, keyId ->
                authors?.let { a -> runCatching { a.publicKey(device, keyId, true) }.getOrNull() }
                    ?: own?.publicHex?.takeIf { device == own.deviceId && keyId == own.keyId } })

    fun binding(db: SupportSQLiteDatabase, scope: String, entity: String): JSONObject? {
        SyncV2Codec.unhex(entity, 32)
        return db.query("SELECT note_revision,deleted,invalidated FROM syncv2_note_bindings WHERE scope=? AND entity_key=?",
            arrayOf(scope, entity)).use { c -> if (!c.moveToFirst()) null
            else JSONObject().put("noteRevision", if (c.isNull(0)) JSONObject.NULL else c.getString(0)).put("deleted", c.getInt(1) != 0).put("invalidated", c.getInt(2) != 0) }
    }

    fun putBinding(db: SupportSQLiteDatabase, scope: String, entity: String, revision: String?, deleted: Boolean, invalidated: Boolean) {
        SyncV2Codec.unhex(entity, 32)
        if (revision != null) Regex("[0-9a-f]{32}").matchEntire(revision) ?: SyncV2Codec.fail("invalid_schema")
        db.execSQL("INSERT OR REPLACE INTO syncv2_note_bindings VALUES(?,?,?,?,?)",
            arrayOf(scope, entity, revision, if (deleted) 1 else 0, if (invalidated) 1 else 0))
    }

    fun removeBinding(db: SupportSQLiteDatabase, scope: String, entity: String) {
        SyncV2Codec.unhex(entity, 32)
        db.execSQL("DELETE FROM syncv2_note_bindings WHERE scope=? AND entity_key=?", arrayOf(scope, entity))
    }

    /**
     * Binding selection for one publish (25.3). A live local binding always wins over any
     * received value (R1); invalidation after a delete boundary forces a fresh CSPRNG value
     * that can never reuse segments across the boundary (R2); followers adopt the received
     * revision so a remote delete-and-recreate refreshes the binding (N1).
     */
    fun revisionForPublish(db: SupportSQLiteDatabase, scope: String, entity: String, received: String?, outboxOwned: Boolean): String {
        val prior = binding(db, scope, entity)
        if (outboxOwned && prior != null && !prior.optBoolean("deleted") && !prior.optBoolean("invalidated") && !prior.isNull("noteRevision"))
            return prior.getString("noteRevision")
        if (prior != null && !prior.optBoolean("deleted") && !prior.optBoolean("invalidated") && !prior.isNull("noteRevision"))
            return prior.getString("noteRevision")
        if (prior != null && prior.optBoolean("deleted")) return SyncV2Codec.hex(SyncV2Codec.random(16))
        if (prior != null && prior.optBoolean("invalidated")) return SyncV2Codec.hex(SyncV2Codec.random(16))
        // First segmentation with no local binding: a received parent revision is the anchor when we do not own pending edits.
        if (!outboxOwned && received != null && Regex("[0-9a-f]{32}").matches(received)) return received
        return SyncV2Codec.hex(SyncV2Codec.random(16))
    }

    private fun priorExisted(db: SupportSQLiteDatabase, scope: String, entity: String) = binding(db, scope, entity) != null

    /** True when the entity still has unpublished local edits (outbox journal, ruling M1). */
    fun outboxOwned(db: SupportSQLiteDatabase, scope: String, entity: String): Boolean =
        db.query("SELECT 1 FROM syncv2_candidates c JOIN syncv2_outbox o ON o.scope=c.scope AND o.logical_id=c.logical_id WHERE c.scope=? AND c.entity_key=? LIMIT 1",
            arrayOf(scope, entity)).use { it.moveToFirst() }

    private fun storedBytes(db: SupportSQLiteDatabase, scope: String, oid: String): ByteArray? =
        db.query("SELECT body FROM syncv2_cloud_objects WHERE scope=? AND oid=?", arrayOf(scope, oid)).use { c ->
            if (!c.moveToFirst()) null else c.getBlob(0).also {
                if (oid.substringAfter(":sha256:") != SyncV2Codec.hex(SyncV2Codec.sha(it))) SyncV2Codec.fail("hash_mismatch")
            }
        }

            /**
     * Seal one ordinary note state for the wire. Returns null when the note stays short;
     * otherwise the parent oid plus the exact segment/ref objects to attach. Reuse only
     * hits when the canonical segment plaintext matches an authenticated previous object
     * under the same binding (25.7); every hit uploads zero new bytes.
     */
    class Sealed(val parentOid: String, val parentBytes: ByteArray, val parentRefs: JSONObject,
        val childRefs: List<JSONObject>, val segmentOids: List<String>, val objects: Map<String, ByteArray>,
        val uploaded: Int, val reused: Int, val noteRevision: String, val upperBound: Long, val streamBytes: Long)

    fun seal(identity: SyncV2Identity, signer: SyncV2Signing, contentKey: ByteArray, keyVersion: Long,
        db: SupportSQLiteDatabase, scope: String, entityKey: String, ordinaryState: JSONObject,
        writeEnabled: Boolean, authors: SyncV2Authors?, remoteRevision: String?, remoteSegmentOids: List<String>,
        remoteRefsMaps: JSONObject?, outboxOwned: Boolean): Sealed? {
        val payload = ordinaryState.getJSONObject("payload")
        val m = SyncV2NoteSegments.measure(entityKey, payload, ordinaryState.getJSONArray("requiredFeatures"))
        if (m.shortPlainBytes <= SyncV2NoteSegments.LIMIT) return null
        if (!writeEnabled) SyncV2Codec.fail("feature_disabled")
        if (m.streamBytes > SyncV2NoteSegments.maxNoteBytes) SyncV2Codec.fail("storage_pressure")
        val revision = revisionForPublish(db, scope, entityKey, remoteRevision, outboxOwned)
        // 25.7: a reuse hit needs the same binding, the canonical segment plaintext and a full
        // re-authentication of the original sealed object. Any miss just seals fresh bytes.
        var reuse: Map<String, Triple<String, ByteArray, ByteArray>>? = null
        val refsByOid = mutableMapOf<String, Pair<String, ByteArray>>()
        if (revision == remoteRevision && remoteSegmentOids.isNotEmpty()) runCatching {
            val ctx = context(identity, authors, { contentKey }, signer)
            val entries = remoteSegmentOids.mapNotNull { oid ->
                val map = remoteRefsMaps?.optJSONObject(oid) ?: return@mapNotNull null
                val sealedBytes = storedBytes(db, scope, oid) ?: return@mapNotNull null
                val refsRaw = storedBytes(db, scope, SyncV2Wire.oid(SyncV2Wire.string(map, "refsOid"), "state-refs")) ?: return@mapNotNull null
                SyncV2NoteClosure.Segment(oid, sealedBytes, ByteArray(0), JSONObject(), SyncV2NoteClosure.Mapping(map, refsRaw))
            }
            for (entry in entries.filterNotNull()) refsByOid[entry.oid] =
                SyncV2Wire.oid(SyncV2Wire.string(entry.mapping.info, "refsOid"), "state-refs") to entry.mapping.raw
            reuse = SyncV2NoteClosure.reusable(ctx, entries).cache
        }
        val objects = linkedMapOf<String, ByteArray>()
        var uploaded = 0; var reused = 0
        val ids = mutableListOf<String>()
        val encoded = SyncV2NoteSegments.encode(entityKey, payload, ordinaryState.getJSONArray("requiredFeatures"), revision,
            identity.scope, SyncV2NoteSegments.maxNoteBytes) { value, index ->
            val canonical = SyncV2Codec.utf8(SyncV2Codec.canonical(value))
            val hit = reuse?.let { cache -> cache[SyncV2Codec.hex(SyncV2Codec.sha(canonical))]?.takeIf { it.third.contentEquals(canonical) }?.let { it.first to it.second } }
            if (hit != null) {
                // A reused segment ships nothing: its bytes and its refs table are already owned.
                reused++; ids.add(index, hit.first)
                objects.putIfAbsent(hit.first, hit.second)
                refsByOid[hit.first]?.let { (refsOid, refsRaw) -> objects.putIfAbsent(refsOid, refsRaw) }
                return@encode hit.first
            }
            val sealed = SyncV2Envelope.seal(identity, contentKey, keyVersion, "state", canonical)
            require(sealed.oid == "state:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(sealed.bytes))}")
            objects[sealed.oid] = sealed.bytes; uploaded++
            ids.add(index, sealed.oid); sealed.oid
        }
        require(encoded.form == "segmented" && encoded.noteRevision == revision && ids.size == encoded.segmentCount)
        // Wire objects are content-addressed by their SEALED bytes, exactly like ordinary states.
        val parentBytes = SyncV2Envelope.seal(identity, contentKey, keyVersion, "state", SyncV2Codec.utf8(SyncV2Codec.canonical(encoded.state)))
        val parentOid = parentBytes.oid
        objects[parentOid] = parentBytes.bytes
        val parentRefs = SyncV2Index.refs(identity, signer, parentOid, JSONArray(ids))
        objects[SyncV2Codec.oid("state-refs", parentRefs)] = SyncV2Codec.utf8(SyncV2Codec.canonical(parentRefs))
        val childRefs = ids.map { oid ->
            val signed = SyncV2Index.refs(identity, signer, oid)
            objects[SyncV2Codec.oid("state-refs", signed)] = SyncV2Codec.utf8(SyncV2Codec.canonical(signed))
            signed
        }
        // Persist the binding with the publish staging decision (25.3). A local delete records the
        // lifetime boundary {deleted, invalidated} so a rebuild can never reuse sealed segments (R2).
        val removing = payload.optBoolean("deleted", false)
        putBinding(db, scope, entityKey, revision, removing, removing && priorExisted(db, scope, entityKey))
        return Sealed(parentOid, parentBytes.bytes, parentRefs, childRefs, ids, objects,
            uploaded, reused, revision, encoded.closureStoredBytesUpperBound, encoded.streamBytes)
    }

    private class ReusableIndex(val cache: Map<String, Triple<String, ByteArray, ByteArray>>) {
        /** The hit compares the full canonical segment state plaintext, never a hash shortcut alone. */
        fun lookup(canonical: ByteArray): Pair<String, ByteArray>? {
            val hit = cache[SyncV2Codec.hex(SyncV2Codec.sha(canonical))] ?: return null
            return if (hit.third.contentEquals(canonical)) hit.first to hit.second else null
        }
    }

    /** Pull-side adoption (mini followCloud/receivedBindings, rulings N1/N2/R1/N3). */
    fun observeBindings(db: SupportSQLiteDatabase, scope: String, rows: List<JSONObject>,
        owns: (JSONObject) -> Boolean = { false }) {
        for (r in rows) {
            if (r.getJSONObject("meta").getString("kind") != "note") continue
            val entity = r.getJSONObject("meta").getString("entityKey")
            val owned = outboxOwned(db, scope, entity) || owns(r)
            val prior = binding(db, scope, entity)
            if (owned) continue // R1/M1: an owned local binding is never overwritten by received data.
            val revision = r.optString(REVISION_KEY).takeIf { it.isNotEmpty() && it != "null" }
            if (revision != null) putBinding(db, scope, entity, revision, false, false)
            else removeBinding(db, scope, entity) // The remote note is short again; no reuse anchor is needed.
        }
    }
}
