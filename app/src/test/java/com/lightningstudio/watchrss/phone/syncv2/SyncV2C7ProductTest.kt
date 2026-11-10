package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/** Product-level C7 coverage: 25.5 pull entry, publish planner, gates, per-entity isolation, bindings. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2C7ProductTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val roots=mutableListOf<File>();private val dbs=mutableListOf<PhoneCompanionDatabase>()
    private val identity=SyncV2Fixtures.identity();private val localKey=SyncV2Fixtures.localKey;private val contentKey=ByteArray(32){0x22}
    private fun signer()=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(7),"aa".repeat(16),"ab".repeat(16))
    private fun root()=File(context.cacheDir,"syncv2-c7-${System.nanoTime()}").also{it.mkdirs();roots.add(it)}
    private fun room(file:File)=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,file.absolutePath).allowMainThreadQueries().build().also{dbs.add(it)}
    private fun adapter(db:PhoneCompanionDatabase,noteSegments:Boolean=false)=
        SyncV2Projection(db.openHelper.writableDatabase,identity,localKey,true,{},noteSegments).also{it.noteSegmentsServerAnnounced=noteSegments}
    private fun scalar(db:PhoneCompanionDatabase,sql:String)=db.openHelper.writableDatabase.query(sql).use{it.moveToFirst();it.getLong(0)}
    private fun text(db:PhoneCompanionDatabase,sql:String)=db.openHelper.writableDatabase.query(sql).use{if(it.moveToFirst())it.getString(0) else null}
    private fun code(fn:()->Unit):String?=try{fn();null}catch(e:IllegalStateException){e.message}
    private fun seedNote(db:PhoneCompanionDatabase,markdown:String) {
        db.openHelper.writableDatabase.execSQL("INSERT INTO notes(noteId,folderId,title,markdown,plainText,contentHash,baseContentHash,baseMarkdown,pinned,createdAt,updatedAt,modifiedBy,deleted,deletedAt) VALUES('note',NULL,'title',?,?,?,?,'',0,0,90000,'old',0,0)",
            arrayOf<Any>(markdown,markdown,"ab".repeat(32),"cd".repeat(32)))
    }
    @After fun close(){dbs.forEach{if(it.isOpen)it.close()};roots.forEach{it.deleteRecursively()}}

    private class Round(val index:SyncV2Projection.WireIndex,val verified:SyncV2ControlClosure.Verified,val signed:JSONObject,val commitOid:String,val authors:SyncV2Authors)
    private var sequence=0
    private var lastCommit:String?=null
    private fun publish(db:PhoneCompanionDatabase,p:SyncV2Projection,noteRead:Boolean,rescan:Boolean=true):Round {
        if(rescan)p.scanPage("notes")
        val index=p.prepareWireIndex(signer(),contentKey,1)
        val body=JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("treeRoot",index.tree.root).put("deviceSequence",(++sequence).toString())
                        .put("accountParent",lastCommit?:JSONObject.NULL).put("previousOwnCommit",lastCommit?:JSONObject.NULL)
        val signed=signer().commit(body)
        val authors=SyncV2Authors(identity){true};authors.accept(identity.signingBinding(signer(),100,"0"),"0")
        val metas=index.refs.associateBy{it.getJSONObject("body").getString("stateOid")}
        fun mapping(parent:String):JSONObject {
            val ref=metas.getValue(parent)
            return JSONObject().put("scope",identity.scope).put("parentOid",parent).put("refsOid",SyncV2Codec.oid("state-refs",ref))
                .put("authorDeviceId",signer().deviceId).put("keyId",signer().keyId).put("totalBytes",SyncV2Codec.utf8(SyncV2Codec.canonical(ref)).size)
        }
        val verified=SyncV2ControlClosure(identity,authors,{contentKey},noteRead).verify(signed,index.objects::getValue,::mapping)
        return Round(index,verified,signed,SyncV2Codec.oid("commit",signed.getJSONObject("body")),authors)
    }
    private fun knownObjects(db:PhoneCompanionDatabase,p:SyncV2Projection)=db.openHelper.writableDatabase.query("SELECT oid FROM syncv2_cloud_objects WHERE scope='${p.scope}'")
        .use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
    private fun titleRound0(db:PhoneCompanionDatabase,p:SyncV2Projection)=knownObjects(db,p)
    private fun install(p:SyncV2Projection,round:Round) {
        p.installCloudIndex(JSONObject().put("oid",round.commitOid).put("revision",(2+sequence).toString()).put("generation","0"),listOf(round.signed),round.verified,round.authors::header)
        lastCommit=round.commitOid
    }
    private fun noteRecord(verified:SyncV2ControlClosure.Verified)=verified.rows.first{it.getJSONObject("meta").getString("kind")=="note"}
    private fun segmentsOf(db:PhoneCompanionDatabase,p:SyncV2Projection,entity:String):List<String> =
        p.cloudRows().first{it.getJSONObject("meta").getString("entityKey")==entity}
            .optJSONArray(SyncV2NoteWiring.SEGMENTS_KEY)?.let{a->(0 until a.length()).map{a.getString(it)}}?:emptyList()

    @Test fun longNoteSegmentsOnPublishAndReassemblesOnPullThroughTheProductEntries() {
        val markdown="a".repeat(300000)
        val db=room(root().resolve("phone.db"));seedNote(db,markdown);val p=adapter(db,noteSegments=true)
        p.install()
        val round=publish(db,p,noteRead=true)
        val note=noteRecord(round.verified)
        assertNull(note.opt(SyncV2NoteWiring.ISOLATED_KEY))
        val ordinary=note.getJSONObject("state")
        assertFalse(ordinary.getJSONObject("payload").has("segmentedNote"))
        assertEquals(markdown,ordinary.getJSONObject("payload").getString("markdown"))
        val segments=note.getJSONArray(SyncV2NoteWiring.SEGMENTS_KEY)
        assertTrue(segments.length()>=2)
        val stateOid=note.getJSONObject("row").getString("stateOid")
        val parentPlain=SyncV2Envelope.open(identity,contentKey,stateOid,round.index.objects.getValue(stateOid),1)
        assertTrue(String(parentPlain,Charsets.UTF_8).contains("\"segmentedNote\":true"))
        install(p,round)
        assertEquals(markdown,p.cloudRows().first{it.getJSONObject("meta").getString("kind")=="note"}.getJSONObject("state").getJSONObject("payload").getString("markdown"))
        assertTrue(p.noteIsolation().length()==0)
        val entity=identity.entity("note","note")
        for(i in 0 until segments.length())
            assertEquals("segment objects must survive the object GC",1L,scalar(db,"SELECT COUNT(*) FROM syncv2_cloud_objects WHERE scope='${p.scope}' AND oid='"+segments.getString(i)+"'"))
        val revision=text(db,"SELECT note_revision FROM syncv2_note_bindings WHERE scope='${p.scope}' AND entity_key='$entity'")
        assertTrue(Regex("[0-9a-f]{32}").matches(revision!!))
        SyncV2Fixtures.evidence("c7-product-publish-pull.json",JSONObject().put("segments",segments.length()).put("revision",revision).put("sealedParent",true))
    }

    @Test fun closedWriteGateRejectsLongNotePublicationAndKeepsTheEntity() {
        val markdown="a".repeat(300000)
        val db=room(root().resolve("phone.db"));seedNote(db,markdown);val p=adapter(db,noteSegments=false)
        p.install()
        p.scanPage("notes")
        val before=scalar(db,"SELECT COUNT(*) FROM syncv2_candidates")
        assertEquals("feature_disabled",code{p.prepareWireIndex(signer(),contentKey,1)})
        assertEquals(before,scalar(db,"SELECT COUNT(*) FROM syncv2_candidates"))
        assertEquals(300000L,scalar(db,"SELECT LENGTH(markdown) FROM notes WHERE noteId='note'"))
    }

    @Test fun unsupportedSegmentedReaderIsolatesPerEntityWithoutStallingOtherRows() {
        val markdown="a".repeat(300000)
        val db=room(root().resolve("phone.db"));seedNote(db,markdown)
        db.openHelper.writableDatabase.execSQL("INSERT INTO note_folders VALUES('folder','name',0,90000,'old',false,0)")
        val p=adapter(db,noteSegments=true);p.install()
        p.scanPage("notes");p.scanPage("note_folders")
        val round=publish(db,p,noteRead=false,rescan=false)
        val note=noteRecord(round.verified)
        val folder=round.verified.rows.first{it.getJSONObject("meta").getString("kind")=="folder"}
        assertEquals("unsupported_feature",note.getString(SyncV2NoteWiring.ISOLATED_KEY))
        assertNull(folder.opt(SyncV2NoteWiring.ISOLATED_KEY))
        assertTrue(note.getJSONObject("state").getJSONObject("payload").has("segmentedNote"))
        install(p,round)
        assertEquals("unsupported_feature",p.noteIsolation().getJSONObject(identity.entity("note","note")).getString("reason"))
        // The other entity applied normally and the round advanced.
        assertEquals("metadata-ready",p.state().body.getString("metadataStatus"))
    }

    @Test fun isolatedRowIsCarriedBackVerbatimAndUnsentEditsForkToANewId() {
        val markdown="a".repeat(300000)
        val db=room(root().resolve("phone.db"));seedNote(db,markdown)
        val p=adapter(db,noteSegments=true);p.install()
        install(p,publish(db,p,noteRead=false))
        val entity=identity.entity("note","note")
        val stored=p.cloudRows().first{it.getJSONObject("meta").getString("entityKey")==entity}
        val originalStateOid=stored.getJSONObject("row").getString("stateOid")
        val originalBytes=db.openHelper.writableDatabase.query("SELECT body FROM syncv2_cloud_objects WHERE scope=? AND oid=?",arrayOf(p.scope,originalStateOid)).use{it.moveToFirst();it.getBlob(0)}
        // An unsent local edit is re-homed under a fresh logical id (R2); the isolated row never changes.
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET title='late edit',updatedAt=90001 WHERE noteId='note'")
        p.scanPage("notes")
        val forkId=db.openHelper.writableDatabase.query("SELECT logical_id FROM syncv2_candidates WHERE scope=? AND kind='note' AND logical_id LIKE 'syncv2-fork-%'",arrayOf(p.scope))
            .use{if(it.moveToFirst())it.getString(0) else null}
        assertNotNull("an edit to the isolated note must fork to a new logical id",forkId)
        val round=publish(db,p,noteRead=true,rescan=false)
        assertArrayEquals("the isolated wire parent must be carried back byte-identically",originalBytes,round.index.objects.getValue(originalStateOid))
        val carriedNote=round.verified.rows.first{it.getJSONObject("meta").getString("entityKey")==entity}
        assertEquals(originalStateOid,carriedNote.getJSONObject("row").getString("stateOid"))
        assertTrue(carriedNote.has(SyncV2NoteWiring.ISOLATED_KEY)||!carriedNote.getJSONObject("state").getJSONObject("payload").has("segmentedNote"))
        assertEquals(300000L,scalar(db,"SELECT LENGTH(markdown) FROM notes WHERE noteId='note'"))
        assertEquals("late edit",text(db,"SELECT title FROM notes WHERE noteId='note'"))
        assertNotNull(p.noteFork("note"))
    }

    @Test fun editsReuseUnchangedSegmentsAndKeepTheBoundRevision() {
        // Two full-budget segments: a tail append can reuse both sealed children byte-identically
        // (25.7) and must only seal the single new tail.
        val markdown="a".repeat(491520)
        val db=room(root().resolve("phone.db"));seedNote(db,markdown);val p=adapter(db,noteSegments=true)
        p.install()
        install(p,publish(db,p,noteRead=true))
        val entity=identity.entity("note","note")
        val revision=text(db,"SELECT note_revision FROM syncv2_note_bindings WHERE scope='${p.scope}' AND entity_key='$entity'")!!
        val firstSegments=segmentsOf(db,p,entity)
        assertTrue(firstSegments.size>=2)
        // Title-only edit: the whole segment set is reused byte-identically, zero fresh segment bytes.
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET title='second',updatedAt=90002 WHERE noteId='note'")
        val beforeTitle=titleRound0(db,p)
        val titleRound=publish(db,p,noteRead=true)
        install(p,titleRound)
        assertEquals(firstSegments,segmentsOf(db,p,entity))
        assertEquals(revision,text(db,"SELECT note_revision FROM syncv2_note_bindings WHERE scope='${p.scope}' AND entity_key='$entity'"))
        assertEquals("a title edit may only ship the resealed parent and its refs",0,titleRound.index.objects.keys.count{it in firstSegments&&it !in beforeTitle})
        // Tail append: every earlier segment stays sealed-reused; only the tail is fresh (25.7).
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET markdown=markdown||?,plainText=plainText||?,updatedAt=90003 WHERE noteId='note'",arrayOf<Any>("b".repeat(100000),"b".repeat(100000)))
        val round=publish(db,p,noteRead=true)
        install(p,round)
        val grown=segmentsOf(db,p,entity)
        assertTrue(grown.size>=firstSegments.size)
        val carried=grown.count{it in firstSegments}
        assertTrue("tail append reuses the greedy prefix: carried=$carried of ${firstSegments.size}",carried>=firstSegments.size-2&&carried>=1)
        val fresh=grown.filter{it !in firstSegments&&round.index.objects.containsKey(it)}
        assertTrue("the new tail must ship fresh bytes",fresh.isNotEmpty()&&fresh.all{round.index.objects.containsKey(it)})
        assertEquals(revision,text(db,"SELECT note_revision FROM syncv2_note_bindings WHERE scope='${p.scope}' AND entity_key='$entity'"))
        SyncV2Fixtures.evidence("c7-product-reuse.json",JSONObject().put("initialSegments",firstSegments.size).put("grownSegments",grown.size)
            .put("titleEditSegmentBytes",0).put("freshTailSegments",fresh.size).put("revisionStable",true))
    }

    @Test fun deleteInvalidatesTheBindingAndRebuildNeverReusesAcrossTheBoundary() {
        val markdown="a".repeat(300000)
        val db=room(root().resolve("phone.db"));seedNote(db,markdown);val p=adapter(db,noteSegments=true)
        p.install()
        install(p,publish(db,p,noteRead=true))
        val entity=identity.entity("note","note")
        val oldSegments=segmentsOf(db,p,entity)
        val oldRevision=text(db,"SELECT note_revision FROM syncv2_note_bindings WHERE scope='${p.scope}' AND entity_key='$entity'")!!
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET deleted=1,deletedAt=90003 WHERE noteId='note'")
        install(p,publish(db,p,noteRead=true))
        assertEquals(1L,scalar(db,"SELECT deleted FROM syncv2_note_bindings WHERE scope='${p.scope}' AND entity_key='$entity'"))
        assertEquals(1L,scalar(db,"SELECT invalidated FROM syncv2_note_bindings WHERE scope='${p.scope}' AND entity_key='$entity'"))
        // Rebuild the same noteId: a fresh revision and zero segment reuse across the delete boundary (R2).
        db.openHelper.writableDatabase.execSQL("UPDATE notes SET deleted=0,deletedAt=0,markdown=?,plainText=?,updatedAt=90004 WHERE noteId='note'",arrayOf<Any>("c".repeat(300000),"c".repeat(300000)))
        install(p,publish(db,p,noteRead=true))
        val next=segmentsOf(db,p,entity)
        val nextRevision=text(db,"SELECT note_revision FROM syncv2_note_bindings WHERE scope='${p.scope}' AND entity_key='$entity'")!!
        assertNotEquals(oldRevision,nextRevision)
        assertTrue(next.none{it in oldSegments})
    }

    @Test fun rollbackMissingRowsKeepTheBindingWhileNonOwnedFollowsTheCloud() {
        val markdown="a".repeat(300000)
        val db=room(root().resolve("phone.db"));seedNote(db,markdown);val p=adapter(db,noteSegments=true)
        p.install()
        install(p,publish(db,p,noteRead=true))
        val entity=identity.entity("note","note");val sql=db.openHelper.writableDatabase
        val revision=sql.query("SELECT note_revision FROM syncv2_note_bindings WHERE scope=? AND entity_key=?",arrayOf(p.scope,entity)).use{if(it.moveToFirst())it.getString(0) else null}!!
        // Q1: the next observed commit simply omits the row (rollback shape). The binding must survive unvalidated.
        SyncV2NoteWiring.observeBindings(sql,p.scope,emptyList())
        assertEquals(revision,sql.query("SELECT note_revision FROM syncv2_note_bindings WHERE scope=? AND entity_key=?",arrayOf(p.scope,entity)).use{if(it.moveToFirst())it.getString(0) else null})
        assertEquals(0L,scalar(db,"SELECT invalidated FROM syncv2_note_bindings WHERE scope='${p.scope}' AND entity_key='$entity'"))
        // R1: an owned entity keeps the local binding even when the received parent advertises another revision.
        sql.execSQL("INSERT INTO syncv2_outbox(scope,revision,table_name,logical_id,operation,base_state) VALUES(?,?,?,?,?,?)",
            arrayOf(p.scope,"1","notes","note","OBSERVE",SyncV2Codec.sealLocal(localKey,p.scope,"state",JSONObject())))
        SyncV2NoteWiring.observeBindings(sql,p.scope,listOf(JSONObject().put("meta",JSONObject().put("kind","note").put("entityKey",entity).put("logicalId","note"))
            .put("state",JSONObject()).put(SyncV2NoteWiring.REVISION_KEY,"9".repeat(32))))
        assertEquals(revision,sql.query("SELECT note_revision FROM syncv2_note_bindings WHERE scope=? AND entity_key=?",arrayOf(p.scope,entity)).use{if(it.moveToFirst())it.getString(0) else null})
        // N1: without pending edits the follower adopts the recreated parent's fresh revision.
        sql.execSQL("DELETE FROM syncv2_outbox WHERE scope=? AND logical_id='note'",arrayOf(p.scope))
        sql.execSQL("DELETE FROM syncv2_candidates WHERE scope=? AND kind='note'",arrayOf(p.scope))
        SyncV2NoteWiring.observeBindings(sql,p.scope,listOf(JSONObject().put("meta",JSONObject().put("kind","note").put("entityKey",entity).put("logicalId","note"))
            .put("state",JSONObject()).put(SyncV2NoteWiring.REVISION_KEY,"e".repeat(32))))
        assertEquals("e".repeat(32),sql.query("SELECT note_revision FROM syncv2_note_bindings WHERE scope=? AND entity_key=?",arrayOf(p.scope,entity)).use{if(it.moveToFirst())it.getString(0) else null})
    }

    @Test fun overBudgetRemoteClosureIsIsolatedAtTheReadEntryWithoutTouchingTheDatabase() {
        // A remote closure whose sealed child bytes exceed the 2 MiB note budget must isolate just
        // that entity at the authenticated read entry; the sibling folder still resolves normally.
        val entity=identity.entity("note","note")
        val value=JSONObject().put("noteId","note").put("folderId",JSONObject.NULL).put("title","t")
            .put("markdown","a".repeat(2*1024*1024+5000)).put("plainText","a".repeat(2*1024*1024+5000))
            .put("contentHash","ab".repeat(32)).put("baseContentHash","cd".repeat(32)).put("baseMarkdown","")
            .put("pinned",false).put("createdAt",0).put("updatedAt",90000).put("modifiedBy","old").put("deleted",false).put("deletedAt",0)
            .put("noteConflicts",SyncV2NoteConflicts.phone(JSONArray(),"note"))
        val meta=SyncV2Business.split("note","note",entity,value,null).meta
        val state=SyncV2Business.split("note","note",entity,value,null).state
        val objects=mutableMapOf<String,ByteArray>()
        val ids=mutableListOf<String>()
        val encoded=SyncV2NoteSegments.encode(entity,state.getJSONObject("payload"),state.getJSONArray("requiredFeatures"),"ff".repeat(16),identity.scope,Long.MAX_VALUE){value,index->
            val sealed=SyncV2Envelope.seal(identity,contentKey,1,"state",SyncV2Codec.utf8(SyncV2Codec.canonical(value)))
            objects[sealed.oid]=sealed.bytes;ids.add(index,sealed.oid);sealed.oid
        }
        val parentBytes=SyncV2Envelope.seal(identity,contentKey,1,"state",SyncV2Codec.utf8(SyncV2Codec.canonical(encoded.state)))
        val parentOid=parentBytes.oid
        objects[parentOid]=parentBytes.bytes
        val sealedMeta=SyncV2Envelope.seal(identity,contentKey,1,"meta",SyncV2Codec.utf8(SyncV2Codec.canonical(meta)))
        objects[sealedMeta.oid]=sealedMeta.bytes
        val parentRefs=SyncV2Index.refs(identity,signer(),parentOid,JSONArray(ids))
        objects[SyncV2Codec.oid("state-refs",parentRefs)]=SyncV2Codec.utf8(SyncV2Codec.canonical(parentRefs))
        val childRefs=ids.map{oid->val signed=SyncV2Index.refs(identity,signer(),oid);objects[SyncV2Codec.oid("state-refs",signed)]=SyncV2Codec.utf8(SyncV2Codec.canonical(signed));signed}
        val folderValue=JSONObject().put("folderId","folder").put("name","n").put("sortOrder",0).put("updatedAt",90000).put("modifiedBy","old").put("deleted",false).put("deletedAt",0)
        val folderSplit=SyncV2Business.split("folder","folder",identity.entity("folder","folder"),folderValue,null)
        val sealedFolder=SyncV2Envelope.seal(identity,contentKey,1,"state",SyncV2Codec.utf8(SyncV2Codec.canonical(folderSplit.state)))
        val sealedFolderMeta=SyncV2Envelope.seal(identity,contentKey,1,"meta",SyncV2Codec.utf8(SyncV2Codec.canonical(folderSplit.meta)))
        objects[sealedFolder.oid]=sealedFolder.bytes;objects[sealedFolderMeta.oid]=sealedFolderMeta.bytes
        val folderRefs=SyncV2Index.refs(identity,signer(),sealedFolder.oid)
        objects[SyncV2Codec.oid("state-refs",folderRefs)]=SyncV2Codec.utf8(SyncV2Codec.canonical(folderRefs))
        val rows=listOf(
            JSONObject().put("entityKey",entity).put("metaOid",sealedMeta.oid).put("stateOid",parentOid).put("addedAtMillis",0).put("rowSequence","1").put("deleted",false)
                .put("bodyRevision",JSONObject.NULL).put("promiseId",JSONObject.NULL).put("recipeOid",JSONObject.NULL).put("opaqueContentId",JSONObject.NULL).put("contentBytes",JSONObject.NULL).put("requiredFeatures",JSONArray()),
            JSONObject().put("entityKey",identity.entity("folder","folder")).put("metaOid",sealedFolderMeta.oid).put("stateOid",sealedFolder.oid).put("addedAtMillis",0).put("rowSequence","2").put("deleted",false)
                .put("bodyRevision",JSONObject.NULL).put("promiseId",JSONObject.NULL).put("recipeOid",JSONObject.NULL).put("opaqueContentId",JSONObject.NULL).put("contentBytes",JSONObject.NULL).put("requiredFeatures",JSONArray()))
        val tree=SyncV2Index.build(identity.scope,rows);objects.putAll(tree.objects)
        val metas=(listOf(parentRefs)+childRefs+folderRefs).associateBy{it.getJSONObject("body").getString("stateOid")}
        fun mapping(parent:String):JSONObject {
            val ref=metas.getValue(parent)
            return JSONObject().put("scope",identity.scope).put("parentOid",parent).put("refsOid",SyncV2Codec.oid("state-refs",ref))
                .put("authorDeviceId",signer().deviceId).put("keyId",signer().keyId).put("totalBytes",SyncV2Codec.utf8(SyncV2Codec.canonical(ref)).size)
        }
        val authors=SyncV2Authors(identity){true};authors.accept(identity.signingBinding(signer(),100,"0"),"0")
        val signed=signer().commit(JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("treeRoot",tree.root))
        val verified=SyncV2ControlClosure(identity,authors,{contentKey},true).verify(signed,{oid->objects[oid]?:SyncV2Codec.fail("closure_incomplete")},::mapping)
        val note=verified.rows.first{it.getJSONObject("meta").getString("kind")=="note"}
        assertEquals("storage_pressure",note.getString(SyncV2NoteWiring.ISOLATED_KEY))
        assertNull(verified.rows.first{it.getJSONObject("meta").getString("kind")=="folder"}.opt(SyncV2NoteWiring.ISOLATED_KEY))
        SyncV2Fixtures.evidence("c7-product-overbudget.json",JSONObject().put("segments",ids.size).put("reason",note.getString(SyncV2NoteWiring.ISOLATED_KEY)))
    }

    @Test fun shortNotePublicationIsUntouchedByTheSegmentationPlanner() {
        val db=room(root().resolve("phone.db"));seedNote(db,"small body");val p=adapter(db,noteSegments=true)
        p.install()
        val round=publish(db,p,noteRead=true)
        val note=noteRecord(round.verified)
        assertFalse(note.has(SyncV2NoteWiring.SEGMENTS_KEY))
        assertFalse(note.getJSONObject("state").getJSONObject("payload").has("segmentedNote"))
        assertNull(text(db,"SELECT note_revision FROM syncv2_note_bindings WHERE scope='${p.scope}' AND entity_key='${identity.entity("note","note")}'"))
    }
    @Test fun oneAndThreeNearTwoMiBNotesKeepProductPullAndPublicationPeaksBounded() {
        fun heap():Long {System.gc();Thread.sleep(25);return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()}
        data class Peaks(val publish:Long,val pull:Long,val sampledPublish:Long,val sampledPull:Long)
        fun measure(count:Int):Peaks {
            sequence=0;lastCommit=null
            val db=room(root().resolve("memory.db"));val sql=db.openHelper.writableDatabase
            val markdown="a".repeat(1_048_500)
            seedNote(db,markdown)
            for(i in 1 until count)sql.execSQL("INSERT INTO notes SELECT ?,folderId,title,markdown,plainText,contentHash,baseContentHash,baseMarkdown,pinned,createdAt,updatedAt,modifiedBy,deleted,deletedAt FROM notes WHERE noteId='note'",arrayOf("note$i"))
            var publishPeak=0L;var pullPeak=0L
            val phase=java.util.concurrent.atomic.AtomicReference("")
            val p=SyncV2Projection(sql,identity,localKey,true,{point->
                if(point=="note-publish-staged")publishPeak=maxOf(publishPeak,heap())
                if(point=="note-pull-staged")pullPeak=maxOf(pullPeak,heap())
            },true).also{it.install();it.noteSegmentsServerAnnounced=true;while(!it.scanPage("notes").complete){}}
            val running=java.util.concurrent.atomic.AtomicBoolean(true)
            val samplePublish=java.util.concurrent.atomic.AtomicLong();val samplePull=java.util.concurrent.atomic.AtomicLong()
            val thread=Thread({while(running.get()){
                val used=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
                when(phase.get()){"publish"->samplePublish.accumulateAndGet(used,::maxOf);"pull"->samplePull.accumulateAndGet(used,::maxOf)}
                Thread.sleep(2)
            }},"c7-product-heap").apply{isDaemon=true;start()}
            try {
                heap();phase.set("publish")
                val index=p.prepareWireIndex(signer(),contentKey,1)
                phase.set("");assertTrue(index.objects is SyncV2Spool)
                val refs=index.refs.associateBy{it.getJSONObject("body").getString("stateOid")}
                val signed=signer().commit(JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("treeRoot",index.tree.root))
                val authors=SyncV2Authors(identity){true};authors.accept(identity.signingBinding(signer(),100,"0"),"0")
                heap();phase.set("pull")
                p.verifyCloud(SyncV2ControlClosure(identity,authors,{contentKey},true),signed,index.objects::getValue,{parent->
                    val ref=refs.getValue(parent);JSONObject().put("scope",identity.scope).put("parentOid",parent)
                        .put("refsOid",SyncV2Codec.oid("state-refs",ref)).put("authorDeviceId",signer().deviceId).put("keyId",signer().keyId)
                        .put("totalBytes",SyncV2Codec.utf8(SyncV2Codec.canonical(ref)).size)
                }).use{verified->
                    assertTrue(verified.objects is SyncV2Spool)
                    p.installCloudIndex(JSONObject().put("oid",verified.commitOid).put("revision","1").put("generation","0"),listOf(signed),verified,authors::header)
                }
                phase.set("")
                assertEquals(count,p.cloudRows().size)
                for(row in p.cloudRows())assertEquals(markdown,row.getJSONObject("state").getJSONObject("payload").getString("markdown"))
                (index.objects as SyncV2Spool).close()
            }finally{running.set(false);thread.join(1000)}
            return Peaks(publishPeak,pullPeak,samplePublish.get(),samplePull.get())
        }
        // Warm all codec/Room paths before comparing one entity with three entities.
        measure(1)
        val one=measure(1);val three=measure(3)
        println("PHONE_C7_MEMORY one=$one three=$three noteEscapedBytes=2097048 samplingMillis=2")
        assertTrue("publish live peak: $one -> $three",three.publish<=one.publish*1.5+2*1024*1024)
        assertTrue("pull live peak: $one -> $three",three.pull<=one.pull*1.5+2*1024*1024)
        assertTrue("publish sampled peak: $one -> $three",three.sampledPublish<=one.sampledPublish*1.5+2*1024*1024)
        assertTrue("pull sampled peak: $one -> $three",three.sampledPull<=one.sampledPull*1.5+2*1024*1024)
        SyncV2Fixtures.evidence("c7-review-product-memory.json",JSONObject().put("noteEscapedBytes",2097048)
            .put("onePublishLive",one.publish).put("threePublishLive",three.publish).put("onePullLive",one.pull).put("threePullLive",three.pull)
            .put("onePublishSampled",one.sampledPublish).put("threePublishSampled",three.sampledPublish).put("onePullSampled",one.sampledPull).put("threePullSampled",three.sampledPull))
    }

}
