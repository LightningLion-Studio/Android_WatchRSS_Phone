package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

object B2Wire {
    private val receivedSizes = java.util.WeakHashMap<JSONObject, Int>()
    internal fun retainSize(o: JSONObject, bytes: Int) { synchronized(receivedSizes) { receivedSizes[o] = bytes } }
    fun frameBytes(o: JSONObject): Int = synchronized(receivedSizes) { receivedSizes[o] } ?: (12 + B2Json.utf8(o.toString()).size)
    const val ACTION = "syncLibraryBinary"
    const val CHUNK = 65536
    const val LEASE = 30000L
    const val RETENTION = 86400000L
    const val MAX_OBJECT_BYTES = 16L * 1024 * 1024
    val hardLimits = linkedMapOf("maxObjectBytes" to MAX_OBJECT_BYTES, "maxInFlightObjects" to 4L, "maxFrameBytes" to 131084L, "maxManifestPageBytes" to 131084L, "maxManifestPageEntries" to 16L, "maxMetadataBytes" to 16384L, "maxPlanObjects" to 4096L, "maxManifestBytes" to 16777216L, "maxRangesPerPage" to 128L, "maxResultsPerPage" to 16L, "maxStagingBytes" to 1610612736L, "maxJournalBytes" to 67108864L, "maxActiveJobs" to 2L, "maxInFlightChunks" to 8L, "maxInFlightBytes" to 524288L, "maxControlHeaderBytes" to 131072L, "maxPackageBytes" to 536870912L)
    val limitKeys = hardLimits.keys.toList()
    const val BOOK_STATE = "bookState"
    const val BOOK_SEGMENT = "bookPackageSegment"
    const val BOOK_COMMIT = "bookPackageCommit"
    const val BOOK_READING = "bookReading"
    const val BOOK_SEGMENT_BYTES = 16 * 1024 * 1024L
    const val BOOK_MAX_BYTES = 512 * 1024 * 1024L
    val bookKinds = setOf(BOOK_STATE, BOOK_SEGMENT, BOOK_COMMIT, BOOK_READING)
    val inheritedKinds = listOf("articleBody", "articleMetadata", "noteAsset", "readerMetadata", "readerResource", "rssSource")
    val kinds = inheritedKinds + bookKinds
    val phases = listOf("prepare", "begin", "manifestPage", "missing", "putChunk", "apply", "complete", "cancel")
    internal fun sorted(xs: List<String>) { if (xs.zipWithNext().any { it.first >= it.second }) fail("PLAN_CONFLICT") }
    private fun one(v: String, choices: List<String>) { if (v !in choices) fail() }
    fun limits(o: JSONObject) {
        B2Json.exact(o, *limitKeys.toTypedArray())
        hardLimits.forEach { (k, max) -> if (B2Json.integer(o.get(k), max) == 0L) fail("CAPABILITY_MISMATCH") }
        if (o.n("maxFrameBytes") < 65628 || o.n("maxInFlightBytes") < CHUNK ||
            o.n("maxInFlightChunks") !in listOf(1L, 8L) || o.n("maxInFlightBytes") != o.n("maxInFlightChunks") * CHUNK) fail("CAPABILITY_MISMATCH")

    }
    private fun kindRows(o: JSONObject, cap: Boolean) {
        val ks = o.a("kinds").values().map { raw ->
            val k = B2Json.exact(raw, *(if (cap) arrayOf("kind", "schemaVersion", "send", "receive") else arrayOf("kind", "schemaVersion")))
            one(k.s("kind"), kinds); if (k.n("schemaVersion") != 1L) fail("UNSUPPORTED_KIND")
            if (cap) { B2Json.bool(k.get("send")); B2Json.bool(k.get("receive")) }; k.s("kind")
        }
        if (ks.isEmpty() || ks.size > kinds.size) fail("CAPABILITY_MISMATCH")
        sorted(ks)
        if ("articleBody" in ks && "articleMetadata" !in ks || "readerResource" in ks && "readerMetadata" !in ks) fail("CAPABILITY_MISMATCH")
        limits(o.o("limits"))
    }
    fun s4Limits() = JSONObject(hardLimits as Map<*, *>)
    fun s3Limits() = JSONObject(hardLimits as Map<*, *>).put("maxStagingBytes", 134217728)
    fun s2Limits() = s3Limits().put("maxInFlightChunks", 1).put("maxInFlightBytes", CHUNK)
    fun capability(o: JSONObject) { B2Json.exact(o, "version", "profile", "kinds", "chunkSizes", "limits"); if (o.n("version") != 2L || o.s("profile") != "merge-b2" || o.a("chunkSizes").length() != 1 || B2Json.integer(o.a("chunkSizes").get(0)) != 65536L) fail("CAPABILITY_MISMATCH"); kindRows(o, true) }
    fun selection(o: JSONObject) { B2Json.exact(o, "profile", "kinds", "chunkBytes", "limits"); if (o.s("profile") != "merge-b2" || o.n("chunkBytes") != 65536L) fail("CAPABILITY_MISMATCH"); kindRows(o, false) }
    fun snapshot(o: JSONObject) {
        B2Json.exact(o, "snapshotId", "manifestDigest", "fromExclusive", "toInclusive")
        if (!o.s("snapshotId").matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) fail()
        B2Json.hex(o.get("manifestDigest")); if (o.n("fromExclusive") > o.n("toInclusive")) fail("PLAN_CONFLICT")
    }
    fun bindings(bs: org.json.JSONArray) {
        if (bs.length() !in 1..2) fail()
        val rows = bs.values().map { B2Json.exact(it, "direction", "jobId", "intent", "selection") }
        rows.forEach { one(it.s("direction"), listOf("phoneToWatch", "watchToPhone")); one(it.s("intent"), listOf("create", "resume")); B2Json.hex(it.get("jobId"), 32); selection(it.o("selection")) }
        sorted(rows.map { it.s("direction") }); if (rows.map { it.s("jobId") }.distinct().size != rows.size) fail()
    }
    fun descriptor(o: JSONObject, lim: JSONObject = JSONObject(hardLimits as Map<*, *>)): JSONObject {
        B2Json.exact(o, "objectId", "kind", "schemaVersion", "businessId", "contentVersion", "encoding", "size", "sha256", "chunkHashes", "metadataUtf8", "metadataSha256", "chunkBytes", "dependsOn")
        B2Json.hex(o.get("objectId")); one(o.s("kind"), kinds); if (o.n("schemaVersion") != 1L) fail("UNSUPPORTED_KIND")
        B2Json.text(o.get("businessId"), 2048); B2Json.text(o.get("contentVersion"), 256)
        val size = B2Json.integer(o.get("size"), lim.n("maxObjectBytes")); B2Json.hex(o.get("sha256")); B2Json.hex(o.get("metadataSha256"))
        val hs = o.a("chunkHashes").strings(); if (hs.size.toLong() != (size + CHUNK - 1) / CHUNK) fail("OBJECT_CONFLICT"); hs.forEach { B2Json.hex(it) }
        val deps = o.a("dependsOn").strings(); if (deps.size > (if (o.s("kind") == BOOK_COMMIT) 33 else 16) || o.s("objectId") in deps) fail("PLAN_CONFLICT"); sorted(deps); deps.forEach { B2Json.hex(it) }
        val enc = when (o.s("kind")) { "articleBody" -> "article-v14"; "readerResource", "noteAsset", BOOK_STATE, BOOK_SEGMENT, BOOK_READING -> "raw"; else -> "none" }
        if (o.s("encoding") != enc || enc == "none" && size != 0L) fail("OBJECT_CONFLICT")
        val metadata = B2Json.utf8(o.s("metadataUtf8")); if (metadata.size > lim.n("maxMetadataBytes") || o.n("chunkBytes") != 65536L) fail("LIMIT_EXCEEDED"); B2Json.parse(metadata, false)
        if (B2Digests.hash(metadata) != o.s("metadataSha256") || size == 0L && B2Digests.hash(byteArrayOf()) != o.s("sha256")) fail("HASH_MISMATCH")
        if (B2Digests.objectId(o) != o.s("objectId")) fail("OBJECT_CONFLICT")
        return o
    }
    fun payload(phase: String, p: JSONObject) {
        fun exact(vararg keys: String) { B2Json.exact(p, *keys) }
        fun hex(k: String, n: Int = 64) { B2Json.hex(p.get(k), n) }
        when (phase) {
            "prepare" -> if (p.s("step") == "offer") {
                exact("step", "accountScope", "phoneDeviceId", "watchDeviceId", "phoneCapabilities", "phoneSnapshot", "watchSnapshot"); hex("accountScope"); B2Json.text(p.get("phoneDeviceId"), 128); B2Json.text(p.get("watchDeviceId"), 128); capability(p.o("phoneCapabilities")); snapshot(p.o("phoneSnapshot")); if (!p.nil("watchSnapshot")) snapshot(p.o("watchSnapshot"))
            } else { exact("step", "offerRequestId", "bindings", "preparationDigest"); if (p.s("step") != "confirm") fail(); hex("offerRequestId", 32); hex("preparationDigest"); bindings(p.a("bindings")) }
            "begin" -> {
                exact("intent", "accountScope", "phoneDeviceId", "watchDeviceId", "direction", "phoneSnapshot", "watchSnapshot", "selection", "selectionDigest", "objectCount", "objectsRoot", "pageObjectCounts")
                one(p.s("intent"), listOf("create", "resume")); one(p.s("direction"), listOf("phoneToWatch", "watchToPhone")); hex("accountScope"); B2Json.text(p.get("phoneDeviceId"), 128); B2Json.text(p.get("watchDeviceId"), 128)
                snapshot(p.o("phoneSnapshot")); snapshot(p.o("watchSnapshot")); selection(p.o("selection")); hex("selectionDigest"); hex("objectsRoot")
                if (B2Digests.selection(p.o("selection")) != p.s("selectionDigest")) fail("PLAN_CONFLICT")
                B2Json.integer(p.get("objectCount"), p.o("selection").o("limits").n("maxPlanObjects"))
                val cs = p.a("pageObjectCounts").values().map { B2Json.integer(it, p.o("selection").o("limits").n("maxManifestPageEntries")) }
                if (cs.size > 4096 || cs.any { it == 0L } || cs.sum() != p.n("objectCount")) fail("PLAN_CONFLICT")
            }
            "manifestPage" -> { exact("pageIndex", "startIndex", "previousPageDigest", "pageDigest", "entries"); p.n("pageIndex"); p.n("startIndex"); hex("previousPageDigest"); hex("pageDigest"); if (p.a("entries").length() !in 1..16) fail(); p.a("entries").values().forEach { descriptor(it as? JSONObject ?: fail()) } }
            "missing" -> { exact("afterObjectId", "afterChunkIndex", "limit"); if (!p.nil("afterObjectId")) hex("afterObjectId"); if (!p.nil("afterChunkIndex")) B2Json.integer(p.get("afterChunkIndex"), 256); B2Json.integer(p.get("limit"), 16); if (p.nil("afterObjectId") && !p.nil("afterChunkIndex") || p.n("limit") == 0L && (!p.nil("afterObjectId") || !p.nil("afterChunkIndex"))) fail() }
            "putChunk" -> { exact("objectId", "chunkIndex"); hex("objectId"); B2Json.integer(p.get("chunkIndex"), 255) }
            "apply" -> { exact("objectId"); hex("objectId") }
            "complete" -> { exact("objectCount", "receiptsRoot", "ackToInclusive"); B2Json.integer(p.get("objectCount"), 4096); hex("receiptsRoot"); p.n("ackToInclusive") }
            "cancel" -> { exact("reason"); one(p.s("reason"), listOf("USER_CANCEL", "ACCOUNT_CHANGED", "LOCAL_ABORT", "SOURCE_CHANGED")) }
            else -> fail("UNSUPPORTED_PHASE")
        }
    }
    fun request(phase: String, job: String?, plan: String?, epoch: Long?, payload: JSONObject, id: String = java.util.UUID.randomUUID().toString().replace("-", "")): JSONObject = obj("action" to ACTION, "version" to 2, "phase" to phase, "type" to "request", "jobId" to job, "requestId" to id, "requestDigest" to B2Digests.request(job, plan, phase, payload), "epoch" to epoch, "planDigest" to plan, "payload" to copy(payload))
    fun envelope(o: JSONObject): JSONObject {
        val response = o.s("type") == "response"
        B2Json.exact(o, *(listOf("action", "version", "phase", "type", "jobId", "requestId", "requestDigest", "epoch", "planDigest") + if (!response) listOf("payload") else listOf("success", "result") + if (o.opt("success") == false) listOf("error") else emptyList()).toTypedArray())
        if (o.s("action") != ACTION || o.n("version") != 2L) fail("UNSUPPORTED_VERSION")
        one(o.s("phase"), phases); one(o.s("type"), listOf("request", "response")); B2Json.hex(o.get("requestId"), 32); B2Json.hex(o.get("requestDigest"))
        if (o.s("phase") == "prepare") { if (!o.nil("jobId") || !o.nil("planDigest") || !o.nil("epoch")) fail() } else {
            B2Json.hex(o.get("jobId"), 32); B2Json.hex(o.get("planDigest"))
            if (o.s("phase") == "begin" && !response) { if (!o.nil("epoch")) fail() }
            else if (o.nil("epoch")) { if (!response || o.get("success") != false) fail() } else if (o.n("epoch") == 0L) fail()
        }
        if (!response) {
            val digest = B2Digests.request(if (o.nil("jobId")) null else o.s("jobId"), if (o.nil("planDigest")) null else o.s("planDigest"), o.s("phase"), o.o("payload"))
            if (digest != o.s("requestDigest")) fail("REQUEST_CONFLICT")
        } else {
            B2Json.bool(o.get("success"))
            if (o.getBoolean("success")) B2Results.validate(o.s("phase"), o.o("result")) else { if (!o.nil("result")) fail(); B2Results.error(o.o("error")) }
        }
        return o
    }
    fun read(input: InputStream): JSONObject = (B2Frames.read(input) as? B2Frame.Control ?: fail()).value
    fun write(output: OutputStream, value: JSONObject) { B2Frames.write(output, B2Frame.Control(value)) }
}
