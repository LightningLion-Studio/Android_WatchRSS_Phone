package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.nio.ByteBuffer

/** Adversarial inputs use real envelopes, signatures and the product closure entry. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2C7RereviewRegressionTest {
    private val identity=SyncV2Fixtures.identity()
    private val key=ByteArray(32){7}
    private val signer=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(7),"aa".repeat(16),"ab".repeat(16))
    private fun code(block:()->Unit)=try{block();null}catch(e:IllegalStateException){e.message}
    private fun value(id:String="note",text:String="a".repeat(300000))=JSONObject().put("noteId",id).put("folderId",JSONObject.NULL).put("title","t")
        .put("markdown",text).put("plainText","").put("baseMarkdown","").put("contentHash","ab".repeat(32)).put("baseContentHash","cd".repeat(32))
        .put("pinned",false).put("createdAt",0).put("updatedAt",0).put("modifiedBy","synthetic").put("deleted",false).put("deletedAt",0)
        .put("noteConflicts",SyncV2NoteConflicts.phone(JSONArray(),id))
    private inner class Input(text:String="a".repeat(300000), logicalId:String="note", private val refSigner:SyncV2Signing=signer) {
        val entity=identity.entity("note",logicalId)
        val pair=SyncV2Business.split("note",logicalId,entity,value(id=logicalId,text=text),null)
        val objects=linkedMapOf<String,ByteArray>()
        val mappings=linkedMapOf<String,JSONObject>()
        val children=mutableListOf<JSONObject>()
        val ids=mutableListOf<String>()
        var parent=SyncV2NoteSegments.encode(entity,pair.state.getJSONObject("payload"),pair.state.getJSONArray("requiredFeatures"),"ff".repeat(16),identity.scope,Long.MAX_VALUE){v,_->
            children.add(v);val sealed=seal(v);ids.add(sealed.first);sealed.first}.state
        fun seal(v:JSONObject):Pair<String,ByteArray> {
            val s=SyncV2Envelope.seal(identity,key,1,"state",SyncV2Codec.utf8(SyncV2Codec.canonical(v)))
            objects[s.oid]=s.bytes;return s.oid to s.bytes
        }
        fun refs(oid:String,children:List<String>) {
            val r=SyncV2Index.refs(identity,refSigner,oid,JSONArray(children));val id=SyncV2Codec.oid("state-refs",r)
            val raw=SyncV2Codec.utf8(SyncV2Codec.canonical(r));objects[id]=raw
            mappings[oid]=JSONObject().put("scope",identity.scope).put("parentOid",oid).put("refsOid",id)
                .put("authorDeviceId",refSigner.deviceId).put("keyId",refSigner.keyId).put("totalBytes",raw.size)
        }
        fun forged(index:Int,change:(JSONObject)->Unit,gzip:Boolean=false) {
            val old=objects.getValue(ids[index]);val n=ByteBuffer.wrap(old,4,4).int
            val header=SyncV2Wire.parse(old.copyOfRange(8,8+n),4096,true)
            val plain=SyncV2Codec.utf8(SyncV2Codec.canonical(children[index]))
            val encoded=if(gzip)java.io.ByteArrayOutputStream().also{out->java.util.zip.GZIPOutputStream(out).use{it.write(plain)}}.toByteArray()else plain
            if(gzip)header.put("encoding","gzip").put("encodedBytes",encoded.size)
            change(header)
            val aad=SyncV2Codec.utf8(SyncV2Codec.canonical(header))
            val derived=SyncV2Codec.hkdf(key,SyncV2Codec.decode64(header.getString("salt")),SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/aead"),SyncV2Codec.sha(aad)))
            val bytes="WRS2".toByteArray()+ByteBuffer.allocate(4).putInt(aad.size).array()+aad+SyncV2Codec.encrypt(derived,SyncV2Codec.decode64(header.getString("nonce")),aad,encoded)
            val oid="state:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(bytes))}";objects[oid]=bytes;ids[index]=oid
        }
        fun read(readGate:Boolean=true,rowFeatures:Boolean=false,refsHook:(()->Unit)?=null,objectHook:(String)->Unit={}):SyncV2ControlClosure.Verified {
            parent.put("noteSegments",JSONArray(ids))
            val state=seal(parent).first;for(id in ids)refs(id,emptyList());refs(state,ids)
            refsHook?.invoke()
            val meta=SyncV2Envelope.seal(identity,key,1,"meta",SyncV2Codec.utf8(SyncV2Codec.canonical(pair.meta)));objects[meta.oid]=meta.bytes
            val row=JSONObject().put("entityKey",entity).put("metaOid",meta.oid).put("stateOid",state).put("addedAtMillis",0).put("rowSequence","1").put("deleted",false)
                .put("bodyRevision",JSONObject.NULL).put("promiseId",JSONObject.NULL).put("recipeOid",JSONObject.NULL).put("opaqueContentId",JSONObject.NULL).put("contentBytes",JSONObject.NULL)
                .put("requiredFeatures",if(rowFeatures)JSONArray().put(SyncV2NoteSegments.feature)else JSONArray())
            val tree=SyncV2Index.build(identity.scope,listOf(row));objects.putAll(tree.objects)
            val signed=refSigner.commit(JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("treeRoot",tree.root))
            val authors=SyncV2Authors(identity){true};authors.accept(identity.signingBinding(refSigner,100,"0"),"0")
            return SyncV2ControlClosure(identity,authors,{key},readGate).verify(signed,{objectHook(it);objects[it]?:SyncV2Codec.fail("object_missing")},{mappings[it]?:SyncV2Codec.fail("object_missing")})
        }
    }

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
    private fun SyncV2P5Fixture.textOf(id:String)=sql.controlQuery("SELECT markdown FROM notes WHERE noteId=?",arrayOf(id)).use{if(it.moveToFirst())it.getString(0)else null}
    @Test fun bbc9ClosedReadGateStillIsolatesRealSegmentsButReadsOrdinaryConflicts()=SyncV2P5Fixture().use{w->
        w.start();assertEquals(0,w.projection.noteIsolation().length())
        val ordinary=w.commit(listOf(w.note("ordinary conflicts","short\n")),w.current.getString("commitOid"),"0","normal",null)
        w.setCurrent(ordinary,"3");w.actions().refresh();w.drain()
        assertEquals("short\n",w.text());assertEquals(0,w.projection.noteIsolation().length())
        val before=w.ackCalls;val oid=w.installSegmented();w.actions().refresh();w.drain()
        assertEquals("unsupported_feature",w.projection.noteIsolation().getJSONObject(w.identity.entity("note","note")).getString("reason"))
        assertEquals("short\n",w.text());assertEquals("other\n",w.textOf("note2"));assertTrue(w.ackCalls>before)
        assertEquals(oid,w.projection.state().body.getJSONObject("appliedAccount").getString("oid"))
    }
    @Test fun enteringIsolationWithExistingUnsentEditsMustForkBeforeLocalChoiceAcknowledgesThem()=existingEdits("local")
    @Test fun enteringIsolationWithExistingUnsentEditsMustSurviveMergeChoice()=existingEdits("merge")
    @Test fun enteringIsolationWithExistingUnsentEditsMustSurviveCloudChoice()=existingEdits("cloud")
    private fun existingEdits(choice:String)=SyncV2P5Fixture().use{w->
        w.start();w.edit("unsent before isolation\n")
        val oid=w.installSegmented();w.actions().refresh();w.drain()
        val initialFork=w.projection.noteFork("note")
        assertNotNull("entering isolation must save the already queued edit",initialFork)
        assertEquals("unsent before isolation\n",w.textOf(initialFork!!))
        assertTrue(w.sql.query("SELECT COUNT(*) FROM syncv2_outbox WHERE logical_id=?",arrayOf(initialFork)).use{it.moveToFirst();it.getLong(0)}>0)
        val queueBefore=w.sql.query("SELECT COUNT(*) FROM syncv2_outbox WHERE logical_id='note'").use{it.moveToFirst();it.getLong(0)}
        // A real rollback creates the product pending-choice barrier, preserving the same long row.
        w.target=oid;w.rollbackRemote();w.actions().refresh();w.drain()
        val a=w.actions();val preview=a.preview()
        if(choice=="merge")assertTrue(preview.reason,preview.canMerge)
        a.choose(preview,choice);w.drain()
        val fork=w.projection.noteFork("note")
        val copies=w.sql.query("SELECT COUNT(*) FROM notes WHERE noteId<>'note' AND markdown='unsent before isolation\n'").use{it.moveToFirst();it.getLong(0)}
        val queueAfter=w.sql.query("SELECT COUNT(*) FROM syncv2_outbox WHERE logical_id='note'").use{it.moveToFirst();it.getLong(0)}
        // The remote edits only the original note, preserving the independently published copy.
        val retained=w.projection.accountLocalRows().filter{it.has("value")&&it.getJSONObject("meta").getString("logicalId")!="note"}
            .map{JSONObject(it.getJSONObject("value").toString())}
        val reopened=w.commit(retained+w.note("remote reopened","remote small\n"),w.current.getString("commitOid"),w.current.getString("rollbackGeneration"),"normal",null)
        w.setCurrent(reopened,SyncV2Codec.next(w.current.getString("revision")));a.refresh();w.drain()
        println("RR_PREEXISTING_EDIT initialFork=$initialFork forkAfter=$fork copies=$copies queueBefore=$queueBefore queueAfter=$queueAfter source=${w.text()}")
        assertNotNull("entering isolation must save the already queued edit",initialFork)
        if(choice!="cloud") {
            assertEquals("exactly one visible independent copy survives $choice",1L,copies)
            assertEquals("unsent before isolation\n",w.textOf(initialFork))
        }
        assertEquals("remote small\n",w.text())
    }
    @Test fun memoryHardCapMustNotHideAuthenticatedScopeFailure()=overCapError("scope_mismatch")
    @Test fun memoryHardCapMustNotHideAuthenticatedAeadFailure()=overCapError("aead_invalid")
    @Test fun memoryHardCapMustNotHideAuthenticatedRefsSignatureFailure()=overCapError("signature_invalid")
    @Test fun authenticatedOverCapNoteStillReturnsStoragePressure()=overCapError("storage_pressure")
    private fun overCapError(expected:String) {
        val input=Input("a".repeat(7*1024*1024))
        if(expected=="scope_mismatch")input.forged(0,{h->h.put("scope",JSONObject(identity.scope.toString()).put("repoId","ff".repeat(16)));Unit})
        if(expected=="aead_invalid") {
            val raw=input.objects.getValue(input.ids.last()).copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
            val oid="state:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}"
            input.objects[oid]=raw;input.ids[input.ids.lastIndex]=oid
        }
        val result=try {input.read(refsHook={if(expected=="signature_invalid") {
            val map=input.mappings.getValue(input.ids.last())
            val signed=SyncV2Wire.parse(input.objects.getValue(map.getString("refsOid")),262144,true).put("signature",SyncV2Codec.b64(ByteArray(64)))
            val bytes=SyncV2Codec.utf8(SyncV2Codec.canonical(signed));val oid=SyncV2Codec.oid("state-refs",signed)
            input.objects[oid]=bytes;map.put("refsOid",oid).put("totalBytes",bytes.size)
        }}).rows.single().optString(SyncV2NoteWiring.ISOLATED_KEY)}catch(e:IllegalStateException){e.message}
        println("RR_AUTH_OVER_CAP observed=$result expected=$expected")
        assertEquals(expected,result)
    }
    @Test fun stagedRoundMustNotRetainParentConflictBodiesAcrossNotes()=SyncV2P5Fixture().use{w->
        for(readGate in listOf(true,false))for(noteCount in listOf(1,3)) {
        val objects=linkedMapOf<String,ByteArray>();val mappings=linkedMapOf<String,JSONObject>()
        val rows=mutableListOf<JSONObject>()
        for(i in 0 until noteCount) {
            val name="note$i";val input=Input(logicalId=name)
            val r=JSONObject().put("conflictId","c$i").put("noteId",name).put("baseMarkdown","b".repeat(60000)).put("localMarkdown","l".repeat(60000))
                .put("remoteMarkdown","r".repeat(60000)).put("remoteDeviceId","synthetic").put("createdAt",0).put("resolvedAt",1)
            input.parent.getJSONObject("payload").getJSONObject("metadata").put("noteConflicts",JSONObject().put("version",1).put("records",JSONArray().put(r)))
            val initial=input.read();rows.add(initial.rows.single().getJSONObject("row"));objects.putAll(input.objects);mappings.putAll(input.mappings)
        }
        val authors=SyncV2Authors(identity){true};authors.accept(identity.signingBinding(signer,100,"0"),"0")
        val tree=SyncV2Index.build(identity.scope,rows);objects.putAll(tree.objects)
        val signed=signer.commit(JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("treeRoot",tree.root))
        val spool=SyncV2Spool(w.sql)
        try {
            val verified=SyncV2ControlClosure(identity,authors,{key},readGate).verifyStaged(signed,objects::getValue,mappings::getValue,stage={staged->spool[staged.entityKey]=w.projection.accountSeal("rr-staged",staged.body)},objectsStore={SyncV2Spool(w.sql)})
            fun bodyChars(value:Any?):Int=when(value) {
                is JSONObject->value.keys().asSequence().sumOf{k->
                    if(k in listOf("baseMarkdown","localMarkdown","remoteMarkdown"))value.getString(k).length else bodyChars(value.get(k))}
                is JSONArray->(0 until value.length()).sumOf{bodyChars(value.get(it))}
                else->0
            }
            // Inspect the actual cross-entity return graph, excluding the encrypted disk spool.
            val count=verified.rows.sumOf{bodyChars(it)}
            val staged=spool.keys.sumOf{bodyChars(w.projection.accountOpen("rr-staged",spool.getValue(it)))}
            assertEquals(noteCount*180000,staged)
            assertEquals(noteCount,verified.rows.size)
            assertTrue(verified.rows.all{it.optBoolean(SyncV2NoteWiring.PULL_STAGED_KEY)})
            println("RR_RETAINED_PARENT_CONFLICT_TEXT readGate=$readGate notes=$noteCount chars=$count staged=$staged")
            assertEquals("cross-entity output must contain only OIDs hashes and binding metadata",0,count)
        } finally {spool.close()}
        }
    }

    @Test fun authenticatedReadErrorsPreserveRealBusinessQueueCursorAndAppliedAck() {
        for(kind in listOf("scope_mismatch","fingerprint_mismatch","invalid_schema","invalid_oid","identity_conflict","invalid_encoding","closure_incomplete","hash_mismatch","signature_invalid","aead_invalid"))SyncV2P5Fixture().use{w->
            w.start();w.edit("local queued\n");w.noteSegmentsAnnounced=true
            val input=Input(refSigner=w.f.signer)
            when(kind) {
                "scope_mismatch"->input.forged(0,{it.put("scope",JSONObject(identity.scope.toString()).put("repoId","ff".repeat(16)));Unit})
                "fingerprint_mismatch"->input.forged(0,{it.put("opaqueContentId","00".repeat(32));Unit})
                "invalid_schema"->input.forged(0,{it.put("plainBytes",it.getInt("plainBytes")+1);Unit},true)
                "aead_invalid"->{
                    val raw=input.objects.getValue(input.ids[0]).copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
                    val oid="state:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}";input.objects[oid]=raw;input.ids[0]=oid
                }
            }
            runCatching{input.read(refsHook={when(kind){
                "invalid_oid"->input.mappings.getValue(input.ids[0]).put("refsOid","bad")
                "identity_conflict"->input.mappings.getValue(input.ids[0]).put("keyId","ff".repeat(16))
                "invalid_encoding"->{val map=input.mappings.getValue(input.ids[0]);input.objects[map.getString("refsOid")]=byteArrayOf(0xc0.toByte(),0xaf.toByte());map.put("totalBytes",2)}
                "closure_incomplete"->input.objects.remove(input.ids[1])
                "hash_mismatch"->{val id=input.ids[0];input.objects[id]=input.objects.getValue(id).copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}}
                "signature_invalid"->{
                    val map=input.mappings.getValue(input.ids[0]);val signed=SyncV2Wire.parse(input.objects.getValue(map.getString("refsOid")),262144,true).put("signature",SyncV2Codec.b64(ByteArray(64)))
                    val bytes=SyncV2Codec.utf8(SyncV2Codec.canonical(signed));val oid=SyncV2Codec.oid("state-refs",signed)
                    input.objects[oid]=bytes;map.put("refsOid",oid).put("totalBytes",bytes.size)
                }
                else->Unit
            }})}
            val root=input.objects.keys.single{it.startsWith("tree:")&&SyncV2Wire.parse(input.objects.getValue(it),262144,true).getString("prefix")==""}
            // Re-sign the fixture's next real history entry with the adversarial authenticated tree.
            val seed=w.commit(listOf(w.note()),w.current.getString("commitOid"),"0","normal",null)
            val body=JSONObject(w.headers.getValue(seed).getJSONObject("body").toString()).put("treeRoot",root)
            val signed=w.f.signer.commit(body);val oid=SyncV2Codec.oid("commit",body)
            w.headers.remove(seed);w.headers[oid]=signed;w.f.objects.putAll(input.objects);w.refs.putAll(input.mappings)
            w.f.objects[oid]=SyncV2Codec.utf8(SyncV2Codec.canonical(signed));w.deviceOid=oid;w.setCurrent(oid,"3")
            val before=w.projection.state();val acks=w.ackCalls
            val queue=w.sql.query("SELECT COUNT(*) FROM syncv2_outbox").use{it.moveToFirst();it.getLong(0)}
            assertTrue(queue>0)
            val client=w.f.client().also{it.noteSegmentsLocal=true}
            val action=SyncV2AccountActions(client,w.identity,w.projection,w.f.signer,{w.key},{it=="synthetic-phone"},w.root,true,{w.f.now},noteSegmentsRead=true)
            val failure=runCatching{action.poll()}.exceptionOrNull()
            println("RR_ERROR_PRESERVATION code=$kind actual=${failure?.message} acksBefore=$acks acksAfter=${w.ackCalls}")
            assertEquals(kind,failure?.message)
            assertEquals("local queued\n",w.text());assertEquals(acks,w.ackCalls)
            assertEquals(queue,w.sql.query("SELECT COUNT(*) FROM syncv2_outbox").use{it.moveToFirst();it.getLong(0)})
            assertEquals(before.body.optString("eventCursor","0"),w.projection.state().body.optString("eventCursor","0"))
            assertEquals(SyncV2Codec.canonical(before.body.getJSONObject("appliedAccount")),SyncV2Codec.canonical(w.projection.state().body.getJSONObject("appliedAccount")))
        }
    }

    @Test fun longForkBudgetMustChargeTheSharedAttachmentClosure()=SyncV2P5Fixture().use{w->
        w.start()
        val raw=ByteArray(32768){42}
        val asset=java.io.File(w.root,"notes/assets/asset.bin").also{it.parentFile!!.mkdirs();it.writeBytes(raw)}
        w.sql.execSQL("INSERT INTO note_assets VALUES('asset','note',?,'asset.bin','application/octet-stream',?,'asset.bin',0,0,0,0)",arrayOf<Any>(SyncV2Codec.hex(SyncV2Codec.sha(raw)),asset.length()))
        val markdown="a".repeat(150000)+"\n![shared](assets/asset.bin)\n![duplicate](assets/asset.bin)\n"
        assertTrue(com.lightningstudio.watchrss.phone.connection.bluetooth.NoteAssetSyncPayload.referencedStorageKeys(markdown).contains("asset.bin"))
        w.edit(markdown);w.installSegmented();w.actions().refresh();w.drain()
        val forkId=w.actions().forkNote("note").getString("forkLogicalId");w.drain()
        val entity=w.identity.entity("note",forkId)
        val pair=w.projection.candidate("note",forkId!!)!!
        val measure=SyncV2NoteSegments.measure(entity,pair.second.getJSONObject("payload"),pair.second.getJSONArray("requiredFeatures"))
        val old=w.sql.query("SELECT length(meta)+length(state) FROM syncv2_candidates WHERE scope=? AND entity_key=?",arrayOf(w.projection.scope,entity)).use{it.moveToFirst();it.getLong(0)}
        val dir=java.io.File(w.sql.path).absoluteFile.parentFile!!
        val before=dir.listFiles()!!.filter{it.name.startsWith("syncv2-note-reserve-")}.toSet()
        SyncV2NoteWiring.seal(w.identity,w.f.signer,w.key,1,w.sql,w.projection.scope,entity,pair.second,true,null,null,emptyList(),null,true)!!.use{sealed->
            val file=(dir.listFiles()!!.filter{it.name.startsWith("syncv2-note-reserve-")}.toSet()-before).single()
            val actual=file.length()
            val expected=SyncV2NoteBudget.required(maxOf(old,measure.shortPlainBytes),sealed.upperBound,asset.length())
            val owned=w.sql.query("SELECT COUNT(*) FROM note_assets WHERE noteId=?",arrayOf(forkId)).use{it.moveToFirst();it.getInt(0)}
            println("RR_FORK_ATTACHMENT_BUDGET actual=$actual expected=$expected attachmentBytes=${asset.length()} forkOwnedAssetRows=$owned")
            assertEquals("the markdown still references the original attachment so the fork must reserve its closure",expected,actual)
        }
    }
}
