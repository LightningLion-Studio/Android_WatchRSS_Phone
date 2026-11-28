package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/** The Room asset keeps its unique digest. Each additional filename is a sync asset of its own. */
internal object SyncV2NoteAssetNames {
    const val aliases = "syncv2_note_asset_aliases"
    const val source = "syncv2_note_asset_names"
    private val digest = Regex("[0-9a-f]{64}")

    /** The regular Room reader can open before Sync v2 installs its alias view. */
    fun metadataSource(db: SupportSQLiteDatabase): String {
        val hasAliases = db.controlQuery(
            "SELECT 1 FROM sqlite_master WHERE type='view' AND name=?", arrayOf(source)
        ).use { it.moveToFirst() }
        return if (hasAliases) source else "note_assets"
    }

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $aliases (
                storageKey TEXT PRIMARY KEY NOT NULL, noteId TEXT NOT NULL, sha256 TEXT NOT NULL,
                displayName TEXT NOT NULL, mimeType TEXT NOT NULL, byteCount INTEGER NOT NULL,
                createdAt INTEGER NOT NULL, deleted INTEGER NOT NULL, deletedAt INTEGER NOT NULL,
                registration_txn TEXT
            )
        """.trimIndent())
        val hasRegistration = db.controlQuery("PRAGMA table_info($aliases)").use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == "registration_txn") found = true
            found
        }
        if (!hasRegistration) db.execSQL("ALTER TABLE $aliases ADD COLUMN registration_txn TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS syncv2_note_asset_alias_registration ON $aliases(registration_txn)")
        db.execSQL("""
            CREATE VIEW IF NOT EXISTS $source AS
            SELECT assetId,noteId,sha256,displayName,mimeType,byteCount,storageKey,isOriginal,createdAt,deleted,deletedAt
            FROM note_assets
            UNION ALL
            SELECT a.storageKey,a.noteId,a.sha256,a.displayName,a.mimeType,a.byteCount,a.storageKey,0,a.createdAt,a.deleted,a.deletedAt
            FROM $aliases a
            WHERE NOT EXISTS (SELECT 1 FROM note_assets n WHERE n.storageKey=a.storageKey)
        """.trimIndent())
        // Only sync writes this table. Include its changes in the existing scan witness.
        val triggers = db.controlQuery(
            "SELECT name,sql FROM sqlite_master WHERE type='trigger' AND tbl_name=?", arrayOf(aliases)
        ).use { rows -> buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getString(1)) } }
        for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            val name = "syncv2_note_asset_alias_${operation.lowercase()}"
            val dirty = com.lightningstudio.watchrss.phone.data.db.cloudDirtyKey(
                "note_assets", if (operation == "DELETE") "OLD" else "NEW"
            ) + if (operation == "UPDATE") com.lightningstudio.watchrss.phone.data.db.cloudDirtyKey("note_assets", "OLD") else ""
            val sql = """
                CREATE TRIGGER $name
                AFTER $operation ON $aliases BEGIN
                    INSERT INTO cloud_local_change_counter(seq) VALUES(NULL);
                    UPDATE cloud_local_non_cursor SET seq=last_insert_rowid();
                    DELETE FROM cloud_local_change_counter WHERE seq < last_insert_rowid();
                    $dirty
                END
            """.trimIndent()
            if (triggers[name] != sql) {
                db.execSQL("DROP TRIGGER IF EXISTS $name")
                db.execSQL(sql)
            }
        }
    }

    fun verifiedDigest(db: SupportSQLiteDatabase, storageKey: String): String? =
        db.controlQuery("SELECT sha256 FROM $source WHERE storageKey=? AND deleted=0", arrayOf(storageKey)).use { rows ->
            if (!rows.moveToFirst()) return@use null
            val hash = rows.getString(0)
            hash.takeIf { digest.matches(it) && !rows.moveToNext() }
        }

    fun exists(db: SupportSQLiteDatabase, storageKey: String): Boolean =
        db.controlQuery("SELECT 1 FROM $source WHERE storageKey=?", arrayOf(storageKey)).use { it.moveToFirst() }

    fun saveAlias(db: SupportSQLiteDatabase, row: JSONObject) =
        SyncV2PhoneApply(db).upsert(aliases, listOf("storageKey"), row)

    fun clear(db: SupportSQLiteDatabase) {
        val installed = db.controlQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(aliases))
            .use { it.moveToFirst() }
        if (installed) db.execSQL("DELETE FROM $aliases")
    }

    fun recordDownload(db: SupportSQLiteDatabase, storageKey: String, hash: String, bytes: Int) {
        val duplicate = db.controlQuery(
            "SELECT 1 FROM note_assets WHERE sha256=? AND storageKey<>?", arrayOf(hash, storageKey)
        ).use { it.moveToFirst() }
        val args = arrayOf<Any>(hash,bytes,storageKey,hash,bytes)
        if (duplicate) {
            // Each received name starts as a separate placeholder. Keep its metadata
            // and verified size when another name already owns the Room digest.
            db.execSQL("""
                INSERT INTO $aliases(storageKey,noteId,sha256,displayName,mimeType,byteCount,createdAt,deleted,deletedAt)
                SELECT storageKey,noteId,?,displayName,mimeType,?,createdAt,deleted,deletedAt
                FROM note_assets WHERE storageKey=?
                ON CONFLICT(storageKey) DO UPDATE SET noteId=excluded.noteId,sha256=excluded.sha256,
                    displayName=excluded.displayName,mimeType=excluded.mimeType,byteCount=excluded.byteCount,
                    createdAt=excluded.createdAt,deleted=excluded.deleted,deletedAt=excluded.deletedAt,registration_txn=NULL
            """.trimIndent(), arrayOf<Any>(hash,bytes,storageKey))
            db.execSQL("DELETE FROM note_assets WHERE storageKey=?", arrayOf(storageKey))
        } else {
            db.execSQL("UPDATE note_assets SET sha256=?,byteCount=? WHERE storageKey=? AND (sha256 IS NOT ? OR byteCount IS NOT ?)",args)
        }
        db.execSQL("UPDATE $aliases SET sha256=?,byteCount=? WHERE storageKey=? AND (sha256 IS NOT ? OR byteCount IS NOT ?)",args)
    }
}
