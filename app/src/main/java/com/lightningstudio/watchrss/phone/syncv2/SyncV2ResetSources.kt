package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import com.lightningstudio.watchrss.phone.data.local.syncDirectory
import com.lightningstudio.watchrss.phone.data.note.NoteAssetFiles
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject

/** Keeps local revisions and original bytes independent of the retired cloud scope. */
internal class SyncV2ResetSources private constructor(
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

    fun footprint(kind: String, id: String): JSONObject? {
        val key = previousKey ?: return null
        return db.controlQuery("""
            SELECT b.body FROM syncv2_identity_map m
            JOIN syncv2_p5_bindings b ON b.entity_key=m.entity_key AND b.scope=?
            WHERE m.repo_id=? AND m.kind=? AND m.logical_id=?
        """.trimIndent(), arrayOf(previous, previousRepo, kind, id)).use { row ->
            if (!row.moveToFirst()) null else JSONObject(SyncV2Codec.openLocal(key, previous, "p5/binding", row.getBlob(0)))
                .let { JSONObject(it.getString("json")) }
                .optJSONObject("localValue")
        }
    }

    fun needed(kind: String, id: String): Boolean = db.controlQuery(
        "SELECT 1 FROM syncv2_reset_dependency WHERE scope=? AND kind=? AND logical_id=?",
        arrayOf(scope, kind, id)).use { it.moveToFirst() }

    /** The caller's candidate transaction commits the verified byte references and scan position together. */
    fun capture(kind: String, id: String, hash: String, value: JSONObject, provenance: JSONObject,
        seal: (JSONObject) -> ByteArray) {
        if (complete()) return
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
        db.execSQL("INSERT OR IGNORE INTO syncv2_reset_dependency VALUES(?,?,?)", arrayOf(scope, kind, id))
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

    fun complete(): Boolean = db.controlQuery("SELECT 1 FROM syncv2_reset_migration WHERE scope=?", arrayOf(scope)).use { it.moveToFirst() }

    fun complete(stamp: String) {
        db.execSQL("INSERT OR IGNORE INTO syncv2_reset_migration VALUES(?,?)", arrayOf(scope, stamp))
    }

    companion object {
        /** The Room witness and old encrypted queues remain durable through the scope switch. */
        fun stage(db: SupportSQLiteDatabase, keys: CloudKeyManager, user: String, resetScope: JSONObject?) {
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
            if (previous.isNotEmpty() && db.controlQuery("""
                SELECT 1 FROM syncv2_legacy_inbox
                WHERE scope=? AND route='pendingChoice' AND applied=0 LIMIT 1
            """.trimIndent(), arrayOf(previous)).use { it.moveToFirst() }) {
                SyncV2Codec.fail("reset_migration_required")
            }
            if (name != null) {
                val stamp = SyncV2BusinessWitness.read(db)
                keys.syncV2Write(user, name, JSONObject().put("previous", previous).put("witness", stamp.encoded()).toString())
            }
        }

        fun open(db: SupportSQLiteDatabase, store: SyncV2AccountStore, scope: String, files: File, depot: File): SyncV2ResetSources {
            val previous = store.read("reset-source/$scope")?.getString("previous")
                ?: SyncV2Codec.fail("reset_migration_required")
            val key = previous.takeIf(String::isNotEmpty)?.let {
                val repo = JSONObject(it).getString("repoId")
                val encoded = store.read("local/$repo")?.getString("key") ?: SyncV2Codec.fail("reset_migration_required")
                SyncV2Codec.unhex(encoded, 32)
            }
            return SyncV2ResetSources(db, scope, previous, key, files, depot)
        }

        fun sameFootprint(current: JSONObject, previous: JSONObject?): Boolean =
            previous != null && SyncV2LocalJson.canonical(current) == SyncV2LocalJson.canonical(previous)
    }
}
