package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Shared fixed-expected vectors for spec 25 run through the phone product implementation
 * (SyncV2NoteSegments / SyncV2NoteClosure / SyncV2Envelope / SyncV2Signing). The expected
 * bytes in note-segments-rc7.json are never regenerated here.
 */
class SyncV2C7VectorsTest {
    private val fixture = SyncV2Fixtures.vector("note-segments-rc7")
    private val context = fixture.getJSONObject("context")
    private val scope = context.getJSONObject("scope")
    private val entityKey = context.getString("entityKey")
    private val boundRevision = context.getString("noteRevision")
    private val idKey = SyncV2Codec.unhex(context.getString("idKeyHex"), 32)
    private val contentKey = SyncV2Codec.unhex(context.getString("contentKeyHex"), 32)
    private val signer = SyncV2Signing.fromPrivate(SyncV2Codec.unhex("77".repeat(32), 32), "11".repeat(16), "22".repeat(16))
    private val opaque = { type: String, plain: ByteArray -> SyncV2Codec.opaque(idKey, scope, type, plain) }
    private val supportedFull = setOf(SyncV2NoteSegments.feature, SyncV2NoteConflicts.feature)

    private class Entry(val oid: String, val sealed: ByteArray, val plain: ByteArray)
    private fun sealEntry(value: JSONObject, tag: String): Entry {
        val plain = SyncV2Codec.utf8(SyncV2Codec.canonical(value))
        val sealed = SyncV2Envelope.sealWithParameters(scope, opaque, contentKey, 1, "state", plain,
            SyncV2Codec.sha(SyncV2Codec.utf8("rc7/salt/$tag")), SyncV2Codec.sha(SyncV2Codec.utf8("rc7/nonce/$tag")).copyOfRange(0, 12))
        return Entry(sealed.oid, sealed.bytes, plain)
    }
    private fun signRefs(oid: String, ids: List<String>): ByteArray = SyncV2Codec.utf8(SyncV2Codec.canonical(
        signer.signed("wrss.sync.v2/state-refs-sign", JSONObject().put("scope", scope).put("stateOid", oid).put("segmentOids", JSONArray(ids)))))
    private fun mappingOf(id: String, raw: ByteArray) = SyncV2NoteClosure.Mapping(JSONObject()
        .put("scope", scope).put("parentOid", id).put("refsOid", "state-refs:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}")
        .put("authorDeviceId", signer.deviceId).put("keyId", signer.keyId).put("totalBytes", raw.size), raw)
    private val closureContext = SyncV2NoteClosure.Context(scope, opaque, { contentKey }, { _, _ -> signer.publicHex })

