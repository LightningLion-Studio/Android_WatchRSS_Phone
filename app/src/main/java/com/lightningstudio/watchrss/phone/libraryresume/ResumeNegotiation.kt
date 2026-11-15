package com.lightningstudio.watchrss.phone.libraryresume

import org.json.JSONArray
import org.json.JSONObject

object ResumeNegotiation {
    /** Explicit integration seam. Does not enable C or modify the legacy ACK loop. */
    fun propose(probe: JSONObject, ready: Boolean): JSONObject = copy(probe).also {
        if (ready) it.put("supportedLibraryResumeVersions", JSONArray(listOf(1)))
        else it.remove("supportedLibraryResumeVersions")
    }
    fun echoed(probe: JSONObject): JSONObject? {
        if (!probe.has("libraryResume")) return null
        return copy(probe.o("libraryResume")).also { ResumeWire.capability(it) }
    }
    /** Capabilities come ONLY from transaction adapters installed on this connection.
     * Never infer receive readiness from application version or the watch's rows. */
    fun capability(sendKinds: Set<String>, receiveKinds: Set<String>, limits: JSONObject = JSONObject(ResumeWire.hardLimits as Map<*, *>)): JSONObject {
        val rows = (sendKinds + receiveKinds).sorted().map { obj("kind" to it, "schemaVersion" to 1, "send" to (it in sendKinds), "receive" to (it in receiveKinds)) }
        return obj("version" to 1, "kinds" to JSONArray(rows), "limits" to copy(limits)).also { ResumeWire.capability(it) }
    }
    fun select(phone: JSONObject, watch: JSONObject, direction: String, original: JSONObject? = null): JSONObject? {
        ResumeWire.capability(phone); ResumeWire.capability(watch)
        val ps = phone.a("kinds").values().map { it as JSONObject }; val ws = watch.a("kinds").values().map { it as JSONObject }
        val ks = ps.filter { p -> ws.any { w -> w.s("kind") == p.s("kind") && if (direction == "phoneToWatch") p.getBoolean("send") && w.getBoolean("receive") else p.getBoolean("receive") && w.getBoolean("send") } }.map { it.s("kind") }.toMutableSet()
        if ("articleMetadata" !in ks) ks.remove("articleBody"); if ("readerMetadata" !in ks) ks.remove("readerResource")
        if (ks.isEmpty()) return null
        val limits = JSONObject(); ResumeWire.limitKeys.forEach { limits.put(it, minOf(phone.o("limits").n(it), watch.o("limits").n(it))) }
        if (original != null) {
            ResumeWire.selection(original)
            if (original.a("kinds").values().any { (it as JSONObject).s("kind") !in ks } || ResumeWire.limitKeys.any { original.o("limits").n(it) > limits.n(it) }) fail("CAPABILITY_MISMATCH")
            return copy(original)
        }
        return obj("kinds" to JSONArray(ks.sorted().map { obj("kind" to it, "schemaVersion" to 1) }), "limits" to limits).also { ResumeWire.selection(it) }
    }
}
