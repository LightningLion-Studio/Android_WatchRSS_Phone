package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.ConcurrentHashMap

/** A committed write is unconfirmed until the original call rechecks authentication.
 * The marker and its exact undo keys commit with the business rows and staged payload. */
internal class SyncV2UnconfirmedWrite(
    private val db: SupportSQLiteDatabase, private val scope: String, val id: String
) {
    private val key = Key(scope, id)
    private val args get() = arrayOf(scope, id)

    fun begin(committedVersion: String? = null) {
        check(db.inTransaction())
        requireSettled(db)
        val prior = committedVersion?.let {
            db.controlQuery("SELECT body FROM syncv2_state WHERE scope=?", arrayOf(scope)).use { rows ->
                check(rows.moveToFirst())
                rows.getBlob(0)
            }
        }
        db.execSQL("INSERT INTO syncv2_unconfirmed_writes VALUES(?,?,?,?)", arrayOf<Any?>(scope,id,committedVersion,prior))
    }

    fun recordNoteAsset(assetId: String) {
        db.execSQL("INSERT INTO syncv2_unconfirmed_note_assets VALUES(?,?,?)", arrayOf(scope,id,assetId))
    }

    fun <T> verify(
        checkOwner: () -> Unit,
        write: () -> T,
        finalize: (T) -> Unit = {}
    ): T {
        check(active.add(key))
        try {
            val result = write()
            checkOwner()
            transaction(db) {
                // The staging commit is validated. This transaction activates only
                // its derived cache and drops the undo marker.
                checkOwner()
                finalize(result)
                clear()
            }
            return result
        } catch (failure: Throwable) {
            try {
                if (pending()) transaction(db) { undo() }
            } catch (cleanup: Throwable) {
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
            throw failure
        } finally {
            active.remove(key)
        }
    }

    private fun pending() = db.controlQuery(
        "SELECT 1 FROM syncv2_unconfirmed_writes WHERE scope=? AND transaction_id=?", args
    ).use { it.moveToFirst() }

    private fun clear() {
        db.execSQL("DELETE FROM syncv2_unconfirmed_note_assets WHERE scope=? AND transaction_id=?", args)
        db.execSQL("DELETE FROM syncv2_unconfirmed_writes WHERE scope=? AND transaction_id=?", args)
    }

    private fun undo() {
        val state = db.controlQuery(
            "SELECT committed_version,prior_state FROM syncv2_unconfirmed_writes WHERE scope=? AND transaction_id=?", args
        ).use { rows ->
            if (!rows.moveToFirst()) return
            if (rows.isNull(0)) null else rows.getString(0) to rows.getBlob(1)
        }
        if (state != null) {
            // Preserve the monotone state version and every field outside the staged body.
            db.execSQL("UPDATE syncv2_state SET body=? WHERE scope=? AND state_version=?", arrayOf(state.second,scope,state.first))
            check(db.controlQuery("SELECT changes()").use { it.moveToFirst(); it.getInt(0) } == 1)
        }
        db.execSQL("""DELETE FROM note_assets WHERE assetId IN (
            SELECT asset_id FROM syncv2_unconfirmed_note_assets WHERE scope=? AND transaction_id=?
        )""", args)
        db.execSQL("DELETE FROM ${SyncV2NoteAssetNames.aliases} WHERE registration_txn=?", arrayOf(id))
        for (table in listOf("syncv2_p5_objects","syncv2_p5_rows")) {
            db.execSQL("DELETE FROM $table WHERE scope=? AND action_id=?", args)
        }
        clear()
    }

    private data class Key(val scope: String, val id: String)

    companion object {
        private val active = ConcurrentHashMap.newKeySet<Key>()

        fun install(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS syncv2_unconfirmed_writes (
                scope TEXT NOT NULL, transaction_id TEXT NOT NULL, committed_version TEXT, prior_state BLOB,
                PRIMARY KEY(scope, transaction_id)
            )""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS syncv2_unconfirmed_note_assets (
                scope TEXT NOT NULL, transaction_id TEXT NOT NULL, asset_id TEXT NOT NULL,
                PRIMARY KEY(scope, transaction_id, asset_id)
            )""")
            recoverInWriter(db)
        }

        private fun hasPending(db: SupportSQLiteDatabase) =
            db.controlQuery("SELECT 1 FROM syncv2_unconfirmed_writes LIMIT 1").use { it.moveToFirst() }

        fun requireSettled(db: SupportSQLiteDatabase) {
            if (hasPending(db)) SyncV2Codec.fail("choice_pending")
        }

        fun recover(db: SupportSQLiteDatabase) {
            if (!hasPending(db)) return
            transaction(db) { recoverInWriter(db) }
            requireSettled(db)
        }

        fun recoverExisting(db: SupportSQLiteDatabase) {
            val installed = db.controlQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='syncv2_unconfirmed_writes'"
            ).use { it.moveToFirst() }
            if (installed) recover(db)
        }

        private fun recoverInWriter(db: SupportSQLiteDatabase) {
            val abandoned = db.controlQuery("SELECT scope,transaction_id FROM syncv2_unconfirmed_writes").use { rows ->
                buildList { while (rows.moveToNext()) add(Key(rows.getString(0),rows.getString(1))) }
            }
            for (key in abandoned) {
                if (key !in active) SyncV2UnconfirmedWrite(db,key.scope,key.id).undo()
            }
        }

        fun <T> transaction(db: SupportSQLiteDatabase, work: () -> T): T {
            db.beginTransaction()
            var failure: Throwable? = null
            try {
                val result = work()
                db.setTransactionSuccessful()
                return result
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    db.endTransaction()
                } catch (error: Throwable) {
                    val primary = failure ?: throw error
                    if (primary !== error) primary.addSuppressed(error)
                }
            }
        }
    }
}
