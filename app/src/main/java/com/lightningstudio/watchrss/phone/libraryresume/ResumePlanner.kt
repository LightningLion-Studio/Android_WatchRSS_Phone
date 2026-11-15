package com.lightningstudio.watchrss.phone.libraryresume

import org.json.JSONObject

/** Explicit pre-begin route decision. This never falls back after begin acceptance. */
object ResumePlanner {
    sealed interface Admission {
        data class Ready(val pageObjectCounts: List<Int>, val journalReservation: Long) : Admission
        data class UseLegacy(val reason: String) : Admission
    }
    fun preflight(mode: String, selection: JSONObject, objects: List<JSONObject>, journalAvailable: Long = 67108864): Admission {
        if (mode != "merge") return Admission.UseLegacy("unsupported_mode")
        ResumeWire.selection(selection)
        val limits = selection.o("limits")
        val kinds = selection.a("kinds").values().map { (it as JSONObject).s("kind") }.toSet()
        if (objects.size > limits.n("maxPlanObjects")) return Admission.UseLegacy("plan_limit")
        var manifestBytes = 0L; var count = 0; var bytes = 0L
        val counts = mutableListOf<Int>()
        val maxPage = minOf(limits.n("maxManifestPageBytes"), limits.n("maxFrameBytes"))
        for (d in objects) {
            if (d.s("kind") !in kinds) return Admission.UseLegacy("unsupported_kind")
            if (d.n("size") > limits.n("maxObjectBytes")) return Admission.UseLegacy("object_limit")
            // Bound before decoding. Exact canonical Base64 and descriptor checks follow.
            if (d.s("metadataBase64").length.toLong() > ((limits.n("maxMetadataBytes") + 2) / 3) * 4) return Admission.UseLegacy("metadata_limit")
            val metadata = ResumeJson.b64(d.get("metadataBase64"), 16386)
            if (metadata.size > limits.n("maxMetadataBytes")) return Admission.UseLegacy("metadata_limit")
            ResumeWire.descriptor(d, limits)
            val n = ResumeJson.utf8(d.toString()).size.toLong(); manifestBytes += n
            if (manifestBytes > limits.n("maxManifestBytes") || n + 4096 > maxPage) return Admission.UseLegacy("manifest_limit")
            if (count > 0 && (count.toLong() == limits.n("maxManifestPageEntries") || bytes + n + 4096 > maxPage)) { counts.add(count); count = 0; bytes = 0 }
            count++; bytes += n
        }
        if (count > 0) counts.add(count)
        ResumeWire.sorted(objects.map { it.s("objectId") }); ResumeReceiver.validateGraph(objects)
        val reservation = 8192 + objects.size * 4096L + manifestBytes + counts.size * 2048L
        if (reservation > minOf(journalAvailable, limits.n("maxJournalBytes"))) return Admission.UseLegacy("journal_budget")
        return Admission.Ready(counts, reservation)
    }
}
