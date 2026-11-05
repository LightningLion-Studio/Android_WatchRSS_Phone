package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.data.db.CLOUD_CHANGE_TABLES
import com.lightningstudio.watchrss.phone.data.db.installCloudLocalChanges
import org.json.JSONArray
import org.json.JSONObject

/** The existing independent business counter is the ONLY business-transaction witness.
 * No syncv2 table, mapping, ciphertext, outbox, counter limit or account state is consulted by
 * a business trigger. All functions here run from the projection worker or open/setup path.
 */
internal object SyncV2BusinessWitness {
    data class Stamp(val identity:String,val revision:String) {
        fun encoded()=SyncV2Codec.canonical(JSONObject().put("identity",identity).put("revision",revision))
        fun sameSource(other:Stamp)=identity==other.identity
    }
    fun decode(value:String):Stamp?=runCatching {
        val obj=JSONObject(value);SyncV2Codec.exact(obj,setOf("identity","revision"))
        Stamp(obj.getString("identity"),obj.getString("revision").also{SyncV2Codec.d64(it)})
    }.getOrNull()
    private fun exists(db:SupportSQLiteDatabase,table:String)=db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{it.moveToFirst()}
    private fun raw(db:SupportSQLiteDatabase):Stamp? {
        if(!exists(db,"cloud_local_change_identity") || !exists(db,"sqlite_sequence"))return null
        val id=db.query("SELECT identity FROM cloud_local_change_identity WHERE id=1").use{if(it.moveToFirst())it.getString(0)else null}?:return null
        val seq=db.query("SELECT seq FROM sqlite_sequence WHERE name='cloud_local_change_counter'").use{if(it.moveToFirst())it.getLong(0)else null}?:return null
        if(seq<0)return null
        return Stamp(id,seq.toString())
    }
    private val coverageCache=java.util.WeakHashMap<SupportSQLiteDatabase,Pair<Long,String?>>()
    private fun coverage(db:SupportSQLiteDatabase):String? {
        val cookie=db.query("PRAGMA schema_version").use{it.moveToFirst();it.getLong(0)}
        synchronized(coverageCache){coverageCache[db]}?.let{if(it.first==cookie)return it.second}
        val expected=CLOUD_CHANGE_TABLES.flatMap {t->listOf("insert","update","delete").map{"cloud_local_change_${t}_$it"}}.toSet()
        val rows=JSONArray();val names=mutableSetOf<String>()
        db.query("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name LIKE 'cloud_local_change_%' ORDER BY name").use {c->
            while(c.moveToNext()){names.add(c.getString(0));rows.put(JSONArray().put(c.getString(0)).put(c.getString(1)))}
        }
        val result=if(names==expected)SyncV2Codec.hash(rows)else null
        synchronized(coverageCache){coverageCache[db]=cookie to result}
        return result
    }
    fun isCurrent(db:SupportSQLiteDatabase):Boolean {
        val current=coverage(db)?:return false
        if(raw(db)==null)return false
        return db.query("SELECT witness_epoch,witness_coverage FROM syncv2_control WHERE id=1").use{
            it.moveToFirst() && it.getString(0).isNotEmpty() && it.getString(1)==current
        }
    }
    /** Transactional repair records an epoch gap BEFORE accepting a replacement witness. */
    fun install(db:SupportSQLiteDatabase) {
        if(isCurrent(db))return
        installCloudLocalChanges(db)
        val hash=coverage(db)?:SyncV2Codec.fail("business_witness_unavailable")
        if(raw(db)==null)SyncV2Codec.fail("business_witness_unavailable")
        db.execSQL("UPDATE syncv2_control SET witness_epoch=?,witness_coverage=? WHERE id=1",arrayOf(SyncV2Codec.hex(SyncV2Codec.random(16)),hash))
    }
    /** Strict Capture guards use this read-only path; repair is an explicit worker operation. */
    fun peek(db:SupportSQLiteDatabase):Stamp? {
        if(!isCurrent(db))return null
        val raw=raw(db)?:return null
        val epoch=db.query("SELECT witness_epoch FROM syncv2_control WHERE id=1").use{if(it.moveToFirst())it.getString(0)else return null}
        return Stamp(raw.identity+":"+epoch,raw.revision)
    }
    fun read(db:SupportSQLiteDatabase):Stamp {
        if(!isCurrent(db)) {
            db.beginTransaction()
            try {install(db);db.setTransactionSuccessful()}finally{db.endTransaction()}
        }
        return peek(db)?:SyncV2Codec.fail("business_witness_unavailable")
    }
    /** Missing v2 edit bases are not proof that an existing v1/local library is clean. */
    fun initialEdits(db:SupportSQLiteDatabase):JSONObject {
        fun any(sql:String)=db.query(sql).use{it.moveToFirst() && it.getInt(0)!=0}
        val note=any("SELECT EXISTS(SELECT 1 FROM notes WHERE contentHash IS NOT baseContentHash OR markdown IS NOT baseMarkdown)")
        val conflicts=any("SELECT EXISTS(SELECT 1 FROM note_conflicts WHERE resolvedAt=0)")
        val provenance=any("SELECT EXISTS(SELECT 1 FROM article_register_provenance WHERE pending IS NOT NULL)")
        val log=any("SELECT EXISTS(SELECT 1 FROM sync_change_log WHERE seq>COALESCE((SELECT MIN(lastLocalSeqAckedByPeer) FROM sync_peer_state),0))")
        val existing=SyncV2Schema.domains.any{any("SELECT EXISTS(SELECT 1 FROM `${it.table}`)")}
        return JSONObject().put("dirtyNote",note).put("unresolvedNoteConflict",conflicts).put("pendingProvenance",provenance)
            .put("unackedLegacyLog",log).put("unknownV2Base",existing||note||conflicts||provenance||log)
    }
}
