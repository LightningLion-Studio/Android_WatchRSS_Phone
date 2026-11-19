package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject

object B2Routing {
    enum class Peer { B2, PODJS_V1, COMPOSE_V14, WAIT_READY }
    data class Ready(val identityVerified: Boolean, val accountConfirmed: Boolean, val legacyAcksDrained: Boolean)
    private val bits = setOf("supportsManifestBatches", "supportsReaderPresets", "supportsReaderAssets", "supportsResourceChunkAck", "supportsReaderPresetPreview", "supportsPersistentSession", "supportsTransferByteProgress")
    private val extensions = setOf("supportsAutomaticAccountSync", "supportsDomainFastPathV2", "supportsBookSyncV1", "supportsBookReadingDeltaV1", "supportsNoteManifestV2", "supportsNoteAssetInventory", "supportsRssCanonicalIds", "supportsFullMetadataOnlyArticles", "supportsMetadataOnlyArticles", "supportsTokenUsageCursor", "persistentSessionAccepted")
    private val dynamic = setOf("deviceId", "sentAt", "account", "accountMatchesRequest", "ipUpgradeAccepted", "watchCapabilities")
    private val special = setOf("libraryPeer", "libraryBinary", "libraryResume", "supportedLibraryResumeVersions", "supportedLibraryBinaryVersions")
    fun classify(p: JSONObject, ready: Ready): Peer = try { classifyChecked(p, ready) } catch (e: B2Exception) {
        if (e.code in setOf("INVALID_FRAME", "UNSUPPORTED_VERSION", "UNSUPPORTED_KIND", "PLAN_CONFLICT")) fail("CAPABILITY_MISMATCH") else throw e
    }
    private fun classifyChecked(p: JSONObject, ready: Ready): Peer {
        if (p.opt("success") != true || p.opt("version") !is Number || p.n("version") != 14L || p.opt("action") != "syncLibrary" || p.opt("phase") != "probe") fail("CAPABILITY_MISMATCH")
        for (key in bits + extensions + "supportsAccountDataSpaces") if (p.has(key) && p.get(key) !is Boolean) fail("CAPABILITY_MISMATCH")
        for ((key, version) in listOf("supportedLibraryResumeVersions" to 1L, "supportedLibraryBinaryVersions" to 2L)) if (p.has(key)) {
            val versions = p.a(key).values().map { B2Json.integer(it) }
            if (versions != listOf(version)) fail("CAPABILITY_MISMATCH")
        }
        if (p.has("libraryResume")) validateLegacyResume(p.o("libraryResume"))
        val live = ready.identityVerified && ready.accountConfirmed && ready.legacyAcksDrained
        fun route(v: Peer) = if (live) v else Peer.WAIT_READY
        val peer = if (p.has("libraryPeer")) B2Json.exact(p.get("libraryPeer"), "implementation", "appVersion").also {
            if (!it.s("appVersion").matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[1-9][0-9]*)?"))) fail("CAPABILITY_MISMATCH")
            it.s("implementation")
        } else null
        if (peer != null && peer.s("implementation") == "podjs") {
            if (peer.s("appVersion").matches(Regex("2\\.0\\.[01](-[1-9][0-9]*)?"))) {
                if (p.has("libraryBinary") || p.has("supportsAccountDataSpaces")) fail("CAPABILITY_MISMATCH")
                return route(Peer.PODJS_V1)
            }
            if (p.has("supportsAccountDataSpaces")) fail("CAPABILITY_MISMATCH")
        }
        val allowed = bits + extensions + dynamic + special + setOf("supportsAccountDataSpaces", "success", "version", "action", "phase")
        if (p.keys().asSequence().any { it !in allowed }) fail("PEER_IMPLEMENTATION_UNKNOWN")
        if (peer != null) {
            if (peer.s("implementation") != "podjs") fail("PEER_IMPLEMENTATION_UNKNOWN")
            if (!p.has("libraryBinary")) return route(Peer.PODJS_V1)
            try { B2Wire.capability(p.o("libraryBinary")) } catch (_: B2Exception) { fail("CAPABILITY_MISMATCH") }
            return route(Peer.B2)
        }
        if (p.has("libraryBinary") || bits.any { !p.has(it) }) fail("PEER_IMPLEMENTATION_UNKNOWN")
        if (!p.has("supportsAccountDataSpaces")) {
            val same = listOf("supportsReaderPresets", "supportsReaderAssets", "supportsResourceChunkAck").map { p.getBoolean(it) }.distinct().size == 1
            if (same && listOf("supportsManifestBatches", "supportsPersistentSession", "supportsTransferByteProgress").all { p.getBoolean(it) }) return route(Peer.PODJS_V1)
        } else if (p.getBoolean("supportsAccountDataSpaces") && bits.all { p.getBoolean(it) }) return route(Peer.COMPOSE_V14)
        fail("PEER_IMPLEMENTATION_UNKNOWN")
    }
    private fun validateLegacyResume(c: JSONObject) {
        B2Json.exact(c, "version", "kinds", "limits")
        if (c.n("version") != 1L) fail("CAPABILITY_MISMATCH")
        val kinds = c.a("kinds").values().map {
            val k = B2Json.exact(it, "kind", "schemaVersion", "send", "receive")
            if (k.s("kind") !in B2Wire.inheritedKinds || k.n("schemaVersion") != 1L) fail("CAPABILITY_MISMATCH")
            B2Json.bool(k.get("send")); B2Json.bool(k.get("receive")); k.s("kind")
        }
        if (kinds.isEmpty() || kinds.size > 6) fail("CAPABILITY_MISMATCH")
        B2Wire.sorted(kinds)
        if ("articleBody" in kinds && "articleMetadata" !in kinds || "readerResource" in kinds && "readerMetadata" !in kinds) fail("CAPABILITY_MISMATCH")
        val keys = B2Wire.limitKeys.take(13)
        B2Json.exact(c.o("limits"), *keys.toTypedArray())
        for (key in keys) {
            val max = when (key) { "maxFrameBytes" -> 262144L; "maxManifestPageBytes" -> 131072L; "maxStagingBytes" -> 134217728L; else -> B2Wire.hardLimits.getValue(key) }
            if (B2Json.integer(c.o("limits").get(key), max) == 0L) fail("CAPABILITY_MISMATCH")
        }
    }
    /** Off returns the identical object: no copy, new field, parser or capability access. */
    fun probe(probe: JSONObject, b2Enabled: Boolean): JSONObject = if (!b2Enabled) probe else B2Negotiation.propose(probe, true)
    fun selected(probe: JSONObject, ready: Ready, enabled: Boolean, phone: JSONObject, direction: String, kind: String): Boolean {
        if (!enabled) return false
        if (classify(probe, ready) != Peer.B2) return false
        val selection = B2Negotiation.select(phone, probe.o("libraryBinary"), direction) ?: return false
        return selection.a("kinds").values().any { (it as JSONObject).s("kind") == kind }
    }
}

