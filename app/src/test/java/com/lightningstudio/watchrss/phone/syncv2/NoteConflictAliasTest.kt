package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.data.note.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NoteConflictAliasTest {
    @Test fun legacyAndNewAliasesConvergeAndKeepResolution() {
        val values=listOf("n","a","x\u0000y","z😀","peer")
        val old=MarkdownNoteCodec.sha256(values.joinToString("\u0000"))
        val fresh=noteConflictId(values[0],values[1],values[2],values[3],values[4])
        fun set(id:String,time:Long)=JSONObject().put("version",1).put("records",JSONArray().put(JSONObject()
            .put("conflictId",id).put("noteId",values[0]).put("baseMarkdown",values[1]).put("localMarkdown",values[2])
            .put("remoteMarkdown",values[3]).put("remoteDeviceId",values[4]).put("createdAt",time).put("resolvedAt",if(id==fresh)10 else 0)))
        val a=set(old,2);val b=set(fresh,1)
        val merged=SyncV2NoteConflicts.merge(a,b,"n")
        assertEquals(SyncV2Codec.canonical(merged),SyncV2Codec.canonical(SyncV2NoteConflicts.merge(b,a,"n")))
        val rows=merged.getJSONArray("records");assertEquals(1,rows.length())
        assertEquals(old,rows.getJSONObject(0).getString("conflictId"))
        assertEquals(10,rows.getJSONObject(0).getInt("resolvedAt"))
        assertEquals(SyncV2Codec.canonical(merged),SyncV2Codec.canonical(SyncV2NoteConflicts.merge(merged,a,"n")))
    }
}
