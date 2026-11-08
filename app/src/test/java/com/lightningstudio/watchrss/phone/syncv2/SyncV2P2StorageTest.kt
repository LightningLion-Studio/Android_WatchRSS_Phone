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

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P2StorageTest {
    private val context:Context=RuntimeEnvironment.getApplication()
    private val roots=mutableListOf<File>();private val dbs=mutableListOf<PhoneCompanionDatabase>()
    private val identity=SyncV2Fixtures.identity();private val localKey=SyncV2Fixtures.localKey;private val contentKey=ByteArray(32){0x22}
    private fun signer()=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(1),"aa".repeat(16),"ab".repeat(16))
    private fun root()=File(context.cacheDir,"syncv2-p2-${System.nanoTime()}").also{it.mkdirs();roots.add(it)}
    private fun room(file:File)=Room.databaseBuilder(context,PhoneCompanionDatabase::class.java,file.absolutePath).allowMainThreadQueries().build().also{dbs.add(it)}
    private fun adapter(db:PhoneCompanionDatabase,fault:(String)->Unit={})=SyncV2Projection(db.openHelper.writableDatabase,identity,localKey,true,fault)
    private fun seed(db:PhoneCompanionDatabase) {
        val sql=db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO notes VALUES('note',NULL,'private title','live newer','live newer','hash','basehash','ancestor',0,0,90000,'old',0,0)")
        sql.execSQL("INSERT INTO note_conflicts VALUES('a','note','base','old local','remote A','peer',10,0)")
        sql.execSQL("INSERT INTO note_conflicts VALUES('b','note','base','old local','remote B','peer',50,0)")
        sql.execSQL("INSERT INTO note_conflicts VALUES('resolved','note','base','old local','remote C','peer',1,7)")
    }
    private fun patch(db:PhoneCompanionDatabase,p:SyncV2Projection,fn:(JSONObject)->Unit) {
        val state=p.state();fn(state.body);db.openHelper.writableDatabase.execSQL("UPDATE syncv2_state SET body=? WHERE scope=?",arrayOf(SyncV2Codec.sealLocal(localKey,p.scope,"state",state.body),p.scope))
    }
    private fun choiceBase(db:PhoneCompanionDatabase,p:SyncV2Projection) {
        p.observeVerified(SyncV2Fixtures.window(),SyncV2Fixtures.observationHeaders())
        patch(db,p){it.put("editBase",JSONObject().put("accountOid",SyncV2Fixtures.window().getString("oid")).put("generation",SyncV2Fixtures.window().getString("generation"))).put("unknownEditBase",false).put("pendingChoice",JSONObject.NULL)}
    }
    private fun code(fn:()->Unit):String?=try{fn();null}catch(e:IllegalStateException){e.message}
    private fun scalar(db:PhoneCompanionDatabase,sql:String)=db.openHelper.writableDatabase.query(sql).use{it.moveToFirst();it.getLong(0)}
    @After fun close(){dbs.forEach{if(it.isOpen)it.close()};roots.forEach{it.deleteRecursively()}}
    @Test fun invalidBookCannotEnterProjectionAndBusinessSaveSurvivesRejection() {
        val db=room(root().resolve("phone.db"));val p=adapter(db);p.install()
        val book=JSONObject().put("bookId","phone-1-book").put("fingerprint","synthetic-book-fingerprint-phone-1").put("title","").put("chapterCount",1).put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false).put("deletedAt",0).put("updatedAt",0).put("sourceDeviceId","")
        db.openHelper.writableDatabase.execSQL("INSERT INTO imported_books VALUES(?,?,'synthetic-hash',0)",arrayOf<Any>("phone-1-book",SyncV2Codec.canonical(book)))
        assertEquals("invalid_schema",code{p.scanPage("imported_books")})
        assertEquals(1,scalar(db,"SELECT COUNT(*) FROM imported_books"));assertEquals(0,scalar(db,"SELECT COUNT(*) FROM syncv2_candidates"))
    }
    @Test fun completeEncryptedIndexAuthenticatesAndPersistsWithoutAnyBodyWorkerOrBusinessAck() {
        val file=root().resolve("phone.db");var db=room(file);seed(db);var p=adapter(db);p.install();p.scanPage("notes")
        val index=p.prepareWireIndex(signer(),contentKey,1);val body=JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("treeRoot",index.tree.root)
        val signed=signer().commit(body);val oid=SyncV2Codec.oid("commit",body)
        val authors=SyncV2Authors(identity){true};authors.accept(identity.signingBinding(signer(),100,"0"),"0")
        val metas=index.refs.associateBy{it.getJSONObject("body").getString("stateOid")}
        fun mapping(parent:String):JSONObject {
            val ref=metas.getValue(parent);return JSONObject().put("scope",identity.scope).put("parentOid",parent).put("refsOid",SyncV2Codec.oid("state-refs",ref)).put("authorDeviceId",signer().deviceId).put("keyId",signer().keyId).put("totalBytes",SyncV2Codec.utf8(SyncV2Codec.canonical(ref)).size)
        }
        val verified=SyncV2ControlClosure(identity,authors){contentKey}.verify(signed,index.objects::getValue,::mapping)
        p.installCloudIndex(JSONObject().put("oid",oid).put("revision","9007199254740993").put("generation","0"),listOf(signed),verified,authors::header)
        assertEquals(1,p.cloudRows().size);assertEquals(3,p.cloudRows()[0].getJSONObject("state").getJSONObject("payload").getJSONObject("noteConflicts").getJSONArray("records").length())
        assertTrue(p.state().body.isNull("appliedAccount"));assertEquals(90000,scalar(db,"SELECT updatedAt FROM notes WHERE noteId='note'"))
        db.close();db=room(file);p=adapter(db);p.install();assertEquals(1,p.cloudRows().size)
        val bytes=file.readBytes().toString(Charsets.ISO_8859_1)
        // The original business table is intentionally plaintext. Inspect the new wire objects separately.
        for((key,value)in index.objects)if(key.startsWith("tree:")){assertFalse(value.toString(Charsets.UTF_8).contains("private title"));assertFalse(value.toString(Charsets.UTF_8).contains("noteConflicts"))}
        assertEquals("metadata-ready",p.state().body.getString("metadataStatus"))
        SyncV2Fixtures.evidence("p2-index-persist.json",JSONObject().put("rows",p.cloudRows().size).put("objects",index.objects.size).put("businessAppliedAck",false).put("revision",p.state().body.getJSONObject("account").getString("revision")))
    }
    @Test fun missingEmptyRefsOrForgedIndexCannotAdvanceAuthenticatedPin() {
        val db=room(root().resolve("phone.db"));seed(db);val p=adapter(db);p.install();p.scanPage("notes")
        val index=p.prepareWireIndex(signer(),contentKey,1);val body=JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("treeRoot",index.tree.root)
        val signed=signer().commit(body);val authors=SyncV2Authors(identity){true};authors.accept(identity.signingBinding(signer(),100,"0"),"0")
        val before=p.state().version
        assertEquals("object_missing",code{SyncV2ControlClosure(identity,authors){contentKey}.verify(signed,index.objects::getValue){SyncV2Codec.fail("object_missing")}})
        assertEquals(before,p.state().version);assertTrue(p.cloudRows().isEmpty())
    }
    @Test fun secondWirePreparationReusesCompleteSealedBytesAndAllReferences() {
        val db=room(root().resolve("phone.db"));seed(db);val p=adapter(db);p.install();p.scanPage("notes")
        val one=p.prepareWireIndex(signer(),contentKey,1);val two=p.prepareWireIndex(signer(),contentKey,1)
        assertEquals(one.tree.root,two.tree.root);assertEquals(one.objects.keys,two.objects.keys)
        for(k in one.objects.keys)assertArrayEquals(one.objects[k],two.objects[k])
        assertEquals(0,scalar(db,"SELECT COUNT(*) FROM sync_peer_state"))
    }
    @Test fun noteResolutionCommitsLiveBodyAllMarkersProjectionAndOneLocalRevision() {
        val db=room(root().resolve("phone.db"));seed(db);val p=adapter(db);p.install();p.scanPage("notes");choiceBase(db,p)
        val before=p.state();val capture=p.captureNoteChoice("note")
        p.resolveNoteChoice(capture,"local",null,100,"new-source",root())
        assertEquals(90001,scalar(db,"SELECT updatedAt FROM notes WHERE noteId='note'"));assertEquals(0,scalar(db,"SELECT COUNT(*) FROM note_conflicts WHERE resolvedAt=0"))
        assertEquals("live newer",db.openHelper.writableDatabase.query("SELECT baseMarkdown FROM notes WHERE noteId='note'").use{it.moveToFirst();it.getString(0)})
        assertEquals(SyncV2Codec.next(before.revision),p.state().revision)
        assertEquals(SyncV2Codec.canonical(before.body.get("applied")),SyncV2Codec.canonical(p.state().body.get("applied")))
        val candidate=p.candidate("note","note")!!.second.getJSONObject("payload").getJSONObject("noteConflicts").getJSONArray("records")
        assertEquals(3,candidate.length());assertEquals(7,candidate.getJSONObject(2).getLong("resolvedAt"))
    }
    @Test fun noteWriterFailureAndPreparationRaceKeepLatestDataAndAllUnresolvedRows() {
        for(boundary in listOf("note-resolution-written","note-resolution-prepared")) {
            val db=room(root().resolve("phone.db"));seed(db);var armed=false
            val p=adapter(db){stage->if(armed&&stage==boundary){if(stage.endsWith("prepared"))db.openHelper.writableDatabase.execSQL("UPDATE notes SET markdown='concurrent edit' WHERE noteId='note'")else SyncV2Codec.fail("simulated_crash")}}
            p.install();p.scanPage("notes");choiceBase(db,p);val capture=p.captureNoteChoice("note");armed=true
            assertEquals(if(boundary.endsWith("prepared"))"choice_stale" else "simulated_crash",code{p.resolveNoteChoice(capture,"remote",null,100,"new-source",root())})
            assertEquals(2,scalar(db,"SELECT COUNT(*) FROM note_conflicts WHERE resolvedAt=0"))
            assertEquals(90000,scalar(db,"SELECT updatedAt FROM notes WHERE noteId='note'"))
        }
    }
    @Test fun legacyIdempotentProofPreservesResolvedConflictsAndNeverIncreasesLocalRevision() {
        val db=room(root().resolve("phone.db"));seed(db);val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE note_conflicts SET resolvedAt=7")
        val p=adapter(db);p.install();p.scanPage("notes");choiceBase(db,p)
        val pair=p.candidate("note","note")!!;val current=SyncV2Business.join("note",pair.first,pair.second).put("identity","note");current.remove("noteConflicts")
        val entity=identity.entity("note","note")
        p.receiveLegacy(SyncV2Projection.LegacyInput("peer",14,"1",JSONObject().put(entity,current)),null,true)
        val before=p.state();val proof=p.planLegacyMerge("peer",14,"1");p.resolveLegacyMerge(proof)
        assertEquals(before.revision,p.state().revision);assertEquals(1,scalar(db,"SELECT applied FROM syncv2_legacy_inbox WHERE peer='peer'"))
        assertEquals(3,p.candidate("note","note")!!.second.getJSONObject("payload").getJSONObject("noteConflicts").getJSONArray("records").length())
        assertEquals("choice_stale",code{p.resolveLegacyMerge(proof)})
    }
    @Test fun fullSyntheticHttpControlPipelineDetectsRollbackAfterExpiredEventsWithoutBodyOrAppliedAck() {
        val db=room(root().resolve("phone.db"));seed(db);val p=adapter(db);p.install();p.scanPage("notes")
        val index=p.prepareWireIndex(signer(),contentKey,1)
        val firstBody=JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("treeRoot",index.tree.root)
        val first=signer().commit(firstBody);val firstOid=SyncV2Codec.oid("commit",firstBody)
        val nextBody=JSONObject(firstBody.toString()).put("accountParent",firstOid).put("previousOwnCommit",firstOid)
            .put("deviceSequence",SyncV2Codec.next(firstBody.getString("deviceSequence"))).put("rollbackGeneration","1")
            .put("operation",JSONObject().put("kind","rollback").put("rollbackTarget",firstOid).put("resolvesGeneration",JSONObject.NULL))
        val next=signer().commit(nextBody);val nextOid=SyncV2Codec.oid("commit",nextBody)
        val algorithm=org.bouncycastle.asn1.x509.AlgorithmIdentifier(org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.10045.2.1"),org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.10045.3.1.7"))
        val spki=org.bouncycastle.asn1.x509.SubjectPublicKeyInfo(algorithm,SyncV2Codec.unhex(signer().publicHex,65)).encoded
        val bootstrap=JSONObject().put("algorithm","ES256").put("receiptKeyId","cc".repeat(16)).put("receiptSigningPublicKey",SyncV2Codec.b64(spki))
        val trust=SyncV2ServiceTrust.fromBootstrap(identity.accountId,bootstrap,null){}
        val mapping=signer().signed("wrss.sync.v2/device-mapping",JSONObject().put("scope",identity.scope).put("deviceId",signer().deviceId).put("keyId",signer().keyId).put("legacyDeviceId","old-phone").put("approvalRevision","1"))
        val binding=identity.signingBinding(signer(),100,"0").put("deviceMapping",mapping)
        val calls=mutableMapOf<String,Int>();var rolled=false
        fun dto()=JSONObject().put("version",2).put("scope",identity.scope)
        fun json(value:JSONObject)=okhttp3.mockwebserver.MockResponse().setBody(value.toString()).setHeader("Content-Type","application/json")
        fun window()=dto().put("windowId","11".repeat(16)).put("expiresAtMillis",301000).put("serverRevision",if(rolled)"2" else "1").put("historyFloorRevision",if(rolled)"1" else "0")
            .put("account",JSONObject().put("commitOid",if(rolled)nextOid else firstOid).put("revision",if(rolled)"2" else "1").put("rollbackGeneration",if(rolled)"1" else "0"))
        val objects=index.objects.toMutableMap().apply{put(firstOid,SyncV2Codec.utf8(SyncV2Codec.canonical(first)));put(nextOid,SyncV2Codec.utf8(SyncV2Codec.canonical(next)))}
        val server=okhttp3.mockwebserver.MockWebServer()
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
            val path=request.requestUrl!!.encodedPath.removePrefix("/functions/v1/sync/v2/");calls[path]=(calls[path]?:0)+1
            return when {
                path=="capabilities" ->json(dto().put("minVersion",2).put("maxVersion",2).put("features",JSONArray()).put("historyDays",7).put("maxPartBytes",65536).put("flags",JSONObject().put("read",true).put("indexWrite",true).put("contentWrite",false).put("relay",false).put("gc",false).put("historyRestore",false)).put("receiptKeyId",bootstrap.getString("receiptKeyId")).put("receiptSigningPublicKey",bootstrap.getString("receiptSigningPublicKey")))
                path=="checkpoint" -> {
                    val w=window();val body=JSONObject().put("scope",identity.scope).put("checkpointId","22".repeat(16)).put("serverRevision",w.get("serverRevision"))
                        .put("refs",JSONArray()).put("account",w.get("account")).put("historyFloorRevision",w.get("historyFloorRevision")).put("historyFloorMillis",0)
                        .put("windowId",w.get("windowId")).put("issuedAtMillis",1000).put("expiresAtMillis",w.get("expiresAtMillis"))
                    json(signer().signed("wrss.sync.v2/checkpoint-sign",body).put("version",2).put("scope",identity.scope))
                }
                path=="negotiate" ->json(window().put("refs",JSONArray()).put("checkpointOid",JSONObject.NULL).put("pageToken",JSONObject.NULL))
                path=="devices/signing-keys" ->json(dto().put("bindings",JSONArray().put(binding)).put("revocationRevision","0"))
                path=="account/ancestry" -> {
                    val envelope=if(rolled)next else first;val oid=if(rolled)nextOid else firstOid
                    val stop=if(rolled)firstOid else null
                    val header=JSONObject().put("commitOid",oid).put("headerOid",SyncV2Codec.oid("commit-header",envelope)).put("body",envelope.getJSONObject("body")).put("signature",envelope.getString("signature"))
                    json(dto().put("head",oid).put("stop",stop?:JSONObject.NULL).put("headers",JSONArray().put(header)).put("nextOid",stop?:JSONObject.NULL).put("complete",true))
                }
                path.startsWith("state-refs/") -> {
                    val parent=path.removePrefix("state-refs/");val ref=index.refs.single{it.getJSONObject("body").getString("stateOid")==parent}
                    json(dto().put("parentOid",parent).put("refsOid",SyncV2Codec.oid("state-refs",ref)).put("authorDeviceId",signer().deviceId).put("keyId",signer().keyId).put("totalBytes",SyncV2Codec.utf8(SyncV2Codec.canonical(ref)).size))
                }
                path.startsWith("objects/") -> {val oid=path.removePrefix("objects/");okhttp3.mockwebserver.MockResponse().setHeader("ETag",oid).setBody(okio.Buffer().write(objects.getValue(oid)))}
                path=="events" ->json(JSONObject().put("error",JSONObject().put("code","history_expired").put("retryable",true).put("retryAfterMillis",JSONObject.NULL).put("details",JSONObject()))).setResponseCode(410)
                else ->okhttp3.mockwebserver.MockResponse().setResponseCode(500)
            }
        }}
        server.start()
        val uuid=identity.accountId.let{it.substring(0,8)+"-"+it.substring(8,12)+"-"+it.substring(12,16)+"-"+it.substring(16,20)+"-"+it.substring(20)}
        val session=SyncV2CloudClient.Session(uuid,"old-phone","old-epoch","old-generation",identity.scopeJson,signer().deviceId,"synthetic-token","synthetic-api")
        try {
            val client=SyncV2CloudClient(server.url("/functions/v1/sync/v2/"),{session},trust,true)
            val sync=SyncV2CloudSync(client,identity,p,{it=="old-phone"},{contentKey},{1000})
            assertEquals("metadata-ready",sync.readCurrent());assertEquals(1,p.cloudRows().size)
            val objectCalls=calls.filterKeys{it.startsWith("objects/")}.values.sum()
            assertEquals("metadata-ready",sync.readCurrent())
            assertEquals(objectCalls,calls.filterKeys{it.startsWith("objects/")}.values.sum())
            rolled=true
            val publicationRace=try{sync.publishIndex(signer(),contentKey,1);null}catch(e:SyncV2CloudError){e.code}
            assertEquals("ref_conflict",publicationRace)
            assertNull(p.pendingPublish())
            val poll=sync.poll();assertTrue(poll.getBoolean("historyExpired"));assertEquals("awaiting-user",poll.getString("status"))
            assertFalse(p.state().body.isNull("pendingChoice"));assertEquals("1",p.state().body.getString("rollbackGeneration"));assertEquals("2",p.state().body.getString("eventCursor"))
            assertTrue(p.state().body.isNull("appliedAccount"));assertEquals(90000,scalar(db,"SELECT updatedAt FROM notes WHERE noteId='note'"))
            assertFalse(calls.keys.any{it.contains("acks/applied")||it.contains("account/publish")||it.contains("blob:")})
            SyncV2Fixtures.evidence("p2-http-rollback.json",JSONObject().put("status",poll.getString("status")).put("calls",JSONObject(calls as Map<*,*>)).put("bodyWorkerCalls",0).put("businessAppliedAckCalls",0))
        }finally{server.shutdown()}
    }
    @Test fun c11ControlPublicationQueriesCurrentPrefixAfterHistoricalReserveBeforePut() {
        val db=room(root().resolve("phone.db"));seed(db);val p=adapter(db);p.install();p.scanPage("notes")
        SyncV2P3Fixture().use { f ->
            val calls=mutableListOf<String>()
            val mapping=JSONObject().put("scope",identity.scope).put("deviceId",f.signer.deviceId).put("keyId",f.signer.keyId)
                .put("legacyDeviceId","synthetic-phone").put("approvalRevision","1")
            val binding=identity.signingBinding(f.signer,f.now,"0").put("deviceMapping",f.signer.signed("wrss.sync.v2/device-mapping",mapping))
            f.route={ r ->
                val path=r.requestUrl!!.encodedPath.substringAfter("/functions/v1/sync/v2/")
                when {
                    path=="capabilities" ->f.response(f.dto().put("minVersion",2).put("maxVersion",2).put("maxPartBytes",65536).put("historyDays",7).put("features",JSONArray())
                        .put("receiptKeyId",f.bootstrap.getString("receiptKeyId")).put("receiptSigningPublicKey",f.bootstrap.getString("receiptSigningPublicKey"))
                        .put("flags",JSONObject().put("read",true).put("indexWrite",true)))
                    path=="negotiate" ->f.response(f.dto().put("windowId","12".repeat(16)).put("expiresAtMillis",f.now+300000).put("serverRevision","0").put("historyFloorRevision","0")
                        .put("refs",JSONArray()).put("account",JSONObject().put("commitOid",JSONObject.NULL).put("revision","0").put("rollbackGeneration","0")))
                    path=="devices/signing-keys" ->f.response(f.dto().put("bindings",JSONArray().put(binding)).put("revocationRevision","0"))
                    path=="objects/reserve" -> {
                        calls.add("reserve");val body=SyncV2Wire.parse(r.body.readByteArray());assertEquals("control",body.getString("channel"))
                        val rows=body.getJSONArray("objects")
                        for(i in 0 until rows.length())rows.getJSONObject(i).put("durableOffset",rows.getJSONObject(i).getInt("totalBytes"))
                        f.response(f.dto().put("reservationId","34".repeat(16)).put("expiresAtMillis",f.now+600000).put("reservedBytes",0).put("objects",rows))
                    }
                    path=="objects/have-want" -> {calls.add("have");null}
                    "/parts/" in path -> {
                        calls.add("put");assertTrue(path.endsWith("/parts/0"))
                        f.response(JSONObject().put("error",JSONObject().put("code","cloud_account_busy").put("retryable",true).put("retryAfterMillis",1000).put("details",JSONObject()))).setResponseCode(503)
                    }
                    else ->null
                }
            }
            val sync=SyncV2CloudSync(f.client(),identity,p,{it=="synthetic-phone"},{contentKey},{f.now})
            try{sync.publishIndex(f.signer,contentKey,1);fail("expected synthetic stop after first PUT")}
            catch(e:SyncV2CloudError){assertEquals("cloud_account_busy",e.code)}
            assertEquals(listOf("reserve","have","put"),calls);assertNull(p.pendingPublish());assertTrue(f.putOffsets.isEmpty())
        }
    }
    @Test fun definitiveCasRejectionDropsOnlyAttemptAndAllowsFreshRequest() {
        val db=room(root().resolve("phone.db"));val p=adapter(db);p.install()
        fun request(id:String)=JSONObject().put("account",JSONObject().put("body",JSONObject().put("requestId",id)))
        val original=p.state();val one=request("11".repeat(16));p.persistPublish(one,original.version)
        assertNotNull(p.pendingPublish());p.rejectPublish(one,"ref_conflict");assertNull(p.pendingPublish())
        assertEquals(original.revision,p.state().revision);assertEquals(SyncV2Codec.canonical(original.body.get("editBase")),SyncV2Codec.canonical(p.state().body.get("editBase")))
        val two=request("22".repeat(16));p.persistPublish(two,p.state().version)
        assertEquals(SyncV2Codec.canonical(two),SyncV2Codec.canonical(p.pendingPublish()))
    }
    @Test fun reservedSequenceSurvivesReopenAndD64ExhaustionNeverRunsHook() {
        val file=root().resolve("phone.db");var db=room(file);var p=adapter(db);p.install();assertEquals("1",p.reserveDeviceSequence());db.close();db=room(file);p=adapter(db);p.install();assertEquals("2",p.reserveDeviceSequence())
        patch(db,p){it.put("reservedDeviceSequence",SyncV2Codec.maxD64.toString())}
        val before=p.state().version;assertEquals("counter_exhausted",code{p.reserveDeviceSequence()});assertEquals(before,p.state().version)
    }

    @Test fun r03FirstReadRejectsInvalidCheckpointBeforeKeysObjectsOrPinChanges() {
        val db=room(root().resolve("phone.db"));seed(db);val p=adapter(db);p.install();p.scanPage("notes")
        val algorithm=org.bouncycastle.asn1.x509.AlgorithmIdentifier(org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.10045.2.1"),org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.10045.3.1.7"))
        val spki=org.bouncycastle.asn1.x509.SubjectPublicKeyInfo(algorithm,SyncV2Codec.unhex(signer().publicHex,65)).encoded
        val trust=SyncV2ServiceTrust.fromBootstrap(identity.accountId,JSONObject().put("algorithm","ES256").put("receiptKeyId","cc".repeat(16)).put("receiptSigningPublicKey",SyncV2Codec.b64(spki)),null){}
        val calls=mutableListOf<String>();val server=okhttp3.mockwebserver.MockWebServer()
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
            val path=request.requestUrl!!.encodedPath.substringAfter("/sync/v2/");calls.add(path)
            val value=JSONObject().put("version",2).put("scope",identity.scope)
            if(path=="capabilities")value.put("minVersion",2).put("maxVersion",2).put("features",JSONArray()).put("maxPartBytes",65536).put("historyDays",7).put("receiptKeyId",trust.keyId).put("receiptSigningPublicKey",trust.spki)
            else if(path=="checkpoint")value.put("body",JSONObject().put("scope",identity.scope)).put("signature",SyncV2Codec.b64(ByteArray(64)))
            else return okhttp3.mockwebserver.MockResponse().setResponseCode(500)
            return okhttp3.mockwebserver.MockResponse().setBody(value.toString())
        }}
        server.start()
        try {
            val uuid=identity.accountId.let{it.substring(0,8)+"-"+it.substring(8,12)+"-"+it.substring(12,16)+"-"+it.substring(16,20)+"-"+it.substring(20)}
            val session=SyncV2CloudClient.Session(uuid,"old-phone","old-epoch","old-generation",identity.scopeJson,signer().deviceId,"synthetic","synthetic")
            val client=SyncV2CloudClient(server.url("/functions/v1/sync/v2/"),{session},trust,true)
            val sync=SyncV2CloudSync(client,identity,p,{true},{contentKey},{1000})
            val before=p.state();val count=scalar(db,"SELECT COUNT(*) FROM notes")
            assertEquals("signature_invalid",code{sync.readCurrent()})
            assertEquals(listOf("capabilities","checkpoint"),calls)
            assertEquals(before.version,p.state().version);assertEquals(SyncV2Codec.canonical(before.body),SyncV2Codec.canonical(p.state().body))
            assertEquals(count,scalar(db,"SELECT COUNT(*) FROM notes"));assertTrue(p.cloudRows().isEmpty())
        }finally{server.shutdown()}
    }

    @Test fun r02ProductReadAuthenticatesStagedPredecessorWithoutPromotingItsWatermark() {
        for(mode in listOf("valid","missing-own","normal-jump")) {
            val db=room(root().resolve("phone.db"));seed(db);val p=adapter(db);p.install();p.scanPage("notes")
            val tree=SyncV2Index.build(identity.scope,emptyList())
            fun header(sequence:String,parent:String?,own:String?,generation:String="0"):JSONObject {
                val body=JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("treeRoot",tree.root).put("deviceSequence",sequence)
                    .put("accountParent",parent?:JSONObject.NULL).put("previousOwnCommit",own?:JSONObject.NULL).put("rollbackGeneration",generation)
                return signer().commit(body)
            }
            fun oid(e:JSONObject)=SyncV2Codec.oid("commit",e.getJSONObject("body"))
            val rootHead=header("1",null,null);val staged=header("2",oid(rootHead),oid(rootHead))
            val head=header("3",oid(rootHead),oid(staged),if(mode=="normal-jump")"1"else"0")
            val algorithm=org.bouncycastle.asn1.x509.AlgorithmIdentifier(org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.10045.2.1"),org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.10045.3.1.7"))
            val spki=org.bouncycastle.asn1.x509.SubjectPublicKeyInfo(algorithm,SyncV2Codec.unhex(signer().publicHex,65)).encoded
            val bootstrap=JSONObject().put("algorithm","ES256").put("receiptKeyId","cc".repeat(16)).put("receiptSigningPublicKey",SyncV2Codec.b64(spki))
            val trust=SyncV2ServiceTrust.fromBootstrap(identity.accountId,bootstrap,null){}
            val mapping=signer().signed("wrss.sync.v2/device-mapping",JSONObject().put("scope",identity.scope).put("deviceId",signer().deviceId).put("keyId",signer().keyId).put("legacyDeviceId","old-phone").put("approvalRevision","1"))
            val binding=identity.signingBinding(signer(),100,"0").put("deviceMapping",mapping)
            val calls=mutableListOf<String>();val server=okhttp3.mockwebserver.MockWebServer()
            fun dto()=JSONObject().put("version",2).put("scope",identity.scope)
            fun response(value:JSONObject)=okhttp3.mockwebserver.MockResponse().setBody(value.toString())
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
                val path=request.requestUrl!!.encodedPath.substringAfter("/sync/v2/");calls.add(path)
                return when {
                    path=="capabilities"->response(dto().put("minVersion",2).put("maxVersion",2).put("features",JSONArray()).put("maxPartBytes",65536).put("historyDays",7).put("receiptKeyId",trust.keyId).put("receiptSigningPublicKey",trust.spki))
                    path=="checkpoint"->{
                        val body=JSONObject().put("scope",identity.scope).put("checkpointId","22".repeat(16)).put("serverRevision","3").put("historyFloorRevision","0").put("historyFloorMillis",0)
                            .put("windowId","11".repeat(16)).put("issuedAtMillis",1000).put("expiresAtMillis",301000)
                            .put("refs",JSONArray().put(JSONObject().put("deviceId",signer().deviceId).put("commitOid",oid(head)).put("refRevision","3")))
                            .put("account",JSONObject().put("commitOid",oid(head)).put("revision","2").put("rollbackGeneration",if(mode=="normal-jump")"1"else"0"))
                        response(signer().signed("wrss.sync.v2/checkpoint-sign",body).put("version",2).put("scope",identity.scope))
                    }
                    path=="devices/signing-keys"->response(dto().put("bindings",JSONArray().put(binding)).put("revocationRevision","0"))
                    path=="account/ancestry"->{
                        val requested=request.requestUrl!!.queryParameter("head")!!
                        if(requested==oid(staged) && mode=="missing-own")return response(JSONObject().put("error",JSONObject().put("code","object_missing").put("retryable",false).put("retryAfterMillis",JSONObject.NULL).put("details",JSONObject()))).setResponseCode(404)
                        val values=when(requested){oid(head)->listOf(head,rootHead);oid(staged)->listOf(staged,rootHead);oid(rootHead)->listOf(rootHead);else->error("unexpected header")}
                        val headers=JSONArray(values.map{e->JSONObject().put("commitOid",oid(e)).put("headerOid",SyncV2Codec.oid("commit-header",e)).put("body",e.getJSONObject("body")).put("signature",e.getString("signature"))})
                        response(dto().put("head",requested).put("stop",JSONObject.NULL).put("headers",headers).put("nextOid",JSONObject.NULL).put("complete",true))
                    }
                    path=="objects/${tree.root}"->okhttp3.mockwebserver.MockResponse().setHeader("ETag",tree.root).setBody(okio.Buffer().write(tree.objects.getValue(tree.root)))
                    else->okhttp3.mockwebserver.MockResponse().setResponseCode(500)
                }
            }}
            server.start()
            try {
                val uuid=identity.accountId.let{it.substring(0,8)+"-"+it.substring(8,12)+"-"+it.substring(12,16)+"-"+it.substring(16,20)+"-"+it.substring(20)}
                val session=SyncV2CloudClient.Session(uuid,"old-phone","old-epoch","old-generation",identity.scopeJson,signer().deviceId,"synthetic","synthetic")
                val client=SyncV2CloudClient(server.url("/functions/v1/sync/v2/"),{session},trust,true)
                val sync=SyncV2CloudSync(client,identity,p,{it=="old-phone"},{contentKey},{1000})
                val pending=scalar(db,"SELECT COUNT(*) FROM syncv2_outbox");val failure=runCatching{sync.readCurrent()}.exceptionOrNull()
                if(mode=="valid") {
                    assertNull(failure);assertEquals(oid(head),p.state().body.getJSONObject("account").getString("oid"))
                    assertEquals("3",p.state().body.getJSONObject("knownAuthors").getJSONObject(signer().deviceId).getString("sequence"))
                    // N01 contract change: the state body only caches the most recent 2 control
                    // headers (the RR02 read-volume bound). Authenticating the staged predecessor
                    // and any older already-authenticated header is the header archive's job, so
                    // the regression asserts the behaviour instead of a body retention count:
                    // every header that authenticated stays resolvable through the sealed archive,
                    // and only real account-path entries carry their historical replay pin.
                    // End-to-end acceptance / forgery / replay proofs: SyncV2P5HeaderArchiveAuthTest.
                    assertEquals(2,p.state().body.getJSONArray("knownControlHeaders").length())
                    val archivedHead=p.accountCatalog.header(oid(head))!!;val archivedStaged=p.accountCatalog.header(oid(staged))!!
                    assertEquals(SyncV2Codec.canonical(head),SyncV2Codec.canonical(archivedHead.getJSONObject("header")))
                    assertTrue(archivedHead.getBoolean("accountPath"));assertFalse(archivedStaged.getBoolean("accountPath"))
                    assertTrue(p.accountKnownHistoricalOid(oid(head)))
                    assertFalse(p.accountKnownHistoricalOid(oid(staged)))
                    val objects=calls.count{it.startsWith("objects/")};sync.readCurrent();assertEquals(objects,calls.count{it.startsWith("objects/")})
                }else {
                    assertEquals(if(mode=="missing-own")"object_missing"else"replay_detected",failure?.message)
                    assertTrue(p.state().body.isNull("account"));assertTrue(p.cloudRows().isEmpty())
                }
                assertEquals(1,scalar(db,"SELECT COUNT(*) FROM notes"));assertEquals(pending,scalar(db,"SELECT COUNT(*) FROM syncv2_outbox"))
                assertTrue(p.state().body.isNull("appliedAccount"));assertFalse(calls.any{it=="account/publish"})
            }finally{server.shutdown()}
        }
    }
}
