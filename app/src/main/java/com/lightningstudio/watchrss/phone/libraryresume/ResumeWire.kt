package com.lightningstudio.watchrss.phone.libraryresume

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

object ResumeWire {
    private val receivedSizes = java.util.WeakHashMap<JSONObject, Int>()
    internal fun retainSize(o: JSONObject, bytes: Int) { synchronized(receivedSizes) { receivedSizes[o] = bytes } }
    fun frameBytes(o: JSONObject): Int = synchronized(receivedSizes) { receivedSizes[o] } ?: ResumeJson.utf8(o.toString()).size
    const val ACTION = "syncLibraryResume"
    const val CHUNK = 65536
    const val LEASE = 30000L
    const val RETENTION = 86400000L
    val hardLimits = linkedMapOf("maxObjectBytes" to 16777216L, "maxInFlightObjects" to 4L, "maxFrameBytes" to 262144L, "maxManifestPageBytes" to 131072L, "maxManifestPageEntries" to 16L, "maxMetadataBytes" to 16384L, "maxPlanObjects" to 4096L, "maxManifestBytes" to 16777216L, "maxRangesPerPage" to 128L, "maxResultsPerPage" to 16L, "maxStagingBytes" to 134217728L, "maxJournalBytes" to 67108864L, "maxActiveJobs" to 2L)
    val limitKeys = hardLimits.keys.toList()
    val kinds = listOf("articleBody", "articleMetadata", "noteAsset", "readerMetadata", "readerResource", "rssSource")
    val phases = listOf("prepare", "begin", "manifestPage", "missing", "putChunk", "apply", "complete", "cancel")
    internal fun sorted(xs: List<String>) { if (xs.zipWithNext().any { it.first >= it.second }) fail("PLAN_CONFLICT") }
    private fun one(v: String, choices: List<String>) { if (v !in choices) fail() }
    fun limits(o: JSONObject) {
        ResumeJson.exact(o, *limitKeys.toTypedArray())
        hardLimits.forEach { (k, max) -> if (ResumeJson.integer(o.get(k), max) == 0L) fail("CAPABILITY_MISMATCH") }
        // The largest mandatory 64-KiB put frame must fit, including a safe-integer epoch.
        val probe = request("putChunk", "0".repeat(32), "0".repeat(64), ResumeJson.MAX_SAFE, obj("objectId" to "0".repeat(64), "chunkIndex" to 255, "dataBase64" to ("A".repeat(87382) + "==")))
        if (o.n("maxFrameBytes") < ResumeJson.utf8(probe.toString()).size) fail("CAPABILITY_MISMATCH")
    }
    private fun kindRows(o: JSONObject, cap: Boolean) {
        val ks = o.a("kinds").values().map { raw ->
            val k = ResumeJson.exact(raw, *(if (cap) arrayOf("kind", "schemaVersion", "send", "receive") else arrayOf("kind", "schemaVersion")))
            one(k.s("kind"), kinds); if (k.n("schemaVersion") != 1L) fail("UNSUPPORTED_KIND")
            if (cap) { ResumeJson.bool(k.get("send")); ResumeJson.bool(k.get("receive")) }; k.s("kind")
        }
        if (ks.isEmpty() || ks.size > 6) fail("CAPABILITY_MISMATCH")
        sorted(ks)
        if ("articleBody" in ks && "articleMetadata" !in ks || "readerResource" in ks && "readerMetadata" !in ks) fail("CAPABILITY_MISMATCH")
        limits(o.o("limits"))
    }
    fun capability(o: JSONObject) { ResumeJson.exact(o, "version", "kinds", "limits"); if (o.n("version") != 1L) fail("CAPABILITY_MISMATCH"); kindRows(o, true) }
    fun selection(o: JSONObject) { ResumeJson.exact(o, "kinds", "limits"); kindRows(o, false) }
    fun snapshot(o: JSONObject) {
        ResumeJson.exact(o, "snapshotId", "manifestDigest", "fromExclusive", "toInclusive")
        if (!o.s("snapshotId").matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) fail()
        ResumeJson.hex(o.get("manifestDigest")); if (o.n("fromExclusive") > o.n("toInclusive")) fail("PLAN_CONFLICT")
    }
    fun bindings(bs: org.json.JSONArray) {
        if (bs.length() !in 1..2) fail()
        val rows = bs.values().map { ResumeJson.exact(it, "direction", "jobId", "intent", "selection") }
        rows.forEach { one(it.s("direction"), listOf("phoneToWatch", "watchToPhone")); one(it.s("intent"), listOf("create", "resume")); ResumeJson.hex(it.get("jobId"), 32); selection(it.o("selection")) }
        sorted(rows.map { it.s("direction") }); if (rows.map { it.s("jobId") }.distinct().size != rows.size) fail()
    }
    fun descriptor(o: JSONObject, lim: JSONObject = JSONObject(hardLimits as Map<*, *>)): JSONObject {
        ResumeJson.exact(o, "objectId", "kind", "schemaVersion", "businessId", "contentVersion", "encoding", "size", "sha256", "chunkHashes", "metadataBase64", "metadataSha256", "dependsOn")
        ResumeJson.hex(o.get("objectId")); one(o.s("kind"), kinds); if (o.n("schemaVersion") != 1L) fail("UNSUPPORTED_KIND")
        ResumeJson.text(o.get("businessId"), 2048); ResumeJson.text(o.get("contentVersion"), 256)
        val size = ResumeJson.integer(o.get("size"), lim.n("maxObjectBytes")); ResumeJson.hex(o.get("sha256")); ResumeJson.hex(o.get("metadataSha256"))
        val hs = o.a("chunkHashes").strings(); if (hs.size.toLong() != (size + CHUNK - 1) / CHUNK) fail("OBJECT_CONFLICT"); hs.forEach { ResumeJson.hex(it) }
        val deps = o.a("dependsOn").strings(); if (deps.size > 16 || o.s("objectId") in deps) fail("PLAN_CONFLICT"); sorted(deps); deps.forEach { ResumeJson.hex(it) }
        val enc = when (o.s("kind")) { "articleBody" -> "article-v14"; "readerResource", "noteAsset" -> "raw"; else -> "none" }
        if (o.s("encoding") != enc || enc == "none" && size != 0L) fail("OBJECT_CONFLICT")
        val metadata = ResumeJson.b64(o.get("metadataBase64"), lim.n("maxMetadataBytes").toInt()); ResumeJson.parse(metadata, false)
        if (ResumeDigests.hash(metadata) != o.s("metadataSha256") || size == 0L && ResumeDigests.hash(byteArrayOf()) != o.s("sha256")) fail("HASH_MISMATCH")
        if (ResumeDigests.objectId(o) != o.s("objectId")) fail("OBJECT_CONFLICT")
        return o
    }
    fun payload(phase: String, p: JSONObject) {
        fun exact(vararg keys: String) { ResumeJson.exact(p, *keys) }
        fun hex(k: String, n: Int = 64) { ResumeJson.hex(p.get(k), n) }
        when (phase) {
            "prepare" -> if (p.s("step") == "offer") {
                exact("step", "accountScope", "phoneDeviceId", "watchDeviceId", "phoneCapabilities", "phoneSnapshot", "watchSnapshot"); hex("accountScope"); ResumeJson.text(p.get("phoneDeviceId"), 128); ResumeJson.text(p.get("watchDeviceId"), 128); capability(p.o("phoneCapabilities")); snapshot(p.o("phoneSnapshot")); if (!p.nil("watchSnapshot")) snapshot(p.o("watchSnapshot"))
            } else { exact("step", "offerRequestId", "bindings", "preparationDigest"); if (p.s("step") != "confirm") fail(); hex("offerRequestId", 32); hex("preparationDigest"); bindings(p.a("bindings")) }
            "begin" -> {
                exact("intent", "accountScope", "phoneDeviceId", "watchDeviceId", "direction", "phoneSnapshot", "watchSnapshot", "selection", "selectionDigest", "objectCount", "objectsRoot", "pageObjectCounts")
                one(p.s("intent"), listOf("create", "resume")); one(p.s("direction"), listOf("phoneToWatch", "watchToPhone")); hex("accountScope"); ResumeJson.text(p.get("phoneDeviceId"), 128); ResumeJson.text(p.get("watchDeviceId"), 128)
                snapshot(p.o("phoneSnapshot")); snapshot(p.o("watchSnapshot")); selection(p.o("selection")); hex("selectionDigest"); hex("objectsRoot")
                if (ResumeDigests.selection(p.o("selection")) != p.s("selectionDigest")) fail("PLAN_CONFLICT")
                ResumeJson.integer(p.get("objectCount"), p.o("selection").o("limits").n("maxPlanObjects"))
                val cs = p.a("pageObjectCounts").values().map { ResumeJson.integer(it, p.o("selection").o("limits").n("maxManifestPageEntries")) }
                if (cs.size > 4096 || cs.any { it == 0L } || cs.sum() != p.n("objectCount")) fail("PLAN_CONFLICT")
            }
            "manifestPage" -> { exact("pageIndex", "startIndex", "previousPageDigest", "pageDigest", "entries"); p.n("pageIndex"); p.n("startIndex"); hex("previousPageDigest"); hex("pageDigest"); if (p.a("entries").length() !in 1..16) fail(); p.a("entries").values().forEach { descriptor(it as? JSONObject ?: fail()) } }
            "missing" -> { exact("afterObjectId", "afterChunkIndex", "limit"); if (!p.nil("afterObjectId")) hex("afterObjectId"); if (!p.nil("afterChunkIndex")) ResumeJson.integer(p.get("afterChunkIndex"), 256); ResumeJson.integer(p.get("limit"), 16); if (p.nil("afterObjectId") && !p.nil("afterChunkIndex") || p.n("limit") == 0L && (!p.nil("afterObjectId") || !p.nil("afterChunkIndex"))) fail() }
            "putChunk" -> { exact("objectId", "chunkIndex", "dataBase64"); hex("objectId"); ResumeJson.integer(p.get("chunkIndex"), 255); ResumeJson.b64(p.get("dataBase64"), CHUNK) }
            "apply" -> { exact("objectId"); hex("objectId") }
            "complete" -> { exact("objectCount", "receiptsRoot", "ackToInclusive"); ResumeJson.integer(p.get("objectCount"), 4096); hex("receiptsRoot"); p.n("ackToInclusive") }
            "cancel" -> { exact("reason"); one(p.s("reason"), listOf("USER_CANCEL", "ACCOUNT_CHANGED", "LOCAL_ABORT", "SOURCE_CHANGED")) }
            else -> fail("UNSUPPORTED_PHASE")
        }
    }
    fun request(phase: String, job: String?, plan: String?, epoch: Long?, payload: JSONObject, id: String = java.util.UUID.randomUUID().toString().replace("-", "")): JSONObject = obj("action" to ACTION, "version" to 1, "phase" to phase, "type" to "request", "jobId" to job, "requestId" to id, "requestDigest" to ResumeDigests.request(job, plan, phase, payload), "epoch" to epoch, "planDigest" to plan, "payload" to copy(payload))
    fun envelope(o: JSONObject): JSONObject {
        val response = o.s("type") == "response"
        ResumeJson.exact(o, *(listOf("action", "version", "phase", "type", "jobId", "requestId", "requestDigest", "epoch", "planDigest") + if (!response) listOf("payload") else listOf("success", "result") + if (o.opt("success") == false) listOf("error") else emptyList()).toTypedArray())
        if (o.s("action") != ACTION || o.n("version") != 1L) fail("UNSUPPORTED_VERSION")
        one(o.s("phase"), phases); one(o.s("type"), listOf("request", "response")); ResumeJson.hex(o.get("requestId"), 32); ResumeJson.hex(o.get("requestDigest"))
        if (o.s("phase") == "prepare") { if (!o.nil("jobId") || !o.nil("planDigest") || !o.nil("epoch")) fail() } else {
            ResumeJson.hex(o.get("jobId"), 32); ResumeJson.hex(o.get("planDigest"))
            if (o.s("phase") == "begin" && !response) { if (!o.nil("epoch")) fail() }
            else if (o.nil("epoch")) { if (!response || o.get("success") != false) fail() } else if (o.n("epoch") == 0L) fail()
        }
        if (!response) {
            val digest = ResumeDigests.request(if (o.nil("jobId")) null else o.s("jobId"), if (o.nil("planDigest")) null else o.s("planDigest"), o.s("phase"), o.o("payload"))
            if (digest != o.s("requestDigest")) fail("REQUEST_CONFLICT")
        } else {
            ResumeJson.bool(o.get("success"))
            if (o.getBoolean("success")) ResumeResults.validate(o.s("phase"), o.o("result")) else { if (!o.nil("result")) fail(); ResumeResults.error(o.o("error")) }
        }
        return o
    }
    fun read(input: InputStream, max: Int = 262144): JSONObject {
        val d = DataInputStream(input); val n = d.readInt(); if (n !in 1..minOf(max, 262144)) fail()
        val bytes = ByteArray(n); d.readFully(bytes); return envelope(ResumeJson.parse(bytes)).also { retainSize(it, n) }
    }
    /** Caller serializes all complete frames, including replies, on one writer lane. */
    fun write(output: OutputStream, value: JSONObject, max: Int = 262144) {
        val bytes = ResumeJson.utf8(envelope(value).toString()); if (bytes.size !in 1..minOf(max, 262144)) fail("LIMIT_EXCEEDED")
        DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() }
    }
}
