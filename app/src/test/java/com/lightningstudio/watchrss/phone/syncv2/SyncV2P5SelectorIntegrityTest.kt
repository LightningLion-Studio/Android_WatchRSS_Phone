package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P5SelectorIntegrityTest {
    @Test fun longSignedHistoryStaysBoundedAndOlderReplayPinsSurviveRestart():Unit=SyncV2P5Fixture().use{w->
        w.start();val first=w.initial;var parent=first;val authors=w.f.authors()
        repeat(160){index->
            parent=w.commit(listOf(w.note("title-$index")),parent,"0","normal",null)
            w.projection.observeVerified(JSONObject().put("oid",parent).put("revision",(index+3).toString()).put("generation","0"),listOf(w.headers.getValue(parent)),authors::header,{w.headers.getValue(it)})
        }
        val state=w.projection.state().body
        assertTrue(state.getJSONArray("knownAccountParents").length()<=2)
        assertTrue(state.getJSONArray("knownControlHeaders").length()<=2)
        assertTrue(SyncV2Codec.utf8(SyncV2Codec.canonical(state)).size<65536)
        w.reopen();assertTrue(w.projection.accountKnownHistoricalOid(first))
        assertEquals(first,SyncV2Codec.oid("commit",w.projection.accountCatalog.header(first)!!.getJSONObject("header").getJSONObject("body")))
        w.setCurrent(first,"999")
        assertEquals("replay_detected",runCatching{w.actions().refresh()}.exceptionOrNull()!!.message)
        w.sql.execSQL("UPDATE syncv2_p5_header_archive SET body=? WHERE oid=?",arrayOf(byteArrayOf(0),first))
        assertEquals("hash_mismatch",runCatching{w.projection.accountKnownHistoricalOid(first)}.exceptionOrNull()!!.message)
    }
    @Test fun unauthenticatedSqlFlagsCannotHideTheAuthenticatedDirtySet():Unit=SyncV2P5Fixture().use{w->
        val folder=JSONObject().put("folderId","folder").put("name","old").put("sortOrder",0).put("updatedAt",0).put("modifiedBy","peer").put("deleted",false).put("deletedAt",0)
        val old=w.commit(listOf(w.note("old title"),folder),w.initial,"0","normal",null)
        val latest=w.commit(listOf(w.note("new title"),JSONObject(folder.toString()).put("name","new")),old,"0","normal",null);w.setCurrent(latest,"4");w.start();w.edit()
        val rolled=w.commit(emptyList(),latest,"1","rollback",old);w.setCurrent(rolled,"5");w.actions().refresh();w.drain()
        w.sql.execSQL("UPDATE syncv2_p5_catalog SET needs_choice=0,candidate_hash=cloud_hash")
        val a=w.actions();a.choose(a.preview(),"local")
        assertEquals("new",w.sql.query("SELECT name FROM note_folders WHERE folderId='folder'").use{it.moveToFirst();it.getString(0)})
        assertEquals("new",w.projection.cloudRows().single{it.getJSONObject("meta").getString("kind")=="folder"}.getJSONObject("meta").getJSONObject("payload").getString("name"))
    }
    @Test fun missingOrTamperedSelectorPagesFailBeforePublication():Unit=SyncV2P5Fixture().use{w->
        w.pending();val original=w.text();w.sql.execSQL("UPDATE syncv2_p5_selector_nodes SET body='{}' WHERE kind='differences' AND prefix=''")
        val a=w.actions();assertEquals("hash_mismatch",runCatching{a.preview()}.exceptionOrNull()!!.message)
        assertEquals(original,w.text());assertEquals(0,w.publishCalls);assertEquals(0,w.choiceCalls)
    }
    @Test fun deletingAConflictChannelCannotMakeAnUnresolvedNoteMergeable():Unit=SyncV2P5Fixture().use{w->
        w.pending();w.sql.execSQL("INSERT INTO note_conflicts VALUES('open','note','base','local','remote','peer',1,0)");w.drain();w.sql.execSQL("DELETE FROM syncv2_p5_channels WHERE side='candidate'")
        val a=w.actions();assertEquals("closure_incomplete",runCatching{a.preview()}.exceptionOrNull()!!.message)
        assertEquals("offline\nline\n",w.text());assertEquals(0,w.publishCalls)
    }
    @Test fun oldDerivedIndexesRebuildOnceWithoutChangingOriginalsOrLegacyCursors():Unit=SyncV2P5Fixture().use{w->
        w.pending();val old=w.projection.state();val before=w.text();val applied=old.body.getJSONObject("applied").toString()
        w.projection.accountWriter(old.version){it.body.put("p5CatalogSchema",0)};w.reopen()
        val a=w.actions();val migrated=w.projection.state();assertEquals(6,migrated.body.getInt("p5CatalogSchema"));assertEquals(before,w.text());assertEquals(applied,migrated.body.getJSONObject("applied").toString())
        val version=migrated.version;w.actions();assertEquals(version,w.projection.state().version)
        a.choose(a.preview(),"local");assertEquals(before,w.text())
    }
}
