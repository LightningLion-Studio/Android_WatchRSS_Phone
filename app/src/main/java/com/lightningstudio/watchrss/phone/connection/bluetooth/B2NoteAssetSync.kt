package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Product seam for the S2 noteAsset subset. Construction is strictly behind b2Enabled.
 * Transport discovery/account/Ready and the legacy split remain manager responsibilities. */
internal class B2NoteAssetSync(private val store: B2Persistence, private val directory: File,
    private val assetDirectory: File, private val account: String, private val phone: String,
    private val watch: String, private val authorize: () -> Unit,
    private val progress: (String) -> Unit) {
    private val files = B2Files(directory)
    private val snapshots = B2Snapshots(store, files)
    private val key = B2Digests.d("wrss.library-binary.v2/asset-attempt", listOf(account, phone, watch))
    val capability: JSONObject get() = B2Negotiation.capability(setOf("noteAsset"), setOf("noteAsset"))
    private var preparing: () -> Unit = {}
    private fun id() = java.util.UUID.randomUUID().toString().replace("-", "")
    suspend fun resetPlan() = store.exclusive { store.transaction {
        val previous = store.get(account, "attempt", key) ?: return@transaction
        previous.put("state", "RETIRING"); store.put(account, "attempt", key, previous)
        val bindings = previous.getJSONArray("bindings")
        for (i in 0 until bindings.length()) {
            val b = bindings.getJSONObject(i)
            if (b.getString("direction") == "watchToPhone") {
                val local = store.get(account, "job", b.getString("jobId")) ?: continue
                if (local.getString("state") != "COMPLETE") { local.put("state", "CANCELLED"); store.put(account, "job", b.getString("jobId"), local) }
            }
        }
    } }
    private suspend fun attempt(assets: List<NoteAssetInventoryEntry>): JSONObject = try { B2LocalWork.run { unit ->
        preparing = unit; files.onProgress = { unit() }
        try { createOrResume(assets) } finally { preparing = {} }
    } }
        catch (_: java.io.IOException) { throw B2Exception("LOCAL_IO") }
        catch (_: android.database.SQLException) { throw B2Exception("LOCAL_IO") }
    private suspend fun createOrResume(assets: List<NoteAssetInventoryEntry>): JSONObject {
        store.exclusive { store.get(account, "attempt", key) }?.takeIf { it.optString("state") in setOf("PREPARED", "RETIRING", "CANCELLED") }?.let { return it }
        val descriptors = assets.sortedBy { it.storageKey }.map { asset ->
            authorize()
            val source = File(assetDirectory, asset.storageKey)
            val hashes = JSONArray(); val buffer = ByteArray(65536); val whole = java.security.MessageDigest.getInstance("SHA-256")
            var total = 0L
            runInterruptible(Dispatchers.IO) { source.inputStream().use { input ->
                while (true) {
                    var n = 0
                    while (n < buffer.size) { val got = input.channel.read(java.nio.ByteBuffer.wrap(buffer, n, buffer.size - n)); if (got < 0) break; if (got > 0) n += got }
                    if (n == 0) break
                    total += n; whole.update(buffer, 0, n); hashes.put(B2Digests.hash(buffer.copyOf(n))); preparing()
                }
            }
            }
            if (total != asset.size || B2Frames.hex(whole.digest()) != asset.sha256) throw B2Exception("SOURCE_CHANGED")
            val metadata = JSONObject().put("storageKey", asset.storageKey).put("sha256", asset.sha256).put("size", asset.size).toString()
            val d = JSONObject().put("objectId", "0".repeat(64)).put("kind", "noteAsset").put("schemaVersion", 1)
                .put("businessId", asset.storageKey).put("contentVersion", asset.sha256).put("encoding", "raw")
                .put("size", asset.size).put("chunkBytes", 65536).put("sha256", asset.sha256).put("chunkHashes", hashes)
                .put("metadataUtf8", metadata).put("metadataSha256", B2Digests.hash(B2Json.utf8(metadata))).put("dependsOn", JSONArray())
            d.put("objectId", B2Digests.objectId(d)); B2Wire.descriptor(d); B2Metadata.validate(d); d
        }
        val witness = snapshots.freeze(account, "asset-" + id(), descriptors) { File(assetDirectory, it.getString("businessId")) }
        return JSONObject().put("state", "PREPARED").put("phoneSnapshot", witness).put("watchSnapshot", JSONObject.NULL).put("bindings", JSONArray()).also { a ->
            store.exclusive { authorize(); store.budget(B2Json.utf8(a.toString()).size.toLong() + 1024); store.put(account, "attempt", key, a) }
        }
    }
    suspend fun run(input: InputStream, output: OutputStream, probe: JSONObject, assets: List<NoteAssetInventoryEntry>, close: () -> Unit) = supervisorScope {
        authorize()
        val sendSelected = B2Negotiation.select(capability, probe.getJSONObject("libraryBinary"), "phoneToWatch") != null
        val a = attempt(if (sendSelected) assets else emptyList())
        val retiring = a.getString("state") in setOf("RETIRING", "CANCELLED")
        if (retiring) {
            val oldBindings = a.getJSONArray("bindings")
            val forward = (0 until oldBindings.length()).map { oldBindings.getJSONObject(it) }.find { it.getString("direction") == "phoneToWatch" }
            val saved = forward?.let { store.get(account, "sourceJob", it.getString("jobId")) }
            if (saved == null || saved.optString("state") == "COMPLETE") {
                store.exclusive { store.delete(account, "attempt", key) }; throw B2Exception("PLAN_RETIRED")
            }
        }
        val session = B2Session(input, output, this, close, authorize = authorize)
        val client = B2Client(store, snapshots, session::exchange, authorize, session, progress)
        if (!client.acceptProbe(probe)) throw B2Exception("CAPABILITY_MISMATCH")
        session.switch(probe.getJSONObject("libraryBinary"), true)
        files.onProgress = session::workProgress
        session.start()
        val reverseDone = CompletableDeferred<Unit>()
        try {
            val offer = client.offer(account, phone, watch, capability, a.getJSONObject("phoneSnapshot"), if (a.isNull("watchSnapshot")) null else a.getJSONObject("watchSnapshot"), verifySource = !retiring)
            val oldBindings = a.getJSONArray("bindings")
            val bindings = if (oldBindings.length() > 0) (0 until oldBindings.length()).map { i ->
                JSONObject(oldBindings.getJSONObject(i).toString()).put("intent", "resume")
            }.filter { !retiring || it.getString("direction") == "phoneToWatch" } else listOf("phoneToWatch", "watchToPhone").mapNotNull { direction ->
                B2Negotiation.select(capability, offer.getJSONObject("watchCapabilities"), direction)?.let { s ->
                    JSONObject().put("direction", direction).put("jobId", id()).put("intent", "create").put("selection", s)
                }
            }
            if (bindings.isEmpty()) throw B2Exception("CAPABILITY_MISMATCH")
            a.put("watchSnapshot", offer.getJSONObject("watchSnapshot"))
            if (!retiring) a.put("bindings", JSONArray(bindings))
            store.exclusive { authorize(); store.put(account, "attempt", key, a) }
            client.confirm(bindings) { auth ->
                val business = B2NoteAssetBusiness(store, account, assetDirectory, authorize)
                val receiver = B2Receiver(store, files, auth, business, progress = progress)
                session.install(receiver) { request ->
                    client.handleRequest(request, receiver).also { answer ->
                        if (request.getString("phase") == "complete" && answer.getBoolean("success")) { progress("complete:watchToPhone"); reverseDone.complete(Unit) }
                        if (request.getString("phase") == "begin" && answer.getJSONObject("result").getString("state") == "COMPLETE") reverseDone.complete(Unit)
                    }
                }
            }
            if (retiring) {
                val forward = bindings.single().getString("jobId")
                try {
                    val reason = store.get(account, "cancelIntent", forward)?.optString("reason") ?: "LOCAL_ABORT"
                    client.retire(forward, reason); session.flush()
                }
                catch (e: B2Exception) { if (e.code !in setOf("NOT_FOUND", "EXPIRED")) throw e }
                store.exclusive { store.delete(account, "attempt", key) }
                throw B2Exception("PLAN_RETIRED")
            }
            val work = async {
                val source = bindings.find { it.getString("direction") == "phoneToWatch" }
                if (source != null) client.send(source.getString("jobId"))
                if (bindings.any { it.getString("direction") == "watchToPhone" }) reverseDone.await()
            }
            val terminated = async { session.awaitTermination() }
            select<Unit> { work.onAwait { }; terminated.onAwait { throw it } }
            terminated.cancel()
            session.flush()
            store.exclusive { authorize(); a.put("state", "COMPLETE"); store.put(account, "attempt", key, a) }
        } catch (e: Throwable) {
            val authorized = runCatching(authorize).isSuccess
            if (e is CancellationException && authorized) session.cancelUser() else session.close()
            if (e is CancellationException || e is B2Exception && e.code in setOf("JOB_CANCELLED", "USER_CANCELLED", "AUTH_REVOKED")) {
                // Cancellation intent survives a lost cancel frame and cannot revive on resume.
                withContext(NonCancellable + Dispatchers.IO) { store.exclusive { store.transaction {
                    val bindings = a.getJSONArray("bindings")
                    for (i in 0 until bindings.length()) {
                        val jobId = bindings.getJSONObject(i).getString("jobId")
                        for (ns in listOf("job", "sourceJob")) {
                            val row = store.get(account, ns, jobId) ?: continue
                            if (row.optString("state") != "COMPLETE") { row.put("state", "CANCELLED"); store.put(account, ns, jobId, row) }
                        }
                        store.put(account, "cancelIntent", jobId, JSONObject().put("reason", if (!authorized || e is B2Exception && e.code == "AUTH_REVOKED") "ACCOUNT_CHANGED" else if (e is CancellationException) "USER_CANCEL" else "LOCAL_ABORT"))
                    }
                    a.put("state", "CANCELLED"); store.put(account, "attempt", key, a)
                } } }
            }
            if (e is android.database.SQLException) throw B2Exception("LOCAL_IO")
            throw e
        } finally { session.close() }
    }
}
