package com.lightningstudio.watchrss.phone.syncv2

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** 25.6: charge old/new business graphs, journals, sealed closure and attachments together.
 * No reuse discount is claimed here: unconfirmed OIDs pay the worst-case staging charge. */
internal object SyncV2NoteBudget {
    fun required(ordinaryBytes:Long,closureUpperBound:Long,attachmentBytes:Long):Long {
        fun add(a:Long,b:Long):Long {if(b<0||a>9007199254740991L-b)SyncV2Codec.fail("storage_pressure");return a+b}
        var total=16384L
        repeat(4){total=add(total,ordinaryBytes)}
        repeat(2){total=add(total,closureUpperBound);total=add(total,attachmentBytes)}
        return total
    }
    class Reservation internal constructor(private val file:File):AutoCloseable {override fun close(){file.delete()}}
    fun reserve(directory:File,bytes:Long,available:Long=directory.usableSpace):Reservation {
        if(bytes<0||available<bytes)SyncV2Codec.fail("storage_pressure")
        val file=try{File.createTempFile("syncv2-note-reserve-",".tmp",directory)}catch(e:java.io.IOException){SyncV2Codec.fail("storage_pressure")}
        try {
            FileOutputStream(file).use{out->val block=ByteArray(8192);var remaining=bytes
                while(remaining>0){val n=minOf(remaining,block.size.toLong()).toInt();out.write(block,0,n);remaining-=n};out.fd.sync()}
            return Reservation(file)
        }catch(e:java.io.IOException){file.delete();SyncV2Codec.fail("storage_pressure")}
    }
    fun reserve(db:SupportSQLiteDatabase,scope:String,entity:String,ordinaryBytes:Long,closureUpperBound:Long,payload:JSONObject):Reservation {
        val id=db.query("SELECT logical_id FROM syncv2_identity_map WHERE entity_key=? LIMIT 1",arrayOf(entity)).use{if(it.moveToFirst())it.getString(0)else null}
        // A fork keeps the original storageKey; attachment ownership is not the
        // reference graph. Charge every referenced key once, including ancestry
        // and conflict texts, without claiming any authenticated OID reuse discount.
        val keys=linkedSetOf<String>()
        fun collect(text:String){keys.addAll(NoteAssetSyncPayload.referencedStorageKeys(text))}
        for(field in SyncV2NoteSegments.names)if(payload.has(field))collect(payload.getString(field))
        payload.optJSONObject("noteConflicts")?.optJSONArray("records")?.let{records->
            for(i in 0 until records.length())for(field in listOf("baseMarkdown","localMarkdown","remoteMarkdown"))collect(records.getJSONObject(i).getString(field))
        }
        // Keep the existing conservative charge for owned attachment rows as well.
        if(id!=null)db.query("SELECT storageKey FROM note_assets WHERE noteId=?",arrayOf(id)).use{c->while(c.moveToNext())keys.add(c.getString(0))}
        var attachments=0L
        for(storageKey in keys) {
            val size=db.query("SELECT MAX(byteCount) FROM note_assets WHERE storageKey=?",arrayOf(storageKey)).use{c->
                if(!c.moveToFirst()||c.isNull(0))SyncV2Codec.fail("closure_incomplete")
                c.getLong(0)
            }
            if(size<0||attachments>9007199254740991L-size)SyncV2Codec.fail("storage_pressure")
            attachments+=size
        }
        val old=db.query("SELECT length(meta)+length(state) FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(scope,entity)).use{if(it.moveToFirst())it.getLong(0)else 0}
        val bytes=required(maxOf(old,ordinaryBytes),closureUpperBound,attachments)
        return reserve(syncV2SpoolDirectory(db),bytes)
    }
}
