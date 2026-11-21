package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.book.BookPackageFiles
import com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile
import com.lightningstudio.watchrss.phone.data.note.NoteAssetFiles
import com.lightningstudio.watchrss.phone.data.local.syncDirectory
import org.json.JSONObject
import java.io.File

/** New B2 bytes are private until the Room transaction publishes their reference. */
internal class B2NoteAssetBusiness(private val store: B2RoomStore, private val account: String,
    assets: File, private val authorize: () -> Unit) : B2BusinessPort {
    private val files = requireNotNull(requireNotNull(assets.parentFile).parentFile)
    private val db: SupportSQLiteDatabase get() = store.attachmentDatabase

    internal data class Prepared(val original: BookPackageFiles.Original, val old: BookPackageFiles.Revision?,
        val previous: File, val publish: Boolean, val owners: List<String>)
    override val kinds = setOf("noteAsset")

    override suspend fun apply(descriptor: JSONObject, metadata: JSONObject, staged: File): String {
        check(db.inTransaction())
        val context = B2ApplyContext("attachment", { fail("UNSUPPORTED_KIND") }, authorize, stagedFile = staged)
        prepareFile(context, descriptor, metadata)
        return publish(context, metadata)
    }

    override suspend fun applyFile(context: B2ApplyContext, descriptor: JSONObject, metadata: JSONObject,
        staged: B2FileRef): String = publish(context, metadata)

    override suspend fun prepareFile(context: B2ApplyContext, descriptor: JSONObject, metadata: JSONObject) {
        B2Metadata.validate(descriptor)
        prepare(context, metadata)
    }

    internal fun prepare(context: B2ApplyContext, metadata: JSONObject) {
        context.check(); authorize()
        val key = metadata.s("storageKey")
        val requested = BookPackageFiles.Revision(metadata.s("sha256"), metadata.n("size"))
        val owners = if (context.guarded) NoteAssetFiles.ownerKeys(db, key) else emptyList()
        if (context.guarded && owners.isEmpty()) fail("BUSINESS_REJECTED")
        val old = NoteAssetFiles.reference(db, key)
        val previous = NoteAssetFiles.current(db, files, key)
        val publish = old == null && !previous.exists()
        val selected = if (publish) {
            if (context.guarded && !NoteAssetFiles.winnerAllows(db, key, requested)) fail("BUSINESS_REJECTED")
            val staged = context.stagedFile ?: fail("LOCAL_IO")
            val candidate = NoteAssetFiles.candidate(files, requested.sha256)
            val parent = NoteAssetFiles.ensureCandidateDirectory(files)
            // A different committed key can already point at this digest. Restoring its
            // missing bytes here would expose this attempt before this transaction commits.
            if (!candidate.exists() && NoteAssetFiles.referencesDigest(db, requested.sha256)) fail("RECEIPT_CONFLICT")
            val linked = ConditionalBodyFile.linkIfAbsent(candidate, staged)
            context.finishAsset = { committed ->
                if (committed) NoteAssetFiles.published()
                else if (linked) discardUnreferencedCandidate(candidate, staged, requested.sha256)
            }
            if (linked) syncDirectory(parent)
            BookPackageFiles.verify(candidate, requested) { context.check() } ?: fail("HASH_MISMATCH")
        } else {
            val digest = if (previous == NoteAssetFiles.legacy(files, key)) ConditionalBodyFile.digest(previous) else old?.sha256
            val revision = BookPackageFiles.Revision(digest ?: fail("RECEIPT_CONFLICT"), previous.length())
            BookPackageFiles.verify(previous, revision) { context.check() } ?: fail("RECEIPT_CONFLICT")
        }
        if (context.guarded && !NoteAssetFiles.winnerAllows(db, key, selected.revision)) fail("BUSINESS_REJECTED")
        context.preparedAsset = Prepared(selected, old, previous, publish, owners)
    }

    private suspend fun publish(context: B2ApplyContext, metadata: JSONObject): String {
        context.check(); authorize()
        val prepared = context.preparedAsset ?: fail("JOURNAL_CORRUPT")
        val key = metadata.s("storageKey")
        val value = prepared.original.revision
        if (NoteAssetFiles.reference(db, key) != prepared.old ||
            NoteAssetFiles.current(db, files, key) != prepared.previous ||
            (prepared.publish && prepared.previous.exists()) ||
            !prepared.original.isCurrent()) fail("BUSINESS_REJECTED")
        if (context.guarded && (NoteAssetFiles.ownerKeys(db, key) != prepared.owners ||
            !NoteAssetFiles.winnerAllows(db, key, value))) fail("BUSINESS_REJECTED")
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

    private fun discardUnreferencedCandidate(candidate: File, staged: File, digest: String) {
        // Other writers can adopt the same immutable hash while this attempt rolls back.
        db.beginTransactionNonExclusive()
        try {
            if (!NoteAssetFiles.referencesDigest(db, digest)) {
                ConditionalBodyFile.unlinkPublication(candidate, staged)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