    /** Synthetic repeat descriptors expand as replayable bounded sources. */
    private fun descriptorSource(desc: Any?): SyncV2NoteSegments.NoteTextSource {
        if (desc is String) { SyncV2Codec.canonical(desc); return SyncV2NoteSegments.NoteTextSource.of(desc) }
        val parts = (desc as? JSONObject)?.getJSONArray("parts") ?: SyncV2Codec.fail("invalid_schema")
        var units = 0L
        val pieces = (0 until parts.length()).map {
            val p = parts.getJSONObject(it); val repeat = SyncV2Wire.string(p, "repeat"); SyncV2Codec.canonical(repeat)
            val count = SyncV2Wire.uint(p, "count", 1_000_000_000L)
            if (repeat.isEmpty()) SyncV2Codec.fail("invalid_schema")
            units += repeat.length.toLong() * count; repeat to count
        }
        return SyncV2NoteSegments.NoteTextSource.replayed(units) {
            sequence { for ((repeat, total) in pieces) {
                var left = total
                while (left > 0L) { val n = minOf(left, maxOf(1L, 8192L / repeat.length)); yield(repeat.repeat(n.toInt())); left -= n }
            } }
        }
    }
    private fun materialize(source: SyncV2NoteSegments.NoteTextSource): String =
        StringBuilder().also { b -> source.blocks().forEach { b.append(it) } }.toString()
    private fun materialize(desc: Any?): String = materialize(descriptorSource(desc))
    private fun hashBlocks(source: SyncV2NoteSegments.NoteTextSource): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256"); source.blocks().forEach { digest.update(SyncV2Codec.utf8(it)) }
        return SyncV2Codec.hex(digest.digest())
    }
    private fun fieldSummary(source: SyncV2NoteSegments.NoteTextSource): JSONObject {
        val digest = java.security.MessageDigest.getInstance("SHA-256"); var bytes = 0L
        for (b in source.blocks()) { val u = SyncV2Codec.utf8(b); digest.update(u); bytes += u.size }
        return JSONObject().put("bytes", bytes).put("sha256", SyncV2Codec.hex(digest.digest()))
    }
    private fun sourcesOf(input: JSONObject): LinkedHashMap<String, SyncV2NoteSegments.NoteTextSource> {
        val fieldsIn = input.getJSONObject("fields"); val out = LinkedHashMap<String, SyncV2NoteSegments.NoteTextSource>()
        for (key in fieldsIn.keys().asSequence().sorted().toList()) out[key] = descriptorSource(fieldsIn.get(key))
        return out
    }
    private fun metadataOf(input: JSONObject, sources: Map<String, SyncV2NoteSegments.NoteTextSource>): JSONObject {
        val metadata = JSONObject().put("contentHash", sources["markdown"]?.let { hashBlocks(it) } ?: SyncV2Codec.hex(SyncV2Codec.sha(ByteArray(0))))
            .put("baseContentHash", sources["baseMarkdown"]?.let { hashBlocks(it) } ?: SyncV2Codec.hex(SyncV2Codec.sha(ByteArray(0))))
            .put("pinned", false).put("updatedAt", 1).put("modifiedBy", "synthetic").put("deleted", false).put("deletedAt", 0)
        if (input.has("metadata")) for (key in input.getJSONObject("metadata").keys()) metadata.put(key, input.getJSONObject("metadata").get(key))
        return metadata
    }
    private class Outcome(val summary: JSONObject, val closure: Retained?)
    private class Retained(val state: JSONObject, val parent: Entry, val objects: Map<String, ByteArray>,
        val refs: List<String>, val tables: Map<String, ByteArray>)
    private fun encodeCase(input: JSONObject, retain: Boolean, reuse: Map<String, ByteArray>? = null): Outcome {
        val seed = if (input.has("sealSeed")) input.getString("sealSeed") else "initial"
        val revision = if (input.has("noteRevision")) input.getString("noteRevision") else boundRevision
        val sources = sourcesOf(input)
        val metadata = metadataOf(input, sources)
        val features = if (input.has("features")) input.getJSONArray("features") else JSONArray()
        val summaries = mutableListOf<JSONObject>(); val objects = LinkedHashMap<String, ByteArray>(); val reused = mutableListOf<Int>()
        val seal = { value: JSONObject, index: Int ->
            val plain = SyncV2Codec.utf8(SyncV2Codec.canonical(value))
            val key = SyncV2Codec.hex(SyncV2Codec.sha(plain))
            val entry = reuse?.get(key)?.let { sealed ->
                // A hash hit only produces a candidate; full authentication must still pass.
                val oid = "state:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(sealed))}"
                runCatching { SyncV2Wire.parse(SyncV2Envelope.open(scope, opaque, contentKey, oid, sealed, 1), 262144, true) }
                    .getOrNull()?.let { if (SyncV2Codec.canonical(it) == SyncV2Codec.canonical(value)) Entry(oid, sealed, plain) else null }
            }
            val sealed = entry ?: sealEntry(value, "$seed/segment/$index")
            if (entry != null) reused.add(index)
            val text = value.getJSONObject("payload").getString("text"); val textBytes = SyncV2Codec.utf8(text)
            summaries.add(JSONObject().put("index", index).put("textBytes", textBytes.size).put("statePlainBytes", plain.size)
                .put("textSha256", SyncV2Codec.hex(SyncV2Codec.sha(textBytes))).put("statePlainSha256", key)
                .put("oid", sealed.oid).put("storedBytes", sealed.sealed.size)
                .put("opaqueContentId", SyncV2Codec.opaque(idKey, scope, "state", plain))
                .put("prefixHex", SyncV2Codec.hex(textBytes.copyOfRange(0, minOf(8, textBytes.size))))
                .put("suffixHex", SyncV2Codec.hex(textBytes.copyOfRange(maxOf(0, textBytes.size - 8), textBytes.size))))
            if (retain) objects[sealed.oid] = sealed.sealed
            sealed.oid
        }
        val encoded = try { SyncV2NoteSegments.encode(entityKey, metadata, sources, features, revision, scope, Long.MAX_VALUE, seal) }
        catch (e: IllegalStateException) {
            if (e.message != "object_too_large") throw e
            return Outcome(JSONObject().put("error", "object_too_large").put("preserved", true), null)
        }
        val fieldsIn = input.getJSONObject("fields")
        val fieldJson = JSONObject().also { out -> for (key in fieldsIn.keys()) out.put(key, fieldSummary(descriptorSource(fieldsIn.get(key)))) }
        if (encoded.form == "short") {
            val parent = sealEntry(encoded.state, "$seed/root")
            return Outcome(JSONObject().put("form", "short").put("shortPlainBytes", encoded.shortPlainBytes).put("rootPlainBytes", parent.plain.size)
                .put("rootOid", parent.oid).put("rootStoredBytes", parent.sealed.size).put("fields", fieldJson), null)
        }
        val parent = sealEntry(encoded.state, "$seed/root")
        val ids = encoded.state.getJSONArray("noteSegments").let { a -> (0 until a.length()).map { a.getString(it) } }
        val rootRefs = SyncV2NoteSegments.refsBytes(scope, parent.oid, ids)
        var closure = parent.plain.size + SyncV2NoteSegments.SEALED_OVERHEAD + rootRefs
        var actual = parent.sealed.size + rootRefs
        for (s in summaries) {
            closure += s.getLong("statePlainBytes") + SyncV2NoteSegments.SEALED_OVERHEAD + SyncV2NoteSegments.refsBytes(scope, s.getString("oid"), emptyList())
            actual += s.getLong("storedBytes") + SyncV2NoteSegments.refsBytes(scope, s.getString("oid"), emptyList())
        }
        val summary = JSONObject().put("form", "segmented").put("shortPlainBytes", encoded.shortPlainBytes)
            .put("rootPlainBytes", parent.plain.size).put("rootOid", parent.oid).put("rootStoredBytes", parent.sealed.size)
            .put("noteRevision", revision).put("streamBytes", encoded.streamBytes).put("plaintextSha256", encoded.plaintextSha256)
            .put("fields", fieldJson).put("metadataSha256",
                SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(SyncV2Codec.canonical(metadataOf(input, sources))))))
            .put("segmentCount", ids.size).put("segments", JSONArray(summaries))
            .put("rootRefsBytes", rootRefs).put("closureStoredBytesUpperBound", closure).put("uniqueStoredBytesWithRefs", actual)
            .put("reusedIndices", JSONArray(reused))
        val tables = LinkedHashMap<String, ByteArray>(); tables.put(parent.oid, signRefs(parent.oid, ids)); for (id in ids) tables[id] = signRefs(id, emptyList())
        return Outcome(summary, if (retain) Retained(encoded.state, parent, objects, ids, tables) else null)
    }
    private fun readCase(retained: Retained, supported: Set<String>): JSONObject {
        val closed = SyncV2NoteClosure.authenticate(closureContext, retained.state, retained.parent.oid, retained.parent.sealed,
            { retained.objects[it] }, { id -> retained.tables[id]?.let { mappingOf(id, it) } }, supported)
        val fields = JSONObject()
        for (key in closed.ordinary.getJSONObject("payload").keys().asSequence().filter { it in SyncV2NoteSegments.names }.sorted()) {
            val value = closed.ordinary.getJSONObject("payload").getString(key); val bytes = SyncV2Codec.utf8(value)
            fields.put(key, JSONObject().put("bytes", bytes.size).put("sha256", SyncV2Codec.hex(SyncV2Codec.sha(bytes))))
        }
        return JSONObject().put("ok", true).put("streamBytes", closed.streamBytes)
            .put("plaintextSha256", SyncV2Wire.string(retained.state.getJSONObject("payload"), "plaintextSha256")).put("fields", fields)
    }

    private fun assertSameJson(id: String, expected: JSONObject, actual: JSONObject) {
        val diffs = mutableListOf<String>()
        fun scalar(v: Any?): String = if (v == JSONObject.NULL) "<null>" else v.toString()
        fun walk(path: String, e: Any?, a: Any?) {
            if (e is JSONObject && a is JSONObject) {
                for (key in (e.keys().asSequence().toSet() + a.keys().asSequence().toSet()).sorted())
                    walk("$path.$key", if (e.has(key)) e.get(key) else "<absent>", if (a.has(key)) a.get(key) else "<absent>")
            } else if (e is JSONArray && a is JSONArray) {
                if (e.length() != a.length()) diffs.add("$path.length ${e.length()} != ${a.length()}")
                for (i in 0 until minOf(e.length(), a.length())) walk("$path[$i]", e.get(i), a.get(i))
            } else if (scalar(e) != scalar(a) && !(e is Number && a is Number && e.toDouble() == a.toDouble())) diffs.add("$path expected=${scalar(e)} actual=${scalar(a)}")
        }
        walk("", expected, actual)
        assertTrue("$id first diffs: ${diffs.take(8).joinToString(" | ")}", diffs.isEmpty())
    }
    @Test fun sharedFixedEncodingVectorsPassThroughTheProductCodec() {
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val input = JSONObject(case.getJSONObject("input").toString())
            if (case.has("expandConflictText") && input.has("metadata")) {
                val records = input.getJSONObject("metadata").getJSONObject("noteConflicts").getJSONArray("records")
                for (i in 0 until records.length()) { val r = records.getJSONObject(i)
                    for (key in listOf("baseMarkdown", "localMarkdown", "remoteMarkdown")) r.put(key, materialize(r.get(key))) }
            }
            val expected = case.getJSONObject("expected")
            val outcome = encodeCase(input, expected.optString("form") == "segmented" && expected.optInt("segmentCount", 0) < 16)
            assertSameJson(case.getString("id"), expected, outcome.summary)
            if (outcome.closure != null) {
                println("RC7 case ${case.getString("id")}: reading closure")
                val restored = readCase(outcome.closure!!, supportedFull)
                assertEquals(case.getString("id") + " exact field bytes", SyncV2Codec.canonical(expected.getJSONObject("fields")), SyncV2Codec.canonical(restored.getJSONObject("fields")))
                assertEquals(case.getString("id") + " total bytes", expected.getLong("streamBytes"), restored.getLong("streamBytes"))
                assertEquals(case.getString("id") + " retained metadata", expected.getString("metadataSha256"),
                    SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(SyncV2Codec.canonical(outcome.closure.state.getJSONObject("payload").getJSONObject("metadata"))))))
            }
        }
    }
    @Test fun sharedAuthenticatedClosureVectorsUseOneProductEntryOrder() {
        val source = closureSourceInput()
        val base = encodeCase(JSONObject(source.toString()), true)
        assertNotNull("closure source must retain an authenticated closure", base.closure)
        val cases = fixture.getJSONArray("closureCases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val kind = when (val m = case.get("mutation")) { is String -> m; is JSONObject -> SyncV2Wire.string(m, "kind"); else -> SyncV2Codec.fail("invalid_schema") }
            val supported = if (case.has("supported") && !case.getBoolean("supported")) setOf(SyncV2NoteConflicts.feature) else supportedFull
            val observed = try {
                SyncV2Codec.canonical(readCase(mutate(base.closure!!, kind), supported))
            } catch (e: IllegalStateException) { if (System.getenv("C7_DEBUG") == kind) println(e.stackTraceToString().take(1600)); SyncV2Codec.canonical(JSONObject().put("error", e.message).put("preserved", true)) }
            assertEquals(case.getString("id"), SyncV2Codec.canonical(case.getJSONObject("expected")), observed)
        }
    }
    private fun closureSourceInput(): JSONObject = fixture.getJSONArray("cases").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.single { it.getString("id") == fixture.getString("closureSource") }.getJSONObject("input") }
    private fun mutate(retained: Retained, kind: String): Retained {
        val state = JSONObject(retained.state.toString()); val ids = state.getJSONArray("noteSegments").let { a -> (0 until a.length()).map { a.getString(it) }.toMutableList() }
        var refs = retained.refs.toMutableList(); val objects = LinkedHashMap(retained.objects)
        fun open(oid: String): JSONObject = SyncV2Wire.parse(SyncV2Envelope.open(scope, opaque, contentKey, oid, objects.getValue(oid), 1), 262144, true)
        fun replace(i: Int, value: JSONObject) { val entry = sealEntry(value, "mutate/$kind"); objects.remove(ids[i]); objects[entry.oid] = entry.sealed; ids[i] = entry.oid; refs = ids.toMutableList() }
        when (kind) {
            "none" -> {}
            "swap-only", "swap-both" -> { val t = ids[0]; ids[0] = ids[1]; ids[1] = t; if (kind == "swap-both") refs = ids.toMutableList() }
            "missing-object" -> objects.remove(ids[1])
            "duplicate" -> ids[1] = ids[0]
            "count" -> state.getJSONObject("payload").put("segmentCount", state.getJSONObject("payload").getLong("segmentCount") + 1)
            "parent-extra" -> state.getJSONObject("payload").put("extra", 1)
            "parent-feature-missing" -> state.put("requiredFeatures", JSONArray())
            "unknown-feature" -> state.put("requiredFeatures", JSONArray(SyncV2Wire.features(state.getJSONArray("requiredFeatures")) + "unknown.v1"))
            "metadata-text" -> state.getJSONObject("payload").getJSONObject("metadata").put("markdown", "duplicate")
            "missing-refs" -> {}
            "missing-child-refs" -> {}
            "bad-refs-signature" -> {}
            "total-hash" -> state.getJSONObject("payload").put("plaintextSha256", "00".repeat(32))
            "wire-tamper", "aead-tamper" -> {
                val oid = ids[0]; val bytes = objects.getValue(oid).copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
                objects.remove(oid)
                if (kind == "aead-tamper") { val next = "state:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(bytes))}"; objects[next] = bytes; ids[0] = next; refs = ids.toMutableList() }
                else objects[oid] = bytes
            }
            "segmentOf", "index", "entityKey", "nested", "features", "empty" -> {
                val value = JSONObject(open(ids[0]).toString()); val p = value.getJSONObject("payload")
                when (kind) { "segmentOf" -> p.put("segmentOf", "99".repeat(16)); "index" -> p.put("index", 1); "entityKey" -> value.put("entityKey", "99".repeat(32))
                    "nested" -> value.put("noteSegments", JSONArray(listOf(ids.getOrElse(1) { ids[0] }))); "features" -> value.put("requiredFeatures", JSONArray(listOf(SyncV2NoteSegments.feature)))
                    else -> p.put("text", "") }
                replace(0, value)
            }
            "old-authenticated-segment" -> { val value = JSONObject(open(ids[0]).toString()); value.getJSONObject("payload").put("text", value.getJSONObject("payload").getString("text").replace("a", "b")); replace(0, value) }
            "noncanonical-boundary", "trailing-data", "duplicate-text-key", "unknown-text-key", "malformed-json" -> {
                val values = ids.map { JSONObject(open(it).toString()) }; val texts = values.map { it.getJSONObject("payload").getString("text") }.toMutableList()
                if (kind == "noncanonical-boundary") { texts[2] = texts[1].takeLast(1) + texts[2]; texts[1] = texts[0].takeLast(1) + texts[1].dropLast(1); texts[0] = texts[0].dropLast(1) }
                if (kind == "trailing-data") texts[texts.size - 1] += " "
                if (kind == "duplicate-text-key") texts[0] = texts[0].replaceFirst("{", "{\"markdown\":\"duplicate\",").take(texts[0].length)
                if (kind == "unknown-text-key") texts[0] = texts[0].replace("markdown", "unknownx")
                if (kind == "malformed-json") texts[0] = "[" + texts[0].drop(1)
                values.forEachIndexed { i, value -> value.getJSONObject("payload").put("text", texts[i]); replace(i, value) }
                state.getJSONObject("payload").put("plaintextSha256", SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(texts.joinToString("")))))
            }
            else -> SyncV2Codec.fail("invalid_schema")
        }
        state.put("noteSegments", JSONArray(ids))
        val parent = sealEntry(state, "mutate-root/$kind")
        val tables = LinkedHashMap<String, ByteArray>()
        for (id in ids) if (!(kind == "missing-child-refs" && id == ids[1])) tables[id] = signRefs(id, emptyList())
        if (kind != "missing-refs") tables[parent.oid] = if (kind == "bad-refs-signature")
            SyncV2Codec.utf8(SyncV2Codec.canonical(JSONObject().put("body", JSONObject().put("scope", scope).put("stateOid", parent.oid).put("segmentOids", JSONArray(refs))).put("signature", "A".repeat(86))))
            else signRefs(parent.oid, refs)
        return Retained(state, parent, objects, refs, tables)
    }

    @Test fun sharedSegmentReuseVectorsAuthenticateBeforeReuse() {
        val source = closureSourceInput()
        val base = encodeCase(JSONObject(source.toString()), true)
        val reuse = LinkedHashMap<String, ByteArray>()
        for ((oid, sealed) in base.closure!!.objects) {
            // A reuse candidate must carry a fully authenticated original object plus its own empty refs table.
            val plain = SyncV2Envelope.open(scope, opaque, contentKey, oid, sealed, 1)
            require(SyncV2NoteClosure.verifyRefs(closureContext, mappingOf(oid, base.closure!!.tables.getValue(oid)), oid, "state").length() == 0)
            reuse[SyncV2Codec.hex(SyncV2Codec.sha(plain))] = sealed
        }
        val cases = fixture.getJSONArray("reuseCases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val outcome = encodeCase(JSONObject(case.getJSONObject("after").toString()), true, reuse)
            assertSameJson(case.getString("id"), case.getJSONObject("expected"), outcome.summary)
            val original = base.closure!!.refs; val after = outcome.closure!!.refs
            for (i in outcome.summary.getJSONArray("reusedIndices").let { a -> (0 until a.length()).map { a.getInt(it) } }) {
                assertEquals(original[i], after[i])
                assertArrayEquals(outcome.closure!!.objects.getValue(after[i]), base.closure!!.objects.getValue(original[i]))
            }
            readCase(outcome.closure!!, supportedFull)
        }
    }
    @Test fun independentEncapsulationNeverGuessesOids() {
        val expected = fixture.getJSONObject("independentEncapsulation").getJSONObject("expected")
        val source = closureSourceInput()
        val fieldsIn = source.getJSONObject("fields")
        val map = LinkedHashMap<String, SyncV2NoteSegments.NoteTextSource>()
        for (key in fieldsIn.keys().asSequence().sorted().toList()) map[key] = descriptorSource(fieldsIn.get(key))
        val segment = SyncV2NoteSegments.chunks(map, entityKey, boundRevision).first()
        val one = sealEntry(segment, "fresh-a"); val two = sealEntry(segment, "fresh-b")
        assertEquals(expected.getString("firstOid"), one.oid)
        assertEquals(expected.getString("secondOid"), two.oid)
        assertEquals(expected.getString("opaqueContentId"), SyncV2Codec.opaque(idKey, scope, "state", one.plain))
        assertTrue(expected.getBoolean("sameOpaque")); assertFalse(expected.getBoolean("sameOid"))
    }

    @Test fun sharedMergeReassembleResegmentUsesThePhonePlanner() {
        val cases = fixture.getJSONArray("mergeCases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val base = materialize(case.get("base")); val local = materialize(case.get("local")); val remote = materialize(case.get("remote"))
            for (text in listOf(base, local, remote)) {
                val encoded = encodeCase(JSONObject().put("fields", JSONObject().put("markdown", text).put("plainText", text).put("baseMarkdown", base)), true)
                readCase(encoded.closure!!, supportedFull)
            }
            fun snapshot(markdown: String) = JSONObject().put("n", JSONObject().put("identity", "n").put("markdown", markdown).put("plainText", markdown).put("baseMarkdown", base))
            val plan = SyncV2Planner.canMerge(JSONObject().put("base", snapshot(base)).put("local", snapshot(local)).put("cloud", snapshot(remote))
                .put("ops", JSONArray(listOf(JSONObject().put("id", "n").put("kind", "update").put("fields", JSONArray(listOf("markdown"))).put("complete", true)))))
            assertTrue("${case.getString("id")} canMerge", plan.getBoolean("canMerge"))
            val merged = plan.getJSONObject("tree").getJSONObject("n").getString("markdown")
            assertEquals(case.getString("id"), case.getString("expectedMarkdownSha256"), SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(merged))))
            assertFalse(merged.contains('\r'))
            val output = encodeCase(JSONObject().put("fields", JSONObject().put("markdown", merged).put("plainText", merged).put("baseMarkdown", base)).put("sealSeed", "merge"), true)
            assertSameJson(case.getString("id"), case.getJSONObject("expected"), output.summary)
            readCase(output.closure!!, supportedFull)
        }
    }
}
