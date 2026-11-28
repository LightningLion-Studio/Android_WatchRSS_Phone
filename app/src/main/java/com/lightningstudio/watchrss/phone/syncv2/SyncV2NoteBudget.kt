package com.lightningstudio.watchrss.phone.syncv2

import android.os.StatFs
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private fun availableSpace(directory:File):Long {
        if(!directory.exists())return 0L
        val statFsAvailable=runCatching{StatFs(directory.path).availableBytes}.getOrDefault(0L)
        return if(statFsAvailable>0L)statFsAvailable else directory.usableSpace
    }
    private val outstanding=AtomicLong(0L)
    class Reservation internal constructor(val reservedBytes:Long):AutoCloseable {
        private val released=AtomicBoolean(false)
        override fun close() {
            if(released.compareAndSet(false,true))outstanding.addAndGet(-reservedBytes)
        }
    }
    fun reserve(directory:File,bytes:Long,available:Long=availableSpace(directory)):Reservation {
        if(bytes<0||!directory.isDirectory)SyncV2Codec.fail("storage_pressure")
        while(true) {
            val current=outstanding.get()
            val remaining=available-current
            if(remaining<bytes||current>Long.MAX_VALUE-bytes)SyncV2Codec.fail("storage_pressure")
            if(outstanding.compareAndSet(current,current+bytes))return Reservation(bytes)
        }
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
        if(id!=null)db.query("SELECT storageKey FROM ${SyncV2NoteAssetNames.source} WHERE noteId=?",arrayOf(id)).use{c->while(c.moveToNext())keys.add(c.getString(0))}
        var attachments=0L
        for(storageKey in keys) {
            val size=db.query("SELECT MAX(byteCount) FROM ${SyncV2NoteAssetNames.source} WHERE storageKey=?",arrayOf(storageKey)).use{c->
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