/** Standalone host fixture policy. The Android app uses its shared library recovery owner. */
class B2Recovery {
    private var failures = 0
    private var emptyIntervals = 0
    private var reconnectReady = false
    private var intervalProgress = false
    private val progress = hashSetOf<String>()
    private val disconnects = ArrayDeque<Long>()
    private var lastConnection: String? = null
    private var activeConnection: String? = null
    private var usable = false
    fun isReady(connection: String) = usable && activeConnection == connection
    fun connected(connection: String, isReconnect: Boolean) { activeConnection = connection; usable = true; ready(isReconnect) }
    fun handoffReady(connection: String) { activeConnection = connection; usable = true; failures = 0 }
    fun transportFailure(now: Long) {
        val active = activeConnection
        if (usable && active != null) disconnected(active, now) else recoveryFailed()
        usable = false
    }
    fun recoveryFailed() { if (++failures >= 3) fail("RECOVERY_FAILED") }
    fun ready(isReconnect: Boolean) { failures = 0; reconnectReady = isReconnect; intervalProgress = false }
    fun confirmed(semanticKey: String) { if (progress.add(semanticKey)) intervalProgress = true }
    fun disconnected(connection: String, now: Long) {
        if (lastConnection == connection) return
        lastConnection = connection
        while (disconnects.isNotEmpty() && disconnects.first() < now - 10000) disconnects.removeFirst()
        disconnects.addLast(now)
        if (disconnects.size >= 3) fail("RECOVERY_FLAPPING")
        if (reconnectReady) {
            emptyIntervals = if (intervalProgress) 0 else emptyIntervals + 1
            if (emptyIntervals >= 3) fail("同步多次中断且没有进展，请稍后重试")
        }
        reconnectReady = false
    }
}

/** Distinguishes a wire deadline from an error in local file or database work. */
internal class B2NetworkTimeout(message: String) : java.net.SocketTimeoutException(message)

/** Deterministic liveness state shared by tests and the live session timer. */
class B2Heartbeat(private val clock: () -> Long) {
    private var next = clock() + 10000
    private var sent: Long? = null
    private var queued = false
    @Synchronized fun due(): Boolean = sent == null && !queued && clock() >= next
    @Synchronized fun queue() { if (sent != null || queued) fail("BUSY"); queued = true }
    @Synchronized fun beginWrite() { if (sent != null) fail("BUSY"); queued = false; sent = clock() }
    @Synchronized fun reply() { if (sent == null) fail(); sent = null; queued = false; next = clock() + 10000 }
    @Synchronized fun checkDeadline() { if (sent?.let { clock() - it >= 30000 } == true) throw B2NetworkTimeout("B2 heartbeat deadline") }
}

class B2LocalWatchdog(private val clock: () -> Long) {
    @Volatile private var lastProgress = clock()
    fun completedWorkUnit() { lastProgress = clock() }
    fun check() { if (clock() - lastProgress >= 30000) fail("LOCAL_IO") }
}
