package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Metadata
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire
import org.json.JSONObject
import java.io.File

/** A frozen inbound plan can defer cloud bytes for the same version until this connection ends.
 * Only the business repository can prove that an original committed. */
internal object B2BodyHandoff {
    private data class Candidate(val kind: String, val id: String, val sha256: String)
    private val plans = mutableMapOf<Connection, MutableMap<String, MutableMap<String, Candidate>>>()
    @Volatile var generation: Long = 0
        private set

    class Connection internal constructor(internal val files: File, internal val scope: String,
        private val resumeDeferred: () -> Unit) : AutoCloseable {
        internal var deferred = false

        fun verified(job: String, descriptors: List<JSONObject>) {
            val candidates = descriptors.mapNotNull { descriptor ->
                val kind = descriptor.getString("kind")
                if (kind != B2Wire.BOOK_COMMIT && kind != B2_NOTE_ASSET) return@mapNotNull null
                val metadata = B2Metadata.validate(descriptor)
                val candidate = if (kind == B2Wire.BOOK_COMMIT)
                    Candidate("book", metadata.getString("bookId"), metadata.getString("packageSha256"))
                else Candidate("asset", metadata.getString("storageKey"), metadata.getString("sha256"))
                descriptor.getString("objectId") to candidate
            }.toMap().toMutableMap()
            synchronized(plans) {
                val jobs = plans[this] ?: return
                if (candidates.isNotEmpty()) {
                    jobs[job] = candidates
                    generation++
                }
            }
        }

        fun applied(job: String, objectId: String): Unit = synchronized(plans) {
            val jobs = plans[this] ?: return@synchronized
            val candidates = jobs[job] ?: return@synchronized
            if (candidates.remove(objectId) != null) generation++
        }

        fun ended(job: String): Unit = synchronized(plans) {
            if (plans[this]?.remove(job) != null) generation++
        }

        override fun close() {
            val resume = synchronized(plans) {
                val removed = plans.remove(this) ?: return
                if (removed.isNotEmpty()) generation++
                deferred
            }
            if (resume) resumeDeferred()
        }
    }

    fun open(files: File, scope: String, resumeDeferred: () -> Unit = {}): Connection = synchronized(plans) {
        Connection(files.absoluteFile, scope, resumeDeferred).also { plans[it] = mutableMapOf() }
    }

    fun hasPending(files: File, scope: String, kind: String, id: String, defer: Boolean = false,
        matches: (String) -> Boolean = { true }): Boolean = synchronized(plans) {
        plans.any { (connection, jobs) ->
            val pending = connection.files == files.absoluteFile && connection.scope == scope &&
                jobs.values.any { objects -> objects.values.any { it.kind == kind && it.id == id && matches(it.sha256) } }
            if (pending && defer) connection.deferred = true
            pending
        }
    }
}
