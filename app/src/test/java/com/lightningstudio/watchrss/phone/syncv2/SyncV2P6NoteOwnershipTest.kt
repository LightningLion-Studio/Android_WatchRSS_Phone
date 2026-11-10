package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P6NoteOwnershipTest {
    private fun SyncV2P5Fixture.installSegmented(markdown:String="a".repeat(300000), sibling:Boolean=true):String {
        val ids=mutableListOf<String>()
        fun addRefs(state:String, segments:List<String>) {
            val signed=SyncV2Index.refs(identity,f.signer,state,JSONArray(segments))
            val oid=SyncV2Codec.oid("state-refs",signed);val bytes=SyncV2Codec.utf8(SyncV2Codec.canonical(signed));f.objects[oid]=bytes
            refs[state]=JSONObject().put("scope",identity.scope).put("parentOid",state).put("refsOid",oid)
                .put("authorDeviceId",f.signer.deviceId).put("keyId",f.signer.keyId).put("totalBytes",bytes.size)
        }
        stateHook={id,state->if(id=="note") {
            val encoded=SyncV2NoteSegments.encode(identity.entity("note","note"),state.getJSONObject("payload"),state.getJSONArray("requiredFeatures"),"ff".repeat(16),identity.scope,Long.MAX_VALUE){child,_->
                val sealed=SyncV2Envelope.seal(identity,key,1,"state",SyncV2Codec.utf8(SyncV2Codec.canonical(child)))
                f.objects[sealed.oid]=sealed.bytes;ids.add(sealed.oid);addRefs(sealed.oid,emptyList());sealed.oid
            }
            for(k in state.keys().asSequence().toList())state.remove(k)
            for(k in encoded.state.keys())state.put(k,encoded.state.get(k))
        }}
        val values=mutableListOf(note("cloud long",markdown))
        if(sibling)values.add(note("other","other\n").put("noteId","note2"))
        val oid=commit(values,current.getString("commitOid"),current.getString("rollbackGeneration"),"normal",null)
        stateHook=null
        val row=SyncV2Index.read(identity.scope,headers.getValue(oid).getJSONObject("body").getString("treeRoot")){f.objects.getValue(it)}.single{it.getString("entityKey")==identity.entity("note","note")}
        addRefs(row.getString("stateOid"),ids)
        setCurrent(oid,SyncV2Codec.next(current.getString("revision")))
        return oid
    }

    @Test fun unrelatedFolderWithSameLogicalIdDoesNotForkUneditedNote()=SyncV2P5Fixture().use {f->
        f.start()
        SyncV2PhoneApply(f.sql).upsert("note_folders",listOf("folderId"),JSONObject().put("folderId","note").put("name","local folder"))
        f.drain()
        assertTrue(f.sql.query("SELECT COUNT(*) FROM syncv2_outbox WHERE table_name='note_folders' AND logical_id='note'").use{it.moveToFirst();it.getInt(0)}>0)
        assertEquals(0,f.sql.query("SELECT COUNT(*) FROM syncv2_outbox WHERE table_name='notes' AND logical_id='note'").use{it.moveToFirst();it.getInt(0)})
        f.installSegmented();f.actions().refresh();f.drain()
        assertNull(f.projection.noteFork("note"));assertEquals("base\nline\n",f.text())
    }
    @Test fun actualNotesOutboxStillForksBeforeIsolation()=SyncV2P5Fixture().use {f->
        f.start();f.edit("real unsent edit\n");f.installSegmented();f.actions().refresh();f.drain()
        val fork=f.projection.noteFork("note");assertNotNull(fork)
        f.sql.query("SELECT markdown FROM notes WHERE noteId=?",arrayOf(fork)).use{assertTrue(it.moveToFirst());assertEquals("real unsent edit\n",it.getString(0))}
    }
}
