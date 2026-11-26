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
    suspend fun listForJob(scope: String, namespace: String, jobId: String): List<JSONObject> =
        list(scope, namespace).map { it.second }.filter { it.optString("jobId") == jobId }
    suspend fun all(): List<Record>
    suspend fun jobs(): List<Record> = all().filter { it.namespace == "job" }
    suspend fun retainsForRecovery(scope: String, jobId: String): Boolean = false
    suspend fun requestOwners(): List<Record> = all().filter { it.namespace == "nativeOwner" }
    suspend fun applyRejections(): List<Record> = all().filter { it.namespace.startsWith(B2Retention.REJECTION_PREFIX) }
    suspend fun deleteJobRecords(scope: String, job: String, namespace: String) {
        for ((id, row) in list(scope, namespace)) if (row.optString("jobId") == job) delete(scope, namespace, id)
    }
    suspend fun deleteLegacyRequestRecords() {
        for (row in all()) {
            if (row.namespace == "request" && !B2Json.parse(row.body, false).has("jobId"))
                delete(row.scope, row.namespace, row.id)
        }
    }
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
