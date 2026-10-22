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
    private val checkOwner: (PhoneAccountSession) -> Unit,
    private val anchorVersion: (String) -> Int = { currentVersion(it) },
    private val refreshKeys: suspend (PhoneAccountSession) -> Unit = {},
    private val keepArticle: (suspend (JSONObject) -> Boolean)? = null,
    private val afterPull: suspend (PhoneAccountSession) -> Unit = {},
    private val canonicalArticleId: suspend (JSONObject) -> String = { it.getString("articleId") }
) {
    suspend fun sync(session: PhoneAccountSession, allowUpload: Boolean, operationEpoch: String? = client.knownEpoch(session), requireAvailable: Boolean = false) = withContext(Dispatchers.IO) {
        expectedEpoch = operationEpoch
        try {
        replay(session)
        if (!pull(session)) { if (requireAvailable) throw CloudRecordsUnavailableException(); return@withContext }
        checkBoundOwner(session); afterPull(session)
        if (!allowUpload) return@withContext
        for (attempt in 0..2) {
            try { push(session); cleanupRssIndexes(session,completedSavedIds=emptySet()); return@withContext }
            catch (error: CloudHttpException) {
                if (error.status != 409 || !error.detail.contains("cloud_record_conflict") || attempt == 2) throw error
                // Whole-batch conflict: merge winners, then regenerate candidates.
                if (!pull(session)) { if (requireAvailable) throw CloudRecordsUnavailableException(); return@withContext }
                checkBoundOwner(session); afterPull(session)
            }
        }
        } finally { expectedEpoch = null }
    }
    private var expectedEpoch: String? = null
    private fun checkBoundOwner(session: PhoneAccountSession) {
        checkOwner(session)
        if (expectedEpoch != null && client.knownEpoch(session) != expectedEpoch) throw CloudTrustException()
    }
    private suspend fun pull(session: PhoneAccountSession): Boolean = try {
        pullPages(session)
    } catch (error: CloudHttpException) {
        if (error.status != 400 || runCatching { JSONObject(error.detail).optString("error") }.getOrDefault("") != "cloud_record_cursor_invalid") throw error
        checkBoundOwner(session)
        store.clear(client.scopeId(session))
        // Exactly one recovery attempt; only the records cache is reset.
        pullPages(session)
    }
    private suspend fun pullPages(session: PhoneAccountSession): Boolean {
        var cursor = store.cursor(client.scopeId(session))
        var pageCount = 0
        var refreshedKeys = false
        while (true) {
            check(++pageCount <= 2000) { "云状态分页数量过多，请稍后重试" }
            checkBoundOwner(session)
            val page = client.recordPage(session, cursor) ?: return false
            checkBoundOwner(session)
            if (page.getInt("formatVersion") == 0) {
                checkBoundOwner(session); store.clear(client.scopeId(session))
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
                val envelope = item.optJSONObject("envelope") ?: JSONObject()
                checkBoundOwner(session)
                var key = accountKey(session.userId, envelope.optInt("keyVersion"))
                if (key == null && !refreshedKeys) {
                    refreshedKeys = true
                    tryRefreshKeys(session); checkBoundOwner(session)
                    key = accountKey(session.userId, envelope.optInt("keyVersion"))
                }
                val decoded = try {
                    if (key == null) null else decode(session, key, id, envelope)
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
                finally { key?.fill(0) }
                if (decoded == null || decoded.first !in setOf("article", "source", "rss-state")) {
                    store.quarantine(client.scopeId(session), QuarantinedCloudRecord(id, rev, envelope.optInt("keyVersion"),
                        if (decoded != null) "unknown_kind" else if (key == null) "missing_key" else "invalid_record", envelope.toString(), item.optBoolean("tombstone")))
                    continue
                }
                val (kind, value) = decoded
                val retired = item.optBoolean("tombstone") && !value.optBoolean("deleted")
                if (!retired && !removedRssIndex(kind,value)) {
                    checkBoundOwner(session)
                    if (kind == "rss-state") store.saveRssState(client.scopeId(session), value)
                    try { apply(session, kind, value) }
                    catch (_: org.json.JSONException) {
                        checkBoundOwner(session); store.quarantine(client.scopeId(session), QuarantinedCloudRecord(id, rev, envelope.optInt("keyVersion"), "invalid_fields", envelope.toString(), item.optBoolean("tombstone"))); continue
                    } catch (_: IllegalArgumentException) {
                        checkBoundOwner(session); store.quarantine(client.scopeId(session), QuarantinedCloudRecord(id, rev, envelope.optInt("keyVersion"), "invalid_fields", envelope.toString(), item.optBoolean("tombstone"))); continue
                    }
                }
                checkBoundOwner(session); store.save(client.scopeId(session), id, rev, if (retired) "retired" else PhoneCloudRecordCodec.hash(kind, value))
                store.saveRawEnvelope(client.scopeId(session), id, envelope.toString())
                store.release(client.scopeId(session), id)
            }
            checkBoundOwner(session); store.advance(client.scopeId(session), next)
            if (!hasMore) return true
            cursor = next
        }
    }
    private suspend fun push(session: PhoneAccountSession) {
        val version = currentVersion(session.userId)
        val key = accountKey(session.userId, version) ?: error("缺少当前账号密钥")
        val anchor = accountKey(session.userId, anchorVersion(session.userId)) ?: error("缺少锚定账号密钥")
        var batch = JSONArray(); var hashes = linkedMapOf<String, String>()
        suspend fun flush() {
            if (batch.length() == 0) return
            checkBoundOwner(session)
            val result = client.postRecords(session, batch, expectedEpoch).getJSONArray("items")
            require(result.length() == batch.length())
            val received = (0 until result.length()).map { result.getJSONObject(it).let { row -> row.getString("recordId") to row.getString("revision").also(::revision) } }
            require(received.map { it.first }.toSet() == hashes.keys)
            received.forEach { (id, rev) ->
                checkBoundOwner(session); store.save(client.scopeId(session), id, rev, hashes.getValue(id))
                val original = (0 until batch.length()).map { batch.getJSONObject(it) }.first { it.getString("recordId") == id }
                store.saveRawEnvelope(client.scopeId(session), id, original.getJSONObject("envelope").toString())
                val decoded = decode(session,key,id,original.getJSONObject("envelope"))
                if (decoded.first == "rss-state") store.saveRssState(client.scopeId(session),decoded.second)
            }
            batch = JSONArray(); hashes = linkedMapOf()
        }
        try {
            pages { kind, value ->
                checkBoundOwner(session)
                val owner = client.scopeId(session)
                val id = PhoneCloudRecordCodec.id(anchor, session.userId, kind, value)
                val hash = PhoneCloudRecordCodec.hash(kind, value)
                val legacy = (anchorVersion(session.userId) + 1..version).mapNotNull { v ->
                    val oldKey = accountKey(session.userId, v) ?: return@mapNotNull null
                    val oldId = try { PhoneCloudRecordCodec.id(oldKey, session.userId, kind, value) } finally { oldKey.fill(0) }
                    val old = store.record(owner, oldId)
                    val envelope = store.rawEnvelope(owner, oldId)
                    if (oldId == id || old == null || old.second == "retired" || envelope == null) null
                    else JSONObject().put("recordId", oldId).put("expectedRevision", old.first)
                        .put("tombstone", true).put("envelope", JSONObject(envelope))
                }
                val previous = store.record(owner, id)
                if (previous?.second != hash || legacy.isNotEmpty()) {
                    val groups = if (legacy.isEmpty()) listOf(emptyList()) else legacy.chunked(49)
                    for ((index, aliases) in groups.withIndex()) {
                        if (index > 0) flush()
                        val current = store.record(owner, id)
                        val envelope = PhoneCloudRecordCodec.encrypt(key, session.userId, id, version, kind, value, idKey = anchor)
                        val item = JSONObject().put("recordId", id).put("expectedRevision", current?.first ?: "0")
                            .put("tombstone", value.optBoolean("deleted")).put("envelope", envelope)
                        val groupBytes = (aliases + item).sumOf { it.toString().toByteArray().size }
                        require(groupBytes <= 1000000) { "云状态迁移批次过大" }
                        if (batch.length() + aliases.size + 1 > 50 || batch.toString().toByteArray().size + groupBytes > 1000000) flush()
                        batch.put(item); hashes[id] = hash
                        aliases.forEach { alias -> val oldId = alias.getString("recordId"); batch.put(alias); hashes[oldId] = "retired" }
                    }
                }
            }
            flush()
        } finally { key.fill(0); anchor.fill(0) }
    }
    suspend fun retireSavedRssIndexes(session: PhoneAccountSession, operationEpoch: String, completedArticleIds: Set<String>) = withContext(Dispatchers.IO) {
        expectedEpoch = operationEpoch
        try { checkBoundOwner(session); cleanupRssIndexes(session,completedSavedIds=completedArticleIds) }
        finally { expectedEpoch = null }
    }

    private fun removedRssIndex(kind: String, value: JSONObject): Boolean = kind == "article" && CloudContentPolicy.publicRss(value) &&
        (value.optBoolean("rssIndexRetired") || (value.optBoolean("deleted") && !value.optBoolean("favoriteSaved") && !value.optBoolean("watchLaterSaved")))

    /** Commit replacement interactions first; only then CAS-delete obsolete public index rows. */
    private suspend fun cleanupRssIndexes(session: PhoneAccountSession, completedSavedIds: Set<String>) {
        val keep=keepArticle ?: return
        val owner=client.scopeId(session)
        var after=""
        var batch=JSONArray()
        val saved=linkedMapOf<String,Pair<JSONObject,JSONObject>>()
        suspend fun flush() {
            if (batch.length()==0) return
            checkBoundOwner(session)
            val items=client.postRecords(session,batch,expectedEpoch).getJSONArray("items")
            require(items.length()==saved.size)
            require((0 until items.length()).map { items.getJSONObject(it).getString("recordId") }.toSet()==saved.keys)
            for (i in 0 until items.length()) {
                val result=items.getJSONObject(i);val id=result.getString("recordId")
                val rev=result.getString("revision").also(::revision);val (value,env)=saved.getValue(id)
                checkBoundOwner(session);store.save(owner,id,rev,PhoneCloudRecordCodec.hash("article",value));store.saveRawEnvelope(owner,id,env.toString())
            }
            batch=JSONArray();saved.clear()
        }
        while (true) {
            val page=store.rawPage(owner,after,50)
            if (page.isEmpty()) break
            for ((id,raw) in page) {
                checkBoundOwner(session)
                val env=JSONObject(raw);val version=env.getInt("keyVersion")
                val key=accountKey(session.userId,version) ?: error("缺少旧记录密钥")
                val keys=idKeys(session)
                try {
                    val (kind,value)=PhoneCloudRecordCodec.decrypt(key,session.userId,id,env,keys)
                    if (kind!="article" || !CloudContentPolicy.publicRss(value) || value.optBoolean("rssIndexRetired") || value.optBoolean("deleted")) continue
                    val canonicalId=canonicalArticleId(value)
                    if (keep(value) && canonicalId !in completedSavedIds) continue
                    if (CloudContentPolicy.interacted(value)) {
                        val sourceKey=runCatching { CloudRssSourceIdentity.sourceKey(value.getString("rssSourceUrl")) }.getOrNull() ?: continue
                        val anchor=accountKey(session.userId,anchorVersion(session.userId)) ?: error("缺少锚定账号密钥")
                        val replacement=try { PhoneCloudRecordCodec.id(anchor,session.userId,"rss-state",CloudContentPolicy.rssState(JSONObject(value.toString()).put("articleId",canonicalId),sourceKey)) } finally { anchor.fill(0) }
                        if (store.record(owner,replacement)==null) continue
                    }
                    val identityKey=keys.first { PhoneCloudRecordCodec.id(it,session.userId,kind,value)==id }
                    val clock=(CloudContentPolicy.interactionNumbers+listOf("updatedAt","importedAt","independentChangedAt","independentSortOrder")).maxOf { value.optLong(it) }
                    val tombstone=JSONObject(value.toString()).put("deleted",true).put("favoriteSaved",false).put("watchLaterSaved",false)
                        .put("rssIndexRetired",true).put("deletedAt",Math.addExact(maxOf(System.currentTimeMillis(),clock),1L))
                    val encrypted=PhoneCloudRecordCodec.encrypt(key,session.userId,id,version,kind,tombstone,idKey=identityKey)
                    val row=JSONObject().put("recordId",id).put("expectedRevision",store.record(owner,id)!!.first).put("tombstone",true).put("envelope",encrypted)
                    if (batch.length()>=50 || batch.toString().toByteArray().size+row.toString().toByteArray().size>1000000) flush()
                    batch.put(row);saved[id]=tombstone to encrypted
                } finally { key.fill(0);keys.forEach { it.fill(0) } }
            }
            after=page.last().first
        }
        flush()
    }
    private fun idKeys(session: PhoneAccountSession): List<ByteArray> =
        (anchorVersion(session.userId)..currentVersion(session.userId)).mapNotNull { accountKey(session.userId, it) }
    private fun decode(session: PhoneAccountSession, key: ByteArray, id: String, envelope: JSONObject): Pair<String, JSONObject> {
        val candidates = idKeys(session)
        return try { PhoneCloudRecordCodec.decrypt(key, session.userId, id, envelope, candidates) }
        finally { candidates.forEach { it.fill(0) } }
    }
    private suspend fun replay(session: PhoneAccountSession) {
        val owner = client.scopeId(session)
        val pending = store.quarantined(owner)
        if (pending.any { it.reason == "missing_key" }) tryRefreshKeys(session)
        for (record in pending) {
            checkBoundOwner(session)
            val envelope = runCatching { JSONObject(record.envelope) }.getOrNull() ?: continue
            val key = accountKey(session.userId, record.keyVersion) ?: continue
            val decoded = try { decode(session, key, record.id, envelope) }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; continue }
                finally { key.fill(0) }
            if (decoded.first !in setOf("article", "source", "rss-state")) continue
            // A newer revision received since quarantine always wins.
            if ((store.record(owner, record.id)?.first?.toLong() ?: -1) <= record.revision.toLong()) {
                val retired = record.tombstone && !decoded.second.optBoolean("deleted")
                try { if (!retired && !removedRssIndex(decoded.first,decoded.second)) {
                    if (decoded.first == "rss-state") store.saveRssState(owner, decoded.second)
                    apply(session, decoded.first, decoded.second) } }
                catch (_: org.json.JSONException) { checkBoundOwner(session); continue }
                catch (_: IllegalArgumentException) { checkBoundOwner(session); continue }
                checkBoundOwner(session)
                store.save(owner, record.id, record.revision, if(retired) "retired" else PhoneCloudRecordCodec.hash(decoded.first, decoded.second))
            }
            store.release(owner, record.id)
        }
    }
    private suspend fun tryRefreshKeys(session: PhoneAccountSession) {
        try { refreshKeys(session) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: CloudTrustException) { throw e }
        catch (e: CloudHttpException) { if (e.status == 409 && e.detail.contains("library_epoch_mismatch")) throw e }
        catch (_: java.io.IOException) { /* Keep the missing record quarantined until reconnect. */ }
    }
    private fun revision(value: String) { require(value.matches(Regex("[0-9]+")) && value.toLong() >= 0) }
}
