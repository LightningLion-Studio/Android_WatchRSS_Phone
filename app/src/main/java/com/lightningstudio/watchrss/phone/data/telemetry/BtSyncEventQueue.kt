package com.lightningstudio.watchrss.phone.data.telemetry

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** One process owner. The worker removes only IDs acknowledged by a successful POST. */
internal class BtSyncEventQueue(path: File, private val now: () -> Long = System::currentTimeMillis) {
    private val file = AtomicFile(path)

    @Synchronized
    fun append(event: BtSyncEvent) {
        val rows = liveRows().filterNot { it.id == event.id }
        val fresh = if (event.occurredAt >= cutoff()) rows + event else rows
        write(fresh.takeLast(MAX_EVENTS))
    }

    @Synchronized
    fun hasPending(): Boolean = prune().isNotEmpty()

    @Synchronized
    fun batch(authenticatedOwner: String?): List<BtSyncEvent> {
        val rows = prune()
        val authenticated = authenticatedOwner != null && rows.firstOrNull()?.owner == authenticatedOwner
        return rows.takeWhile { (authenticatedOwner != null && it.owner == authenticatedOwner) == authenticated }
            .take(MAX_BATCH)
    }

    @Synchronized
    fun remove(ids: Set<String>) = write(liveRows().filterNot { it.id in ids })

    @Synchronized
    fun clear() = file.delete()

    private fun cutoff() = now() - BT_SYNC_EVENT_LIMIT_MS

    private fun liveRows(): List<BtSyncEvent> = read().filter { it.occurredAt >= cutoff() }

    private fun prune(): List<BtSyncEvent> {
        val rows = read()
        val cutoff = cutoff()
        val fresh = rows.filter { it.occurredAt >= cutoff }
        if (fresh.size != rows.size) write(fresh)
        return fresh
    }

    private fun read(): List<BtSyncEvent> = try {
        val array = JSONArray(file.readFully().toString(Charsets.UTF_8))
        (0 until array.length()).map { i ->
            val row = array.getJSONObject(i)
            BtSyncEvent(row.getJSONObject("event"), row.opt("owner") as? String)
        }
    } catch (_: FileNotFoundException) {
        emptyList()
    } catch (_: JSONException) {
        emptyList()
    }

    private fun write(rows: List<BtSyncEvent>) {
        if (rows.isEmpty()) {
            file.delete()
            return
        }
        val payload = JSONArray(rows.map { JSONObject().put("event", it.data).put("owner", it.owner ?: JSONObject.NULL) })
        val out = file.startWrite()
        try {
            out.write(payload.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (error: Exception) {
            file.failWrite(out)
            throw error
        }
    }

    companion object {
        const val MAX_EVENTS = 500
        const val MAX_BATCH = 50
    }
}
