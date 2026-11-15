package com.lightningstudio.watchrss.phone.libraryresume

import org.json.JSONObject

internal object ResumeResults {
    private val jobs = listOf("PLANNING", "READY", "TRANSFERRING", "APPLIED", "COMPLETE", "CANCELLED")
    private val codes = ("ACCOUNT_REQUIRED ACCOUNT_MISMATCH PEER_MISMATCH AUTH_REVOKED INVALID_FRAME UNSUPPORTED_VERSION UNSUPPORTED_PHASE CAPABILITY_MISMATCH REQUEST_CONFLICT JOB_CONFLICT OBJECT_CONFLICT PLAN_CONFLICT RECEIPT_CONFLICT HASH_MISMATCH STALE_EPOCH BUSINESS_REJECTED UNSUPPORTED_KIND LIMIT_EXCEEDED STORAGE_PRESSURE LOCAL_IO JOURNAL_CORRUPT NOT_FOUND EXPIRED SOURCE_CHANGED LEASE_EXPIRED MANIFEST_INCOMPLETE CHUNKS_MISSING DEPENDENCIES_PENDING WINDOW_FULL BUSY USER_CANCELLED JOB_CANCELLED").split(" ")
    fun errorValue(code: String): JSONObject {
        if (code !in codes) fail()
        var cls = "PROTOCOL"; var recovery = "STOP"; var retry = false
        when (code) {
            "ACCOUNT_REQUIRED", "ACCOUNT_MISMATCH", "PEER_MISMATCH", "AUTH_REVOKED" -> cls = "ACCOUNT"
            "BUSINESS_REJECTED", "UNSUPPORTED_KIND", "LIMIT_EXCEEDED" -> cls = "BUSINESS"
            "STORAGE_PRESSURE", "LOCAL_IO", "JOURNAL_CORRUPT" -> cls = "LOCAL"
            "USER_CANCELLED", "JOB_CANCELLED" -> cls = "CANCELLED"
            "NOT_FOUND", "EXPIRED", "SOURCE_CHANGED" -> { cls = "STATE"; recovery = "REPLAN"; retry = true }
            "LEASE_EXPIRED" -> { cls = "STATE"; recovery = "BEGIN"; retry = true }
            "MANIFEST_INCOMPLETE", "CHUNKS_MISSING", "DEPENDENCIES_PENDING" -> { cls = "STATE"; recovery = "QUERY"; retry = true }
            "WINDOW_FULL", "BUSY" -> { cls = "STATE"; recovery = "WAIT"; retry = true }
        }
        return obj("code" to code, "class" to cls, "retryable" to retry, "recovery" to recovery, "retryAfterMillis" to if (recovery == "WAIT") 100 else 0)
    }
    fun error(o: JSONObject) {
        ResumeJson.exact(o, "code", "class", "retryable", "recovery", "retryAfterMillis")
        val expected = errorValue(o.s("code")); listOf("class", "retryable", "recovery").forEach { if (o.get(it) != expected.get(it)) fail() }
        if (o.n("retryAfterMillis") !in (if (o.s("recovery") == "WAIT") 1L..1000L else 0L..0L)) fail()
    }
    fun receipt(o: JSONObject) {
        ResumeJson.exact(o, "jobId", "planDigest", "objectId", "applyRequestId", "applyRequestDigest", "receiverDeviceId", "resultDigest", "outcome")
        listOf("jobId", "applyRequestId").forEach { ResumeJson.hex(o.get(it), 32) }
        listOf("planDigest", "objectId", "applyRequestDigest", "resultDigest").forEach { ResumeJson.hex(o.get(it)) }
        ResumeJson.text(o.get("receiverDeviceId"), 128)
        if (o.s("outcome") !in listOf("merged", "retainedLocal", "alreadyPresent") || ResumeDigests.receiptDigest(o) != o.s("resultDigest")) fail("RECEIPT_CONFLICT")
    }
    fun completion(o: JSONObject) {
        ResumeJson.exact(o, "state", "objectCount", "receiptsRoot", "ackToInclusive", "completionDigest")
        if (o.s("state") != "COMPLETE") fail(); ResumeJson.integer(o.get("objectCount"), 4096); ResumeJson.hex(o.get("receiptsRoot")); o.n("ackToInclusive"); ResumeJson.hex(o.get("completionDigest"))
    }
    fun validate(phase: String, o: JSONObject) {
        fun ex(vararg keys: String) { ResumeJson.exact(o, *keys) }
        fun state() { if (o.s("state") !in jobs) fail() }
        fun completed() { if (o.s("state") == "COMPLETE") completion(o.o("completion")) else if (!o.nil("completion")) fail() }
        when (phase) {
            "prepare" -> if (o.s("step") == "offer") { ex("step", "watchCapabilities", "watchSnapshot", "offerDigest"); ResumeWire.capability(o.o("watchCapabilities")); ResumeWire.snapshot(o.o("watchSnapshot")); ResumeJson.hex(o.get("offerDigest")) } else { ex("step", "preparationDigest"); if (o.s("step") != "confirm") fail(); ResumeJson.hex(o.get("preparationDigest")) }
            "begin" -> { ex("disposition", "selectionDigest", "state", "expiresAtMillis", "nextManifestPage", "completion"); if (o.s("disposition") !in listOf("CREATED", "RESUMED")) fail(); ResumeJson.hex(o.get("selectionDigest")); state(); o.n("expiresAtMillis"); ResumeJson.integer(o.get("nextManifestPage"), 4096); completed() }
            "manifestPage" -> { ex("pageIndex", "pageDigest", "nextManifestPage", "manifestVerified", "state"); ResumeJson.integer(o.get("pageIndex"), 4095); ResumeJson.hex(o.get("pageDigest")); if (o.n("nextManifestPage") !in 1..4096 || o.s("state") !in listOf("PLANNING", "READY") || ResumeJson.bool(o.get("manifestVerified")) != (o.s("state") == "READY")) fail() }
            "missing" -> {
                ex("state", "nextManifestPage", "expiresAtMillis", "items", "next", "done", "completion"); state(); ResumeJson.integer(o.get("nextManifestPage"), 4096); o.n("expiresAtMillis"); completed()
                var ranges = 0
                val ids = o.a("items").values().map { raw ->
                    val r = ResumeJson.exact(raw, "objectId", "state", "missingRanges", "receipt"); ResumeJson.hex(r.get("objectId")); if (r.s("state") !in listOf("PENDING", "PARTIAL", "STAGED", "APPLIED")) fail()
                    var last = -1L
                    r.a("missingRanges").values().forEach { v -> val a = v as? org.json.JSONArray ?: fail(); if (a.length() != 2) fail(); val start = ResumeJson.integer(a.get(0), 255); val end = ResumeJson.integer(a.get(1), 256); if (start >= end || start < last) fail(); last = end; ranges++ }
                    if (r.s("state") in listOf("STAGED", "APPLIED") && r.a("missingRanges").length() != 0) fail()
                    if (r.s("state") == "APPLIED") { receipt(r.o("receipt")); if (r.o("receipt").s("objectId") != r.s("objectId")) fail() } else if (!r.nil("receipt")) fail()
                    r.s("objectId")
                }
                ResumeWire.sorted(ids); if (ids.size > 16 || ranges > 128) fail()
                if (!o.nil("next")) { val n = ResumeJson.exact(o.get("next"), "afterObjectId", "afterChunkIndex"); ResumeJson.hex(n.get("afterObjectId")); if (!n.nil("afterChunkIndex")) ResumeJson.integer(n.get("afterChunkIndex"), 256) }
                if (ResumeJson.bool(o.get("done")) != o.nil("next")) fail()
                if (o.s("state") == "CANCELLED" && (ids.isNotEmpty() || !o.nil("next"))) fail()
            }
            "putChunk" -> { ex("objectId", "chunkIndex", "chunkState", "objectState", "receipt"); ResumeJson.hex(o.get("objectId")); ResumeJson.integer(o.get("chunkIndex"), 255); if (o.s("objectState") == "APPLIED") { if (!o.nil("chunkState")) fail(); receipt(o.o("receipt")); if (o.o("receipt").s("objectId") != o.s("objectId")) fail() } else if (o.s("objectState") !in listOf("PARTIAL", "STAGED") || o.s("chunkState") != "STAGED" || !o.nil("receipt")) fail() }
            "apply" -> { ex("state", "receipt"); if (o.s("state") != "APPLIED") fail(); receipt(o.o("receipt")) }
            "complete" -> completion(o)
            "cancel" -> { ex("state", "alreadyComplete"); if (o.s("state") !in listOf("COMPLETE", "CANCELLED") || ResumeJson.bool(o.get("alreadyComplete")) != (o.s("state") == "COMPLETE")) fail() }
            else -> fail()
        }
    }
    fun answer(request: JSONObject, result: JSONObject?, epoch: Long?, error: String? = null): JSONObject {
        val o = obj(*listOf("action", "version", "phase", "jobId", "requestId", "requestDigest", "planDigest").map { it to request.get(it) }.toTypedArray())
            .put("type", "response").put("epoch", epoch ?: JSONObject.NULL).put("success", error == null).put("result", result ?: JSONObject.NULL)
        if (error != null) o.put("error", errorValue(error))
        return ResumeWire.envelope(o)
    }
}
