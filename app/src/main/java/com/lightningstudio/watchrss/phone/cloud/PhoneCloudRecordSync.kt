package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded pull and CAS diff. Android never grants the mini program's consent. */
internal class PhoneCloudRecordSync(
    private val client: PhoneCloudClient,
    private val store: CloudRecordState,
    private val pages: suspend (suspend (String, JSONObject) -> Unit) -> Unit,
    private val apply: suspend (PhoneAccountSession, String, JSONObject) -> Unit,
    private val currentVersion: (String) -> Int,
    private val accountKey: (String, Int) -> ByteArray?,
    private val checkOwner: (PhoneAccountSession) -> Unit
) {
    suspend fun sync(session: PhoneAccountSession, writable: Boolean) = withContext(Dispatchers.IO) {
        if (!pull(session) || !writable) return@withContext
        for (attempt in 0..2) {
            try { push(session); return@withContext }
            catch (error: CloudHttpException) {
                if (error.status != 409 || !error.detail.contains("cloud_record_conflict") || attempt == 2) throw error
                // Whole-batch conflict: merge winners, then regenerate candidates.
                if (!pull(session)) return@withContext
            }
        }
    }
    private suspend fun pull(session: PhoneAccountSession): Boolean = try {
        pullPages(session)
    } catch (error: CloudHttpException) {
        if (error.status != 400 || runCatching { JSONObject(error.detail).optString("error") }.getOrDefault("") != "cloud_record_cursor_invalid") throw error
        checkOwner(session)
        store.clear(session.userId)
        // Exactly one recovery attempt; only the records cache is reset.
        pullPages(session)
    }
    private suspend fun pullPages(session: PhoneAccountSession): Boolean {
        var cursor = store.cursor(session.userId)
        var pageCount = 0
        while (true) {
            check(++pageCount <= 2000) { "云状态分页数量过多，请稍后重试" }
            checkOwner(session)
            val page = client.recordPage(session, cursor) ?: return false
            checkOwner(session)
            if (page.getInt("formatVersion") == 0) {
                checkOwner(session); store.clear(session.userId)
                return false
            }
            require(page.getInt("formatVersion") == 1)
            val items = page.getJSONArray("items"); require(items.length() <= 50)
            val next = page.getString("cursor").also(::revision)
            val head = page.getString("headRevision").also(::revision)
            val hasMore = page.getBoolean("hasMore")
            require(next.toLong() >= cursor.toLong() && next.toLong() <= head.toLong())
            if (hasMore) require(items.length() > 0 && next.toLong() > cursor.toLong())
            else require(next == head) { "云状态终页游标不完整" }
            var previousRevision = cursor.toLong()
            for (i in 0 until items.length()) {
                val itemRevision = items.getJSONObject(i).getString("revision").also(::revision).toLong()
                require(itemRevision > previousRevision && itemRevision <= next.toLong()) { "云状态记录版本顺序异常" }
                previousRevision = itemRevision
            }
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i); val id = item.getString("recordId")
                val rev = item.getString("revision").also(::revision)
                val envelope = item.getJSONObject("envelope")
                val key = accountKey(session.userId, envelope.getInt("keyVersion")) ?: error("缺少云状态所需的账号密钥")
                val (kind, value) = PhoneCloudRecordCodec.decrypt(key, session.userId, id, envelope)
                checkOwner(session); apply(session, kind, value)
                checkOwner(session); store.save(session.userId, id, rev, PhoneCloudRecordCodec.hash(kind, value))
            }
            checkOwner(session); store.advance(session.userId, next)
            if (!hasMore) return true
            cursor = next
        }
    }
    private suspend fun push(session: PhoneAccountSession) {
        val version = currentVersion(session.userId)
        val key = accountKey(session.userId, version) ?: error("缺少当前账号密钥")
        var batch = JSONArray(); var hashes = linkedMapOf<String, String>()
        suspend fun flush() {
            if (batch.length() == 0) return
            checkOwner(session)
            val result = client.postRecords(session, batch).getJSONArray("items")
            require(result.length() == batch.length())
            val received = (0 until result.length()).map { result.getJSONObject(it).let { row -> row.getString("recordId") to row.getString("revision").also(::revision) } }
            require(received.map { it.first }.toSet() == hashes.keys)
            received.forEach { (id, rev) -> checkOwner(session); store.save(session.userId, id, rev, hashes.getValue(id)) }
            batch = JSONArray(); hashes = linkedMapOf()
        }
        pages { kind, value ->
            checkOwner(session)
            val id = PhoneCloudRecordCodec.id(key, session.userId, kind, value)
            val hash = PhoneCloudRecordCodec.hash(kind, value)
            val previous = store.record(session.userId, id)
            if (previous?.second != hash) {
                val item = JSONObject().put("recordId", id).put("expectedRevision", previous?.first ?: "0")
                    .put("envelope", PhoneCloudRecordCodec.encrypt(key, session.userId, id, version, kind, value))
                if (batch.length() >= 50 || batch.toString().toByteArray().size + item.toString().toByteArray().size > 1000000) flush()
                batch.put(item); hashes[id] = hash
            }
        }
        flush()
    }
    private fun revision(value: String) { require(value.matches(Regex("[0-9]+")) && value.toLong() >= 0) }
}
