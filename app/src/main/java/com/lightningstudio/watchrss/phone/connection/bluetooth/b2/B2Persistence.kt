package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import org.json.JSONObject

/** Host owns the single commit lane and the business DB transaction. No Android type
 * crosses this port. get/put/list are only called inside exclusive/transaction except
 * committed lightweight heartbeat reads, which must not join the business worker. */
interface B2Persistence {
    data class Record(val scope: String, val namespace: String, val id: String, val body: String)
    suspend fun <T> exclusive(block: suspend () -> T): T
    suspend fun <T> transaction(block: suspend () -> T): T
    suspend fun get(scope: String, ns: String, id: String): JSONObject?
    suspend fun list(scope: String, ns: String): List<Pair<String, JSONObject>>
    suspend fun all(): List<Record>
    suspend fun put(scope: String, ns: String, id: String, value: JSONObject)
    suspend fun delete(scope: String, ns: String, id: String)
    suspend fun budget(extra: Long, limit: Long = 67108864) {
        var ceiling = limit
        val used = all().sumOf { row ->
            if (row.namespace == "job") { val j = B2Json.parse(row.body, false); ceiling = minOf(ceiling, j.o("description").o("selection").o("limits").n("maxJournalBytes")); j.n("reservation") }
            else B2Json.utf8(row.body).size.toLong() + 512
        }
        if (extra < 0 || used > ceiling - extra) fail("STORAGE_PRESSURE")
    }
}
