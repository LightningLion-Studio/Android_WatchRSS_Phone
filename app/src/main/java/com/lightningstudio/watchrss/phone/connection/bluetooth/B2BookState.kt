package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.*
import com.lightningstudio.watchrss.phone.data.book.BookSync
import org.json.JSONArray
import org.json.JSONObject

internal object B2BookState {
    const val VERSION = "b2BookStateVersion"
    const val ACTION = "syncLibraryBinaryBookState"
    const val MAX_PROOFS = 512
    private val sha = Regex("[0-9a-f]{64}")
    private val operation = Regex("[0-9a-f]{32}")

    fun negotiated(probe: JSONObject): Boolean {
        val version = probe.opt(VERSION)
        return (version is Int || version is Long) && (version as Number).toLong() == 1L
    }

    fun proof(state: JSONObject, hash: String, bytes: Long): JSONObject = obj(
        "bookId" to state.s("bookId"), "fingerprint" to state.s("fingerprint"),
        "chapterCount" to state.n("chapterCount"), "packageSha256" to hash,
        "packageBytes" to bytes, "d1Complete" to true
    ).also { proof -> state.optJSONObject("lifecycle")?.let { proof.put("lifecycle", it) } }

    fun read(value: JSONArray?): Map<String, JSONObject> {
        if (value == null || value.length() > MAX_PROOFS) return emptyMap()
        val proofs = mutableMapOf<String, JSONObject>()
        val duplicates = mutableSetOf<String>()
        for (i in 0 until value.length()) {
            val row = value.optJSONObject(i) ?: continue
            if (row.opt("d1Complete") != true || !sha.matches(row.optString("bookId")) ||
                !sha.matches(row.optString("fingerprint")) || !sha.matches(row.optString("packageSha256"))) continue
            try {
                if (row.n("chapterCount") !in 1L..100_000L || row.n("packageBytes") !in 1L..B2Wire.BOOK_MAX_BYTES) continue
                if (row.has("lifecycle")) {
                    val token = B2Json.exact(row.get("lifecycle"), "generation", "operationId", "startedAt")
                    if (token.n("generation") < 1 || !operation.matches(token.s("operationId"))) continue
                }
            } catch (_: B2Exception) {
                continue
            }
            if (proofs.put(row.s("bookId"), row) != null) duplicates.add(row.s("bookId"))
        }
        for (id in duplicates) proofs.remove(id)
        return proofs
    }

    fun same(local: JSONObject, peer: JSONObject?): Boolean = peer != null &&
        local.s("bookId") == peer.s("bookId") && local.s("fingerprint") == peer.s("fingerprint") &&
        local.n("chapterCount") == peer.n("chapterCount") && local.s("packageSha256") == peer.s("packageSha256") &&
        local.n("packageBytes") == peer.n("packageBytes") && BookSync.compareLifecycle(local, peer) == 0

    suspend fun exchange(session: PhoneSyncSession, parent: String, packages: JSONArray = JSONArray()): Map<String, JSONObject> {
        var peerPackages = emptyMap<String, JSONObject>()
        session.exchangeApplied(obj("action" to ACTION, "version" to 14, "deviceId" to session.localDeviceId,
            "packages" to packages), "$parent-bookState") { response ->
            if (response.opt("success") != true || response.opt("action") != ACTION || response.n("version") != 14L ||
                response.opt("deviceId") != session.device.remoteDeviceId) fail("CAPABILITY_MISMATCH")
            peerPackages = read(response.optJSONArray("packages"))
        }
        return peerPackages
    }
}
