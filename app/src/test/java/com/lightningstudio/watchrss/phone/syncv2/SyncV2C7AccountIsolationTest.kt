package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * C7 review issues 1-4 and 16: an isolated note (F1/R3) is a verbatim wire row on every account
 * path. Normal refresh applies the other entities and advances the ACK; choose(cloud/local/merge)
 * and rollback carry it back under its original stateOid and never crash on its missing value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2C7AccountIsolationTest {
    private val isolate:(String,JSONObject)->Unit={id,state->if(id=="note")state.put("requiredFeatures",JSONArray().put(SyncV2NoteSegments.feature))}
    private fun SyncV2P5Fixture.other(markdown:String="other\n")=note("other",markdown).put("noteId","note2")
    private fun SyncV2P5Fixture.treeRows(commit:String)=SyncV2Index.read(identity.scope,headers.getValue(commit).getJSONObject("body").getString("treeRoot")){f.objects.getValue(it)}
    private fun SyncV2P5Fixture.stateOid(commit:String,noteId:String)=treeRows(commit).first{it.getString("entityKey")==identity.entity("note",noteId)}.getString("stateOid")
    private fun SyncV2P5Fixture.textOf(noteId:String)=sql.query("SELECT markdown FROM notes WHERE noteId=?",arrayOf(noteId)).use{if(it.moveToFirst())it.getString(0) else null}
    private fun SyncV2P5Fixture.publishIsolated(markdown:String="cloud\nline\n"):String {
        stateHook=isolate
        val oid=commit(listOf(note("cloud title",markdown),other()),current.getString("commitOid"),current.getString("rollbackGeneration"),"normal",null)
        stateHook=null;setCurrent(oid,SyncV2Codec.next(current.getString("revision")));return oid
    }

    @Test fun refreshAppliesOtherEntitiesAndAdvancesAckWhenAnIsolatedNoteExists()=SyncV2P5Fixture().use{w->
        w.start();assertEquals("base\nline\n",w.text())
        val oid=w.publishIsolated();val acks=w.ackCalls
        w.actions().refresh();w.drain()
        assertEquals("the isolated note keeps its local business body","base\nline\n",w.text())
        assertEquals("other entities still apply","other\n",w.textOf("note2"))
        assertEquals("unsupported_feature",w.projection.noteIsolation().getJSONObject(w.identity.entity("note","note")).getString("reason"))
        assertEquals(oid,w.projection.state().body.getJSONObject("appliedAccount").getString("oid"))
        assertTrue("the applied ACK advances",w.ackCalls>acks)
        // A second poll is stable (no crash, no re-isolation churn).
        w.actions().poll();w.drain();assertEquals("other\n",w.textOf("note2"))
    }

    private fun pendingWithIsolatedTarget(w:SyncV2P5Fixture):String {
        w.start()
        val c1=w.commit(listOf(w.note("new title","base\nline\n"),w.other()),w.current.getString("commitOid"),"0","normal",null);w.setCurrent(c1,"3")
        w.actions().refresh();w.drain();assertEquals("other\n",w.textOf("note2"))
        w.sql.execSQL("UPDATE notes SET markdown='mine\n',plainText='mine\n',updatedAt=updatedAt+1 WHERE noteId='note2'");w.drain()
        w.stateHook=isolate
        val t=w.commit(listOf(w.note("cloud title","cloud\nline\n"),w.other()),c1,"0","normal",null)
        w.stateHook=null;w.target=t
        w.setCurrent(c1,"3");w.rollbackRemote(listOf(w.note("cloud title","cloud\nline\n"),w.other()))
        w.actions().refresh();w.drain()
        return w.stateOid(t,"note")
    }

    @Test fun everyChoiceCarriesTheIsolatedRowBackVerbatim() {
        for(choice in listOf("cloud","local","merge"))SyncV2P5Fixture().use{w->
            val isolatedState=pendingWithIsolatedTarget(w)
            assertEquals("awaiting-user",w.projection.status())
            val a=w.actions();val preview=a.preview()
            if(choice=="merge" && !preview.canMerge)return@use
            a.choose(preview,choice)
            assertTrue(w.projection.state().body.isNull("pendingChoice"))
            assertEquals("$choice keeps the isolated row under its original stateOid",isolatedState,w.stateOid(w.current.getString("commitOid"),"note"))
            assertEquals("the local Room note is never overwritten by an isolated row","base\nline\n",w.textOf("note"))
        }
    }

    @Test fun rollbackToATargetWithAnIsolatedNoteCarriesItVerbatim()=SyncV2P5Fixture().use{w->
        w.start()
        w.stateHook=isolate
        val t=w.commit(listOf(w.note("cloud title","cloud\nline\n"),w.other()),w.current.getString("commitOid"),"0","normal",null)
        w.stateHook=null;w.target=t
        val c2=w.commit(listOf(w.note("new title","base\nline\n")),t,"0","normal",null);w.setCurrent(c2,"3")
        w.actions().refresh();w.drain()
        val a=w.actions();a.rollback(a.captureRollback(),t)
        val head=w.current.getString("commitOid")
        assertEquals(w.headers.getValue(t).getJSONObject("body").getString("treeRoot"),w.headers.getValue(head).getJSONObject("body").getString("treeRoot"))
        assertEquals(w.stateOid(t,"note"),w.stateOid(head,"note"))
        assertEquals("other\n",w.textOf("note2"))
        assertEquals("base\nline\n",w.text())
    }
}
