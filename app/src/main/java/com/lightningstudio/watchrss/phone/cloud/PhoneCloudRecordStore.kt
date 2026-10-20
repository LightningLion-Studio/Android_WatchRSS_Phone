package com.lightningstudio.watchrss.phone.cloud

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal interface CloudRecordState {
    fun cursor(owner: String): String
    fun record(owner: String, id: String): Pair<String, String>?
    fun save(owner: String, id: String, revision: String, hash: String)
    fun advance(owner: String, cursor: String)
    fun clear(owner: String)
}

/** Dedicated app-private SQLite, never the unbounded shared-preferences XML. */
internal class PhoneCloudRecordStore(context: Context) : SQLiteOpenHelper(context, "cloud-records-v1.db", null, 1), CloudRecordState {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (owner TEXT NOT NULL, id TEXT NOT NULL, revision TEXT NOT NULL, hash TEXT NOT NULL, PRIMARY KEY(owner,id))")
        db.execSQL("CREATE TABLE cursors (owner TEXT PRIMARY KEY, revision TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    override fun cursor(owner: String): String = readableDatabase.rawQuery("SELECT revision FROM cursors WHERE owner=?", arrayOf(owner)).use { if (it.moveToFirst()) it.getString(0) else "0" }
    override fun record(owner: String, id: String): Pair<String, String>? = readableDatabase.rawQuery("SELECT revision,hash FROM records WHERE owner=? AND id=?", arrayOf(owner, id)).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
    override fun save(owner: String, id: String, revision: String, hash: String) {
        writableDatabase.insertWithOnConflict("records", null, ContentValues().apply { put("owner", owner); put("id", id); put("revision", revision); put("hash", hash) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun advance(owner: String, cursor: String) {
        writableDatabase.insertWithOnConflict("cursors", null, ContentValues().apply { put("owner", owner); put("revision", cursor) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun clear(owner: String) { writableDatabase.delete("records", "owner=?", arrayOf(owner)); writableDatabase.delete("cursors", "owner=?", arrayOf(owner)) }
}
