package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject

/** Caller holds the B2 store's writer lane during admission and reclamation. */
internal class B2Retention(
    private val store: B2Persistence,
    private val files: B2Files,
    private val scope: String,
    private val monotonic: () -> Long,
    generation: String?,
    private val check: () -> Unit
) {
    private val clockGeneration = generation ?: processGeneration

    private fun key(vararg parts: String) =
        B2Digests.d("wrss.library-binary.v2/record-key", parts.toList())

    suspend fun clock(owner: String = scope): Long {
        // A proven boot identity survives an app restart. On an unknown/new boot
        // continue at the last persisted logical instant, without using wall time.
        val old = store.get(owner, "clock", "clock")
        val now = monotonic()
        val logical = if (old == null) 0L else old.n("logical") +
            if (old.s("process") == clockGeneration) maxOf(0L, now - old.n("elapsed")) else 0L
        store.put(owner, "clock", "clock", obj("logical" to logical, "elapsed" to now, "process" to clockGeneration))
        return logical
    }
    suspend fun collect(now: Long, currentId: String? = null) {
        check()
        files.collectScratch()
        val times = mutableMapOf(scope to now)
        suspend fun time(owner: String) = times[owner] ?: clock(owner).also { times[owner] = it }
        val jobs = store.jobs()
        val existing = jobs.mapTo(mutableSetOf()) { it.scope to it.id }
        var legacyRetained = false
        for (record in jobs) {
            val (owner, _, id, body) = record
            val row = B2Json.parse(body, false)
            if (owner == scope && id == currentId || row.n("expiresAtMillis") >= time(owner)) {
                legacyRetained = legacyRetained || row.optInt("bindingVersion") < 2
                continue
            }
            check()
            collectJob(record.scope, row)
        }
        for (record in store.requestOwners()) {
            val (owner, _, id, body) = record
            if (owner to id in existing || owner == scope && id == currentId) continue
            if (B2Json.parse(body, false).n("expiresAtMillis") < time(owner)) {
                files.collectRequests(owner, id)
                store.delete(owner, "nativeOwner", id)
            }
        }
        val migration = store.get(GLOBAL, "gc", "legacyBindings")
        if (migration != null || files.hasLegacyRequests()) {
            val elapsed = time(GLOBAL)
            if (migration == null) {
                // Old shards lack a job index. Protect even orphaned legacy begin
                // bindings for one complete recovery interval after upgrade.
                store.put(GLOBAL, "gc", "legacyBindings", obj("expiresAtMillis" to elapsed + B2Wire.RETENTION))
            } else if (!legacyRetained && migration.n("expiresAtMillis") < elapsed) {
                files.collectLegacyRequests()
                store.transaction {
                    store.deleteLegacyRequestRecords()
                    store.delete(GLOBAL, "gc", "legacyBindings")
                }
            }
        }
    }

    private suspend fun collectJob(owner: String, row: JSONObject) {
        val id = row.s("jobId")
        val records = store.listForJob(owner, "object", id)
        for (record in records) {
            val d = record.o("descriptor")
            if (!files.release(owner, id, d)) return
            if (d.s("kind") == B2Wire.BOOK_COMMIT) {
                val assembly = files.assembly(owner, id, d.s("objectId"))
                if (assembly.exists() && !assembly.delete()) return
            }
        }
        files.collectRequests(owner, id)
        store.transaction {
            if (row.optInt("bindingVersion") < 2) {
                val count = row.o("description").a("pageObjectCounts").length()
                for (index in 0 until count) store.delete(owner, "page", key(id, index.toString()))
                for (record in records) {
                    val d = record.o("descriptor")
                    val oid = d.s("objectId")
                    for (index in 0 until d.a("chunkHashes").length())
                        store.delete(owner, "chunk", key(id, oid, index.toString()))
                    if (d.s("kind") in B2Wire.bookKinds) {
                        val book = B2Metadata.validate(d).s("bookId")
                        store.delete(owner, "bookStateWitness", "$id:$book")
                        store.delete(owner, "bookReadingIndices", "$id:$book")
                        store.delete(owner, "bookAssembly", key(id, book))
                    }
                }
            }
            for (kind in JOB_RECORDS) store.deleteJobRecords(owner, id, kind)
            store.delete(owner, "nativeOwner", id)
            store.delete(owner, "job", id)
        }
    }

    private companion object {
        const val GLOBAL = "b2-binding-journal"
        val JOB_RECORDS = listOf("object", "request", "page", "chunk", "bookAssembly", "bookStateWitness", "bookReadingIndices")
        val processGeneration: String = java.util.UUID.randomUUID().toString()
    }
}
