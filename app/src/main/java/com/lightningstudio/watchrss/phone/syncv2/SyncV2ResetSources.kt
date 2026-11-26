package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Files
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Metadata
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import com.lightningstudio.watchrss.phone.data.local.syncDirectory
import com.lightningstudio.watchrss.phone.data.note.NoteAssetFiles
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Keeps local revisions and original bytes independent of the retired cloud scope. */
internal class SyncV2ResetSources(
    private val db: SupportSQLiteDatabase,
    private val scope: String,
    private val previous: String,
    private val previousKey: ByteArray?,
    private val files: File,
    private val depot: File
) : AutoCloseable {
    private val repository = SyncV2BodyRepository(db, files)
    private val articleStore = FileArticleContentStore(File(files, "imported_text"))
    private val previousRepo = previous.takeIf(String::isNotEmpty)?.let { JSONObject(it).getString("repoId") }

    override fun close() { previousKey?.fill(0) }

    fun acknowledged(kind: String, id: String, current: JSONObject): Boolean {
        val key = previousKey ?: return false
        val binding = db.controlQuery("""
            SELECT b.body FROM syncv2_identity_map m
            JOIN syncv2_p5_bindings b ON b.entity_key=m.entity_key AND b.scope=?
            WHERE m.repo_id=? AND m.kind=? AND m.logical_id=?
        """.trimIndent(), arrayOf(previous, previousRepo, kind, id)).use { row ->
            if (!row.moveToFirst()) null else JSONObject(SyncV2Codec.openLocal(key, previous, "p5/binding", row.getBlob(0)))
                .let { JSONObject(it.getString("json")) }
        } ?: return false
        if (!sameFootprint(current, binding.optJSONObject("localValue"))) return false
        // A metadata acknowledgment does not acknowledge the original body bytes.
        if (!current.optBoolean("deleted") && kind in setOf("article", "book", "asset")) {
            return !hasLocalBody(kind, id) ||
                matchesPublishedBody(kind, id, current, binding.getJSONObject("meta"), binding.optJSONObject("row"))
        }
        return true
    }

    private fun hasLocalBody(kind: String, id: String): Boolean = when (kind) {
        "article" -> db.controlQuery(
            "SELECT 1 FROM phone_articles WHERE articleId=? AND (length(contentText)>0 OR length(contentHtml)>0)",
            arrayOf(id)
        ).use { it.moveToFirst() }
        "book" -> db.controlQuery(
            "SELECT 1 FROM imported_books WHERE bookId=? AND packageBytes>0", arrayOf(id)
        ).use { it.moveToFirst() }
        "asset" -> NoteAssetFiles.current(db, files, id).isFile
        else -> false
    }

    private fun matchesPublishedBody(kind: String, id: String, value: JSONObject, meta: JSONObject, row: JSONObject?): Boolean {
        if (row == null || row.isNull("recipeOid")) return false
        val entity = meta.getString("entityKey")
        val localRevision = when (kind) {
            "article" -> {
                val hasSecondaryBody = db.controlQuery(
                    "SELECT 1 FROM phone_articles WHERE articleId=? AND length(contentText)>0 AND length(contentHtml)>0",
                    arrayOf(id)
                ).use { it.moveToFirst() }
                if (hasSecondaryBody) return false // The article recipe covers the primary format only.
                SyncV2ArticleBody.source(db, files, id) {}?.sha256?.let { SyncV2ArticleBody.revision(entity, it) }
            }
            "book" -> repository.committedBook(id, value)?.revision?.sha256?.let {
                SyncV2BodyCodec.revision(kind, entity, it)
            }
            "asset" -> SyncV2NoteAssetNames.verifiedDigest(db, id)?.let {
                val revision = BookPackageFiles.Revision(it, NoteAssetFiles.current(db, files, id).length())
                NoteAssetFiles.verifiedOriginal(db, files, id, revision) {}
            }?.revision?.sha256?.let { SyncV2BodyCodec.revision(kind, entity, it) }
            else -> null
        }
        return localRevision != null && row.optString("bodyRevision") == localRevision
    }

    fun requiresSeparateCopy(local: JSONObject, remote: JSONObject): Boolean {
        if (remote.has(SyncV2NoteWiring.ISOLATED_KEY)) return true
        val meta = remote.getJSONObject("meta")
        val kind = meta.getString("kind")
        val id = meta.getString("logicalId")
        val value = local.getJSONObject("value")
        if (!sameFootprint(local.getJSONObject("meta"), meta) ||
            !sameFootprint(local.getJSONObject("state"), remote.getJSONObject("state"))) return true
        return !value.optBoolean("deleted") && hasLocalBody(kind, id) &&
            !matchesPublishedBody(kind, id, value, meta, remote.optJSONObject("row"))
    }

    fun needed(kind: String, id: String): Boolean = db.controlQuery(
        "SELECT 1 FROM syncv2_reset_dependency WHERE scope=? AND kind=? AND logical_id=?",
        arrayOf(scope, kind, id)).use { it.moveToFirst() }

    /** The caller's candidate transaction commits the verified byte references and scan position together. */
    fun capture(kind: String, id: String, hash: String, value: JSONObject, provenance: JSONObject,
        seal: (JSONObject) -> ByteArray) {
        val exists = db.controlQuery("SELECT 1 FROM syncv2_reset_pending WHERE scope=? AND kind=? AND logical_id=? AND revision_hash=?",
            arrayOf(scope, kind, id, hash)).use { it.moveToFirst() }
        if (exists) return
        val references = JSONObject()
        if (!value.optBoolean("deleted")) when (kind) {
            "article" -> copyArticle(id, references)
            "book" -> {
                val original = repository.committedBook(id, value) ?: SyncV2Codec.fail("closure_incomplete")
                references.put("book", copyOriginal(original))
            }
            "asset" -> references.put("asset", copyAsset(id))
            "note" -> {
                if (!value.isNull("folderId")) dependency("folder", value.getString("folderId"))
                val texts = mutableListOf(value.getString("markdown"), value.getString("baseMarkdown"))
                SyncV2NoteConflicts.declared(value)?.getJSONArray("records")?.let { conflicts ->
                    for (i in 0 until conflicts.length()) {
                        for (field in listOf("baseMarkdown", "localMarkdown", "remoteMarkdown")) {
                            texts.add(conflicts.getJSONObject(i).getString(field))
                        }
                    }
                }
                for (asset in texts.flatMap(NoteAssetSyncPayload::referencedStorageKeys).toSortedSet()) {
                    references.put("asset/$asset", copyAsset(asset))
                    dependency("asset", asset)
                }
            }
        }
        val record = JSONObject().put("value", value).put("provenance", provenance).put("files", references)
        db.execSQL("INSERT INTO syncv2_reset_pending VALUES(?,?,?,?,?)", arrayOf(scope, kind, id, hash, seal(record)))
    }

    private fun dependency(kind: String, id: String) {
        addDependency(db, scope, kind, id)
    }

    private fun copyAsset(id: String): String {
        val expected = db.controlQuery(
            "SELECT DISTINCT sha256,byteCount FROM ${SyncV2NoteAssetNames.source} WHERE storageKey=? AND deleted=0",
            arrayOf(id)
        ).use { rows ->
            if (!rows.moveToFirst()) SyncV2Codec.fail("closure_incomplete")
            BookPackageFiles.Revision(rows.getString(0), rows.getLong(1)).also {
                if (rows.moveToNext()) SyncV2Codec.fail("closure_incomplete")
            }
        }
        return copyOriginal(NoteAssetFiles.verifiedOriginal(db, files, id, expected) {}
            ?: SyncV2Codec.fail("closure_incomplete"))
    }

    private fun copyOriginal(original: BookPackageFiles.Original): String {
        val hash = copyBytes(original.revision.sha256) { destination ->
            original.file.inputStream().use { it.copyTo(destination) }
        }
        if (!original.isCurrent()) SyncV2Codec.fail("choice_stale")
        return hash
    }

    private fun copyArticle(id: String, references: JSONObject) {
        for (column in listOf("contentText", "contentHtml")) {
            val source = db.controlQuery("SELECT length(CAST($column AS BLOB)),substr($column,1,256) FROM phone_articles WHERE articleId=?",
                arrayOf(id)).use { row ->
                if (!row.moveToFirst()) SyncV2Codec.fail("choice_stale")
                if (row.isNull(0)) null else row.getLong(0) to row.getString(1)
            } ?: continue
            if (source.first == 0L) continue
            val hash = if (column == "contentText" && articleStore.isMarker(source.second)) {
                val file = articleStore.openTextFile(source.second) ?: SyncV2Codec.fail("closure_incomplete")
                val expected = file.name.removeSuffix(".txt").split('-').takeIf {
                    it.size == 3 && it.first() == "v2"
                }?.last()
                copyBytes(expected) { destination -> file.inputStream().use { it.copyTo(destination) } }
            } else copyBytes(null) { destination ->
                var offset = 0L
                while (offset < source.first) {
                    val count = minOf(65_536L, source.first - offset).toInt()
                    val bytes = db.controlQuery("SELECT substr(CAST($column AS BLOB),?,?) FROM phone_articles WHERE articleId=?",
                        arrayOf<Any>(offset + 1, count, id)).use { row ->
                        if (!row.moveToFirst() || row.isNull(0)) SyncV2Codec.fail("choice_stale")
                        row.getBlob(0)
                    }
                    if (bytes.size != count) SyncV2Codec.fail("choice_stale")
                    destination.write(bytes)
                    offset += count
                }
            }
            references.put(column, hash)
        }
    }

    private fun copyBytes(expected: String?, write: (java.io.OutputStream) -> Unit): String {
        check(depot.isDirectory || depot.mkdirs())
        if (expected != null && File(depot, expected).takeIf(File::isFile)?.let(::digest) == expected) return expected
        val stage = File.createTempFile(".reset-", ".part", depot)
        try {
            FileOutputStream(stage).use { destination ->
                write(destination)
                destination.fd.sync()
            }
            val hash = digest(stage)
            if (expected != null && hash != expected) SyncV2Codec.fail("hash_mismatch")
            val target = File(depot, hash)
            if (!target.isFile || digest(target) != hash) ConditionalBodyFile.install(target, stage)
            if (digest(target) != hash) SyncV2Codec.fail("hash_mismatch")
            syncDirectory(depot)
            return hash
        } finally { stage.delete() }
    }

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        return SyncV2Codec.hex(sha.digest())
    }

    fun migrateBluetoothBeforeSwitch() {
        check(db.inTransaction())
        if (previous.isEmpty() || complete()) return
        migrateBluetooth(db, files, previous, scope) { previousKey?.copyOf() }
    }

    fun complete(): Boolean = db.controlQuery("SELECT 1 FROM syncv2_reset_migration WHERE scope=?", arrayOf(scope)).use { it.moveToFirst() }

    fun complete(stamp: String) {
        db.execSQL("INSERT OR IGNORE INTO syncv2_reset_migration VALUES(?,?)", arrayOf(scope, stamp))
    }

    companion object {
        private fun addDependency(db: SupportSQLiteDatabase, scope: String, kind: String, id: String) {
            db.execSQL("INSERT OR IGNORE INTO syncv2_reset_dependency VALUES(?,?,?)", arrayOf(scope, kind, id))
        }

        /** The Room witness and old encrypted queues remain durable through the scope switch. */
        fun stage(db: SupportSQLiteDatabase, keys: CloudKeyManager, user: String, resetScope: JSONObject?, files: File) {
            SyncV2Schema.install(db)
            SyncV2UnconfirmedWrite.requireSettled(db)
            val scope = resetScope?.let(SyncV2Codec::scope)
            val name = scope?.let { "reset-source/$it" }
            if (name != null && keys.syncV2Read(user, name) != null) return
            val account = user.replace("-", "").lowercase()
            val previous = db.controlQuery("""
                SELECT state.scope FROM syncv2_state state
                JOIN syncv2_control control ON control.active_scope=state.scope
                WHERE state.account_id=? AND state.scope<>?
            """.trimIndent(), arrayOf(account, scope.orEmpty())).use { if (it.moveToFirst()) it.getString(0) else "" }
            if (name == null && previous.isNotEmpty()) {
                migrateBluetooth(db, files, previous, null) {
                    val repo = JSONObject(previous).getString("repoId")
                    keys.syncV2Read(user, "local/$repo")?.let { SyncV2Codec.unhex(JSONObject(it).getString("key"), 32) }
                }
            }
            if (name != null) {
                keys.syncV2Write(user, name, JSONObject().put("previous", previous).toString())
            }
        }

        /** Native B2 staging can contain bytes before an inbox entry or Room receipt exists. */
        internal fun pinUnconfirmedBluetooth(db: SupportSQLiteDatabase, files: File, accountScope: String): Boolean {
            if (!db.controlQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='library_binary_records'")
                    .use { it.moveToFirst() }) return false
            val journal = File(files, "library-binary-v2").takeIf(File::isDirectory)?.let(::B2Files)
            val pending = mutableListOf<String>()
            db.controlQuery("SELECT id,body FROM library_binary_records WHERE scope=? AND namespace='job'",
                arrayOf(accountScope)).use { jobs ->
                while (jobs.moveToNext()) {
                    val id = jobs.getString(0)
                    val job = JSONObject(jobs.getString(1))
                    if (job.getJSONObject("description").optString("direction") != "watchToPhone" ||
                        job.optString("state") in setOf("APPLIED", "COMPLETE", "CANCELLED")) continue
                    val hasNativePart = db.controlQuery("""
                        SELECT body FROM library_binary_records
                        WHERE scope=? AND namespace='object' AND instr(body,?)>0
                    """.trimIndent(), arrayOf(accountScope, """"jobId":"$id"""")).use nativeParts@{ records ->
                        while (records.moveToNext()) {
                            val record = JSONObject(records.getString(0))
                            if (record.optString("jobId") != id) continue
                            val descriptor = record.getJSONObject("descriptor")
                            val kind = descriptor.getString("kind")
                            // Applied book segments can be the only staged copy of an
                            // incomplete package until the book commit publishes it.
                            if (!record.isNull("receipt") && kind != B2Wire.BOOK_SEGMENT) continue
                            val materialized = journal?.jobDataBytes(accountScope, id, listOf(descriptor))?.let { it > 0 } == true
                            if (!materialized && !(record.isNull("receipt") && record.optBoolean("active"))) continue
                            if (kind == "noteAsset" && runCatching {
                                SyncV2RecoveryStorage.guardedAlreadyPresent(db, files, SyncV2BodyRepository(db, files),
                                    JSONObject().put("descriptor", descriptor)) { _, _, _, _ ->
                                    SyncV2Codec.fail("reset_bluetooth_pending")
                                }
                            }.getOrDefault(false)) continue
                            return@nativeParts true
                        }
                        false
                    }
                    if (hasNativePart) pending.add(id)
                }
            }
            if (pending.isEmpty()) return false
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS syncv2_reset_bluetooth_pin(" +
                    "scope TEXT NOT NULL,job_id TEXT NOT NULL,PRIMARY KEY(scope,job_id))")
                for (id in pending) db.execSQL("INSERT OR IGNORE INTO syncv2_reset_bluetooth_pin VALUES(?,?)",
                    arrayOf(accountScope, id))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            return true
        }

        internal fun migrateBluetooth(db: SupportSQLiteDatabase, files: File, previous: String,
            target: String?, readKey: () -> ByteArray?) {
            val repository = SyncV2BodyRepository(db, files)
            db.controlQuery("""
                SELECT body,body_hash FROM syncv2_legacy_inbox
                WHERE scope=? AND route='pendingChoice' AND applied=0 ORDER BY arrival,rowid
            """.trimIndent(), arrayOf(previous)).use { rows ->
                if (!rows.moveToFirst()) return
                val key = readKey() ?: SyncV2Codec.fail("reset_bluetooth_pending")
                val archiveKey = SyncV2Codec.localRecoveryKey(key, previous)
                try {
                    do {
                        val verified = runCatching {
                            val envelope = JSONObject(SyncV2Codec.openLocal(key, previous, "legacy", rows.getBlob(0)))
                            val intent = if (envelope.length() == 1 && envelope.opt("json") is String)
                                JSONObject(envelope.getString("json")) else envelope
                            check(revision(intent) == rows.getString(1))
                            val link = intent.getJSONObject("body").getJSONObject("guarded-transfer")
                            val descriptor = link.getJSONObject("descriptor")
                            val metadata = B2Metadata.validate(descriptor)
                            val (kind, id) = when (descriptor.getString("kind")) {
                                "noteAsset" -> "asset" to metadata.getString("storageKey")
                                "bookState" -> "book" to metadata.getString("bookId")
                                else -> SyncV2Codec.fail("reset_bluetooth_pending")
                            }
                            check(SyncV2RecoveryStorage.guardedAlreadyPresent(db, files, repository, link) { archive, size, offset, count ->
                                SyncV2BluetoothInbox.readArchive(File(files, "syncv2-guarded-inbox"), previous, archiveKey,
                                    archive, size, offset, count)
                            })
                            kind to id
                        }.getOrNull() ?: SyncV2Codec.fail("reset_bluetooth_pending")
                        // The checked Room original is the next scope's source. Do not
                        // transfer an old transport ACK, cursor or encrypted descriptor.
                        if (target != null) {
                            val (kind, id) = verified
                            addDependency(db, target, kind, id)
                            if (kind == "asset") {
                                for (owner in NoteAssetFiles.ownerKeys(db, id)) {
                                    val note = if (owner.startsWith("note:")) owner.removePrefix("note:")
                                    else db.controlQuery("SELECT noteId FROM note_conflicts WHERE conflictId=?",
                                        arrayOf(owner.removePrefix("conflict:"))).use {
                                        if (!it.moveToFirst()) SyncV2Codec.fail("reset_bluetooth_pending")
                                        it.getString(0)
                                    }
                                    addDependency(db, target, "note", note)
                                }
                            }
                        }
                    } while (rows.moveToNext())
                } finally {
                    archiveKey.fill(0)
                    key.fill(0)
                }
            }
        }

        fun open(db: SupportSQLiteDatabase, store: SyncV2AccountStore, scope: String, files: File, depot: File): SyncV2ResetSources {
            val previous = store.read("reset-source/$scope")?.getString("previous")
                ?: SyncV2Codec.fail("reset_migration_required")
            val key = previous.takeIf(String::isNotEmpty)?.let {
                val repo = JSONObject(it).getString("repoId")
                val encoded = store.read("local/$repo")?.getString("key") ?: run {
                    migrateBluetooth(db, files, previous, null) { null }
                    SyncV2Codec.fail("reset_migration_required")
                }
                SyncV2Codec.unhex(encoded, 32)
            }
            return SyncV2ResetSources(db, scope, previous, key, files, depot)
        }

        fun revision(value: JSONObject): String =
            SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(SyncV2LocalJson.canonical(value))))

        fun forkNote(scope: String, source: JSONObject): JSONObject {
            val id = "syncv2-reset-" + SyncV2Codec.hash(JSONArray().put(scope).put(revision(source)))
            val fork = JSONObject(source.toString()).put("noteId", id)
            val title = source.getString("title")
            val suffix = " 副本"
            if (SyncV2Codec.utf8(title + suffix).size <= 245760) fork.put("title", title + suffix)
            val original = SyncV2NoteConflicts.declared(source)?.getJSONArray("records") ?: JSONArray()
            val rebound = JSONArray()
            for (index in 0 until original.length()) {
                val conflict = JSONObject(original.getJSONObject(index).toString())
                val conflictId = SyncV2Codec.hash(JSONArray().put(id).put(conflict.getString("conflictId")))
                rebound.put(conflict.put("conflictId", conflictId).put("noteId", id))
            }
            return fork.put("noteConflicts", SyncV2NoteConflicts.phone(rebound, id))
        }

        fun sameFootprint(current: JSONObject, previous: JSONObject?): Boolean =
            previous != null && SyncV2LocalJson.canonical(current) == SyncV2LocalJson.canonical(previous)
    }
}
