package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONArray
import org.json.JSONObject

object B2Negotiation {
    /** Explicit integration seam. Does not enable C or modify the legacy ACK loop. */
    fun propose(probe: JSONObject, ready: Boolean): JSONObject = copy(probe).also {
        if (ready) it.put("supportedLibraryBinaryVersions", JSONArray(listOf(2)))
        else it.remove("supportedLibraryBinaryVersions")
    }
    fun echoed(probe: JSONObject): JSONObject? {
        if (!probe.has("libraryBinary")) return null
        return copy(probe.o("libraryBinary")).also { B2Wire.capability(it) }
    }
    /** Capabilities come ONLY from transaction adapters installed on this connection.
     * Never infer receive readiness from application version or the watch's rows. */
    fun capability(sendKinds: Set<String>, receiveKinds: Set<String>, limits: JSONObject = B2Wire.s3Limits()): JSONObject {
        val rows = (sendKinds + receiveKinds).sorted().map { obj("kind" to it, "schemaVersion" to 1, "send" to (it in sendKinds), "receive" to (it in receiveKinds)) }
        return obj("version" to 2, "profile" to "merge-b2", "chunkSizes" to JSONArray(listOf(65536)), "kinds" to JSONArray(rows), "limits" to copy(limits)).also { B2Wire.capability(it) }
    }
    fun select(phone: JSONObject, watch: JSONObject, direction: String, original: JSONObject? = null): JSONObject? {
        B2Wire.capability(phone); B2Wire.capability(watch)
        val ps = phone.a("kinds").values().map { it as JSONObject }; val ws = watch.a("kinds").values().map { it as JSONObject }
        val ks = ps.filter { p -> ws.any { w -> w.s("kind") == p.s("kind") && if (direction == "phoneToWatch") p.getBoolean("send") && w.getBoolean("receive") else p.getBoolean("receive") && w.getBoolean("send") } }.map { it.s("kind") }.toMutableSet()
        if ("articleMetadata" !in ks) ks.remove("articleBody"); if ("readerMetadata" !in ks) ks.remove("readerResource")
        if (!B2Wire.bookKinds.all { kind ->
                listOf(ps, ws).all { rows -> rows.any { it.s("kind") == kind && it.getBoolean("send") && it.getBoolean("receive") } }
            }) ks.removeAll(B2Wire.bookKinds)
        if (ks.isEmpty()) return null
        val limits = JSONObject(); B2Wire.limitKeys.forEach { limits.put(it, minOf(phone.o("limits").n(it), watch.o("limits").n(it))) }
        if (original != null) {
            B2Wire.selection(original)
            if (original.a("kinds").values().any { (it as JSONObject).s("kind") !in ks } || B2Wire.limitKeys.any { original.o("limits").n(it) > limits.n(it) }) fail("CAPABILITY_MISMATCH")
            return copy(original)
        }
        return obj("profile" to "merge-b2", "chunkBytes" to 65536, "kinds" to JSONArray(ks.sorted().map { obj("kind" to it, "schemaVersion" to 1) }), "limits" to limits).also { B2Wire.selection(it) }
    }
}
