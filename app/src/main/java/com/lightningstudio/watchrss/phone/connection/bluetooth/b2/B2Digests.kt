package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

object B2Digests {
    private const val PREFIX = "wrss.library-binary.v2/"
    fun hash(bytes: ByteArray) = B2Frames.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun lp(value: String): ByteArray = ByteArrayOutputStream().also { out -> val b = B2Json.utf8(value); DataOutputStream(out).apply { writeInt(b.size); write(b) } }.toByteArray()
    fun d(domain: String, parts: List<String>): String = hash(ByteArrayOutputStream().also { out -> out.write(lp(domain)); DataOutputStream(out).writeInt(parts.size); parts.forEach { out.write(lp(it)) } }.toByteArray())
    private fun digest(domain: String, parts: List<String>) = d(PREFIX + domain, parts)
    fun account(base: String, user: String) = d("wrss.library-resume.v1/account", listOf(base, user))
    fun snapshot(s: JSONObject?): List<String> = if (s == null) listOf("none") else listOf("some", s.s("snapshotId"), s.s("manifestDigest"), s.n("fromExclusive").toString(), s.n("toInclusive").toString())
    fun selection(s: JSONObject): String { B2Wire.selection(s); return digest("selection", listOf(s.s("profile"), s.n("chunkBytes").toString(), s.a("kinds").length().toString()) + s.a("kinds").values().flatMap { val k = it as JSONObject; listOf(k.s("kind"), k.n("schemaVersion").toString()) } + B2Wire.limitKeys.map { s.o("limits").n(it).toString() }) }
    fun capability(c: JSONObject): String { B2Wire.capability(c); return digest("capability", listOf(c.n("version").toString(), c.s("profile"), c.a("kinds").length().toString()) + c.a("kinds").values().flatMap { val k = it as JSONObject; listOf(k.s("kind"), k.n("schemaVersion").toString(), k.get("send").toString(), k.get("receive").toString()) } + listOf(c.a("chunkSizes").length().toString()) + c.a("chunkSizes").values().map { B2Json.integer(it).toString() } + B2Wire.limitKeys.map { c.o("limits").n(it).toString() }) }
    fun offer(p: JSONObject, watchCapabilities: JSONObject, watchSnapshot: JSONObject) = digest("offer", listOf(p.s("accountScope"), p.s("phoneDeviceId"), p.s("watchDeviceId"), capability(p.o("phoneCapabilities")), capability(watchCapabilities)) + snapshot(p.o("phoneSnapshot")) + snapshot(watchSnapshot))
    fun preparation(offer: String, bindings: List<JSONObject>) = digest("prepare", listOf(offer, bindings.size.toString()) + bindings.flatMap { listOf(it.s("direction"), it.s("jobId"), it.s("intent"), selection(it.o("selection"))) })
    fun objectId(o: JSONObject) = digest("object", listOf(o.s("kind"), "1", o.s("businessId"), o.s("contentVersion"), o.s("encoding"), o.n("size").toString(), o.n("chunkBytes").toString(), o.s("sha256"), o.a("chunkHashes").length().toString()) + o.a("chunkHashes").strings() + listOf(o.s("metadataSha256"), o.a("dependsOn").length().toString()) + o.a("dependsOn").strings())
    fun objects(ids: List<String>) = digest("objects", listOf(ids.size.toString()) + ids)
    fun plan(p: JSONObject) = digest("plan", listOf(p.s("accountScope"), p.s("phoneDeviceId"), p.s("watchDeviceId"), p.s("direction"), p.s("selectionDigest")) + listOf("phoneSnapshot", "watchSnapshot").flatMap { val s = p.o(it); listOf(s.s("snapshotId"), s.s("manifestDigest"), s.n("fromExclusive").toString(), s.n("toInclusive").toString()) } + listOf(p.n("objectCount").toString(), p.s("objectsRoot")))
    fun page(plan: String, p: JSONObject) = digest("page", listOf(plan, p.n("pageIndex").toString(), p.n("startIndex").toString(), p.a("entries").length().toString()) + p.a("entries").values().map { (it as JSONObject).s("objectId") } + p.s("previousPageDigest"))
    fun args(phase: String, p: JSONObject): List<String> {
        B2Wire.payload(phase, p)
        return when (phase) {
            "prepare" -> if (p.s("step") == "offer") listOf("offer", p.s("accountScope"), p.s("phoneDeviceId"), p.s("watchDeviceId"), capability(p.o("phoneCapabilities"))) + snapshot(p.o("phoneSnapshot")) + snapshot(if (p.nil("watchSnapshot")) null else p.o("watchSnapshot")) else listOf("confirm", p.s("offerRequestId"), p.s("preparationDigest"))
            "begin" -> listOf(p.s("intent"), p.s("accountScope"), p.s("phoneDeviceId"), p.s("watchDeviceId"), p.s("direction"), p.s("selectionDigest")) + listOf("phoneSnapshot", "watchSnapshot").flatMap { val s = p.o(it); listOf(s.s("snapshotId"), s.s("manifestDigest"), s.n("fromExclusive").toString(), s.n("toInclusive").toString()) } + listOf(p.n("objectCount").toString(), p.s("objectsRoot"), p.a("pageObjectCounts").length().toString()) + p.a("pageObjectCounts").values().map { B2Json.integer(it).toString() }
            "manifestPage" -> listOf(p.n("pageIndex").toString(), p.n("startIndex").toString(), p.s("previousPageDigest"), p.s("pageDigest"), p.a("entries").length().toString()) + p.a("entries").values().map { (it as JSONObject).s("objectId") }
            "missing" -> listOf(if (p.nil("afterObjectId")) "" else p.s("afterObjectId"), if (p.nil("afterChunkIndex")) "" else p.n("afterChunkIndex").toString(), p.n("limit").toString())
            "putChunk" -> listOf(p.s("objectId"), p.n("chunkIndex").toString())
            "apply" -> listOf(p.s("objectId"))
            "complete" -> listOf(p.n("objectCount").toString(), p.s("receiptsRoot"), p.n("ackToInclusive").toString())
            "cancel" -> listOf(p.s("reason"))
            else -> fail("UNSUPPORTED_PHASE")
        }
    }
    fun request(job: String?, plan: String?, phase: String, p: JSONObject) = digest("request", listOf(job ?: "", plan ?: "", phase) + args(phase, p))
    fun receipt(r: JSONObject, oid: String, receiver: String, outcome: String): JSONObject {
        if (outcome !in listOf("merged", "retainedLocal", "alreadyPresent")) fail("BUSINESS_REJECTED")
        val o = obj("jobId" to r.s("jobId"), "planDigest" to r.s("planDigest"), "objectId" to oid, "applyRequestId" to r.s("requestId"), "applyRequestDigest" to r.s("requestDigest"), "receiverDeviceId" to receiver, "outcome" to outcome)
        return o.put("resultDigest", receiptDigest(o))
    }
    fun receiptDigest(r: JSONObject) = digest("applied", listOf("jobId", "planDigest", "objectId", "applyRequestId", "applyRequestDigest", "receiverDeviceId", "outcome").map { r.s(it) })
    fun receipts(rs: List<JSONObject>) = digest("receipts", listOf(rs.size.toString()) + rs.map { it.s("resultDigest") })
    fun completion(job: String, plan: String, count: Long, root: String, ack: Long) = obj("state" to "COMPLETE", "objectCount" to count, "receiptsRoot" to root, "ackToInclusive" to ack, "completionDigest" to digest("complete", listOf(job, plan, count.toString(), root, ack.toString())))
}

/** Semantic progress excludes job IDs, operation IDs, metadata formatting and epochs. */
object B2Progress {
    fun objectVersion(d: JSONObject): String = B2Digests.d("wrss.library-binary.v2/local-progress", listOf(d.s("kind"), d.s("businessId"), d.s("contentVersion"), d.s("sha256")))
    fun applied(d: JSONObject) = "applied:" + objectVersion(d)
    fun chunk(d: JSONObject, index: Int) = "chunk:" + objectVersion(d) + ":" + index + ":" + d.a("chunkHashes").getString(index)
}
