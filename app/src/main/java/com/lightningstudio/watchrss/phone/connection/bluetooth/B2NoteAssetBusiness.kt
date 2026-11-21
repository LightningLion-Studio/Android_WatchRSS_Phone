package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile
import com.lightningstudio.watchrss.phone.data.note.NoteAssetFiles
import com.lightningstudio.watchrss.phone.data.local.syncDirectory
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Note and attachment rows arrive via v1. B2 records the immutable asset reference
 * alongside APPLIED and withdraws its own publication if that transaction rolls back. */
internal class B2NoteAssetBusiness(private val store: B2Persistence, private val account: String,
    private val assets: File, private val authorize: () -> Unit) : B2BusinessPort {
    internal var database: (() -> SupportSQLiteDatabase)? = null
    private val files get() = assets.parentFile!!.parentFile!!

    internal data class Prepared(val original: BookPackageFiles.Original, val old: BookPackageFiles.Revision?,
        val previous: File, val publish: Boolean, val owners: List<String>)
    override val kinds = setOf("noteAsset")

    override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String =
        applyAsset(null, descriptor, metadata, staged)

    override suspend fun applyFile(context: B2ApplyContext, descriptor: JSONObject, metadata: JSONObject,
        staged: B2FileRef): String = if (context.guarded) publish(context, metadata)
        else applyAsset(context, descriptor, metadata, staged.file)

    override suspend fun prepareFile(context: B2ApplyContext, descriptor: JSONObject, metadata: JSONObject) {
        if (context.guarded) prepare(context, metadata)
    }

    internal fun prepare(context: B2ApplyContext, metadata: JSONObject) {
        context.check(); authorize()
        val db = database?.invoke() ?: fail("LOCAL_IO")
        val key = metadata.s("storageKey")
        val requested = BookPackageFiles.Revision(metadata.s("sha256"), metadata.n("size"))
        val owners = NoteAssetFiles.ownerKeys(db, key)
        if (owners.isEmpty()) fail("BUSINESS_REJECTED")
        val old = NoteAssetFiles.reference(db, key)
        val previous = NoteAssetFiles.current(db, files, key)
        val publish = old == null && !previous.exists()
        val selected = if (publish) {
            if (!NoteAssetFiles.winnerAllows(db, key, requested)) fail("BUSINESS_REJECTED")
            val staged = context.stagedFile ?: fail("LOCAL_IO")
            val candidate = NoteAssetFiles.candidate(files, requested.sha256)
            val parent = NoteAssetFiles.ensureCandidateDirectory(files)
            val linked = ConditionalBodyFile.linkIfAbsent(candidate, staged)
            context.finishAsset = { committed ->
                if (committed) NoteAssetFiles.published()
                else if (linked) ConditionalBodyFile.unlinkPublication(candidate, staged)
            }
            if (linked) syncDirectory(parent)
            BookPackageFiles.verify(candidate, requested) { context.check() } ?: fail("HASH_MISMATCH")
        } else {
            val digest = if (previous == NoteAssetFiles.legacy(files, key)) ConditionalBodyFile.digest(previous) else old?.sha256
            val revision = BookPackageFiles.Revision(digest ?: fail("RECEIPT_CONFLICT"), previous.length())
            BookPackageFiles.verify(previous, revision) { context.check() } ?: fail("RECEIPT_CONFLICT")
        }
        if (!NoteAssetFiles.winnerAllows(db, key, selected.revision)) fail("BUSINESS_REJECTED")
        context.preparedAsset = Prepared(selected, old, previous, publish, owners)
    }

    private suspend fun publish(context: B2ApplyContext, metadata: JSONObject): String {
        context.check(); authorize()
        val prepared = context.preparedAsset ?: fail("JOURNAL_CORRUPT")
        val db = database?.invoke() ?: fail("LOCAL_IO")
        val key = metadata.s("storageKey")
        val value = prepared.original.revision
        if (NoteAssetFiles.reference(db, key) != prepared.old ||
            NoteAssetFiles.current(db, files, key) != prepared.previous ||
            (prepared.publish && prepared.previous.exists()) ||
            !prepared.original.isCurrent() || NoteAssetFiles.ownerKeys(db, key) != prepared.owners ||
            !NoteAssetFiles.winnerAllows(db, key, value)) fail("BUSINESS_REJECTED")
        if (prepared.publish) NoteAssetFiles.publish(db, key, value)
        store.put(account, "assetReference", key,
            obj("storageKey" to key, "sha256" to value.sha256, "size" to value.bytes))
        return when {
            prepared.publish -> "merged"
            value.sha256 == metadata.s("sha256") -> "alreadyPresent"
            else -> "retainedLocal"
        }
    }

    override suspend fun receiptWitness(
        context: B2ApplyContext?, descriptor: JSONObject, metadata: JSONObject, prior: JSONObject?
    ): JSONObject? {
        val db = database?.invoke() ?: return null
        val key = metadata.s("storageKey")
        val owners = NoteAssetFiles.ownerKeys(db, key)
        if (owners.isEmpty()) return null
        val target = NoteAssetFiles.current(db, files, key)
        val selected = context?.preparedAsset?.original ?: run {
            val revision = if (target == NoteAssetFiles.legacy(files, key)) {
                val digest = ConditionalBodyFile.digest(target) ?: return null
                BookPackageFiles.Revision(digest, target.length())
            } else NoteAssetFiles.reference(db, key) ?: return null
            BookPackageFiles.verify(target, revision) { authorize() } ?: return null
        }
        if (target != selected.file || !selected.isCurrent() || !NoteAssetFiles.winnerAllows(db, key, selected.revision)) return null
        return obj("sha256" to selected.revision.sha256, "size" to selected.revision.bytes,
            "owners" to org.json.JSONArray(owners))
    }

    private suspend fun applyAsset(context: B2ApplyContext?, descriptor: JSONObject, metadata: JSONObject,
        staged: File): String {
        context?.check?.invoke()
        authorize()
        B2Metadata.validate(descriptor)
        assets.mkdirs()
        val key = metadata.getString("storageKey")
        val target = File(assets, key)
        if (target.canonicalFile.parentFile != assets.canonicalFile) fail("BUSINESS_REJECTED")
        val expected = metadata.getString("sha256")
        var outcome = "alreadyPresent"
        var finish: ((Boolean) -> Unit)? = null
        var linked = false
        var completed = false
        try {
            if (!target.exists()) {
                val temp = File.createTempFile(".b2-", ".tmp", assets)
                finish = { committed ->
                    try {
                        if (!committed && linked) ConditionalBodyFile.unlinkPublication(target, temp)
                    } finally {
                        temp.delete()
                    }
                }
                if (context != null) {
                    context.finishAsset = finish
                    finish = null
                }
                FileOutputStream(temp).use { out ->
                    staged.inputStream().use { it.copyTo(out, 65536) }
                    out.fd.sync()
                }
                context?.check?.invoke()
                authorize()
                linked = ConditionalBodyFile.linkIfAbsent(target, temp)
                if (linked) {
                    outcome = "merged"
                    syncDirectory(assets)
                }
            }
            if (!target.isFile) fail("LOCAL_IO")
            val actual = ConditionalBodyFile.digest(target) ?: fail("LOCAL_IO")
            if (actual != expected) outcome = "retainedLocal"
            context?.check?.invoke()
            authorize()
            store.put(account, "assetReference", key,
                JSONObject().put("storageKey", key).put("sha256", actual).put("size", target.length()))
            completed = true
            return outcome
        } finally {
            finish?.invoke(completed)
        }
    }
}
