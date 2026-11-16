package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject

/** Explicit pre-begin route decision. This never falls back after begin acceptance. */
object B2Planner {
    sealed interface Admission {
        data class Ready(val pageObjectCounts: List<Int>, val journalReservation: Long) : Admission
        data class UseLegacy(val reason: String) : Admission
    }
    fun preflight(mode: String, selection: JSONObject, objects: List<JSONObject>, journalAvailable: Long = 67108864): Admission {
        if (mode != "merge") return Admission.UseLegacy("unsupported_mode")
        B2Wire.selection(selection)
        val limits = selection.o("limits")
        val kinds = selection.a("kinds").values().map { (it as JSONObject).s("kind") }.toSet()
        if (objects.size > limits.n("maxPlanObjects")) return Admission.UseLegacy("plan_limit")
        var manifestBytes = 0L; var count = 0; var bytes = 0L
        val counts = mutableListOf<Int>()
        val maxPage = minOf(limits.n("maxManifestPageBytes"), limits.n("maxFrameBytes"))
        for (d in objects) {
            if (d.s("kind") !in kinds) return Admission.UseLegacy("unsupported_kind")
            if (d.n("size") > limits.n("maxObjectBytes")) return Admission.UseLegacy("object_limit")
            if (B2Json.utf8(d.s("metadataUtf8")).size > limits.n("maxMetadataBytes")) return Admission.UseLegacy("metadata_limit")
            B2Wire.descriptor(d, limits)
            val n = B2Json.utf8(d.toString()).size.toLong(); manifestBytes += n
            if (manifestBytes > limits.n("maxManifestBytes") || n + 4096 > maxPage) return Admission.UseLegacy("manifest_limit")
            if (count > 0 && (count.toLong() == limits.n("maxManifestPageEntries") || bytes + n + 4096 > maxPage)) { counts.add(count); count = 0; bytes = 0 }
            count++; bytes += n
        }
        if (count > 0) counts.add(count)
        B2Wire.sorted(objects.map { it.s("objectId") }); B2Receiver.validateGraph(objects)
        val reservation = 8192 + objects.size * 4096L + manifestBytes + counts.size * 2048L
        if (reservation > minOf(journalAvailable, limits.n("maxJournalBytes"))) return Admission.UseLegacy("journal_budget")
        return Admission.Ready(counts, reservation)
    }
}
